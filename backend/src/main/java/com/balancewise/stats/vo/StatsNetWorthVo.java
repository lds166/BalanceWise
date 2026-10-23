package com.balancewise.stats.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 净资产趋势（UC-5.4）：initialNetWorth = 全部账户初始净额（负债取负）；points 按期累计 */
@Data
public class StatsNetWorthVo {
    private BigDecimal initialNetWorth;
    private List<NetWorthPointVo> points;
}
