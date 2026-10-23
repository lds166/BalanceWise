package com.balancewise.importbill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.balancewise.account.entity.Account;
import com.balancewise.account.mapper.AccountMapper;
import org.springframework.stereotype.Service;

/**
 * 入账账户匹配（UC-4.5）：按支付方式精确匹配用户账户名（如"余额宝"→余额宝账户）；
 * 匹配不到用默认账户兜底；都无则返回 null 由用户在前端指定。
 */
@Service
public class AccountMatcher {

    private final AccountMapper accountMapper;

    public AccountMatcher(AccountMapper accountMapper) {
        this.accountMapper = accountMapper;
    }

    public Account match(Long userId, String payMethod, Long defaultAccountId) {
        if (payMethod != null && !payMethod.isBlank()) {
            Account hit = accountMapper.selectOne(new LambdaQueryWrapper<Account>()
                    .eq(Account::getUserId, userId)
                    .eq(Account::getName, payMethod)
                    .last("LIMIT 1"));
            if (hit != null && Integer.valueOf(0).equals(hit.getIsArchived())) {
                return hit;
            }
        }
        if (defaultAccountId != null) {
            Account def = accountMapper.selectById(defaultAccountId);
            if (def != null && def.getUserId().equals(userId) && Integer.valueOf(0).equals(def.getIsArchived())) {
                return def;
            }
        }
        return null;
    }
}
