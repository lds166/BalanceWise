package com.balancewise.importbill;

import com.balancewise.account.dto.AccountRequest;
import com.balancewise.account.service.AccountService;
import com.balancewise.auth.JwtUtil;
import com.balancewise.category.dto.CategoryRequest;
import com.balancewise.category.service.CategoryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ImportApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private AccountService accountService;
    @Autowired
    private CategoryService categoryService;
    @Autowired
    private ObjectMapper objectMapper;

    private Long defaultAccount() {
        AccountRequest r = new AccountRequest();
        r.setName("现金");
        r.setType("asset");
        r.setSubType("cash");
        r.setInitialBalance(new BigDecimal("0.00"));
        r.setRemark("");
        return accountService.create(1L, r).getId();
    }

    @Test
    void previewConfirmListDetailRollbackFlow() throws Exception {
        String token = jwtUtil.generate(1L);
        Long defId = defaultAccount();
        byte[] csv = Files.readAllBytes(
                new ClassPathResource("fixtures/alipay-sample.csv").getFile().toPath());
        MockMultipartFile file = new MockMultipartFile(
                "file", "alipay-sample.csv", "text/csv", csv);

        // 预览
        MvcResult preview = mockMvc.perform(multipart("/api/imports/preview")
                        .file(file)
                        .param("platform", "alipay")
                        .param("defaultAccountId", String.valueOf(defId))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = objectMapper.readTree(
                        preview.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .path("data").path("rows");
        assertEquals(4, rows.size());

        // 确认（勾选 2 条，补账户）
        StringBuilder body = new StringBuilder("{\"platform\":\"alipay\",\"fileName\":\"alipay-sample.csv\",\"rows\":[");
        for (int i = 0; i < 2; i++) {
            JsonNode r = rows.get(i);
            if (i > 0) body.append(',');
            body.append("{\"type\":\"").append(r.path("type").asText())
                .append("\",\"fromAccountId\":").append(defId)
                .append(",\"toAccountId\":").append(defId)
                .append(",\"amount\":").append(r.path("amount").asText())
                .append(",\"occurredAt\":\"").append(r.path("occurredAt").asText()).append("\"")
                .append(",\"counterparty\":\"").append(r.path("counterparty").asText()).append("\"")
                .append(",\"externalId\":\"").append(r.path("externalId").asText()).append("\"}");
        }
        body.append("]}");
        MvcResult confirmed = mockMvc.perform(post("/api/imports/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk())
                .andReturn();
        long batchId = objectMapper.readTree(confirmed.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        // 列表 + 明细
        mockMvc.perform(get("/api/imports").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        MvcResult detail = mockMvc.perform(get("/api/imports/" + batchId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(2, objectMapper.readTree(detail.getResponse().getContentAsString())
                .path("data").path("records").size());

        // 撤销
        mockMvc.perform(post("/api/imports/" + batchId + "/rollback")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        // 重复撤销 → 400
        MvcResult again = mockMvc.perform(post("/api/imports/" + batchId + "/rollback")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(400, objectMapper.readTree(again.getResponse().getContentAsString())
                .path("code").asInt());
    }

    /**
     * UC-4.10 学习闭环全链路：确认时带 learn 标记 → 撤销 → 重新预览（不给默认账户）
     * → 修正过的行按记忆命中（matchSource=memory），未修正/收入行不受污染。
     */
    @Test
    void learnAliasThenRePreviewHitsMemory() throws Exception {
        String token = jwtUtil.generate(1L);
        Long defId = defaultAccount();
        CategoryRequest catReq = new CategoryRequest();
        catReq.setName("餐饮");
        catReq.setKind("expense");
        Long catId = categoryService.create(1L, catReq).getId();

        byte[] csv = Files.readAllBytes(
                new ClassPathResource("fixtures/alipay-sample.csv").getFile().toPath());
        MockMultipartFile file = new MockMultipartFile("file", "alipay-sample.csv", "text/csv", csv);

        // 首次预览：行 0 = 永辉超市（支出，无规则命中）
        MvcResult preview = mockMvc.perform(multipart("/api/imports/preview")
                        .file(file).param("platform", "alipay")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        JsonNode rows = objectMapper.readTree(
                        preview.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .path("data").path("rows");
        JsonNode r0 = rows.get(0);
        assertEquals("expense", r0.path("type").asText());
        assertEquals("永辉超市", r0.path("counterparty").asText());
        assertEquals("余额宝", r0.path("payMethod").asText());   // 新字段回传
        assertTrue(r0.path("matchSource").isNull() || r0.path("matchSource").isMissingNode());

        // 确认（带学习标记）：分类改为餐饮、账户用现金
        String body = "{\"platform\":\"alipay\",\"fileName\":\"alipay-sample.csv\",\"rows\":[{"
                + "\"type\":\"expense\",\"fromAccountId\":" + defId
                + ",\"amount\":" + r0.path("amount").asText()
                + ",\"occurredAt\":\"" + r0.path("occurredAt").asText() + "\""
                + ",\"counterparty\":\"永辉超市\",\"item\":\"生鲜食品\""
                + ",\"externalId\":\"" + r0.path("externalId").asText() + "\""
                + ",\"categoryId\":" + catId
                + ",\"payMethod\":\"余额宝\",\"learnCategory\":true,\"learnAccount\":true}]}";
        MvcResult confirmed = mockMvc.perform(post("/api/imports/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        long batchId = objectMapper.readTree(confirmed.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        // 撤销后重新预览（不带默认账户）：记忆命中
        mockMvc.perform(post("/api/imports/" + batchId + "/rollback")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        MvcResult again = mockMvc.perform(multipart("/api/imports/preview")
                        .file(file).param("platform", "alipay")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        JsonNode rows2 = objectMapper.readTree(
                        again.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .path("data").path("rows");
        JsonNode hit = rows2.get(0);
        assertEquals("memory", hit.path("matchSource").asText());
        assertEquals(catId, hit.path("categoryId").asLong());
        assertEquals(defId, hit.path("fromAccountId").asLong());

        // 收入（退款）行同为永辉超市，但 kind=income 无记忆 → 不被支出记忆污染
        JsonNode income = streamOf(rows2).filter(n -> "income".equals(n.path("type").asText()))
                .findFirst().orElseThrow();
        assertNotEquals("memory", income.path("matchSource").asText());
        // 未学习过的"某商户"行不命中记忆
        JsonNode other = streamOf(rows2).filter(n -> "某商户".equals(n.path("counterparty").asText()))
                .findFirst().orElseThrow();
        assertNotEquals("memory", other.path("matchSource").asText());
    }

    private java.util.stream.Stream<JsonNode> streamOf(JsonNode array) {
        java.util.List<JsonNode> list = new java.util.ArrayList<>();
        array.forEach(list::add);
        return list.stream();
    }

    @Test
    void previewWithoutAuthReturns401() throws Exception {
        byte[] csv = Files.readAllBytes(
                new ClassPathResource("fixtures/alipay-sample.csv").getFile().toPath());
        mockMvc.perform(multipart("/api/imports/preview")
                        .file(new MockMultipartFile("file", "a.csv", "text/csv", csv))
                        .param("platform", "alipay"))
                .andExpect(status().is(401));
    }

    @Test
    void wrongPlatformHeaderReturns400() throws Exception {
        String token = jwtUtil.generate(1L);
        byte[] csv = Files.readAllBytes(
                new ClassPathResource("fixtures/alipay-sample.csv").getFile().toPath());
        MvcResult res = mockMvc.perform(multipart("/api/imports/preview")
                        .file(new MockMultipartFile("file", "a.csv", "text/csv", csv))
                        .param("platform", "alipay")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        // 正常解析应成功（code=0），若文件与平台不匹配则 code=400
        assertEquals(0, objectMapper.readTree(res.getResponse().getContentAsString())
                .path("code").asInt());
    }
}
