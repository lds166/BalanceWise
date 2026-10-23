package com.balancewise.stats.vo;

import lombok.Data;

import java.math.BigDecimal;

/** 汇总卡片（UC-5.2）：区间内收入合计、支出合计、结余 = 收入 - 支出 */
@Data
public class StatsSummaryVo {
    private BigDecimal income;
    private BigDecimal expense;
    private BigDecimal balance;
}
