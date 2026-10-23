package com.balancewise.stats.mapper.row;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 按日+类型汇总投影行（dailyFlows 结果，按 day 升序） */
@Data
public class DailyFlowRow {
    private LocalDate day;
    private String type;
    private BigDecimal total;
}
