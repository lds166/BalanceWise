package com.balancewise.importbill.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AiConfigRequest {
    /** 是否启用 AI 分类 */
    private Boolean enabled;
    /** OpenAI 兼容接口地址（留空取服务端默认） */
    @NotBlank(message = "接口地址不能为空")
    private String baseUrl;
    /** API Key（留空表示不修改已保存的 Key） */
    private String apiKey;
    /** 模型名（留空取服务端默认） */
    private String model;
}
