package com.balancewise.stats.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/** 分类构成查询参数（UC-5.5）：type 决定支出/收入构成；start/end 可选（空 = 全部时间）；accountId 可选 */
@Data
public class StatsCategoryQuery {
    /** expense / income，默认 expense */
    private String type = "expense";
    /** 含当天 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate start;
    /** 含当天（排他上界实现） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate end;
    /** 命中 from 或 to 任一账户 */
    private Long accountId;
}
