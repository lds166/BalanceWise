package com.balancewise.importbill;

import com.balancewise.account.dto.AccountRequest;
import com.balancewise.account.entity.Account;
import com.balancewise.account.service.AccountService;
import com.balancewise.category.entity.Category;
import com.balancewise.category.mapper.CategoryMapper;
import com.balancewise.importbill.service.AccountMatcher;
import com.balancewise.importbill.service.CategoryMappingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ImportMappingTest {

    @Autowired
    private CategoryMappingService categoryMappingService;
    @Autowired
    private AccountMatcher accountMatcher;
    @Autowired
    private AccountService accountService;
    @Autowired
    private CategoryMapper categoryMapper;

    private void category(String name, String kind) {
        Category c = new Category();
        c.setUserId(1L);
        c.setName(name);
        c.setKind(kind);
        c.setIsSystem(0);
        categoryMapper.insert(c);
    }

    @Test
    void keywordRulesMapItemTextToCategory() {
        category("餐饮", "expense");
        category("购物", "expense");
        category("交通", "expense");
        category("工资", "income");

        assertEquals(nameOf("餐饮"),
                categoryMappingService.mapCategoryId(1L, "expense", "交易付款", "美团外卖午餐"));
        assertEquals(nameOf("购物"),
                categoryMappingService.mapCategoryId(1L, "expense", "交易付款", "超市采购"));
        assertEquals(nameOf("交通"),
                categoryMappingService.mapCategoryId(1L, "expense", "交易付款", "滴滴打车"));
        assertEquals(nameOf("工资"),
                categoryMappingService.mapCategoryId(1L, "income", "转账", "十月工资"));
        // 无匹配 → null（未分类）
        assertNull(categoryMappingService.mapCategoryId(1L, "expense", "交易付款", "不知所云"));
    }

    private Long nameOf(String name) {
        return categoryMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Category>()
                .eq(Category::getUserId, 1L).eq(Category::getName, name)).getId();
    }

    @Test
    void accountMatchedByNameThenDefault() {
        AccountRequest r = new AccountRequest();
        r.setName("余额宝");
        r.setType("asset");
        r.setSubType("yuebao");
        r.setInitialBalance(new java.math.BigDecimal("0.00"));
        r.setRemark("");
        Account yuebao = accountService.create(1L, r);

        // 付款方式精确匹配账户名
        assertEquals(yuebao.getId(), accountMatcher.match(1L, "余额宝", null).getId());
        // 匹配不到 → 默认账户
        Account def = accountService.create(1L, reqOf("默认卡"));
        assertEquals(def.getId(), accountMatcher.match(1L, "招商银行信用卡", def.getId()).getId());
        // 匹配不到且无默认 → null
        assertNull(accountMatcher.match(1L, "火星银行", null));
    }

    @Test
    void transferTypeNeverMapped() {
        category("餐饮", "expense");
        // 转账不挂分类：即使文本命中关键词也返回 null
        assertNull(categoryMappingService.mapCategoryId(1L, "transfer", "余额宝转入", "余额宝"));
        assertNull(categoryMappingService.mapCategoryId(1L, "transfer", "转账", "十月工资"));
    }

    private AccountRequest reqOf(String name) {
        AccountRequest r = new AccountRequest();
        r.setName(name);
        r.setType("asset");
        r.setSubType("cash");
        r.setInitialBalance(new java.math.BigDecimal("0.00"));
        r.setRemark("");
        return r;
    }
}
