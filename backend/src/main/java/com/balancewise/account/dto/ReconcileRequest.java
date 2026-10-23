package com.balancewise.account.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/** 余额校正（UC-2.6 / 规格 7.6）：录入账户实际余额（负债账户按界面正数欠款录入），由后端归一为库内口径 */
@Data
public class ReconcileRequest {
    @NotNull(message = "请填写实际余额")
    private BigDecimal actualBalance;
}
