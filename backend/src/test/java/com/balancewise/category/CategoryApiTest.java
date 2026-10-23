package com.balancewise.category;

import com.balancewise.auth.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CategoryApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void missingKindReturns400() throws Exception {
        // 台账 #1：裸 GET 曾因 @RequestParam 缺参落入全局 500，应为 400
        MvcResult res = mockMvc.perform(get("/api/categories")
                        .header("Authorization", "Bearer " + jwtUtil.generate(1L)))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(400, objectMapper.readTree(res.getResponse().getContentAsString())
                .path("code").asInt());
    }

    @Test
    void invalidKindReturns400() throws Exception {
        MvcResult res = mockMvc.perform(get("/api/categories")
                        .param("kind", "profit")
                        .header("Authorization", "Bearer " + jwtUtil.generate(1L)))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(400, objectMapper.readTree(res.getResponse().getContentAsString())
                .path("code").asInt());
    }
}
