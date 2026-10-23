package com.balancewise.importbill.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

@Data
public class ImportConfirmRequest {
    @NotBlank(message = "平台不能为空")
    @Pattern(regexp = "alipay|wechat", message = "平台必须是 alipay/wechat")
    private String platform;
    private String fileName;
    @Valid
    @NotEmpty(message = "请至少勾选一条流水")
    private List<ImportRowRequest> rows;
}
