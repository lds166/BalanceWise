package com.balancewise.stats;

import com.balancewise.account.dto.AccountRequest;
import com.balancewise.account.service.AccountService;
import com.balancewise.auth.JwtUtil;
import com.balancewise.record.dto.TransactionRequest;
import com.balancewise.record.service.TransactionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StatsApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private AccountService accountService;
    @Autowired
    private TransactionService transactionService;
    @Autowired
    private ObjectMapper objectMapper;

    private void seedOneExpense() {
        AccountRequest r = new AccountRequest();
        r.setName("统计卡");
        r.setType("asset");
        r.setSubType("cash");
        r.setInitialBalance(new BigDecimal("0.00"));
        r.setRemark("");
        Long accountId = accountService.create(1L, r).getId();
        TransactionRequest t = new TransactionRequest();
        t.setType("expense");
        t.setFromAccountId(accountId);
        t.setAmount(new BigDecimal("100.00"));
        t.setOccurredAt(LocalDateTime.of(2026, 10, 5, 12, 0));
        transactionService.create(1L, t);
    }

    @Test
    void summaryReturnsAggregatedShape() throws Exception {
        seedOneExpense();
        String token = jwtUtil.generate(1L);
        MvcResult res = mockMvc.perform(get("/api/stats/summary")
                        .param("start", "2026-10-01").param("end", "2026-10-31")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(
                res.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(0, body.path("code").asInt());
        assertEquals(0, new BigDecimal("100.00").compareTo(
                body.path("data").path("expense").decimalValue()));
        assertTrue(body.path("data").has("income"));
        assertTrue(body.path("data").has("balance"));
    }

    @Test
    void allStatsEndpointsRequireAuth() throws Exception {
        mockMvc.perform(get("/api/stats/summary")).andExpect(status().is(401));
        mockMvc.perform(get("/api/stats/trend").param("groupBy", "month")).andExpect(status().is(401));
        mockMvc.perform(get("/api/stats/net-worth").param("groupBy", "month")).andExpect(status().is(401));
        mockMvc.perform(get("/api/stats/category").param("type", "expense")).andExpect(status().is(401));
    }

    @Test
    void trendInvalidGroupByReturnsCode400() throws Exception {
        String token = jwtUtil.generate(1L);
        MvcResult res = mockMvc.perform(get("/api/stats/trend")
                        .param("groupBy", "week")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(
                res.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(400, body.path("code").asInt());
        assertEquals("groupBy 必须是 month 或 day", body.path("message").asText());
    }

    @Test
    void categoryInvalidTypeReturnsCode400() throws Exception {
        String token = jwtUtil.generate(1L);
        MvcResult res = mockMvc.perform(get("/api/stats/category")
                        .param("type", "transfer")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(
                res.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(400, body.path("code").asInt());
        assertEquals("type 必须是 expense 或 income", body.path("message").asText());
    }
}
