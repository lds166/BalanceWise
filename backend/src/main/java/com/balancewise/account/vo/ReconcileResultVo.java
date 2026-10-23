package com.balancewise.account.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 余额校正结果（UC-2.6）。全部库内口径（负债账户为负）：
 * diff = 归一后实际余额 − 校正前余额；diff > 0 记收入校正，diff < 0 记支出校正。
 */
@Data
public class ReconcileResultVo {
    private Long accountId;
    private String accountName;
    private BigDecimal beforeBalance;
    private BigDecimal afterBalance;
    private BigDecimal diff;
    private String correctionType;
    private Long transactionId;
}
