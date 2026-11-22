package com.balancewise.importbill.dto;

import lombok.Data;

@Data
public class AiConfigVo {
    private boolean enabled;
    private String baseUrl;
    private String model;
    /** 是否已保存过 API Key */
    private boolean hasKey;
    /** Key 掩码（如 sk-a****b3cd），原文永不回显 */
    private String keyMasked;
}
