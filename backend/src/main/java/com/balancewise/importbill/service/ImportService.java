package com.balancewise.importbill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.balancewise.account.entity.Account;
import com.balancewise.common.BizException;
import com.balancewise.importbill.dto.ImportPreviewRow;
import com.balancewise.importbill.parser.BillParseResult;
import com.balancewise.importbill.parser.BillParser;
import com.balancewise.importbill.parser.ParsedRow;
import com.balancewise.importbill.vo.ImportPreviewVo;
import com.balancewise.record.entity.TransactionRecord;
import com.balancewise.record.mapper.TransactionRecordMapper;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 账单导入编排（UC-4.1~4.7 预览侧）。
 * 预览不落库：解析 → 清洗 → 类型识别 → 分类映射 → 账户匹配 → 重复检测（库中 is_deleted=0 同源 + 批内），返回预览行。
 */
@Service
public class ImportService {

    private final Map<String, BillParser> parsers;
    private final CategoryMappingService categoryMappingService;
    private final AccountMatcher accountMatcher;
    private final TransactionRecordMapper transactionRecordMapper;

    public ImportService(List<BillParser> parserList,
                         CategoryMappingService categoryMappingService,
                         AccountMatcher accountMatcher,
                         TransactionRecordMapper transactionRecordMapper) {
        this.parsers = parserList.stream().collect(Collectors.toMap(BillParser::platform, Function.identity()));
        this.categoryMappingService = categoryMappingService;
        this.accountMatcher = accountMatcher;
        this.transactionRecordMapper = transactionRecordMapper;
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
            row.setCategoryId(categoryMappingService.mapCategoryId(
                    userId, r.getType(), r.getPlatformCategory(), r.getItem()));
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
}
