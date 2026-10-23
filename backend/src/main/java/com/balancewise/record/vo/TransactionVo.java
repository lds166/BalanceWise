package com.balancewise.record.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class TransactionVo {
    private Long id;
    private String type;
    private Long fromAccountId;
    private String fromAccountName;
    private Long toAccountId;
    private String toAccountName;
    private BigDecimal amount;
    private Long categoryId;
    private String categoryName;
    private LocalDateTime occurredAt;
    private String counterparty;
    private String item;
    private String source;
    private String externalId;
    private Long importBatchId;
}
