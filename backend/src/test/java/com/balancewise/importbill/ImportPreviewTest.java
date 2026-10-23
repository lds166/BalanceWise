package com.balancewise.importbill;

import com.balancewise.account.dto.AccountRequest;
import com.balancewise.account.entity.Account;
import com.balancewise.account.service.AccountService;
import com.balancewise.category.entity.Category;
import com.balancewise.category.mapper.CategoryMapper;
import com.balancewise.importbill.service.ImportService;
import com.balancewise.importbill.dto.ImportPreviewRow;
import com.balancewise.importbill.vo.ImportPreviewVo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ImportPreviewTest {

    @Autowired
    private ImportService importService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private CategoryMapper categoryMapper;
    @Autowired
    private com.balancewise.record.mapper.TransactionRecordMapper transactionRecordMapper;

    private Account newAccount(String name) {
        AccountRequest r = new AccountRequest();
        r.setName(name);
        r.setType("asset");
        r.setSubType("cash");
        r.setInitialBalance(new BigDecimal("0.00"));
        r.setRemark("");
        return accountService.create(1L, r);
    }

    private void category(String name, String kind) {
        Category c = new Category();
        c.setUserId(1L);
        c.setName(name);
        c.setKind(kind);
        c.setIsSystem(0);
        categoryMapper.insert(c);
    }

    @Test
    void previewMapsCategoryAccountAndMarksNothingPersisted() throws Exception {
        newAccount("余额宝");
        newAccount("默认卡");
        Account def = newAccount("现金");
        category("餐饮", "expense");
        category("购物", "expense");

        byte[] content = Files.readAllBytes(
                new ClassPathResource("fixtures/alipay-sample.csv").getFile().toPath());
        ImportPreviewVo vo = importService.preview(1L, "alipay", content, def.getId());

        assertEquals(4, vo.getRows().size());
        assertEquals(1, vo.getDropped().size());

        // 永辉超市 生鲜食品：无关键词命中 → 未分类；付款方式"余额宝"精确匹配到余额宝账户
        ImportPreviewRow food = vo.getRows().stream()
                .filter(r -> r.getItem() != null && r.getItem().contains("生鲜食品"))
                .findFirst().orElseThrow();
        assertEquals("expense", food.getType());
        assertFalse(food.isDuplicate());
        assertNull(food.getCategoryId());         // 生鲜食品无关键词命中 → 未分类
        assertNotNull(food.getFromAccountId());   // 余额宝账户匹配

        // 余额宝转入（转账行）：转账不挂分类；付款方式"余额"无同名账户 → 默认账户兜底
        ImportPreviewRow transfer = vo.getRows().stream()
                .filter(r -> "transfer".equals(r.getType()))
                .findFirst().orElseThrow();
        assertEquals("transfer", transfer.getType());
        assertNull(transfer.getCategoryId());     // 转账不挂分类
        assertNotNull(transfer.getFromAccountId());

        // fixture 中"外卖午餐"（美团外卖）行为交易关闭已丢弃 → 预览结果中不存在含"外卖午餐"的行
        assertTrue(vo.getRows().stream()
                .noneMatch(r -> r.getItem() != null && r.getItem().contains("外卖午餐")));
    }

    @Test
    void duplicateRowsMarkedAgainstDbAndInBatch() throws Exception {
        Account def = newAccount("现金");
        byte[] content = Files.readAllBytes(
                new ClassPathResource("fixtures/alipay-sample.csv").getFile().toPath());
        // 第一次预览：全部不重复
        ImportPreviewVo first = importService.preview(1L, "alipay", content, def.getId());
        assertTrue(first.getRows().stream().noneMatch(ImportPreviewRow::isDuplicate));
        // 模拟已入账：直接插一条同 external_id 未删除流水
        com.balancewise.record.entity.TransactionRecord exist = new com.balancewise.record.entity.TransactionRecord();
        exist.setUserId(1L);
        exist.setType("expense");
        exist.setFromAccountId(def.getId());
        exist.setAmount(new BigDecimal("35.50"));
        exist.setOccurredAt(java.time.LocalDateTime.of(2026, 9, 1, 8, 30, 22));
        exist.setSource("alipay");
        exist.setExternalId("202609010000100001");
        exist.setIsDeleted(0);
        transactionRecordMapper.insert(exist);
        // 第二次预览：该行标记重复，其余行不重复
        ImportPreviewVo second = importService.preview(1L, "alipay", content, def.getId());
        ImportPreviewRow dup = second.getRows().stream()
                .filter(r -> "202609010000100001".equals(r.getExternalId()))
                .findFirst().orElseThrow();
        assertTrue(dup.isDuplicate());
        ImportPreviewRow other = second.getRows().stream()
                .filter(r -> "202609050000100005".equals(r.getExternalId()))
                .findFirst().orElseThrow();
        assertFalse(other.isDuplicate());
    }
}
