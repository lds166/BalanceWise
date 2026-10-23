package com.balancewise.record.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.balancewise.account.entity.Account;
import com.balancewise.account.mapper.AccountMapper;
import com.balancewise.category.entity.Category;
import com.balancewise.category.mapper.CategoryMapper;
import com.balancewise.common.BizException;
import com.balancewise.record.dto.TransactionRequest;
import com.balancewise.record.entity.TransactionRecord;
import com.balancewise.record.mapper.TransactionRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

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
}
