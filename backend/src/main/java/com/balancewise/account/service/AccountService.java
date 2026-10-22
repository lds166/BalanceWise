package com.balancewise.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.balancewise.account.dto.AccountRequest;
import com.balancewise.account.entity.Account;
import com.balancewise.account.mapper.AccountMapper;
import com.balancewise.account.vo.AccountSummaryVo;
import com.balancewise.common.BizException;
import com.balancewise.record.entity.TransactionRecord;
import com.balancewise.record.mapper.TransactionRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

@Service
public class AccountService extends ServiceImpl<AccountMapper, Account> {

    public static final Set<String> ASSET_SUBTYPES = Set.of("cash", "debit_card", "wechat", "alipay", "yuebao", "custom");
    public static final Set<String> LIABILITY_SUBTYPES = Set.of("credit_card", "huabei", "custom");

    private final TransactionRecordMapper transactionRecordMapper;

    public AccountService(TransactionRecordMapper transactionRecordMapper) {
        this.transactionRecordMapper = transactionRecordMapper;
    }

    @Transactional
    public void createDefault(Long userId) {
        Account a = new Account();
        a.setUserId(userId);
        a.setName("现金");
        a.setType("asset");
        a.setSubType("cash");
        a.setBalance(BigDecimal.ZERO.setScale(2));
        a.setInitialBalance(BigDecimal.ZERO.setScale(2));
        a.setIsArchived(0);
        a.setSortOrder(0);
        save(a);
    }

    private void validateSubType(String type, String subType) {
        boolean ok = "asset".equals(type) ? ASSET_SUBTYPES.contains(subType)
                : LIABILITY_SUBTYPES.contains(subType);
        if (!ok) {
            throw new BizException("子类型与账户类别不匹配");
        }
    }

    @Transactional
    public Account create(Long userId, AccountRequest req) {
        validateSubType(req.getType(), req.getSubType());
        Account a = new Account();
        a.setUserId(userId);
        a.setName(req.getName());
        a.setType(req.getType());
        a.setSubType(req.getSubType());
        // 负债账户余额为负（欠款）
        BigDecimal initial = req.getInitialBalance().setScale(2);
        a.setInitialBalance(initial);
        a.setBalance("asset".equals(req.getType()) ? initial : initial.negate());
        a.setRemark(req.getRemark());
        a.setIsArchived(0);
        a.setSortOrder(0);
        save(a);
        return a;
    }

    private Account owned(Long userId, Long accountId) {
        Account a = getById(accountId);
        if (a == null || !a.getUserId().equals(userId)) {
            throw new BizException("账户不存在");
        }
        return a;
    }

    @Transactional
    public Account update(Long userId, Long accountId, AccountRequest req) {
        Account a = owned(userId, accountId);
        validateSubType(req.getType(), req.getSubType());
        a.setName(req.getName());
        a.setType(req.getType());
        a.setSubType(req.getSubType());
        a.setRemark(req.getRemark());
        updateById(a);
        return a;
    }

    @Transactional
    public void delete(Long userId, Long accountId) {
        Account a = owned(userId, accountId);
        if (countByAccount(userId, accountId) > 0) {
            throw new BizException("该账户存在交易记录，无法删除，可选择归档");
        }
        removeById(a.getId());
    }

    @Transactional
    public void archive(Long userId, Long accountId, boolean isArchived) {
        Account a = owned(userId, accountId);
        a.setIsArchived(isArchived ? 1 : 0);
        updateById(a);
    }

    public long countByAccount(Long userId, Long accountId) {
        return transactionRecordMapper.selectCount(new LambdaQueryWrapper<TransactionRecord>()
                .eq(TransactionRecord::getUserId, userId)
                .eq(TransactionRecord::getIsDeleted, 0)
                .and(w -> w.eq(TransactionRecord::getFromAccountId, accountId)
                        .or().eq(TransactionRecord::getToAccountId, accountId)));
    }

    public AccountSummaryVo summary(Long userId) {
        List<Account> accounts = list(new LambdaQueryWrapper<Account>()
                .eq(Account::getUserId, userId)
                .orderByAsc(Account::getSortOrder)
                .orderByAsc(Account::getId));
        BigDecimal netWorth = BigDecimal.ZERO;
        BigDecimal assetTotal = BigDecimal.ZERO;
        BigDecimal liabilityTotal = BigDecimal.ZERO;
        for (Account a : accounts) {
            netWorth = netWorth.add(a.getBalance());
            if ("asset".equals(a.getType())) {
                assetTotal = assetTotal.add(a.getBalance());
            } else {
                liabilityTotal = liabilityTotal.add(a.getBalance().negate());
            }
        }
        AccountSummaryVo vo = new AccountSummaryVo();
        vo.setAccounts(accounts);
        vo.setNetWorth(netWorth);
        vo.setAssetTotal(assetTotal);
        vo.setLiabilityTotal(liabilityTotal);
        return vo;
    }
}
