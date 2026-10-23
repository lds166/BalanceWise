package com.balancewise.record.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.balancewise.account.entity.Account;
import com.balancewise.account.mapper.AccountMapper;
import com.balancewise.category.entity.Category;
import com.balancewise.category.mapper.CategoryMapper;
import com.balancewise.common.BizException;
import com.balancewise.common.PageResult;
import com.balancewise.record.dto.TransactionQuery;
import com.balancewise.record.dto.TransactionRequest;
import com.balancewise.record.entity.TransactionRecord;
import com.balancewise.record.mapper.TransactionRecordMapper;
import com.balancewise.record.vo.TransactionVo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TransactionService extends ServiceImpl<TransactionRecordMapper, TransactionRecord> {

    private final AccountMapper accountMapper;
    private final CategoryMapper categoryMapper;

    public TransactionService(AccountMapper accountMapper, CategoryMapper categoryMapper) {
        this.accountMapper = accountMapper;
        this.categoryMapper = categoryMapper;
    }

    /**
     * 余额联动（规格 5.4 规则 1/2）：原子增减，任何流水 = from 减、to 加。
     * 负债账户余额为负，因此还款/负债消费方向全部自洽。
     */
    protected void applyBalance(Long fromAccountId, Long toAccountId, BigDecimal amount) {
        if (fromAccountId != null) {
            accountMapper.addBalance(fromAccountId, amount.negate());
        }
        if (toAccountId != null) {
            accountMapper.addBalance(toAccountId, amount);
        }
    }

    protected Account usableAccount(Long userId, Long accountId, String label) {
        Account a = accountMapper.selectById(accountId);
        if (a == null || !a.getUserId().equals(userId)) {
            throw new BizException(label + "不存在");
        }
        if (a.getIsArchived() == 1) {
            throw new BizException("账户「" + a.getName() + "」已归档，无法记账");
        }
        return a;
    }

    protected void validateCategory(Long userId, String type, Long categoryId) {
        if (categoryId == null || "transfer".equals(type)) {
            return;
        }
        Category c = categoryMapper.selectById(categoryId);
        if (c == null || !c.getUserId().equals(userId)) {
            throw new BizException("分类不存在");
        }
        String expectKind = "expense".equals(type) ? "expense" : "income";
        if (!expectKind.equals(c.getKind())) {
            throw new BizException("分类类型与流水类型不匹配");
        }
    }

    /** 按类型解析并校验 from/to，返回 [from, to]（收入 from 为 null，支出 to 为 null） */
    protected Long[] resolveAccounts(Long userId, TransactionRequest req) {
        Long from = null;
        Long to = null;
        switch (req.getType()) {
            case "expense" -> {
                if (req.getFromAccountId() == null) {
                    throw new BizException("请选择支出账户");
                }
                usableAccount(userId, req.getFromAccountId(), "支出账户");
                from = req.getFromAccountId();
            }
            case "income" -> {
                if (req.getToAccountId() == null) {
                    throw new BizException("请选择收入账户");
                }
                usableAccount(userId, req.getToAccountId(), "收入账户");
                to = req.getToAccountId();
            }
            case "transfer" -> {
                if (req.getFromAccountId() == null || req.getToAccountId() == null) {
                    throw new BizException("转账需要转出和转入账户");
                }
                if (req.getFromAccountId().equals(req.getToAccountId())) {
                    throw new BizException("转出与转入账户不能相同");
                }
                usableAccount(userId, req.getFromAccountId(), "转出账户");
                usableAccount(userId, req.getToAccountId(), "转入账户");
                from = req.getFromAccountId();
                to = req.getToAccountId();
            }
            default -> throw new BizException("不支持的流水类型");
        }
        return new Long[]{from, to};
    }

    protected BigDecimal normalizeAmount(TransactionRequest req) {
        return req.getAmount().setScale(2, RoundingMode.HALF_UP);
    }

    @Transactional
    public TransactionRecord create(Long userId, TransactionRequest req) {
        Long[] accounts = resolveAccounts(userId, req);
        validateCategory(userId, req.getType(), req.getCategoryId());
        BigDecimal amount = normalizeAmount(req);

        TransactionRecord r = new TransactionRecord();
        r.setUserId(userId);
        r.setType(req.getType());
        r.setFromAccountId(accounts[0]);
        r.setToAccountId(accounts[1]);
        r.setAmount(amount);
        r.setCategoryId("transfer".equals(req.getType()) ? null : req.getCategoryId());
        r.setOccurredAt(req.getOccurredAt() != null ? req.getOccurredAt() : LocalDateTime.now());
        r.setCounterparty(req.getCounterparty());
        r.setItem(req.getItem());
        r.setSource("manual");
        r.setIsDeleted(0);
        save(r);

        applyBalance(accounts[0], accounts[1], amount);
        return r;
    }

    protected TransactionRecord owned(Long userId, Long id) {
        TransactionRecord r = getById(id);
        if (r == null || !r.getUserId().equals(userId) || r.getIsDeleted() == 1) {
            throw new BizException("流水不存在");
        }
        return r;
    }

    /**
     * 编辑流水（规格 7.2）：事务内两步法——旧记录反向冲销（旧 from 加回、旧 to 减回），
     * 新记录按新值入账。天然支持换账户、变更类型（expense↔income↔transfer）。
     * source/externalId/importBatchId 保持不变（导入溯源信息不可因编辑丢失）。
     */
    @Transactional
    public TransactionRecord update(Long userId, Long id, TransactionRequest req) {
        TransactionRecord old = owned(userId, id);
        Long[] accounts = resolveAccounts(userId, req);
        validateCategory(userId, req.getType(), req.getCategoryId());
        BigDecimal amount = normalizeAmount(req);

        // 旧记录反向冲销
        applyBalance(old.getFromAccountId(), old.getToAccountId(), old.getAmount().negate());

        old.setType(req.getType());
        old.setFromAccountId(accounts[0]);
        old.setToAccountId(accounts[1]);
        old.setAmount(amount);
        old.setCategoryId("transfer".equals(req.getType()) ? null : req.getCategoryId());
        if (req.getOccurredAt() != null) {
            old.setOccurredAt(req.getOccurredAt());
        }
        old.setCounterparty(req.getCounterparty());
        old.setItem(req.getItem());
        updateById(old);

        // 新记录入账
        applyBalance(accounts[0], accounts[1], amount);
        return old;
    }

    /**
     * 删除流水（规格 7.3）：事务内反向冲销余额 → is_deleted=1（软删除，可追溯）。
     */
    @Transactional
    public void delete(Long userId, Long id) {
        TransactionRecord r = owned(userId, id);
        applyBalance(r.getFromAccountId(), r.getToAccountId(), r.getAmount().negate());
        r.setIsDeleted(1);
        updateById(r);
    }

    /**
     * 构建流水查询条件（分页与导出共用，Task 5 复用）。
     * 只查 is_deleted=0；账户筛选命中 from 或 to；关键字模糊匹配对方/备注。
     */
    protected LambdaQueryWrapper<TransactionRecord> buildWrapper(Long userId, TransactionQuery q) {
        LambdaQueryWrapper<TransactionRecord> w = new LambdaQueryWrapper<>();
        w.eq(TransactionRecord::getUserId, userId).eq(TransactionRecord::getIsDeleted, 0);
        if (q.getType() != null && !q.getType().isBlank()) {
            w.eq(TransactionRecord::getType, q.getType());
        }
        if (q.getAccountId() != null) {
            w.and(x -> x.eq(TransactionRecord::getFromAccountId, q.getAccountId())
                    .or().eq(TransactionRecord::getToAccountId, q.getAccountId()));
        }
        if (q.getCategoryId() != null) {
            w.eq(TransactionRecord::getCategoryId, q.getCategoryId());
        }
        if (q.getStart() != null) {
            w.ge(TransactionRecord::getOccurredAt, q.getStart().atStartOfDay());
        }
        if (q.getEnd() != null) {
            w.le(TransactionRecord::getOccurredAt, q.getEnd().atTime(23, 59, 59));
        }
        if (q.getKeyword() != null && !q.getKeyword().isBlank()) {
            w.and(x -> x.like(TransactionRecord::getCounterparty, q.getKeyword())
                    .or().like(TransactionRecord::getItem, q.getKeyword()));
        }
        return w;
    }

    /** 批量填充账户名与分类名，避免 N+1 */
    protected List<TransactionVo> enrich(List<TransactionRecord> list) {
        Set<Long> accIds = new HashSet<>();
        Set<Long> catIds = new HashSet<>();
        for (TransactionRecord r : list) {
            if (r.getFromAccountId() != null) accIds.add(r.getFromAccountId());
            if (r.getToAccountId() != null) accIds.add(r.getToAccountId());
            if (r.getCategoryId() != null) catIds.add(r.getCategoryId());
        }
        Map<Long, Account> accMap = accIds.isEmpty() ? Map.of()
                : accountMapper.selectBatchIds(accIds).stream()
                        .collect(Collectors.toMap(Account::getId, a -> a));
        Map<Long, Category> catMap = catIds.isEmpty() ? Map.of()
                : categoryMapper.selectBatchIds(catIds).stream()
                        .collect(Collectors.toMap(Category::getId, c -> c));
        List<TransactionVo> vos = new ArrayList<>(list.size());
        for (TransactionRecord r : list) {
            TransactionVo v = new TransactionVo();
            v.setId(r.getId());
            v.setType(r.getType());
            v.setFromAccountId(r.getFromAccountId());
            v.setToAccountId(r.getToAccountId());
            Account fa = r.getFromAccountId() != null ? accMap.get(r.getFromAccountId()) : null;
            Account ta = r.getToAccountId() != null ? accMap.get(r.getToAccountId()) : null;
            v.setFromAccountName(fa != null ? fa.getName() : null);
            v.setToAccountName(ta != null ? ta.getName() : null);
            v.setAmount(r.getAmount());
            v.setCategoryId(r.getCategoryId());
            Category c = r.getCategoryId() != null ? catMap.get(r.getCategoryId()) : null;
            v.setCategoryName(c != null ? c.getName() : null);
            v.setOccurredAt(r.getOccurredAt());
            v.setCounterparty(r.getCounterparty());
            v.setItem(r.getItem());
            v.setSource(r.getSource());
            v.setExternalId(r.getExternalId());
            v.setImportBatchId(r.getImportBatchId());
            vos.add(v);
        }
        return vos;
    }

    public PageResult<TransactionVo> page(Long userId, TransactionQuery q) {
        long pageNo = Math.max(q.getPage(), 1);
        long size = Math.min(Math.max(q.getSize(), 1), 100);
        var wrapper = buildWrapper(userId, q);
        wrapper.orderByDesc(TransactionRecord::getOccurredAt).orderByDesc(TransactionRecord::getId);
        Page<TransactionRecord> p = page(new Page<>(pageNo, size), wrapper);
        return new PageResult<>(p.getTotal(), enrich(p.getRecords()));
    }

    private static final java.util.Map<String, String> TYPE_LABEL = java.util.Map.of(
            "expense", "支出", "income", "收入", "transfer", "转账");
    private static final java.util.Map<String, String> SOURCE_LABEL = java.util.Map.of(
            "manual", "手工", "alipay", "支付宝", "wechat", "微信", "reconcile", "余额校正");

    /** CSV 字段转义：含逗号/引号/换行则加引号并翻倍引号 */
    private String csv(String s) {
        if (s == null) {
            return "";
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /**
     * 按当前筛选条件导出 CSV（UC-3.6）。字段：类型,分类,账户,金额,时间,对方,备注,来源,批次号。
     * 带 UTF-8 BOM 以便 Excel 直接打开中文。上限 50000 行。
     */
    public String exportCsv(Long userId, TransactionQuery q) {
        var wrapper = buildWrapper(userId, q);
        wrapper.orderByDesc(TransactionRecord::getOccurredAt).orderByDesc(TransactionRecord::getId);
        wrapper.last("LIMIT 50000");
        java.util.List<TransactionRecord> list = list(wrapper);
        if (list.size() >= 50000) {
            throw new BizException("导出数据超过 50000 行，请缩小筛选范围");
        }

        java.time.format.DateTimeFormatter fmt =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("类型,分类,账户,金额,时间,对方,备注,来源,批次号\n");
        java.util.List<TransactionVo> vos = enrich(list);
        for (TransactionVo v : vos) {
            String acc = "transfer".equals(v.getType())
                    ? (v.getFromAccountName() != null ? v.getFromAccountName() : "")
                      + " → " + (v.getToAccountName() != null ? v.getToAccountName() : "")
                    : ("expense".equals(v.getType()) ? v.getFromAccountName() : v.getToAccountName());
            sb.append(csv(TYPE_LABEL.getOrDefault(v.getType(), v.getType()))).append(',')
              .append(csv(v.getCategoryName())).append(',')
              .append(csv(acc)).append(',')
              .append(v.getAmount().toPlainString()).append(',')
              .append(v.getOccurredAt() != null ? v.getOccurredAt().format(fmt) : "").append(',')
              .append(csv(v.getCounterparty())).append(',')
              .append(csv(v.getItem())).append(',')
              .append(csv(SOURCE_LABEL.getOrDefault(v.getSource(), v.getSource()))).append(',')
              .append(v.getImportBatchId() != null ? String.valueOf(v.getImportBatchId()) : "")
              .append('\n');
        }
        return sb.toString();
    }
}
