package com.balancewise.stats.vo;

import lombok.Data;

import java.math.BigDecimal;

/** 趋势单期：label 为 yyyy-MM（月）或 yyyy-MM-dd（日）；balance = income - expense */
@Data
public class TrendPointVo {
    private String label;
    private BigDecimal income;
    private BigDecimal expense;
    private BigDecimal balance;
}
