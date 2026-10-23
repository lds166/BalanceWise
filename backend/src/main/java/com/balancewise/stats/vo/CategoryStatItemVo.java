package com.balancewise.stats.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 分类构成单节点：一级分类聚合值；children 为二级分类明细（下钻用）。未分类 categoryId = null */
@Data
public class CategoryStatItemVo {
    private Long categoryId;
    private String name;
    private BigDecimal amount;
    /** amount / 总计 × 100，两位小数 */
    private BigDecimal percentage;
    private List<CategoryStatItemVo> children;
}
