package com.balancewise.importbill;

import com.balancewise.category.entity.Category;
import com.balancewise.importbill.ai.AiClassifier;
import com.balancewise.importbill.ai.AiProperties;
import com.balancewise.importbill.dto.ImportPreviewRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * AI 分类器单元测试（MockRestServiceServer 模拟 OpenAI 兼容端点，不起 Spring 容器）：
 * 有效命中 / 无效分类与低置信度过滤 / kind 过滤 / 异常降级 / 未启用零请求。
 */
class AiClassifierTest {

    private static final String URL = "https://ai.example.com/v1/chat/completions";

    private AiProperties props(boolean enabled) {
        AiProperties p = new AiProperties();
        p.setEnabled(enabled);
        p.setApiKey(enabled ? "test-key" : "");
        p.setModel("test-model");
        p.setBaseUrl("https://ai.example.com/v1");
        return p;
    }

    private List<Category> categories() {
        Category e = new Category();
        e.setId(11L);
        e.setKind("expense");
        e.setName("餐饮");
        Category i = new Category();
        i.setId(12L);
        i.setKind("income");
        i.setName("其他收入");
        return List.of(e, i);
    }

    private ImportPreviewRow row(int lineNo, String type) {
        ImportPreviewRow r = new ImportPreviewRow();
        r.setLineNo(lineNo);
        r.setType(type);
        r.setAmount(new BigDecimal("1.00"));
        r.setCounterparty("商户" + lineNo);
        r.setItem("商品" + lineNo);
        return r;
    }

    /** 把 AI 返回的 items JSON 包成 OpenAI chat 响应体 */
    private static String chatBody(String itemsJson) {
        String escaped = itemsJson.replace("\"", "\\\"").replace("\n", "\\n");
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + escaped + "\"}}]}";
    }

    @Test
    void classifyKeepsValidHighConfidenceHitsOnly() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AiClassifier c = new AiClassifier(props(true), new ObjectMapper(), builder);

        String items = "{\"items\":["
                + "{\"lineNo\":1,\"categoryId\":11,\"confidence\":0.9},"
                + "{\"lineNo\":2,\"categoryId\":999,\"confidence\":0.9},"   // 不在用户分类列表 → 忽略
                + "{\"lineNo\":3,\"categoryId\":12,\"confidence\":0.2},"    // 低置信度 → 忽略
                + "{\"lineNo\":4,\"categoryId\":12,\"confidence\":0.9},"    // income 分类用于 expense 行 → kind 过滤
                + "{\"lineNo\":9,\"categoryId\":11,\"confidence\":0.9}]}";  // 行号不存在 → 忽略
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                // 输入脱敏：只含交易描述字段，不含金额
                .andExpect(jsonPath("$.messages[1].content",
                        org.hamcrest.Matchers.containsString("对方=")))
                .andRespond(withSuccess(chatBody(items), MediaType.APPLICATION_JSON));

        List<ImportPreviewRow> rows = List.of(
                row(1, "expense"), row(2, "expense"), row(3, "expense"),
                row(4, "expense"), row(5, "expense"));
        AiClassifier.AiOutcome out = c.classify(1L, categories(), rows);
        assertNull(out.error());
        assertEquals(Map.of(1, 11L), out.hits());
        server.verify();
    }

    @Test
    void classifyDegradesGracefullyOnServerError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AiClassifier c = new AiClassifier(props(true), new ObjectMapper(), builder);
        server.expect(requestTo(URL)).andRespond(withServerError());

        AiClassifier.AiOutcome out = c.classify(1L, categories(), List.of(row(1, "expense")));
        assertNotNull(out.error());
        assertTrue(out.hits().isEmpty());
        server.verify();
    }

    @Test
    void malformedAiOutputDegradesInsteadOfThrowing() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AiClassifier c = new AiClassifier(props(true), new ObjectMapper(), builder);
        // 缺 items 数组 → 解析异常 → 降级
        server.expect(requestTo(URL))
                .andRespond(withSuccess(chatBody("{\"foo\":1}"), MediaType.APPLICATION_JSON));

        AiClassifier.AiOutcome out = c.classify(1L, categories(), List.of(row(1, "expense")));
        assertNotNull(out.error());
        assertTrue(out.hits().isEmpty());
        server.verify();
    }

    @Test
    void disabledOrMissingKeySkipsHttpCall() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build(); // 无任何 expectation
        AiClassifier c = new AiClassifier(props(false), new ObjectMapper(), builder);

        AiClassifier.AiOutcome out = c.classify(1L, categories(), List.of(row(1, "expense")));
        assertTrue(out.hits().isEmpty());
        assertNull(out.error());
        server.verify(); // 未发出任何请求
    }
}
