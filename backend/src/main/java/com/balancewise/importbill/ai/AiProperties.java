package com.balancewise.importbill.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 分类配置（OpenAI 兼容接口，默认对接硅基流动；换厂商只改 base-url/model）。
 * enabled 且配置了 api-key 才真正启用；api-key 缺失时导入照常走记忆+规则两层。
 */
@Data
@Component
@ConfigurationProperties(prefix = "balancewise.ai")
public class AiProperties {
    private boolean enabled = false;
    private String baseUrl = "https://api.siliconflow.cn/v1";
    private String apiKey = "";
    private String model = "Qwen/Qwen2.5-7B-Instruct";
    private long timeoutMs = 15000;
    /** 置信度低于该值的 AI 建议不采纳（宁可未分类，不要错分类） */
    private double confidenceThreshold = 0.6;
    /** 单次预览送 AI 分类的最大行数（超出部分只走规则层，控制延迟与成本） */
    private int maxRows = 300;

    public boolean usable() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
