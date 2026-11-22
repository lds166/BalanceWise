package com.balancewise.importbill.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 分类配置（用户级，一人一行）：用户在个人信息页配置的大模型接入参数。
 * api_key 只存不回显（查询接口返回掩码）。
 */
@Data
@TableName("ai_config")
public class AiConfig {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** 0 否 / 1 是 */
    private Integer enabled;
    /** OpenAI 兼容接口地址（如 https://api.siliconflow.cn/v1） */
    private String baseUrl;
    /** 用户自备 Key（只存不回显） */
    private String apiKey;
    /** 模型名（如 Qwen/Qwen2.5-7B-Instruct） */
    private String model;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
