package com.balancewise.account;

import com.balancewise.account.dto.AccountRequest;
import com.balancewise.account.entity.Account;
import com.balancewise.account.service.AccountService;
import com.balancewise.account.vo.AccountSummaryVo;
import com.balancewise.common.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional // 测试方法回滚，保证方法间数据隔离（同内存库共享）
class AccountServiceTest {

    @Autowired
    private AccountService accountService;

    private AccountRequest req(String name, String type, String subType, String balance) {
        AccountRequest r = new AccountRequest();
        r.setName(name);
        r.setType(type);
        r.setSubType(subType);
        r.setInitialBalance(new BigDecimal(balance));
        r.setRemark("");
        return r;
    }

    @Test
    void assetAccountBalancePositive() {
        Account a = accountService.create(1L, req("招商卡", "asset", "debit_card", "100.00"));
        assertEquals(0, new BigDecimal("100.00").compareTo(a.getBalance()));
    }

    @Test
    void liabilityAccountBalanceNegative() {
        // 负债账户：欠款以负数存储
        Account a = accountService.create(1L, req("信用卡", "liability", "credit_card", "5000.00"));
        assertEquals(0, new BigDecimal("-5000.00").compareTo(a.getBalance()));
    }

    @Test
    void subTypeMustMatchType() {
        BizException e = assertThrows(BizException.class,
                () -> accountService.create(1L, req("错卡", "asset", "credit_card", "1.00")));
        assertEquals("子类型与账户类别不匹配", e.getMessage());
    }

    @Test
    void cannotOperateOtherUsersAccount() {
        Account mine = accountService.create(1L, req("我的卡", "asset", "cash", "0.00"));
        BizException e = assertThrows(BizException.class,
                () -> accountService.archive(2L, mine.getId(), true));
        assertEquals("账户不存在", e.getMessage());
    }

    @Test
    void cannotChangeAccountTypeOnUpdate() {
        Account a = accountService.create(1L, req("招商卡", "asset", "debit_card", "100.00"));
        // 编辑时把类别改成负债：不允许，且余额/类型保持不变
        AccountRequest change = req("招商卡", "liability", "credit_card", "100.00");
        BizException e = assertThrows(BizException.class,
                () -> accountService.update(1L, a.getId(), change));
        assertEquals("账户类别不可修改", e.getMessage());
        Account after = accountService.getById(a.getId());
        assertEquals("asset", after.getType());
        assertEquals(0, new BigDecimal("100.00").compareTo(after.getBalance()));
    }

    @Test
    void renameAccountWithoutChangingType() {
        Account a = accountService.create(1L, req("旧名卡", "asset", "debit_card", "100.00"));
        AccountRequest rename = req("新名卡", "asset", "debit_card", "100.00");
        rename.setRemark("改备注");
        Account updated = accountService.update(1L, a.getId(), rename);
        assertEquals("新名卡", updated.getName());
        assertEquals("asset", updated.getType());
        assertEquals("debit_card", updated.getSubType());
        assertEquals("改备注", updated.getRemark());
    }

    @Test
    void summaryComputesNetWorth() {
        accountService.create(1L, req("资产A", "asset", "cash", "1000.00"));
        accountService.create(1L, req("负债B", "liability", "huabei", "300.00"));
        AccountSummaryVo s = accountService.summary(1L);
        assertEquals(0, new BigDecimal("700.00").compareTo(s.getNetWorth()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(s.getAssetTotal()));
        assertEquals(0, new BigDecimal("300.00").compareTo(s.getLiabilityTotal()));
    }
}
