package com.balancewise.importbill.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ImportRowRequest {
    @NotBlank(message = "类型不能为空")
    @Pattern(regexp = "expense|income|transfer", message = "类型必须是 expense/income/transfer")
    private String type;
    private Long fromAccountId;
    private Long toAccountId;
    @NotNull(message = "金额不能为空")
    @DecimalMin(value = "0.01", message = "金额必须大于 0")
    @Digits(integer = 10, fraction = 2, message = "金额最多两位小数")
    private BigDecimal amount;
    private Long categoryId;
    private LocalDateTime occurredAt;
    private String counterparty;
    private String item;
    private String externalId;
    /** 付款方式原文（回传自预览行），仅用于账户记忆学习 */
    private String payMethod;
    /** 用户在预览中修改了分类且勾选"记住"：确认后沉淀分类记忆 */
    private Boolean learnCategory;
    /** 用户在预览中修改了账户且勾选"记住"：确认后沉淀账户记忆 */
    private Boolean learnAccount;
}
