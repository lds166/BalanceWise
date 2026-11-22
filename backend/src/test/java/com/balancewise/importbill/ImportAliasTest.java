package com.balancewise.importbill;

import com.balancewise.account.dto.AccountRequest;
import com.balancewise.account.entity.Account;
import com.balancewise.account.service.AccountService;
import com.balancewise.category.entity.Category;
import com.balancewise.category.mapper.CategoryMapper;
import com.balancewise.importbill.entity.ImportAlias;
import com.balancewise.importbill.mapper.ImportAliasMapper;
import com.balancewise.importbill.service.AccountMatcher;
import com.balancewise.importbill.service.ImportAliasService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 导入映射记忆（UC-4.10）：学习 upsert、命中优先级、kind 隔离、目标失效回落、账户四级匹配顺序。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ImportAliasTest {

    @Autowired
    private ImportAliasService importAliasService;
    @Autowired
    private AccountMatcher accountMatcher;
    @Autowired
    private AccountService accountService;
    @Autowired
    private CategoryMapper categoryMapper;
    @Autowired
    private ImportAliasMapper aliasMapper;

    private Account newAccount(String name) {
        AccountRequest r = new AccountRequest();
        r.setName(name);
        r.setType("asset");
        r.setSubType("cash");
        r.setInitialBalance(new BigDecimal("0.00"));
        r.setRemark("");
        return accountService.create(1L, r);
    }

    private Long category(String name, String kind) {
        Category c = new Category();
        c.setUserId(1L);
        c.setName(name);
        c.setKind(kind);
        c.setIsSystem(0);
        categoryMapper.insert(c);
        return c.getId();
    }

    @Test
    void learnCategoryRoundTripUpsertAndHitCount() {
        Long catId = category("餐饮", "expense");

        importAliasService.learnCategory(1L, "expense", "永辉超市", null, catId);
        assertEquals(catId, importAliasService.matchMemoryCategory(1L, "expense", "永辉超市", "生鲜食品"));

        // 同键再学习：last-write-wins 刷新目标 + hit_count 累计，不产生新行
        Long otherCat = category("购物", "expense");
        importAliasService.learnCategory(1L, "expense", "永辉超市", null, otherCat);
        List<ImportAlias> all = importAliasService.list(1L);
        assertEquals(1, all.size());
        assertEquals(otherCat, all.get(0).getTargetId());
        assertEquals(1, all.get(0).getHitCount());
        assertEquals(otherCat, importAliasService.matchMemoryCategory(1L, "expense", "永辉超市", null));
    }

    @Test
    void memoryKeyFallsBackToItemWhenCounterpartyBlank() {
        Long catId = category("医疗", "expense");
        importAliasService.learnCategory(1L, "expense", "", "体温计", catId);
        assertEquals(catId, importAliasService.matchMemoryCategory(1L, "expense", null, "体温计"));
    }

    @Test
    void kindMismatchDoesNotHit() {
        Long expenseCat = category("餐饮", "expense");
        importAliasService.learnCategory(1L, "expense", "永辉超市", null, expenseCat);
        // 收入（退款）行 kind=income：支出记忆不命中，退款分类不被支出记忆污染
        assertNull(importAliasService.matchMemoryCategory(1L, "income", "永辉超市", "生鲜退款"));
        // 收入记忆单独学习后可独立命中
        Long incomeCat = category("其他收入", "income");
        importAliasService.learnCategory(1L, "income", "永辉超市", null, incomeCat);
        assertEquals(incomeCat, importAliasService.matchMemoryCategory(1L, "income", "永辉超市", null));
        assertEquals(2, importAliasService.list(1L).size());
    }

    @Test
    void memoryFallsBackWhenTargetMissing() {
        Long catId = category("餐饮", "expense");
        importAliasService.learnCategory(1L, "expense", "永辉超市", null, catId);
        // 目标分类被删除 → 记忆静默失效返回 null，绝不阻断导入
        categoryMapper.deleteById(catId);
        assertNull(importAliasService.matchMemoryCategory(1L, "expense", "永辉超市", null));
    }

    @Test
    void learnIgnoresForeignOrMissingTarget() {
        // 目标分类属于其他用户 → 静默跳过
        Category foreign = new Category();
        foreign.setUserId(2L);
        foreign.setName("别人的分类");
        foreign.setKind("expense");
        foreign.setIsSystem(0);
        categoryMapper.insert(foreign);
        importAliasService.learnCategory(1L, "expense", "某商户", null, foreign.getId());
        // 目标账户不存在 → 静默跳过
        importAliasService.learnAccount(1L, "余额宝", 99999L);
        assertEquals(0, aliasMapper.selectCount(null));
    }

    @Test
    void accountMatchOrderExactMemoryFuzzyDefault() {
        Account cash = newAccount("现金");
        Account cmb = newAccount("建设银行");

        // ① 精确等值：付款方式与账户名相同
        assertEquals(cash.getId(), accountMatcher.match(1L, "现金", null).getId());

        // ③ 模糊（无记忆时）：去掉括号尾号后双向包含，"中国建设银行" contains "建设银行"
        assertEquals(cmb.getId(), accountMatcher.match(1L, "中国建设银行(5678)", null).getId());

        // ② 记忆优先于模糊：学习过"中国建设银行(5678)"→现金后按记忆命中
        importAliasService.learnAccount(1L, "中国建设银行(5678)", cash.getId());
        assertEquals(cash.getId(), accountMatcher.match(1L, "中国建设银行(5678)", null).getId());

        // ④ 默认账户兜底 / 全部未命中返回 null 由前端手选
        assertEquals(cash.getId(), accountMatcher.match(1L, "陌生付款方式", cash.getId()).getId());
        assertNull(accountMatcher.match(1L, "陌生付款方式", null));
    }

    @Test
    void fuzzyMatchSkipsArchivedAccounts() {
        Account cash = newAccount("现金");
        Account cmb = newAccount("建设银行");
        accountService.archive(1L, cmb.getId(), true);
        // 归档账户不参与模糊匹配 → 陌生的"中国建设银行(5678)"只能走默认兜底
        assertNull(accountMatcher.match(1L, "中国建设银行(5678)", null));
        assertEquals(cash.getId(), accountMatcher.match(1L, "中国建设银行(5678)", cash.getId()).getId());
    }

    @Test
    void fuzzyMatchRejectsShortNamePrefixCollision() {
        newAccount("余额宝");
        // "余额"仅 2 字，不参与模糊比名 → 不误匹配"余额宝"（实为不同账户），回落到默认/null
        assertNull(accountMatcher.match(1L, "余额", null));
    }

    @Test
    void deleteValidatesOwnership() {
        Long catId = category("餐饮", "expense");
        importAliasService.learnCategory(1L, "expense", "永辉超市", null, catId);
        ImportAlias alias = importAliasService.list(1L).get(0);
        importAliasService.delete(1L, alias.getId());
        assertEquals(0, importAliasService.list(1L).size());
        assertThrows(com.balancewise.common.BizException.class,
                () -> importAliasService.delete(1L, alias.getId()));
    }
}
