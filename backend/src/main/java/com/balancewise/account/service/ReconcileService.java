package com.balancewise.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.balancewise.account.dto.ReconcileRequest;
import com.balancewise.account.entity.Account;
import com.balancewise.account.mapper.AccountMapper;
import com.balancewise.account.vo.ReconcileResultVo;
import com.balancewise.category.entity.Category;
import com.balancewise.category.mapper.CategoryMapper;
import com.balancewise.common.BizException;
import com.balancewise.record.dto.TransactionRequest;
import com.balancewise.record.entity.TransactionRecord;
import com.balancewise.record.service.TransactionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 余额校正（UC-2.6 / 规格 7.6）：差额 = 实际余额 − 当前余额，为 0 拒绝；
 * 差额 > 0 记收入（to=该账户）、< 0 记支出（from=该账户），分类归入「其他」，备注"余额校正"，source=reconcile。
 * 校正流水复用 TransactionService.importRecord 既有路径（校验/余额联动/事务零新增），可查询、可编辑、可删除冲销。
 */
@Service
public class ReconcileService {

    private final AccountMapper accountMapper;
    private final CategoryMapper categoryMapper;
    private final TransactionService transactionService;

    public ReconcileService(AccountMapper accountMapper, CategoryMapper categoryMapper,
                            TransactionService transactionService) {
        this.accountMapper = accountMapper;
        this.categoryMapper = categoryMapper;
        this.transactionService = transactionService;
    }

    @Transactional
    public ReconcileResultVo reconcile(Long userId, Long accountId, ReconcileRequest req) {
        Account a = accountMapper.selectById(accountId);
        if (a == null || !a.getUserId().equals(userId)) {
            throw new BizException("账户不存在");
        }
        if (a.getIsArchived() == 1) {
            throw new BizException("账户「" + a.getName() + "」已归档，请先恢复后再对账");
        }

        // 口径归一：负债账户界面按正数欠款录入，库内余额为负（规格 5.4 规则 2）
        BigDecimal actualStored = req.getActualBalance().setScale(2, RoundingMode.HALF_UP);
        if ("liability".equals(a.getType())) {
            actualStored = actualStored.negate();
        }
        BigDecimal before = a.getBalance();
        BigDecimal diff = actualStored.subtract(before);
        if (diff.compareTo(BigDecimal.ZERO) == 0) {
            throw new BizException("无需校正：账户余额与实际一致");
        }

        boolean positive = diff.compareTo(BigDecimal.ZERO) > 0;
        TransactionRequest txn = new TransactionRequest();
        txn.setType(positive ? "income" : "expense");
        txn.setToAccountId(positive ? accountId : null);
        txn.setFromAccountId(positive ? null : accountId);
        txn.setAmount(diff.abs());
        txn.setCategoryId(findOtherCategoryId(userId, positive ? "income" : "expense"));
        txn.setOccurredAt(LocalDateTime.now());
        txn.setItem("余额校正");
        // 复用导入入账同款路径：source=reconcile，余额联动与事务由 createInternal 单一抽象承载
        TransactionRecord r = transactionService.importRecord(userId, "reconcile", null, null, txn);

        ReconcileResultVo vo = new ReconcileResultVo();
        vo.setAccountId(a.getId());
        vo.setAccountName(a.getName());
        vo.setBeforeBalance(before);
        vo.setAfterBalance(accountMapper.selectById(accountId).getBalance());
        vo.setDiff(diff);
        vo.setCorrectionType(positive ? "income" : "expense");
        vo.setTransactionId(r.getId());
        return vo;
    }

    /** 「其他支出/其他收入」一级分类（预置分类，用户可能改名故找不到时归未分类，不阻断对账） */
    private Long findOtherCategoryId(Long userId, String kind) {
        String name = "income".equals(kind) ? "其他收入" : "其他支出";
        Category c = categoryMapper.selectOne(new LambdaQueryWrapper<Category>()
                .eq(Category::getUserId, userId)
                .eq(Category::getKind, kind)
                .isNull(Category::getParentId)
                .eq(Category::getName, name)
                .last("LIMIT 1"));
        return c != null ? c.getId() : null;
    }
}
