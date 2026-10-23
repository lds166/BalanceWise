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
    private Long categoryId;
    private Long fromAccountId;
    private Long toAccountId;
    private String accountName;
    /** true = 与库中未删除记录或批内记录重复，默认不勾选 */
    private boolean duplicate;
}
