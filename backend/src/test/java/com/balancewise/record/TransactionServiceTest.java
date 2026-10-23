package com.balancewise.record;

import com.balancewise.account.dto.AccountRequest;
import com.balancewise.account.entity.Account;
import com.balancewise.account.service.AccountService;
import com.balancewise.category.entity.Category;
import com.balancewise.category.mapper.CategoryMapper;
import com.balancewise.common.BizException;
import com.balancewise.common.PageResult;
import com.balancewise.record.dto.TransactionQuery;
import com.balancewise.record.dto.TransactionRequest;
import com.balancewise.record.vo.TransactionVo;
import com.balancewise.record.entity.TransactionRecord;
import com.balancewise.record.service.TransactionService;
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
class TransactionServiceTest {

    @Autowired
    private TransactionService transactionService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private CategoryMapper categoryMapper;

    private Account acc(String name, String type, String subType, String balance) {
        return acc(1L, name, type, subType, balance);
    }

    private Account acc(Long userId, String name, String type, String subType, String balance) {
        AccountRequest r = new AccountRequest();
        r.setName(name);
        r.setType(type);
        r.setSubType(subType);
        r.setInitialBalance(new BigDecimal(balance));
        r.setRemark("");
        return accountService.create(userId, r);
    }

    private Category cat(String name, String kind, Long parentId) {
        Category c = new Category();
        c.setUserId(1L);
        c.setName(name);
        c.setKind(kind);
        c.setParentId(parentId);
        c.setIsSystem(0);
        categoryMapper.insert(c);
        return c;
    }

    private TransactionRequest req(String type, Long from, Long to, String amount, Long categoryId) {
        TransactionRequest r = new TransactionRequest();
        r.setType(type);
        r.setFromAccountId(from);
        r.setToAccountId(to);
        r.setAmount(new BigDecimal(amount));
        r.setCategoryId(categoryId);
        r.setOccurredAt(LocalDateTime.of(2026, 10, 1, 12, 0));
        return r;
    }

    private BigDecimal balanceOf(Long accountId) {
        return accountService.getById(accountId).getBalance();
    }

    @Test
    void expenseReducesAssetBalance() {
        Account a = acc("储蓄卡", "asset", "debit_card", "1000.00");
        transactionService.create(1L, req("expense", a.getId(), null, "200.00", null));
        assertEquals(0, new BigDecimal("800.00").compareTo(balanceOf(a.getId())));
    }

    @Test
    void incomeIncreasesAssetBalance() {
        Account a = acc("钱包", "asset", "cash", "0.00");
        transactionService.create(1L, req("income", null, a.getId(), "3500.00", null));
        assertEquals(0, new BigDecimal("3500.00").compareTo(balanceOf(a.getId())));
    }

    @Test
    void transferMovesBetweenTwoAccounts() {
        Account from = acc("卡A", "asset", "debit_card", "500.00");
        Account to = acc("卡B", "asset", "debit_card", "100.00");
        TransactionRecord r = transactionService.create(1L,
                req("transfer", from.getId(), to.getId(), "150.00", null));
        assertNull(r.getCategoryId());
        assertEquals(0, new BigDecimal("350.00").compareTo(balanceOf(from.getId())));
        assertEquals(0, new BigDecimal("250.00").compareTo(balanceOf(to.getId())));
    }

    @Test
    void expenseOnLiabilityMakesDebtMoreNegative() {
        // 负债消费：信用卡欠款 5000，刷卡花 1000 → -6000
        Account card = acc("信用卡", "liability", "credit_card", "5000.00");
        transactionService.create(1L, req("expense", card.getId(), null, "1000.00", null));
        assertEquals(0, new BigDecimal("-6000.00").compareTo(balanceOf(card.getId())));
    }

    @Test
    void incomeOnLiabilityReducesDebt() {
        // 还款：向信用卡转入 2000 → 欠款从 -5000 变为 -3000
        Account card = acc("信用卡", "liability", "credit_card", "5000.00");
        transactionService.create(1L, req("income", null, card.getId(), "2000.00", null));
        assertEquals(0, new BigDecimal("-3000.00").compareTo(balanceOf(card.getId())));
    }

    @Test
    void transferFromAssetToLiabilityIsRepayment() {
        // 储蓄卡还花呗：from 减、to 加，负债余额向 0 收敛
        Account card = acc("储蓄卡", "asset", "debit_card", "10000.00");
        Account huabei = acc("花呗", "liability", "huabei", "500.00");
        transactionService.create(1L, req("transfer", card.getId(), huabei.getId(), "500.00", null));
        assertEquals(0, new BigDecimal("9500.00").compareTo(balanceOf(card.getId())));
        assertEquals(0, BigDecimal.ZERO.compareTo(balanceOf(huabei.getId())));
    }

    @Test
    void transferSameAccountRejected() {
        Account a = acc("卡A", "asset", "debit_card", "100.00");
        BizException e = assertThrows(BizException.class,
                () -> transactionService.create(1L, req("transfer", a.getId(), a.getId(), "10.00", null)));
        assertEquals("转出与转入账户不能相同", e.getMessage());
    }

    @Test
    void archivedAccountRejected() {
        Account a = acc("旧卡", "asset", "debit_card", "100.00");
        accountService.archive(1L, a.getId(), true);
        BizException e = assertThrows(BizException.class,
                () -> transactionService.create(1L, req("expense", a.getId(), null, "10.00", null)));
        assertTrue(e.getMessage().contains("已归档"));
    }

    @Test
    void categoryKindMustMatchType() {
        Account a = acc("钱包", "asset", "cash", "100.00");
        Category expenseCat = cat("餐饮", "expense", null);
        Category incomeCat = cat("工资", "income", null);
        // 支出用收入分类 → 拒绝
        assertThrows(BizException.class,
                () -> transactionService.create(1L, req("expense", a.getId(), null, "10.00", incomeCat.getId())));
        // 支出用支出分类 → 通过
        TransactionRecord r = transactionService.create(1L,
                req("expense", a.getId(), null, "10.00", expenseCat.getId()));
        assertEquals(expenseCat.getId(), r.getCategoryId());
    }

    @Test
    void cannotUseOtherUsersAccount() {
        Account mine = acc("我的卡", "asset", "debit_card", "100.00");
        BizException e = assertThrows(BizException.class,
                () -> transactionService.create(2L, req("expense", mine.getId(), null, "10.00", null)));
        assertEquals("支出账户不存在", e.getMessage());
    }

    @Test
    void editChangeAccountReversesOldAndAppliesNew() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        Account b = acc("卡B", "asset", "debit_card", "500.00");
        TransactionRecord r = transactionService.create(1L,
                req("expense", a.getId(), null, "200.00", null));
        // 编辑：账户从 A 换到 B
        transactionService.update(1L, r.getId(), req("expense", b.getId(), null, "200.00", null));
        assertEquals(0, new BigDecimal("1000.00").compareTo(balanceOf(a.getId())));
        assertEquals(0, new BigDecimal("300.00").compareTo(balanceOf(b.getId())));
    }

    @Test
    void editChangeAmountAndType() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        Account b = acc("卡B", "asset", "debit_card", "0.00");
        TransactionRecord r = transactionService.create(1L,
                req("expense", a.getId(), null, "200.00", null));
        // 类型 expense → income，账户从 A 换到 B，金额 200 → 300
        TransactionRecord updated = transactionService.update(1L, r.getId(),
                req("income", null, b.getId(), "300.00", null));
        assertEquals("income", updated.getType());
        assertEquals(0, new BigDecimal("1000.00").compareTo(balanceOf(a.getId()))); // 冲销加回：800 + 200
        assertEquals(0, new BigDecimal("300.00").compareTo(balanceOf(b.getId())));
    }

    @Test
    void deleteReversesBalanceAndSoftDeletes() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        TransactionRecord r = transactionService.create(1L,
                req("expense", a.getId(), null, "200.00", null));
        transactionService.delete(1L, r.getId());
        assertEquals(0, new BigDecimal("1000.00").compareTo(balanceOf(a.getId())));
        TransactionRecord after = transactionService.getById(r.getId());
        assertEquals(1, after.getIsDeleted());
    }

    @Test
    void deleteTransferReversesBothAccounts() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        Account b = acc("卡B", "asset", "debit_card", "0.00");
        TransactionRecord r = transactionService.create(1L,
                req("transfer", a.getId(), b.getId(), "400.00", null));
        transactionService.delete(1L, r.getId());
        assertEquals(0, new BigDecimal("1000.00").compareTo(balanceOf(a.getId())));
        assertEquals(0, BigDecimal.ZERO.compareTo(balanceOf(b.getId())));
    }

    @Test
    void cannotEditOrDeleteOtherUsersRecord() {
        Account a = acc("我的卡", "asset", "debit_card", "1000.00");
        TransactionRecord r = transactionService.create(1L,
                req("expense", a.getId(), null, "200.00", null));
        BizException e1 = assertThrows(BizException.class,
                () -> transactionService.update(2L, r.getId(), req("expense", a.getId(), null, "1.00", null)));
        assertEquals("流水不存在", e1.getMessage());
        BizException e2 = assertThrows(BizException.class,
                () -> transactionService.delete(2L, r.getId()));
        assertEquals("流水不存在", e2.getMessage());
    }

    @Test
    void editKeepsSourceAndExternalId() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        TransactionRecord r = transactionService.create(1L,
                req("expense", a.getId(), null, "200.00", null));
        r.setSource("alipay");
        r.setExternalId("202610010001");
        transactionService.updateById(r);
        transactionService.update(1L, r.getId(), req("expense", a.getId(), null, "250.00", null));
        TransactionRecord after = transactionService.getById(r.getId());
        assertEquals("alipay", after.getSource());
        assertEquals("202610010001", after.getExternalId());
    }

    @Test
    void pageFiltersByTypeAccountAndKeyword() {
        Account a = acc("卡A", "asset", "debit_card", "10000.00");
        Account b = acc("卡B", "asset", "cash", "0.00");
        Category food = cat("餐饮", "expense", null);
        transactionService.create(1L, req("expense", a.getId(), null, "20.00", food.getId()));
        TransactionRequest r2 = req("income", null, b.getId(), "3000.00", null);
        r2.setCounterparty("公司");
        r2.setItem("十月工资");
        transactionService.create(1L, r2);

        // 默认：全部、倒序
        TransactionQuery q = new TransactionQuery();
        PageResult<TransactionVo> all = transactionService.page(1L, q);
        assertEquals(2, all.getTotal());

        // 按类型
        TransactionQuery byType = new TransactionQuery();
        byType.setType("income");
        assertEquals(1, transactionService.page(1L, byType).getTotal());

        // 按账户（from 或 to 命中均可）
        TransactionQuery byAcc = new TransactionQuery();
        byAcc.setAccountId(a.getId());
        PageResult<TransactionVo> page = transactionService.page(1L, byAcc);
        assertEquals(1, page.getTotal());
        assertEquals("卡A", page.getRecords().get(0).getFromAccountName());
        assertEquals("餐饮", page.getRecords().get(0).getCategoryName());

        // 按关键字（对方/备注模糊）
        TransactionQuery byKw = new TransactionQuery();
        byKw.setKeyword("工资");
        assertEquals(1, transactionService.page(1L, byKw).getTotal());
    }

    @Test
    void pageExcludesSoftDeletedAndOtherUsers() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        TransactionRecord r = transactionService.create(1L,
                req("expense", a.getId(), null, "10.00", null));
        transactionService.create(1L, req("expense", a.getId(), null, "20.00", null)); // 保留
        Account a2 = acc(2L, "卡B", "asset", "cash", "0.00");
        transactionService.create(2L, req("expense", a2.getId(), null, "10.00", null)); // 他人
        transactionService.delete(1L, r.getId()); // 自己删除一条
        assertEquals(1, transactionService.page(1L, new TransactionQuery()).getTotal());
    }

    @Test
    void pageFiltersByDateRange() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        transactionService.create(1L, req("expense", a.getId(), null, "10.00", null)); // 2026-10-01
        TransactionRequest r2 = req("expense", a.getId(), null, "20.00", null);
        r2.setOccurredAt(LocalDateTime.of(2026, 11, 15, 8, 0));
        transactionService.create(1L, r2);

        TransactionQuery q = new TransactionQuery();
        q.setStart(LocalDate.of(2026, 10, 1));
        q.setEnd(LocalDate.of(2026, 10, 31));
        assertEquals(1, transactionService.page(1L, q).getTotal());
    }

    @Test
    void exportCsvContainsBomAndRows() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        Category food = cat("餐饮", "expense", null);
        TransactionRequest r = req("expense", a.getId(), null, "25.50", food.getId());
        r.setCounterparty("麦当劳");
        r.setItem("午餐");
        transactionService.create(1L, r);

        String csv = transactionService.exportCsv(1L, new TransactionQuery());
        assertTrue(csv.startsWith("\uFEFF")); // Excel 中文 BOM
        assertTrue(csv.contains("类型,分类,账户,金额,时间,对方,备注,来源,批次号"));
        assertTrue(csv.contains("支出"));
        assertTrue(csv.contains("餐饮"));
        assertTrue(csv.contains("卡A"));
        assertTrue(csv.contains("25.50"));
        assertTrue(csv.contains("麦当劳"));
    }

    @Test
    void exportCsvRespectsFiltersAndExcludesDeleted() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        Account b = acc("卡B", "asset", "cash", "0.00");
        TransactionRecord del = transactionService.create(1L,
                req("expense", a.getId(), null, "10.00", null));
        transactionService.delete(1L, del.getId());
        transactionService.create(1L, req("income", null, b.getId(), "100.00", null));

        TransactionQuery q = new TransactionQuery();
        q.setType("income");
        String csv = transactionService.exportCsv(1L, q);
        assertTrue(csv.contains("收入"));
        assertFalse(csv.contains("支出"));
    }

    @Test
    void exportCsvGuardsFormulaInjectionAndCarriageReturn() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        TransactionRequest r = req("expense", a.getId(), null, "10.00", null);
        r.setCounterparty("=cmd|'/c calc'!A1");   // Excel 公式注入样本
        r.setItem("含\r裸回车");
        transactionService.create(1L, r);

        String csv = transactionService.exportCsv(1L, new TransactionQuery());
        // 公式注入：以 ' 前缀中和
        assertTrue(csv.contains("'=cmd"));
        // 裸 \r 触发引号包裹
        assertTrue(csv.contains("\"含\r裸回车\""));
    }

    @Test
    void endDateFilterIncludesWholeDayExclusively() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        TransactionRequest r1 = req("expense", a.getId(), null, "10.00", null);
        r1.setOccurredAt(LocalDateTime.of(2026, 10, 31, 23, 59, 59));
        transactionService.create(1L, r1);
        TransactionRequest r2 = req("expense", a.getId(), null, "20.00", null);
        r2.setOccurredAt(LocalDateTime.of(2026, 11, 1, 0, 0, 0));
        transactionService.create(1L, r2);

        TransactionQuery q = new TransactionQuery();
        q.setStart(java.time.LocalDate.of(2026, 10, 1));
        q.setEnd(java.time.LocalDate.of(2026, 10, 31));
        // 新语义：end 含当天 23:59:59.xxx 全部（< 次日 00:00）
        assertEquals(1, transactionService.page(1L, q).getTotal());
    }

    @Test
    void doubleDeleteRejected() {
        Account a = acc("卡A", "asset", "debit_card", "1000.00");
        TransactionRecord r = transactionService.create(1L,
                req("expense", a.getId(), null, "10.00", null));
        transactionService.delete(1L, r.getId());
        BizException e = assertThrows(BizException.class,
                () -> transactionService.delete(1L, r.getId()));
        assertEquals("流水不存在", e.getMessage());
    }
}
