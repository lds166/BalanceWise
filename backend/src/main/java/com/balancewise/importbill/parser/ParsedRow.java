package com.balancewise.importbill.parser;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 解析后的标准化账单行（与平台无关） */
@Data
public class ParsedRow {
    private int lineNo;
    private String externalId;
    /** expense / income / transfer */
    private String type;
    private BigDecimal amount;
    private LocalDateTime occurredAt;
    private String counterparty;
    private String item;
    /** 平台侧分类字段（支付宝=类型，微信=交易类型），用于分类映射 */
    private String platformCategory;
    /** 付款方式，用于账户匹配 */
    private String payMethod;
}
