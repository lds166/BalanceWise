package com.balancewise.importbill.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ImportBatchVo {
    private Long id;
    private String platform;
    private String fileName;
    private Integer recordCount;
    private BigDecimal totalAmount;
    private String status;
    private LocalDateTime importedAt;
    private LocalDateTime rolledBackAt;
}
