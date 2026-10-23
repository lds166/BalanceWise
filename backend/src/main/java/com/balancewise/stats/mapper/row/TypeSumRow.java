package com.balancewise.stats.mapper.row;

import lombok.Data;

import java.math.BigDecimal;

/** 按类型汇总投影行（sumByType 结果） */
@Data
public class TypeSumRow {
    private String type;
    private BigDecimal total;
}
