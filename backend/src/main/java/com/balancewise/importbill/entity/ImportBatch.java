package com.balancewise.importbill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("import_batch")
public class ImportBatch {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** alipay / wechat */
    private String platform;
    private String fileName;
    private Integer recordCount;
    private BigDecimal totalAmount;
    /** confirmed / rolled_back */
    private String status;
    private LocalDateTime importedAt;
    private LocalDateTime rolledBackAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
