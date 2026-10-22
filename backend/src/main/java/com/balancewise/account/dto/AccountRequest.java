package com.balancewise.account.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class AccountRequest {
    @NotBlank
    @Size(max = 32)
    private String name;

    @NotBlank
    @Pattern(regexp = "^(asset|liability)$", message = "账户类别只能是 asset 或 liability")
    private String type;

    @NotBlank
    private String subType;

    @NotNull
    @DecimalMin(value = "0.00", message = "初始余额不能为负")
    private BigDecimal initialBalance;

    @Size(max = 255)
    private String remark;
}
