package com.balancewise.account;

import com.balancewise.account.dto.AccountRequest;
import com.balancewise.account.service.AccountService;
import com.balancewise.auth.JwtUtil;
import com.balancewise.record.dto.TransactionRequest;
import com.balancewise.record.service.TransactionService;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AccountApiTest {

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

    @Test
    void deleteProtectedWhenTransactionsExist() throws Exception {
        String token = jwtUtil.generate(1L);
        AccountRequest r = new AccountRequest();
        r.setName("测试卡");
        r.setType("asset");
        r.setSubType("debit_card");
        r.setInitialBalance(new BigDecimal("100.00"));
        r.setRemark("");
        Long accountId = accountService.create(1L, r).getId();

        TransactionRequest t = new TransactionRequest();
        t.setType("expense");
        t.setFromAccountId(accountId);
        t.setAmount(new BigDecimal("10.00"));
        transactionService.create(1L, t);

        MvcResult res = mockMvc.perform(delete("/api/accounts/" + accountId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String body = res.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(400, objectMapper.readTree(body).path("code").asInt());
        assertTrue(body.contains("无法删除"));
    }
}
