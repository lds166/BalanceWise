package com.balancewise.stats.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 分类构成（UC-5.5）：按一级分类聚合，items 按金额降序，未分类单列 */
@Data
public class StatsCategoryVo {
    private String type;
    private BigDecimal total;
    private List<CategoryStatItemVo> items;
}
