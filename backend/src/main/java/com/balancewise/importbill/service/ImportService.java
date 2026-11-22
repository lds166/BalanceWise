package com.balancewise.importbill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.balancewise.account.entity.Account;
import com.balancewise.common.BizException;
import com.balancewise.importbill.dto.ImportConfirmRequest;
import com.balancewise.importbill.dto.ImportPreviewRow;
import com.balancewise.importbill.dto.ImportRowRequest;
import com.balancewise.importbill.entity.ImportBatch;
import com.balancewise.importbill.mapper.ImportBatchMapper;
import com.balancewise.importbill.parser.BillParseResult;
import com.balancewise.importbill.parser.BillParser;
import com.balancewise.importbill.parser.ParsedRow;
import com.balancewise.importbill.vo.ImportBatchDetailVo;
import com.balancewise.importbill.vo.ImportBatchVo;
import com.balancewise.importbill.vo.ImportPreviewVo;
import com.balancewise.record.entity.TransactionRecord;
import com.balancewise.record.mapper.TransactionRecordMapper;
import com.balancewise.record.service.TransactionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 账单导入编排（UC-4.1~4.7 预览侧）。
 * 预览不落库：解析 → 清洗 → 类型识别 → 分类匹配流水线（记忆→规则，AI 层见 AiClassifier）→ 账户匹配
 * → 重复检测（库中 is_deleted=0 同源 + 批内），返回预览行。
 */
@Service
public class ImportService {

    private final Map<String, BillParser> parsers;
    private final CategoryMappingService categoryMappingService;
    private final ImportAliasService importAliasService;
    private final AccountMatcher accountMatcher;
    private final TransactionRecordMapper transactionRecordMapper;
    private final ImportBatchMapper importBatchMapper;
    private final TransactionService transactionService;

    public ImportService(List<BillParser> parserList,
                         CategoryMappingService categoryMappingService,
                         ImportAliasService importAliasService,
                         AccountMatcher accountMatcher,
                         TransactionRecordMapper transactionRecordMapper,
                         ImportBatchMapper importBatchMapper,
                         TransactionService transactionService) {
        this.parsers = parserList.stream().collect(Collectors.toMap(BillParser::platform, Function.identity()));
        this.categoryMappingService = categoryMappingService;
        this.importAliasService = importAliasService;
        this.accountMatcher = accountMatcher;
        this.transactionRecordMapper = transactionRecordMapper;
        this.importBatchMapper = importBatchMapper;
        this.transactionService = transactionService;
    }

    public ImportPreviewVo preview(Long userId, String platform, byte[] content, Long defaultAccountId) {
        BillParser parser = parsers.get(platform);
        if (parser == null) {
            throw new BizException("不支持的平台：" + platform);
        }
        BillParseResult parsed = parser.parse(content);
        List<String> externalIds = parsed.getRows().stream()
                .map(ParsedRow::getExternalId).filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toList());
        Set<String> existing = existingExternalIds(userId, platform, externalIds);
        Set<String> seenInBatch = new HashSet<>();

        List<ImportPreviewRow> rows = parsed.getRows().stream().map(r -> {
            ImportPreviewRow row = new ImportPreviewRow();
            row.setLineNo(r.getLineNo());
            row.setExternalId(r.getExternalId());
            row.setType(r.getType());
            row.setAmount(r.getAmount());
            row.setOccurredAt(r.getOccurredAt());
            row.setCounterparty(r.getCounterparty());
            row.setItem(r.getItem());
            row.setPlatformCategory(r.getPlatformCategory());
            row.setPayMethod(r.getPayMethod());
            // 分类匹配流水线第 1/2 层：记忆 → 关键词规则（AI 第 3 层由 AiClassifier 在此外插）
            String kind = "expense".equals(r.getType()) ? "expense" : "income";
            Long memoryCat = importAliasService.matchMemoryCategory(userId, kind, r.getCounterparty(), r.getItem());
            if (memoryCat != null) {
                row.setCategoryId(memoryCat);
                row.setMatchSource("memory");
            } else {
                Long ruleCat = categoryMappingService.mapCategoryId(
                        userId, r.getType(), r.getPlatformCategory(), r.getItem());
                row.setCategoryId(ruleCat);
                row.setMatchSource(ruleCat != null ? "rule" : null);
            }
            Account acc = accountMatcher.match(userId, r.getPayMethod(), defaultAccountId);
            if (acc != null) {
                row.setAccountName(acc.getName());
                if ("income".equals(r.getType())) {
                    row.setToAccountId(acc.getId());
                } else {
                    // expense / transfer：先填 from（转账的 to 由用户在预览中补选）
                    row.setFromAccountId(acc.getId());
                }
            }
            boolean dup = existing.contains(r.getExternalId()) || !seenInBatch.add(r.getExternalId());
            row.setDuplicate(dup);
            return row;
        }).collect(Collectors.toList());

        ImportPreviewVo vo = new ImportPreviewVo();
        vo.setPlatform(platform);
        vo.setRows(rows);
        vo.setDropped(parsed.getDropped());
        return vo;
    }

    /**
     * 批次入账（UC-4.8 / 规格 7.4 步骤 7）：单事务——
     * 重复校验 → 插 import_batch(confirmed) → 逐条经 TransactionService.importRecord 入账（余额联动同生共死）。
     */
    @Transactional
    public ImportBatch confirm(Long userId, ImportConfirmRequest req) {
        // 服务层兜底：直接调用（绕过 @Valid）时空行必须在插批次前拒绝；文案须含"至少一条"（测试断言）
        if (req.getRows() == null || req.getRows().isEmpty()) {
            throw new BizException("批次入账至少一条流水，请勾选后再确认");
        }
        List<String> externalIds = req.getRows().stream()
                .map(ImportRowRequest::getExternalId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toList());
        // 批内互重校验：同一批次中 external_id 不得重复（防止同一文件内重复交易号自我重复入账）
        Set<String> seenInBatch = new HashSet<>();
        for (String id : externalIds) {
            if (!seenInBatch.add(id)) {
                throw new BizException("批次内存在重复的 external_id：" + id);
            }
        }
        long dupCount = existingExternalIds(userId, req.getPlatform(), externalIds).size();
        if (dupCount > 0) {
            throw new BizException("包含 " + dupCount + " 条已入账记录，请重新预览后再确认");
        }

        ImportBatch batch = new ImportBatch();
        batch.setUserId(userId);
        batch.setPlatform(req.getPlatform());
        batch.setFileName(req.getFileName() != null ? req.getFileName() : "");
        batch.setRecordCount(req.getRows().size());
        batch.setTotalAmount(req.getRows().stream()
                .map(ImportRowRequest::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, java.math.RoundingMode.HALF_UP));
        batch.setStatus("confirmed");
        batch.setImportedAt(java.time.LocalDateTime.now());
        importBatchMapper.insert(batch);

        for (ImportRowRequest row : req.getRows()) {
            com.balancewise.record.dto.TransactionRequest txn = new com.balancewise.record.dto.TransactionRequest();
            txn.setType(row.getType());
            txn.setFromAccountId(row.getFromAccountId());
            txn.setToAccountId(row.getToAccountId());
            txn.setAmount(row.getAmount());
            txn.setCategoryId(row.getCategoryId());
            txn.setOccurredAt(row.getOccurredAt());
            txn.setCounterparty(row.getCounterparty());
            txn.setItem(row.getItem());
            transactionService.importRecord(userId, req.getPlatform(), row.getExternalId(),
                    batch.getId(), txn);
        }
        // 学习用户修正（UC-4.10，与入账同事务）：勾选"记住"的行沉淀为个人映射，下次导入优先命中
        for (ImportRowRequest row : req.getRows()) {
            if (Boolean.TRUE.equals(row.getLearnCategory()) && row.getCategoryId() != null
                    && !"transfer".equals(row.getType())) {
                importAliasService.learnCategory(userId, kindOf(row.getType()),
                        row.getCounterparty(), row.getItem(), row.getCategoryId());
            }
            if (Boolean.TRUE.equals(row.getLearnAccount())) {
                Long accId = "income".equals(row.getType()) ? row.getToAccountId() : row.getFromAccountId();
                importAliasService.learnAccount(userId, row.getPayMethod(), accId);
            }
        }
        return batch;
    }

    private String kindOf(String type) {
        return "expense".equals(type) ? "expense" : "income";
    }

    /** 库中已存在的同源未删除 external_id（§5.4 规则 4：应用层重复检测） */
    private Set<String> existingExternalIds(Long userId, String platform, List<String> externalIds) {
        if (externalIds.isEmpty()) {
            return Set.of();
        }
        return transactionRecordMapper.selectList(new LambdaQueryWrapper<TransactionRecord>()
                        .eq(TransactionRecord::getUserId, userId)
                        .eq(TransactionRecord::getSource, platform)
                        .eq(TransactionRecord::getIsDeleted, 0)
                        .in(TransactionRecord::getExternalId, externalIds))
                .stream().map(TransactionRecord::getExternalId).collect(Collectors.toSet());
    }

    public List<ImportBatchVo> list(Long userId) {
        return importBatchMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ImportBatch>()
                        .eq(ImportBatch::getUserId, userId)
                        .orderByDesc(ImportBatch::getImportedAt))
                .stream().map(this::toVo).collect(Collectors.toList());
    }

    public ImportBatchDetailVo detail(Long userId, Long batchId) {
        ImportBatch batch = ownedBatch(userId, batchId);
        ImportBatchDetailVo vo = new ImportBatchDetailVo();
        vo.setId(batch.getId());
        vo.setPlatform(batch.getPlatform());
        vo.setFileName(batch.getFileName());
        vo.setRecordCount(batch.getRecordCount());
        vo.setTotalAmount(batch.getTotalAmount());
        vo.setStatus(batch.getStatus());
        vo.setImportedAt(batch.getImportedAt());
        vo.setRolledBackAt(batch.getRolledBackAt());
        // 明细含已软删流水（可追溯）
        List<TransactionRecord> records = transactionRecordMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TransactionRecord>()
                        .eq(TransactionRecord::getImportBatchId, batchId)
                        .orderByDesc(TransactionRecord::getOccurredAt));
        vo.setRecords(transactionService.enrich(records));
        return vo;
    }

    /**
     * 批次撤销（UC-4.11 / 规格 7.5）：单事务——
     * 条件更新抢占（台账 #10 TOCTOU 加固）：UPDATE ... SET status='rolled_back' WHERE id=? AND status='confirmed'，
     * 并发双撤销时第二个请求影响 0 行，直接拒绝，绝不二次冲销；
     * 置状态成功后逐条按当前值反向冲销（编辑过以当前值为准）→ 软删。撤销后可重新导入。
     */
    @Transactional
    public ImportBatch rollback(Long userId, Long batchId) {
        ImportBatch batch = ownedBatch(userId, batchId);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        int updated = importBatchMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ImportBatch>()
                .eq(ImportBatch::getId, batch.getId())
                .eq(ImportBatch::getStatus, "confirmed")
                .set(ImportBatch::getStatus, "rolled_back")
                .set(ImportBatch::getRolledBackAt, now));
        if (updated == 0) {
            throw new BizException("批次已撤销，请勿重复操作");
        }
        batch.setStatus("rolled_back");
        batch.setRolledBackAt(now);
        List<TransactionRecord> records = transactionRecordMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TransactionRecord>()
                        .eq(TransactionRecord::getImportBatchId, batchId)
                        .eq(TransactionRecord::getIsDeleted, 0));
        for (TransactionRecord r : records) {
            transactionService.reverse(r);
            r.setIsDeleted(1);
            transactionRecordMapper.updateById(r);
        }
        return batch;
    }

    private ImportBatch ownedBatch(Long userId, Long batchId) {
        ImportBatch batch = importBatchMapper.selectById(batchId);
        if (batch == null || !batch.getUserId().equals(userId)) {
            throw new BizException("批次不存在");
        }
        return batch;
    }

    private ImportBatchVo toVo(ImportBatch b) {
        ImportBatchVo vo = new ImportBatchVo();
        vo.setId(b.getId());
        vo.setPlatform(b.getPlatform());
        vo.setFileName(b.getFileName());
        vo.setRecordCount(b.getRecordCount());
        vo.setTotalAmount(b.getTotalAmount());
        vo.setStatus(b.getStatus());
        vo.setImportedAt(b.getImportedAt());
        vo.setRolledBackAt(b.getRolledBackAt());
        return vo;
    }
}
