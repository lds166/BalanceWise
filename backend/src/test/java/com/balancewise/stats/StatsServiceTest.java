package com.balancewise.stats;

import com.balancewise.account.dto.AccountRequest;
import com.balancewise.account.entity.Account;
import com.balancewise.account.service.AccountService;
import com.balancewise.record.dto.TransactionRequest;
import com.balancewise.record.service.TransactionService;
import com.balancewise.stats.service.StatsService;
import com.balancewise.stats.vo.StatsSummaryVo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StatsServiceTest {

    @Autowired
    private StatsService statsService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private TransactionService transactionService;

    private Account acc(String name, String type, String subType, String balance) {
        AccountRequest r = new AccountRequest();
        r.setName(name);
        r.setType(type);
        r.setSubType(subType);
        r.setInitialBalance(new BigDecimal(balance));
        r.setRemark("");
        return accountService.create(1L, r);
    }

    /** occurredAt 固定，避免依赖当前时间 */
    private void txn(String type, Long from, Long to, String amount, LocalDateTime at) {
        TransactionRequest r = new TransactionRequest();
        r.setType(type);
        r.setFromAccountId(from);
        r.setToAccountId(to);
        r.setAmount(new BigDecimal(amount));
        r.setOccurredAt(at);
        transactionService.create(1L, r);
    }

    @Test
    void emptyDataReturnsZeros() {
        StatsSummaryVo vo = statsService.summary(1L,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null);
        assertEquals(0, new BigDecimal("0.00").compareTo(vo.getIncome()));
        assertEquals(0, new BigDecimal("0.00").compareTo(vo.getExpense()));
        assertEquals(0, new BigDecimal("0.00").compareTo(vo.getBalance()));
    }

    @Test
    void aggregatesIncomeExpenseAndBalance() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        Account b = acc("钱包", "asset", "cash", "0.00");
        txn("expense", a.getId(), null, "200.00", LocalDateTime.of(2026, 10, 5, 12, 0));
        txn("expense", a.getId(), null, "300.00", LocalDateTime.of(2026, 10, 6, 12, 0));
        txn("income", null, b.getId(), "5000.00", LocalDateTime.of(2026, 10, 7, 12, 0));
        // 区间外流水不进统计
        txn("expense", a.getId(), null, "999.00", LocalDateTime.of(2026, 11, 1, 12, 0));
        // 转账不进收支
        txn("transfer", a.getId(), b.getId(), "100.00", LocalDateTime.of(2026, 10, 8, 12, 0));

        StatsSummaryVo vo = statsService.summary(1L,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null);
        assertEquals(0, new BigDecimal("5000.00").compareTo(vo.getIncome()));
        assertEquals(0, new BigDecimal("500.00").compareTo(vo.getExpense()));
        assertEquals(0, new BigDecimal("4500.00").compareTo(vo.getBalance()));
    }

    @Test
    void rangeAndAccountFilter() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        Account b = acc("卡B", "asset", "cash", "0.00");
        txn("expense", a.getId(), null, "100.00", LocalDateTime.of(2026, 10, 5, 12, 0));
        txn("expense", a.getId(), null, "200.00", LocalDateTime.of(2026, 11, 5, 12, 0)); // 区间外
        txn("income", null, b.getId(), "1000.00", LocalDateTime.of(2026, 10, 6, 12, 0));

        StatsSummaryVo oct = statsService.summary(1L,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null);
        assertEquals(0, new BigDecimal("100.00").compareTo(oct.getExpense()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(oct.getIncome()));

        // 账户筛选：只看账户 a → 只有支出 100（收入落在 b）
        StatsSummaryVo byA = statsService.summary(1L,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), a.getId());
        assertEquals(0, new BigDecimal("100.00").compareTo(byA.getExpense()));
        assertEquals(0, BigDecimal.ZERO.compareTo(byA.getIncome()));
    }
}
