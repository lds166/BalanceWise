package com.balancewise.importbill.ai;

import com.balancewise.auth.CurrentUser;
import com.balancewise.common.Result;
import com.balancewise.importbill.ai.AiClassifier.AiRuntimeConfig;
import com.balancewise.importbill.dto.AiConfigRequest;
import com.balancewise.importbill.dto.AiConfigVo;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** AI 分类配置（个人信息页）：回显（Key 脱敏）/ 保存 / 测试连接。 */
@RestController
@RequestMapping("/api/user/ai-config")
public class AiConfigController {

    private final AiConfigService aiConfigService;
    private final AiClassifier aiClassifier;

    public AiConfigController(AiConfigService aiConfigService, AiClassifier aiClassifier) {
        this.aiConfigService = aiConfigService;
        this.aiClassifier = aiClassifier;
    }

    @GetMapping
    public Result<AiConfigVo> get(@CurrentUser Long userId) {
        return Result.ok(aiConfigService.get(userId));
    }

    /** 保存配置：apiKey 留空表示不修改已存 Key；返回保存后的脱敏视图 */
    @PutMapping
    public Result<AiConfigVo> save(@CurrentUser Long userId, @Valid @RequestBody AiConfigRequest req) {
        aiConfigService.save(userId, req);
        return Result.ok(aiConfigService.get(userId));
    }

    /** 测试连接：用表单当前值（Key 留空回退已存 Key）发一次最小请求 */
    @PostMapping("/test")
    public Result<String> test(@CurrentUser Long userId, @Valid @RequestBody AiConfigRequest req) {
        AiRuntimeConfig cfg = aiConfigService.resolveForTest(userId, req);
        String error = aiClassifier.testConnection(cfg);
        return Result.ok(error == null ? "ok" : error);
    }
}
