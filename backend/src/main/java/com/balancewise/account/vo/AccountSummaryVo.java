package com.balancewise.account.vo;

import com.balancewise.account.entity.Account;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class AccountSummaryVo {
    private List<Account> accounts;
    private BigDecimal netWorth;
    private BigDecimal assetTotal;
    private BigDecimal liabilityTotal;
}
