package com.balancewise.importbill.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ImportPreviewRow {
    private int lineNo;
    private String externalId;
    private String type;
    private BigDecimal amount;
    private LocalDateTime occurredAt;
    private String counterparty;
    private String item;
    /** 平台原始分类（支付宝"类型"/微信"交易类型"），回传供前端展示与学习传递 */
    private String platformCategory;
    /** 付款方式原文，回传供账户记忆学习传递 */
    private String payMethod;
    private Long categoryId;
    private Long fromAccountId;
    private Long toAccountId;
    private String accountName;
    /** 分类/账户的匹配来源：memory 记忆 / rule 关键词规则 / ai 大模型，null=未命中 */
    private String matchSource;
    /** true = 与库中未删除记录或批内记录重复，默认不勾选 */
    private boolean duplicate;
}
