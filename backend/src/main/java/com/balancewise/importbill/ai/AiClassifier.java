package com.balancewise.importbill.ai;

import com.balancewise.category.entity.Category;
import com.balancewise.importbill.dto.ImportPreviewRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * AI 分类（分类流水线第 3 层，UC-4.4 增强）：只处理记忆/规则未命中的行，
 * OpenAI 兼容 chat/completions + JSON 输出。设计约束：
 * ① 分类约束——只允许从用户自己的分类列表中选择，禁止模型自由发挥；
 * ② 输入脱敏——只发送 对方/商品/平台分类/收支方向，不含金额与时间；
 * ③ 优雅降级——未配置/超时/任何异常都只返回带 error 的空结果，绝不阻断导入；
 * ④ 运行时配置——接入参数来自用户级配置（个人信息页，AiConfigService 解析），
 *    客户端按配置缓存复用；模板 builder 仅在首次构建时短暂使用（加锁防并发竞态）。
 */
@Service
public class AiClassifier {

    private static final Logger log = LoggerFactory.getLogger(AiClassifier.class);

    /** 运行时接入参数（来自用户配置或服务端全局配置） */
    public record AiRuntimeConfig(String baseUrl, String apiKey, String model) {
    }

    /** AI 推荐结果：lineNo → categoryId；error 非空表示本次降级跳过 */
    public record AiOutcome(Map<Integer, Long> hits, String error) {
        public static AiOutcome empty() {
            return new AiOutcome(Map.of(), null);
        }
    }

    private final AiConfigService aiConfigService;
    private final AiProperties props;
    private final ObjectMapper objectMapper;
    private final RestClient.Builder template;
    /** 已构建的客户端缓存：key = baseUrl|apiKey（同配置复用，避免并发改写共享 builder） */
    private final ConcurrentHashMap<String, RestClient> clients = new ConcurrentHashMap<>();

    public AiClassifier(AiConfigService aiConfigService, AiProperties props,
                        ObjectMapper objectMapper,
                        @Qualifier("aiRestClientBuilder") RestClient.Builder template) {
        this.aiConfigService = aiConfigService;
        this.props = props;
        this.objectMapper = objectMapper;
        this.template = template;
    }

    public int getMaxRows() {
        return props.getMaxRows();
    }

    /** 按用户解析配置并分类；未配置返回空结果（error=null，调用方静默跳过） */
    public AiOutcome classifyForUser(Long userId, List<Category> categories, List<ImportPreviewRow> rows) {
        return classify(aiConfigService.resolve(userId), categories, rows);
    }

    /**
     * 对未命中记忆/规则的行做 AI 分类。
     *
     * @param categories 用户全量分类（expense+income），用于约束输出与校验返回值
     * @param rows       待分类行（非转账且前两层未命中）
     * @return 命中映射；任何异常返回带 error 的空结果，不抛出
     */
    public AiOutcome classify(AiRuntimeConfig cfg, List<Category> categories, List<ImportPreviewRow> rows) {
        if (cfg == null || rows.isEmpty()) {
            return AiOutcome.empty();
        }
        try {
            String content = chat(clientFor(cfg), cfg.model(),
                    systemPrompt(categories), userPrompt(categories, rows));
            return parseOutcome(content, categories, rows);
        } catch (Exception e) {
            log.warn("AI 分类失败，本次跳过（不影响导入）：{}", e.getMessage());
            return new AiOutcome(Map.of(), e.getClass().getSimpleName());
        }
    }

    /** 配置连通性测试（个人信息页"测试连接"按钮）：成功返回 null，失败返回原因 */
    public String testConnection(AiRuntimeConfig cfg) {
        try {
            Map<String, Object> body = Map.of(
                    "model", cfg.model(),
                    "messages", List.of(Map.of("role", "user", "content", "收到请只回复两个字母：OK")),
                    "max_tokens", 8,
                    "stream", false);
            String resp = clientFor(cfg).post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode content = objectMapper.readTree(resp == null ? "" : resp)
                    .path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull() || content.asText().isBlank()) {
                return "接口响应异常（未返回内容）";
            }
            return null;
        } catch (Exception e) {
            log.warn("AI 配置测试连接失败：{}", e.getMessage());
            return e.getClass().getSimpleName() + "：请检查地址/Key/模型名是否正确";
        }
    }

    /** 按运行时配置取（或建）客户端；模板 builder 的改写阶段加锁，避免并发竞态 */
    private RestClient clientFor(AiRuntimeConfig cfg) {
        String key = cfg.baseUrl() + '|' + cfg.apiKey();
        return clients.computeIfAbsent(key, k -> {
            synchronized (template) {
                return template
                        .baseUrl(cfg.baseUrl())
                        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + cfg.apiKey())
                        .build();
            }
        });
    }

    private String chat(RestClient client, String model, String system, String user)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)),
                "temperature", 0.1,
                "response_format", Map.of("type", "json_object"),
                "stream", false);
        String resp = client.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        JsonNode content = objectMapper.readTree(resp == null ? "" : resp)
                .path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new IllegalStateException("响应缺少 message.content");
        }
        return content.asText();
    }

    private AiOutcome parseOutcome(String content, List<Category> categories, List<ImportPreviewRow> rows)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        Map<Long, String> kindById = categories.stream()
                .collect(Collectors.toMap(Category::getId, Category::getKind, (a, b) -> a));
        Map<Integer, ImportPreviewRow> rowByLineNo = rows.stream()
                .collect(Collectors.toMap(ImportPreviewRow::getLineNo, r -> r, (a, b) -> a));
        Map<Integer, Long> hits = new HashMap<>();
        JsonNode items = objectMapper.readTree(content).path("items");
        if (!items.isArray()) {
            throw new IllegalStateException("AI 输出缺少 items 数组");
        }
        for (JsonNode item : items) {
            int lineNo = item.path("lineNo").asInt(-1);
            JsonNode catNode = item.path("categoryId");
            double confidence = item.path("confidence").asDouble(0);
            if (lineNo < 0 || !catNode.canConvertToLong()
                    || confidence < props.getConfidenceThreshold()) {
                continue;
            }
            ImportPreviewRow row = rowByLineNo.get(lineNo);
            if (row == null) {
                continue;
            }
            Long catId = catNode.asLong();
            String kind = kindById.get(catId);
            // 分类必须属于用户列表且 kind 与该行收支方向一致，否则忽略（宁缺毋滥）
            if (kind == null || !kind.equals(kindOf(row.getType()))) {
                continue;
            }
            hits.put(lineNo, catId);
        }
        return new AiOutcome(hits, null);
    }

    private String kindOf(String type) {
        return "expense".equals(type) ? "expense" : "income";
    }

    private String systemPrompt(List<Category> categories) {
        return "你是个人记账应用的账单智能分类助手。给你一组账单流水（仅含交易描述，不含金额与时间）"
                + "和用户自己的分类列表。请为每条流水从分类列表中选出最合适的一个分类 id，"
                + "并给出 0~1 的置信度。若没有合适的分类，categoryId 置为 null。\n"
                + "硬性要求：只能使用分类列表中出现的 id，禁止编造；只输出 JSON 对象，格式："
                + "{\"items\":[{\"lineNo\":行号,\"categoryId\":分类id或null,\"confidence\":0.0到1.0}]}";
    }

    /** 输入脱敏：仅 对方/商品/平台分类/收支方向，不含金额与时间 */
    private String userPrompt(List<Category> categories, List<ImportPreviewRow> rows) {
        StringBuilder sb = new StringBuilder("分类列表：\n");
        for (Category c : categories) {
            sb.append("- id=").append(c.getId())
                    .append(" 类型=").append("expense".equals(c.getKind()) ? "支出" : "收入")
                    .append(" 名称=").append(c.getName()).append('\n');
        }
        sb.append("\n账单流水：\n");
        for (ImportPreviewRow r : rows) {
            sb.append("- 行").append(r.getLineNo())
                    .append(" 对方=").append(nvl(r.getCounterparty()))
                    .append(" 商品=").append(nvl(r.getItem()))
                    .append(" 平台分类=").append(nvl(r.getPlatformCategory()))
                    .append(" 方向=").append("expense".equals(r.getType()) ? "支出" : "收入")
                    .append('\n');
        }
        return sb.toString();
    }

    private String nvl(String s) {
        return s == null || s.isBlank() ? "无" : s;
    }
}
