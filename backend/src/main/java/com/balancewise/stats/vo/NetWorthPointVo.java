package com.balancewise.stats.vo;

import lombok.Data;

import java.math.BigDecimal;

/** 净资产趋势单期：label 为 yyyy-MM（月）或 yyyy-MM-dd（日）；netWorth = 期末净资产 */
@Data
public class NetWorthPointVo {
    private String label;
    private BigDecimal netWorth;
}
