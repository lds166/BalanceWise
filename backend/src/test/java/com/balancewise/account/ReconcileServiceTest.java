package com.balancewise.account;

import com.balancewise.account.dto.AccountRequest;
import com.balancewise.account.dto.ReconcileRequest;
import com.balancewise.account.entity.Account;
import com.balancewise.account.service.AccountService;
import com.balancewise.account.service.ReconcileService;
import com.balancewise.account.vo.ReconcileResultVo;
import com.balancewise.category.entity.Category;
import com.balancewise.category.mapper.CategoryMapper;
import com.balancewise.common.BizException;
import com.balancewise.record.entity.TransactionRecord;
import com.balancewise.record.mapper.TransactionRecordMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReconcileServiceTest {

    @Autowired
    private ReconcileService reconcileService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private TransactionRecordMapper transactionRecordMapper;
    @Autowired
    private CategoryMapper categoryMapper;

    private Account acc(String name, String type, String subType, String balance) {
        AccountRequest r = new AccountRequest();
        r.setName(name);
        r.setType(type);
        r.setSubType(subType);
        r.setInitialBalance(new BigDecimal(balance));
        r.setRemark("");
        return accountService.create(1L, r);
    }

    private ReconcileRequest req(String actual) {
        ReconcileRequest r = new ReconcileRequest();
        r.setActualBalance(new BigDecimal(actual));
        return r;
    }

    private long reconcileTxnCount() {
        return transactionRecordMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TransactionRecord>()
                .eq(TransactionRecord::getUserId, 1L)
                .eq(TransactionRecord::getSource, "reconcile"));
    }

    @Test
    void positiveDiffCreatesIncomeCorrection() {
        Account a = acc("对账卡A", "asset", "debit_card", "100.00");
        ReconcileResultVo vo = reconcileService.reconcile(1L, a.getId(), req("150.00"));

        assertEquals(0, new BigDecimal("100.00").compareTo(vo.getBeforeBalance()));
        assertEquals(0, new BigDecimal("150.00").compareTo(vo.getAfterBalance()));
        assertEquals(0, new BigDecimal("50.00").compareTo(vo.getDiff()));
        assertEquals("income", vo.getCorrectionType());
        assertNotNull(vo.getTransactionId());
        // 余额经既有 importRecord 路径联动
        assertEquals(0, new BigDecimal("150.00").compareTo(accountService.getById(a.getId()).getBalance()));
        // 校正流水可追溯：source=reconcile、备注"余额校正"
        TransactionRecord r = transactionRecordMapper.selectById(vo.getTransactionId());
        assertEquals("reconcile", r.getSource());
        assertEquals("income", r.getType());
        assertEquals(a.getId(), r.getToAccountId());
        assertNull(r.getFromAccountId());
        assertEquals("余额校正", r.getItem());
        assertEquals(0, new BigDecimal("50.00").compareTo(r.getAmount()));
    }

    @Test
    void negativeDiffCreatesExpenseCorrection() {
        Account a = acc("对账卡B", "asset", "debit_card", "100.00");
        ReconcileResultVo vo = reconcileService.reconcile(1L, a.getId(), req("80.00"));

        assertEquals("expense", vo.getCorrectionType());
        assertEquals(0, new BigDecimal("-20.00").compareTo(vo.getDiff()));
        assertEquals(0, new BigDecimal("80.00").compareTo(vo.getAfterBalance()));
        TransactionRecord r = transactionRecordMapper.selectById(vo.getTransactionId());
        assertEquals(a.getId(), r.getFromAccountId());
        assertNull(r.getToAccountId());
    }

    @Test
    void liabilityAccountUsesDisplaySign() {
        // 负债账户：界面按正数录入欠款 400，库内 balance = -400；账面 -500 → 差额 +100 记收入
        Account c = acc("对账信用C", "liability", "credit_card", "500.00");
        assertEquals(0, new BigDecimal("-500.00").compareTo(accountService.getById(c.getId()).getBalance()));

        ReconcileResultVo vo = reconcileService.reconcile(1L, c.getId(), req("400.00"));
        assertEquals("income", vo.getCorrectionType());
        assertEquals(0, new BigDecimal("100.00").compareTo(vo.getDiff()));
        assertEquals(0, new BigDecimal("-400.00").compareTo(vo.getAfterBalance()));
    }

    @Test
    void zeroDiffRejectedWithoutTransaction() {
        Account a = acc("对账卡D", "asset", "debit_card", "100.00");
        BizException e = assertThrows(BizException.class,
                () -> reconcileService.reconcile(1L, a.getId(), req("100.00")));
        assertTrue(e.getMessage().contains("无需校正"));
        assertEquals(0, reconcileTxnCount());
        assertEquals(0, new BigDecimal("100.00").compareTo(accountService.getById(a.getId()).getBalance()));
    }

    @Test
    void otherUserAccountRejected() {
        Account a = acc("对账卡E", "asset", "debit_card", "100.00");
        BizException e = assertThrows(BizException.class,
                () -> reconcileService.reconcile(2L, a.getId(), req("150.00")));
        assertEquals("账户不存在", e.getMessage());
        assertEquals(0, reconcileTxnCount());
    }

    @Test
    void archivedAccountRejected() {
        Account a = acc("对账卡F", "asset", "debit_card", "100.00");
        accountService.archive(1L, a.getId(), true);
        BizException e = assertThrows(BizException.class,
                () -> reconcileService.reconcile(1L, a.getId(), req("150.00")));
        assertTrue(e.getMessage().contains("已归档"));
        assertEquals(0, reconcileTxnCount());
    }

    @Test
    void correctionUsesOtherCategoryWhenPresent() {
        Category other = new Category();
        other.setUserId(1L);
        other.setName("其他支出");
        other.setKind("expense");
        other.setIsSystem(0);
        categoryMapper.insert(other);

        Account a = acc("对账卡G", "asset", "debit_card", "100.00");
        ReconcileResultVo vo = reconcileService.reconcile(1L, a.getId(), req("70.00"));
        TransactionRecord r = transactionRecordMapper.selectById(vo.getTransactionId());
        assertEquals(other.getId(), r.getCategoryId());
    }
}
