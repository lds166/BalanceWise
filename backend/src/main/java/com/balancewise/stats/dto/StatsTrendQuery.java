package com.balancewise.stats.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/** 收支趋势 / 净资产趋势查询参数。month 模式用 months 近 N 个月快捷区间，或用 start/end 覆盖；day 模式必须 start+end 且跨度 ≤ 366 天 */
@Data
public class StatsTrendQuery {
    /** month / day，默认 month */
    private String groupBy = "month";
    /** month 快捷区间月数（1~24），默认 6；给了 start/end 时忽略 */
    private Integer months;
    /** 含当天 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate start;
    /** 含当天（排他上界实现） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate end;
    /** 命中 from 或 to 任一账户；净资产趋势忽略该参数（净资产是全账户口径） */
    private Long accountId;
}
