package com.balancewise.record;

import com.balancewise.account.dto.AccountRequest;
import com.balancewise.account.service.AccountService;
import com.balancewise.auth.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TransactionApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private AccountService accountService;
    @Autowired
    private ObjectMapper objectMapper;

    private Long assetId;
    private String token;

    private void prepare() {
        token = jwtUtil.generate(1L);
        AccountRequest r = new AccountRequest();
        r.setName("工资卡");
        r.setType("asset");
        r.setSubType("debit_card");
        r.setInitialBalance(new BigDecimal("5000.00"));
        r.setRemark("");
        assetId = accountService.create(1L, r).getId();
    }

    private String expenseJson(String amount) {
        return "{\"type\":\"expense\",\"fromAccountId\":" + assetId
                + ",\"amount\":" + amount + ",\"occurredAt\":\"2026-10-01T12:00:00\"}";
    }

    @Test
    void fullLifecycleCreateListEditDelete() throws Exception {
        prepare();
        // 记一笔
        MvcResult created = mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expenseJson("200.00")))
                .andExpect(status().isOk())
                .andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        assertTrue(id > 0);

        // 列表
        MvcResult listed = mockMvc.perform(get("/api/transactions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(listed.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        assertEquals(1, data.path("total").asLong());
        assertEquals("工资卡", data.path("records").get(0).path("fromAccountName").asText());

        // 编辑金额
        mockMvc.perform(put("/api/transactions/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expenseJson("300.00")))
                .andExpect(status().isOk());

        // 删除
        mockMvc.perform(delete("/api/transactions/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 列表为空，余额恢复
        MvcResult after = mockMvc.perform(get("/api/transactions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(0, objectMapper.readTree(after.getResponse().getContentAsString())
                .path("data").path("total").asLong());
        assertEquals(0, new BigDecimal("5000.00")
                .compareTo(accountService.getById(assetId).getBalance()));
    }

    @Test
    void noTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/transactions"))
                .andExpect(status().is(401));
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"expense\",\"amount\":1}"))
                .andExpect(status().is(401));
    }

    @Test
    void invalidAmountReturns400() throws Exception {
        prepare();
        MvcResult res = mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expenseJson("0")))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(400, objectMapper.readTree(res.getResponse().getContentAsString())
                .path("code").asInt());
    }

    @Test
    void exportReturnsCsv() throws Exception {
        prepare();
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expenseJson("88.00")))
                .andExpect(status().isOk());
        MvcResult res = mockMvc.perform(get("/api/transactions/export")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andReturn();
        String body = res.getResponse().getContentAsString();
        assertTrue(body.startsWith("﻿"));
        assertTrue(body.contains("支出"));
        assertTrue(body.contains("88.00"));
    }
}
