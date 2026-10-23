package com.balancewise.stats.vo;

import lombok.Data;

import java.util.List;

/** 收支趋势（UC-5.3）：按期排列，无数据期补零 */
@Data
public class StatsTrendVo {
    private List<TrendPointVo> points;
}
