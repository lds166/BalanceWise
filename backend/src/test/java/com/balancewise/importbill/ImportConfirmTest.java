package com.balancewise.importbill;

import com.balancewise.account.dto.AccountRequest;
import com.balancewise.account.entity.Account;
import com.balancewise.account.service.AccountService;
import com.balancewise.common.BizException;
import com.balancewise.importbill.dto.ImportConfirmRequest;
import com.balancewise.importbill.dto.ImportPreviewRow;
import com.balancewise.importbill.dto.ImportRowRequest;
import com.balancewise.importbill.entity.ImportBatch;
import com.balancewise.importbill.service.ImportService;
import com.balancewise.importbill.vo.ImportPreviewVo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ImportConfirmTest {

    @Autowired
    private ImportService importService;
    @Autowired
    private AccountService accountService;

    private Account newAccount(String name) {
        AccountRequest r = new AccountRequest();
        r.setName(name);
        r.setType("asset");
        r.setSubType("cash");
        r.setInitialBalance(new BigDecimal("0.00"));
        r.setRemark("");
        return accountService.create(1L, r);
    }

    private ImportConfirmRequest confirmOf(ImportPreviewVo preview, Account defFrom, Account defTo) {
        List<ImportRowRequest> rows = preview.getRows().stream()
                .filter(r -> !r.isDuplicate())
                .map(r -> {
                    ImportRowRequest row = new ImportRowRequest();
                    row.setType(r.getType());
                    row.setAmount(r.getAmount());
                    row.setOccurredAt(r.getOccurredAt());
                    row.setCounterparty(r.getCounterparty());
                    row.setItem(r.getItem());
                    row.setExternalId(r.getExternalId());
                    row.setCategoryId(r.getCategoryId());
                    // 预览只填了 from（转账缺 to），这里分别补兜底账户（from/to 不同，避免转账同账户校验失败）
                    row.setFromAccountId(r.getFromAccountId() != null ? r.getFromAccountId() : defFrom.getId());
                    row.setToAccountId(r.getToAccountId() != null ? r.getToAccountId() : defTo.getId());
                    return row;
                }).collect(Collectors.toList());
        ImportConfirmRequest req = new ImportConfirmRequest();
        req.setPlatform(preview.getPlatform());
        req.setFileName("alipay-sample.csv");
        req.setRows(rows);
        return req;
    }

    @Test
    void confirmPersistsBatchRecordsAndBalances() throws Exception {
        Account yuebao = newAccount("余额宝");
        Account defFrom = newAccount("现金");
        Account defTo = newAccount("储蓄卡");
        byte[] content = Files.readAllBytes(
                new ClassPathResource("fixtures/alipay-sample.csv").getFile().toPath());
        ImportPreviewVo preview = importService.preview(1L, "alipay", content, defFrom.getId());

        ImportBatch batch = importService.confirm(1L, confirmOf(preview, defFrom, defTo));
        assertNotNull(batch.getId());
        assertEquals("confirmed", batch.getStatus());
        assertEquals(4, batch.getRecordCount());
        // 35.50 + 99.99 + 1000.00 + 35.50 = 1170.99
        assertEquals(0, new BigDecimal("1170.99").compareTo(batch.getTotalAmount()));

        // 行0 永辉 35.50 支出 + 行1 iPad 99.99 支出，付款方式均"余额宝"精确匹配 → from 余额宝；
        // 行3 退款 35.50 收入 to 余额宝 → 余额宝净额 -35.50 - 99.99 + 35.50 = -99.99
        assertEquals(0, new BigDecimal("-99.99")
                .compareTo(accountService.getById(yuebao.getId()).getBalance()));
        // 行2 余额宝转入 1000.00 转账：付款方式"余额"无同名账户 → 默认账户 defFrom 兜底为转出 → -1000.00
        assertEquals(0, new BigDecimal("-1000.00")
                .compareTo(accountService.getById(defFrom.getId()).getBalance()));
        // 行2 转账 1000.00 to defTo（确认时补的兜底转入账户）→ +1000.00
        assertEquals(0, new BigDecimal("1000.00")
                .compareTo(accountService.getById(defTo.getId()).getBalance()));
    }

    @Test
    void duplicateConfirmRejected() throws Exception {
        Account defFrom = newAccount("现金");
        Account defTo = newAccount("储蓄卡");
        byte[] content = Files.readAllBytes(
                new ClassPathResource("fixtures/alipay-sample.csv").getFile().toPath());
        ImportConfirmRequest req = confirmOf(importService.preview(1L, "alipay", content, defFrom.getId()), defFrom, defTo);
        importService.confirm(1L, req);
        // 同一批 external_id 再次入账 → 拒
        BizException e = assertThrows(BizException.class, () -> importService.confirm(1L, req));
        assertTrue(e.getMessage().contains("已入账"));
    }

    @Test
    void emptyRowsRejected() {
        ImportConfirmRequest req = new ImportConfirmRequest();
        req.setPlatform("alipay");
        req.setFileName("x.csv");
        req.setRows(List.of());
        BizException e = assertThrows(BizException.class, () -> importService.confirm(1L, req));
        assertTrue(e.getMessage().contains("至少一条"));
    }

    @Test
    void inBatchDuplicateExternalIdRejected() {
        Account def = newAccount("现金");
        ImportRowRequest row = new ImportRowRequest();
        row.setType("expense");
        row.setFromAccountId(def.getId());
        row.setAmount(new BigDecimal("10.00"));
        row.setOccurredAt(LocalDateTime.of(2026, 9, 1, 12, 0));
        row.setExternalId("EXT-DUP");
        ImportRowRequest dup = new ImportRowRequest();
        dup.setType("expense");
        dup.setFromAccountId(def.getId());
        dup.setAmount(new BigDecimal("20.00"));
        dup.setOccurredAt(LocalDateTime.of(2026, 9, 2, 12, 0));
        dup.setExternalId("EXT-DUP");
        ImportConfirmRequest req = new ImportConfirmRequest();
        req.setPlatform("alipay");
        req.setFileName("dup.csv");
        req.setRows(java.util.List.of(row, dup));
        BizException e = assertThrows(BizException.class, () -> importService.confirm(1L, req));
        assertTrue(e.getMessage().contains("重复"));
    }
}
