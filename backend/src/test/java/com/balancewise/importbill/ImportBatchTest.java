package com.balancewise.importbill;

import com.balancewise.account.dto.AccountRequest;
import com.balancewise.account.entity.Account;
import com.balancewise.account.service.AccountService;
import com.balancewise.common.BizException;
import com.balancewise.importbill.dto.ImportConfirmRequest;
import com.balancewise.importbill.dto.ImportRowRequest;
import com.balancewise.importbill.entity.ImportBatch;
import com.balancewise.importbill.service.ImportService;
import com.balancewise.importbill.vo.ImportBatchDetailVo;
import com.balancewise.importbill.vo.ImportBatchVo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ImportBatchTest {

    @Autowired
    private ImportService importService;
    @Autowired
    private AccountService accountService;

    private Long confirmedBatchWithOneExpense() {
        AccountRequest r = new AccountRequest();
        r.setName("现金");
        r.setType("asset");
        r.setSubType("cash");
        r.setInitialBalance(new BigDecimal("0.00"));
        r.setRemark("");
        Account def = accountService.create(1L, r);

        ImportRowRequest row = new ImportRowRequest();
        row.setType("expense");
        row.setFromAccountId(def.getId());
        row.setAmount(new BigDecimal("100.00"));
        row.setOccurredAt(LocalDateTime.of(2026, 9, 1, 12, 0));
        row.setExternalId("EXT-001");
        ImportConfirmRequest req = new ImportConfirmRequest();
        req.setPlatform("alipay");
        req.setFileName("t.csv");
        req.setRows(List.of(row));
        return importService.confirm(1L, req).getId();
    }

    @Test
    void rollbackRestoresBalancesAndSoftDeletes() {
        Long batchId = confirmedBatchWithOneExpense();
        Account cash = accountService.getOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Account>()
                .eq(Account::getUserId, 1L).eq(Account::getName, "现金"));
        // 注意：必须重新 getById——confirm 前查到的实体是余额变动前的快照
        assertEquals(0, new BigDecimal("-100.00").compareTo(
                accountService.getById(cash.getId()).getBalance()));

        ImportBatch after = importService.rollback(1L, batchId);
        assertEquals("rolled_back", after.getStatus());
        assertNotNull(after.getRolledBackAt());
        // 余额还原
        assertEquals(0, BigDecimal.ZERO.compareTo(
                accountService.getById(cash.getId()).getBalance()));
        // 明细仍可见（软删保留，可追溯）
        ImportBatchDetailVo detail = importService.detail(1L, batchId);
        assertEquals(1, detail.getRecords().size());
        assertEquals("EXT-001", detail.getRecords().get(0).getExternalId());
    }

    @Test
    void doubleRollbackRejected() {
        Long batchId = confirmedBatchWithOneExpense();
        importService.rollback(1L, batchId);
        BizException e = assertThrows(BizException.class, () -> importService.rollback(1L, batchId));
        assertTrue(e.getMessage().contains("已撤销"));
    }

    @Test
    void listShowsBatchesAndOtherUserRejected() {
        Long batchId = confirmedBatchWithOneExpense();
        List<ImportBatchVo> list = importService.list(1L);
        assertEquals(1, list.size());
        assertEquals(batchId, list.get(0).getId());
        assertEquals("confirmed", list.get(0).getStatus());
        assertEquals("t.csv", list.get(0).getFileName());

        BizException e = assertThrows(BizException.class, () -> importService.detail(2L, batchId));
        assertEquals("批次不存在", e.getMessage());
        BizException e2 = assertThrows(BizException.class, () -> importService.rollback(2L, batchId));
        assertEquals("批次不存在", e2.getMessage());
    }

    @Test
    void reimportAfterRollbackSucceeds() {
        Long batchId = confirmedBatchWithOneExpense();
        importService.rollback(1L, batchId);

        // 撤销后流水软删，重复检测不再命中（§5.4 规则 4：只比对未删除记录），同文件可重新入账
        Account cash = accountService.getOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Account>()
                .eq(Account::getUserId, 1L).eq(Account::getName, "现金"));
        ImportRowRequest row = new ImportRowRequest();
        row.setType("expense");
        row.setFromAccountId(cash.getId());
        row.setAmount(new BigDecimal("100.00"));
        row.setOccurredAt(LocalDateTime.of(2026, 9, 1, 12, 0));
        row.setExternalId("EXT-001");
        ImportConfirmRequest req = new ImportConfirmRequest();
        req.setPlatform("alipay");
        req.setFileName("t.csv");
        req.setRows(List.of(row));
        ImportBatch again = importService.confirm(1L, req);
        assertEquals("confirmed", again.getStatus());
        // 余额再次扣减 100（重导生效）
        assertEquals(0, new BigDecimal("-100.00").compareTo(
                accountService.getById(cash.getId()).getBalance()));
    }
}
