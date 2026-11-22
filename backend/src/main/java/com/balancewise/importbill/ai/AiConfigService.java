package com.balancewise.importbill.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.balancewise.common.BizException;
import com.balancewise.importbill.dto.AiConfigRequest;
import com.balancewise.importbill.dto.AiConfigVo;
import com.balancewise.importbill.ai.AiClassifier.AiRuntimeConfig;
import com.balancewise.importbill.mapper.AiConfigMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * AI 分类配置（用户级）：个人信息页的保存/回显，以及预览时的运行时解析。
 * 解析优先级：用户配置（enabled 且有 Key）→ 服务端全局配置（balancewise.ai）→ null（跳过 AI 层）。
 */
@Service
public class AiConfigService {

    private final AiConfigMapper aiConfigMapper;
    private final AiProperties globalProps;

    public AiConfigService(AiConfigMapper aiConfigMapper, AiProperties globalProps) {
        this.aiConfigMapper = aiConfigMapper;
        this.globalProps = globalProps;
    }

    /** 回显视图：Key 不回显原文，只给掩码与"是否已配置"标记 */
    public AiConfigVo get(Long userId) {
        AiConfig config = findOwned(userId);
        AiConfigVo vo = new AiConfigVo();
        if (config == null) {
            vo.setEnabled(false);
            vo.setBaseUrl(globalProps.getBaseUrl());
            vo.setModel(globalProps.getModel());
            vo.setHasKey(false);
            vo.setKeyMasked(null);
            return vo;
        }
        vo.setEnabled(Integer.valueOf(1).equals(config.getEnabled()));
        vo.setBaseUrl(config.getBaseUrl());
        vo.setModel(config.getModel());
        vo.setHasKey(config.getApiKey() != null && !config.getApiKey().isBlank());
        vo.setKeyMasked(mask(config.getApiKey()));
        return vo;
    }

    /** 保存（upsert）：apiKey 为空/空白表示"不修改已存 Key" */
    public AiConfig save(Long userId, AiConfigRequest req) {
        AiConfig config = findOwned(userId);
        String apiKey = req.getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            apiKey = apiKey.trim();
        } else if (config == null) {
            throw new BizException("首次启用请填写 API Key");
        } else {
            apiKey = config.getApiKey(); // 不修改
        }
        if (config == null) {
            config = new AiConfig();
            config.setUserId(userId);
            config.setCreatedAt(LocalDateTime.now());
        }
        config.setEnabled(Boolean.TRUE.equals(req.getEnabled()) ? 1 : 0);
        config.setBaseUrl(req.getBaseUrl() != null && !req.getBaseUrl().isBlank()
                ? req.getBaseUrl().trim() : globalProps.getBaseUrl());
        config.setApiKey(apiKey);
        config.setModel(req.getModel() != null && !req.getModel().isBlank()
                ? req.getModel().trim() : globalProps.getModel());
        config.setUpdatedAt(LocalDateTime.now());
        if (config.getId() == null) {
            aiConfigMapper.insert(config);
        } else {
            aiConfigMapper.updateById(config);
        }
        return config;
    }

    /** 预览时的运行时配置解析：用户配置 → 全局兜底 → null */
    public AiRuntimeConfig resolve(Long userId) {
        AiConfig config = findOwned(userId);
        if (config != null && Integer.valueOf(1).equals(config.getEnabled())
                && config.getApiKey() != null && !config.getApiKey().isBlank()) {
            return new AiRuntimeConfig(config.getBaseUrl(), config.getApiKey(), config.getModel());
        }
        if (globalProps.usable()) {
            return new AiRuntimeConfig(globalProps.getBaseUrl(), globalProps.getApiKey(), globalProps.getModel());
        }
        return null;
    }

    /**
     * "测试连接"用解析：表单里的 Key 优先，留空回退已存 Key（再无则报错）。
     * 允许在未保存配置的情况下先用表单值试连。
     */
    public AiRuntimeConfig resolveForTest(Long userId, AiConfigRequest req) {
        String baseUrl = req.getBaseUrl() != null && !req.getBaseUrl().isBlank()
                ? req.getBaseUrl().trim() : globalProps.getBaseUrl();
        String model = req.getModel() != null && !req.getModel().isBlank()
                ? req.getModel().trim() : globalProps.getModel();
        String apiKey = req.getApiKey() != null && !req.getApiKey().isBlank()
                ? req.getApiKey().trim() : null;
        if (apiKey == null) {
            AiConfig config = findOwned(userId);
            if (config != null && config.getApiKey() != null && !config.getApiKey().isBlank()) {
                apiKey = config.getApiKey();
            }
        }
        if (apiKey == null) {
            throw new BizException("请先填写 API Key");
        }
        return new AiRuntimeConfig(baseUrl, apiKey, model);
    }

    private AiConfig findOwned(Long userId) {
        return aiConfigMapper.selectOne(new LambdaQueryWrapper<AiConfig>()
                .eq(AiConfig::getUserId, userId)
                .last("LIMIT 1"));
    }

    private String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        String k = apiKey.trim();
        if (k.length() <= 8) {
            return k.charAt(0) + "****";
        }
        return k.substring(0, 4) + "****" + k.substring(k.length() - 4);
    }
}
