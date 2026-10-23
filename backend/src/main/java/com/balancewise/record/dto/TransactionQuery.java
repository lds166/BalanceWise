package com.balancewise.record.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class TransactionQuery {
    private long page = 1;
    private long size = 20;
    /** expense / income / transfer，空 = 全部 */
    private String type;
    /** 命中 from 或 to 任一账户 */
    private Long accountId;
    /** 精确匹配该分类（前端传叶子分类 id） */
    private Long categoryId;
    /** 含当天 00:00:00 起 */
    private LocalDate start;
    /** 含当天 23:59:59 止 */
    private LocalDate end;
    /** 对方/备注模糊 */
    private String keyword;
}
