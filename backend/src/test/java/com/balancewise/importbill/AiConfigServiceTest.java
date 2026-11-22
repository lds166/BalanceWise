package com.balancewise.importbill;

import com.balancewise.common.BizException;
import com.balancewise.importbill.ai.AiClassifier.AiRuntimeConfig;
import com.balancewise.importbill.ai.AiConfigService;
import com.balancewise.importbill.mapper.AiConfigMapper;
import com.balancewise.importbill.dto.AiConfigRequest;
import com.balancewise.importbill.dto.AiConfigVo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AI 分类配置（用户级）：保存 upsert / Key 掩码回显 / 留空不改 Key / 首次必填 Key / 解析优先级。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AiConfigServiceTest {

    private static final String BASE_URL = "https://api.siliconflow.cn/v1";
    private static final String MODEL = "Qwen/Qwen2.5-7B-Instruct";

    @Autowired
    private AiConfigService aiConfigService;
    @Autowired
    private AiConfigMapper aiConfigMapper;

    private AiConfigRequest req(Boolean enabled, String apiKey) {
        AiConfigRequest r = new AiConfigRequest();
        r.setEnabled(enabled);
        r.setBaseUrl(BASE_URL);
        r.setApiKey(apiKey);
        r.setModel(MODEL);
        return r;
    }

    @Test
    void noConfigYetReturnsDefaultsAndNoKey() {
        AiConfigVo vo = aiConfigService.get(1L);
        assertFalse(vo.isEnabled());
        assertFalse(vo.isHasKey());
        assertNull(vo.getKeyMasked());
        assertNotNull(vo.getBaseUrl());  // 默认值来自全局配置
        assertNotNull(vo.getModel());
    }

    @Test
    void saveAndGetMaskedRoundTrip() {
        aiConfigService.save(1L, req(true, "sk-abcdef1234567890"));

        AiConfigVo vo = aiConfigService.get(1L);
        assertTrue(vo.isEnabled());
        assertTrue(vo.isHasKey());
        assertEquals(BASE_URL, vo.getBaseUrl());
        assertEquals(MODEL, vo.getModel());
        assertEquals("sk-a****7890", vo.getKeyMasked()); // 原文永不回显
        // 用户级唯一：重复保存仍是 upsert 单行
        assertEquals(1, aiConfigMapper.selectCount(null));
    }

    @Test
    void blankKeyKeepsStoredKey() {
        aiConfigService.save(1L, req(true, "sk-stored-key-123456"));
        // 二次保存 Key 留空 → 不修改；地址/模型更新
        AiConfigRequest update = req(true, "  ");
        update.setBaseUrl("https://api.deepseek.com/v1");
        update.setModel("deepseek-chat");
        aiConfigService.save(1L, update);

        AiRuntimeConfig cfg = aiConfigService.resolve(1L);
        assertEquals("sk-stored-key-123456", cfg.apiKey());
        assertEquals("https://api.deepseek.com/v1", cfg.baseUrl());
        assertEquals("deepseek-chat", cfg.model());
    }

    @Test
    void firstSaveWithoutKeyRejected() {
        assertThrows(BizException.class, () -> aiConfigService.save(1L, req(true, null)));
        assertThrows(BizException.class, () -> aiConfigService.save(1L, req(true, "   ")));
        assertEquals(0, aiConfigMapper.selectCount(null));
    }

    @Test
    void resolvePrecedenceUserConfigOverGlobalFallback() {
        // 测试环境无全局 AI_API_KEY：未配置/未启用 → null（跳过 AI 层）
        assertNull(aiConfigService.resolve(1L));
        aiConfigService.save(1L, req(false, "sk-key-000111222"));
        assertNull(aiConfigService.resolve(1L)); // 配置了但开关关着
        aiConfigService.save(1L, req(true, "sk-key-000111222"));
        AiRuntimeConfig cfg = aiConfigService.resolve(1L);
        assertNotNull(cfg);
        assertEquals(BASE_URL, cfg.baseUrl());
        assertEquals(MODEL, cfg.model());
        assertEquals("sk-key-000111222", cfg.apiKey());
    }

    @Test
    void resolveForTestPrefersFormKeyThenStoredKey() {
        // 表单带 Key：直接用表单值（未保存也能试连）
        AiRuntimeConfig cfg = aiConfigService.resolveForTest(1L, req(true, "sk-form-key-xyz"));
        assertEquals("sk-form-key-xyz", cfg.apiKey());
        // 表单 Key 留空：回退已存 Key；无已存 Key → 拒绝
        aiConfigService.save(1L, req(true, "sk-stored-key-999"));
        assertEquals("sk-stored-key-999",
                aiConfigService.resolveForTest(1L, req(true, "  ")).apiKey());
        assertThrows(BizException.class, () -> aiConfigService.resolveForTest(2L, req(true, " ")));
    }
}
