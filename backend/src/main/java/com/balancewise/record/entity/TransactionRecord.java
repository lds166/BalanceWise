package com.balancewise.record.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("transaction_record")
public class TransactionRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String type;
    private Long fromAccountId;
    private Long toAccountId;
    private BigDecimal amount;
    private Long categoryId;
    private LocalDateTime occurredAt;
    private String counterparty;
    private String item;
    private String source;
    private String externalId;
    private Long importBatchId;
    private Integer isDeleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
