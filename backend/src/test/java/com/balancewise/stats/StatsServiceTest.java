package com.balancewise.stats;

import com.balancewise.account.dto.AccountRequest;
import com.balancewise.common.BizException;
import com.balancewise.stats.dto.StatsCategoryQuery;
import com.balancewise.stats.dto.StatsTrendQuery;
import com.balancewise.stats.vo.StatsCategoryVo;
import com.balancewise.stats.vo.StatsTrendVo;
import com.balancewise.account.entity.Account;
import com.balancewise.account.service.AccountService;
import com.balancewise.record.dto.TransactionRequest;
import com.balancewise.record.service.TransactionService;
import com.balancewise.stats.service.StatsService;
import com.balancewise.stats.vo.StatsNetWorthVo;
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
        return accFor(1L, name, type, subType, balance);
    }

    private Account accFor(Long userId, String name, String type, String subType, String balance) {
        AccountRequest r = new AccountRequest();
        r.setName(name);
        r.setType(type);
        r.setSubType(subType);
        r.setInitialBalance(new BigDecimal(balance));
        r.setRemark("");
        return accountService.create(userId, r);
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

    @Test
    void netWorthReconstructsFromInitialAndFlows() {
        // 储蓄卡 initial 1000（资产）；信用卡 initial 500（负债 → 净初始 = 1000 - 500 = 500）
        Account card = acc("储蓄卡", "asset", "debit_card", "1000.00");
        Account credit = acc("信用卡", "liability", "credit_card", "500.00");
        txn("expense", card.getId(), null, "100.00", LocalDateTime.of(2026, 10, 1, 8, 30));
        txn("income", null, card.getId(), "700.00", LocalDateTime.of(2026, 10, 2, 9, 0));

        StatsNetWorthVo vo = statsService.netWorth(1L, trendQuery("day", null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3)));
        assertEquals(0, new BigDecimal("500.00").compareTo(vo.getInitialNetWorth()));
        assertEquals(3, vo.getPoints().size());
        // T=10-01：500 - 100 = 400；T=10-02：400 + 700 = 1100；T=10-03 无流水 → 持平 1100
        assertEquals(0, new BigDecimal("400.00").compareTo(vo.getPoints().get(0).getNetWorth()));
        assertEquals(0, new BigDecimal("1100.00").compareTo(vo.getPoints().get(1).getNetWorth()));
        assertEquals(0, new BigDecimal("1100.00").compareTo(vo.getPoints().get(2).getNetWorth()));
    }

    @Test
    void netWorthIgnoresTransfersAndCountsFromBeginning() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        Account b = acc("卡B", "asset", "cash", "0.00");
        // 区间开始之前的流水必须计入（自时间起点累计）
        txn("income", null, a.getId(), "500.00", LocalDateTime.of(2026, 9, 1, 8, 30));
        // 转账双向抵消，不改变净资产
        txn("transfer", a.getId(), b.getId(), "100.00", LocalDateTime.of(2026, 10, 1, 8, 30));

        StatsNetWorthVo vo = statsService.netWorth(1L, trendQuery("day", null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1)));
        assertEquals(0, new BigDecimal("1000.00").compareTo(vo.getInitialNetWorth()));
        assertEquals(0, new BigDecimal("1500.00").compareTo(vo.getPoints().get(0).getNetWorth()));
    }

    @Autowired
    private com.balancewise.category.mapper.CategoryMapper categoryMapper;

    private com.balancewise.category.entity.Category cat(String name, String kind, Long parentId, Long userId) {
        com.balancewise.category.entity.Category c = new com.balancewise.category.entity.Category();
        c.setUserId(userId);
        c.setName(name);
        c.setKind(kind);
        c.setParentId(parentId);
        c.setIsSystem(0);
        categoryMapper.insert(c);
        return c;
    }

    /** 造数纪律：分类必须在 create 时带 categoryId（已记账流水再改分类会触发冲销，不用于统计造数） */
    private void expenseWithCategory(Account from, String amount, LocalDateTime at, Long categoryId) {
        com.balancewise.record.dto.TransactionRequest r = new com.balancewise.record.dto.TransactionRequest();
        r.setType("expense");
        r.setFromAccountId(from.getId());
        r.setAmount(new BigDecimal(amount));
        r.setOccurredAt(at);
        r.setCategoryId(categoryId);
        transactionService.create(1L, r);
    }

    @Test
    void categoryRollsUpToTopLevelWithChildren() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        var food = cat("餐饮", "expense", null, 1L);
        var meal = cat("三餐", "expense", food.getId(), 1L);
        var takeout = cat("外卖", "expense", food.getId(), 1L);
        // 餐饮一级：直接 10 + 三餐 30 + 外卖 20 = 60；未分类 15
        expenseWithCategory(a, "30.00", LocalDateTime.of(2026, 10, 1, 8, 0), meal.getId());
        expenseWithCategory(a, "20.00", LocalDateTime.of(2026, 10, 2, 8, 0), takeout.getId());
        expenseWithCategory(a, "10.00", LocalDateTime.of(2026, 10, 3, 8, 0), food.getId());
        expenseWithCategory(a, "15.00", LocalDateTime.of(2026, 10, 4, 8, 0), null);

        StatsCategoryQuery q = new StatsCategoryQuery();
        q.setType("expense");
        q.setStart(LocalDate.of(2026, 10, 1));
        q.setEnd(LocalDate.of(2026, 10, 31));
        StatsCategoryVo vo = statsService.category(1L, q);

        assertEquals(0, new BigDecimal("75.00").compareTo(vo.getTotal()));
        assertEquals(2, vo.getItems().size());
        // 降序：餐饮(60) 在前，未分类(15) 在后
        var top = vo.getItems().get(0);
        assertEquals("餐饮", top.getName());
        assertEquals(food.getId(), top.getCategoryId());
        assertEquals(0, new BigDecimal("60.00").compareTo(top.getAmount()));
        assertEquals(0, new BigDecimal("80.00").compareTo(top.getPercentage()));
        assertEquals(2, top.getChildren().size());
        assertEquals("三餐", top.getChildren().get(0).getName());
        assertEquals(0, new BigDecimal("30.00").compareTo(top.getChildren().get(0).getAmount()));
        var uncategorized = vo.getItems().get(1);
        assertNull(uncategorized.getCategoryId());
        assertEquals("未分类", uncategorized.getName());
        assertEquals(0, new BigDecimal("15.00").compareTo(uncategorized.getAmount()));
    }

    @Test
    void categoryIsolatesByUserTypeAndTime() {
        Account a1 = acc("卡A", "asset", "debit_card", "1000.00");
        Account a2 = accFor(2L, "卡B", "asset", "cash", "0.00");
        var mine = cat("餐饮", "expense", null, 1L);
        var others = cat("餐饮", "expense", null, 2L);
        com.balancewise.record.dto.TransactionRequest r1 = new com.balancewise.record.dto.TransactionRequest();
        r1.setType("expense"); r1.setFromAccountId(a1.getId()); r1.setAmount(new BigDecimal("100.00"));
        r1.setOccurredAt(LocalDateTime.of(2026, 10, 1, 8, 0)); r1.setCategoryId(mine.getId());
        transactionService.create(1L, r1);
        com.balancewise.record.dto.TransactionRequest r2 = new com.balancewise.record.dto.TransactionRequest();
        r2.setType("expense"); r2.setFromAccountId(a2.getId()); r2.setAmount(new BigDecimal("999.00"));
        r2.setOccurredAt(LocalDateTime.of(2026, 10, 1, 8, 0)); r2.setCategoryId(others.getId());
        transactionService.create(2L, r2);

        StatsCategoryQuery q = new StatsCategoryQuery();
        q.setType("expense");
        q.setStart(LocalDate.of(2026, 10, 1));
        q.setEnd(LocalDate.of(2026, 10, 31));
        StatsCategoryVo vo = statsService.category(1L, q);
        // user 2 的 999 绝不混入
        assertEquals(0, new BigDecimal("100.00").compareTo(vo.getTotal()));
        assertEquals(1, vo.getItems().size());

        // 时间过滤：只看 11 月 → 空
        q.setStart(LocalDate.of(2026, 11, 1));
        q.setEnd(LocalDate.of(2026, 11, 30));
        StatsCategoryVo empty = statsService.category(1L, q);
        assertEquals(0, BigDecimal.ZERO.compareTo(empty.getTotal()));
        assertTrue(empty.getItems().isEmpty());

        // 收入分类 kind 不混进支出构成（type=expense 只查 expense 分类树，且 SQL 已限定 type）
        q.setStart(LocalDate.of(2026, 10, 1));
        q.setEnd(LocalDate.of(2026, 10, 31));
        q.setType("income");
        assertTrue(statsService.category(1L, q).getItems().isEmpty());
    }

    @Test
    void categoryValidatesType() {
        StatsCategoryQuery q = new StatsCategoryQuery();
        q.setType("transfer");
        BizException e = assertThrows(BizException.class, () -> statsService.category(1L, q));
        assertEquals("type 必须是 expense 或 income", e.getMessage());
    }

    @Test
    void netWorthByMonthBucketsCumulate() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        txn("expense", a.getId(), null, "100.00", LocalDateTime.of(2026, 9, 15, 12, 0));
        txn("expense", a.getId(), null, "50.00", LocalDateTime.of(2026, 10, 2, 12, 0));

        StatsNetWorthVo vo = statsService.netWorth(1L, trendQuery("month", null,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31)));
        assertEquals(0, new BigDecimal("1000.00").compareTo(vo.getInitialNetWorth()));
        assertEquals(2, vo.getPoints().size());
        assertEquals("2026-09", vo.getPoints().get(0).getLabel());
        assertEquals(0, new BigDecimal("900.00").compareTo(vo.getPoints().get(0).getNetWorth()));
        assertEquals(0, new BigDecimal("850.00").compareTo(vo.getPoints().get(1).getNetWorth()));
    }

    @Test
    void monthBucketIncludesLastDayTransactions() {
        // 台账 #11：落在月桶最后一日（9-30 23:59:59）的流水必须计入该月桶（排他上界 < 次日 00:00）
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        txn("expense", a.getId(), null, "66.00", LocalDateTime.of(2026, 9, 30, 23, 59, 59));
        txn("expense", a.getId(), null, "10.00", LocalDateTime.of(2026, 10, 1, 0, 0, 0));

        StatsTrendVo vo = statsService.trend(1L, trendQuery("month", null,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31)));
        assertEquals(2, vo.getPoints().size());
        assertEquals("2026-09", vo.getPoints().get(0).getLabel());
        assertEquals(0, new BigDecimal("66.00").compareTo(vo.getPoints().get(0).getExpense()));
        assertEquals(0, new BigDecimal("10.00").compareTo(vo.getPoints().get(1).getExpense()));
    }
}
