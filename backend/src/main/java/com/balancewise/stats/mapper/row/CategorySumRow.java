package com.balancewise.stats.mapper.row;

import lombok.Data;

import java.math.BigDecimal;

/** 按分类汇总投影行（sumByCategory 结果）；category_id 为 NULL = 未分类行 */
@Data
public class CategorySumRow {
    private Long categoryId;
    private BigDecimal total;
}
