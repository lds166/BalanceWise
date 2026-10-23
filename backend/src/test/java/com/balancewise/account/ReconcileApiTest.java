package com.balancewise.account;

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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReconcileApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private AccountService accountService;
    @Autowired
    private ObjectMapper objectMapper;

    private Long prepareAccount(String name, String balance) {
        AccountRequest r = new AccountRequest();
        r.setName(name);
        r.setType("asset");
        r.setSubType("debit_card");
        r.setInitialBalance(new BigDecimal(balance));
        r.setRemark("");
        return accountService.create(1L, r).getId();
    }

    @Test
    void reconcileSuccessReturnsVo() throws Exception {
        String token = jwtUtil.generate(1L);
        Long id = prepareAccount("接口对账卡", "200.00");
        MvcResult res = mockMvc.perform(post("/api/accounts/" + id + "/reconcile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actualBalance\":\"260.50\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(res.getResponse().getContentAsString()).path("data");
        assertEquals(id, data.path("accountId").asLong());
        assertEquals("income", data.path("correctionType").asText());
        assertEquals(0, new BigDecimal("60.50")
                .compareTo(new BigDecimal(data.path("diff").asText())));
        assertTrue(data.path("transactionId").asLong() > 0);
    }

    @Test
    void noTokenReturns401() throws Exception {
        Long id = prepareAccount("接口对账卡2", "0.00");
        mockMvc.perform(post("/api/accounts/" + id + "/reconcile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actualBalance\":\"1.00\"}"))
                .andExpect(status().is(401));
    }

    @Test
    void missingActualBalanceReturns400() throws Exception {
        String token = jwtUtil.generate(1L);
        Long id = prepareAccount("接口对账卡3", "0.00");
        MvcResult res = mockMvc.perform(post("/api/accounts/" + id + "/reconcile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(400, objectMapper.readTree(res.getResponse().getContentAsString())
                .path("code").asInt());
    }

    @Test
    void notFoundAccountReturns400() throws Exception {
        String token = jwtUtil.generate(1L);
        MvcResult res = mockMvc.perform(post("/api/accounts/999999999/reconcile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actualBalance\":\"1.00\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(400, objectMapper.readTree(res.getResponse().getContentAsString())
                .path("code").asInt());
    }
}
