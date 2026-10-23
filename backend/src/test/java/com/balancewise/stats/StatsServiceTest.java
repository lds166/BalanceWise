package com.balancewise.stats;

import com.balancewise.account.dto.AccountRequest;
import com.balancewise.common.BizException;
import com.balancewise.stats.dto.StatsTrendQuery;
import com.balancewise.stats.vo.StatsTrendVo;
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

    private StatsTrendQuery trendQuery(String groupBy, Integer months, LocalDate start, LocalDate end) {
        StatsTrendQuery q = new StatsTrendQuery();
        q.setGroupBy(groupBy);
        q.setMonths(months);
        q.setStart(start);
        q.setEnd(end);
        return q;
    }

    @Test
    void trendValidatesGroupByAndDayRange() {
        // groupBy 非法
        BizException bad = assertThrows(BizException.class,
                () -> statsService.trend(1L, trendQuery("week", null, null, null)));
        assertEquals("groupBy 必须是 month 或 day", bad.getMessage());
        // day 模式缺 start/end
        BizException noRange = assertThrows(BizException.class,
                () -> statsService.trend(1L, trendQuery("day", null, null, null)));
        assertEquals("按日统计必须提供 start 和 end", noRange.getMessage());
        // start 晚于 end
        BizException reversed = assertThrows(BizException.class,
                () -> statsService.trend(1L, trendQuery("day", null,
                        LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 1))));
        assertEquals("start 不能晚于 end", reversed.getMessage());
        // 按日跨度超 366 天
        BizException tooLong = assertThrows(BizException.class,
                () -> statsService.trend(1L, trendQuery("day", null,
                        LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 10))));
        assertEquals("按日统计区间不能超过 366 天", tooLong.getMessage());
        // months 超界
        BizException badMonths = assertThrows(BizException.class,
                () -> statsService.trend(1L, trendQuery("month", 99, null, null)));
        assertEquals("months 必须在 1~24 之间", badMonths.getMessage());
    }

    @Test
    void trendByDayAggregatesAndZeroFills() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        Account b = acc("卡B", "asset", "cash", "0.00");
        txn("expense", a.getId(), null, "100.00", LocalDateTime.of(2026, 10, 1, 8, 30));
        txn("income", null, b.getId(), "400.00", LocalDateTime.of(2026, 10, 3, 9, 0));
        // 10-02 无数据 → 补零点；10-04 无数据 → 补零点

        StatsTrendVo vo = statsService.trend(1L, trendQuery("day", null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 4)));
        assertEquals(4, vo.getPoints().size());
        assertEquals("2026-10-01", vo.getPoints().get(0).getLabel());
        assertEquals("2026-10-04", vo.getPoints().get(3).getLabel());
        assertEquals(0, new BigDecimal("100.00").compareTo(vo.getPoints().get(0).getExpense()));
        assertEquals(0, new BigDecimal("-100.00").compareTo(vo.getPoints().get(0).getBalance()));
        // 10-02 补零
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getPoints().get(1).getIncome()));
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getPoints().get(1).getExpense()));
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getPoints().get(1).getBalance()));
        // 10-03 收入 400
        assertEquals(0, new BigDecimal("400.00").compareTo(vo.getPoints().get(2).getIncome()));
        // 区间外流水不进趋势
        txn("expense", a.getId(), null, "999.00", LocalDateTime.of(2026, 10, 5, 8, 30));
        StatsTrendVo after = statsService.trend(1L, trendQuery("day", null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 4)));
        assertEquals(4, after.getPoints().size());
        assertEquals(0, new BigDecimal("100.00").compareTo(after.getPoints().get(0).getExpense()));
    }

    @Test
    void trendByMonthWithExplicitRangeZeroFillsMiddle() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        txn("expense", a.getId(), null, "100.00", LocalDateTime.of(2026, 9, 15, 12, 0));
        txn("expense", a.getId(), null, "200.00", LocalDateTime.of(2026, 11, 5, 12, 0));
        // 10 月无数据 → 补零桶

        StatsTrendVo vo = statsService.trend(1L, trendQuery("month", null,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 11, 30)));
        assertEquals(3, vo.getPoints().size());
        assertEquals("2026-09", vo.getPoints().get(0).getLabel());
        assertEquals("2026-10", vo.getPoints().get(1).getLabel());
        assertEquals("2026-11", vo.getPoints().get(2).getLabel());
        assertEquals(0, new BigDecimal("100.00").compareTo(vo.getPoints().get(0).getExpense()));
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getPoints().get(1).getExpense()));
        assertEquals(0, new BigDecimal("200.00").compareTo(vo.getPoints().get(2).getExpense()));
        assertEquals(0, new BigDecimal("-200.00").compareTo(vo.getPoints().get(2).getBalance()));
    }

    @Test
    void trendSupportsAccountFilter() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        Account b = acc("卡B", "asset", "cash", "0.00");
        txn("expense", a.getId(), null, "100.00", LocalDateTime.of(2026, 10, 1, 8, 30));
        txn("income", null, b.getId(), "1000.00", LocalDateTime.of(2026, 10, 1, 9, 0));

        StatsTrendQuery q = trendQuery("day", null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1));
        q.setAccountId(a.getId());
        StatsTrendVo vo = statsService.trend(1L, q);
        assertEquals(0, new BigDecimal("100.00").compareTo(vo.getPoints().get(0).getExpense()));
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getPoints().get(0).getIncome()));
    }
}
