package com.balancewise.importbill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.balancewise.account.entity.Account;
import com.balancewise.account.mapper.AccountMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 入账账户匹配（UC-4.5）四级顺序：
 * ① 付款方式与账户名精确等值（如"余额宝"→同名账户）
 * ② 记忆表命中（用户确认过的 付款方式→账户 映射）
 * ③ 模糊匹配（付款方式去掉括号尾号后与账户名双向 contains，双方均 ≥3 字才参与，
 *    防止"余额"误匹配"余额宝"这类短名前缀误伤——二者实为不同账户；多命中取名字最短者）
 * ④ 默认账户兜底；都无则返回 null 由用户在前端指定。
 */
@Service
public class AccountMatcher {

    /** 模糊匹配参与比名的最小长度（去括号后） */
    private static final int MIN_FUZZY_LEN = 3;

    private final AccountMapper accountMapper;
    private final ImportAliasService importAliasService;

    public AccountMatcher(AccountMapper accountMapper, ImportAliasService importAliasService) {
        this.accountMapper = accountMapper;
        this.importAliasService = importAliasService;
    }

    public Account match(Long userId, String payMethod, Long defaultAccountId) {
        if (payMethod != null && !payMethod.isBlank()) {
            List<Account> candidates = activeAccounts(userId);
            // ① 精确等值
            for (Account a : candidates) {
                if (a.getName().equals(payMethod)) {
                    return a;
                }
            }
            // ② 记忆表（目标归属与归档在此复验）
            Long aliasId = importAliasService.matchMemoryAccount(userId, payMethod);
            if (aliasId != null) {
                for (Account a : candidates) {
                    if (a.getId().equals(aliasId)) {
                        return a;
                    }
                }
            }
            // ③ 模糊匹配：付款方式常带尾号/括号（如"招商银行(1234)"），去括号后双向包含；
            //    短名（<3 字）不参与，避免"余额"误伤"余额宝"
            String core = normalize(payMethod);
            if (core.length() >= MIN_FUZZY_LEN) {
                Account best = null;
                for (Account a : candidates) {
                    String name = normalize(a.getName());
                    if (name.length() < MIN_FUZZY_LEN) {
                        continue;
                    }
                    if (name.contains(core) || core.contains(name)) {
                        // 名字更短者更具体（如"建设银行"优先于"建设银行储蓄卡"歧义时的短名）
                        if (best == null || name.length() < normalize(best.getName()).length()) {
                            best = a;
                        }
                    }
                }
                if (best != null) {
                    return best;
                }
            }
        }
        // ④ 默认账户兜底
        if (defaultAccountId != null) {
            Account def = accountMapper.selectById(defaultAccountId);
            if (def != null && def.getUserId().equals(userId) && Integer.valueOf(0).equals(def.getIsArchived())) {
                return def;
            }
        }
        return null;
    }

    private List<Account> activeAccounts(Long userId) {
        return accountMapper.selectList(new LambdaQueryWrapper<Account>()
                .eq(Account::getUserId, userId)
                .eq(Account::getIsArchived, 0));
    }

    /** 去全/半角括号及其内容、去空白，用于尾号类付款方式的模糊比较 */
    private String normalize(String s) {
        return s == null ? "" : s.replaceAll("[（(][^）)]*[）)]", "").replace(" ", "").trim();
    }
}
