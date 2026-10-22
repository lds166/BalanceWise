package com.balancewise.auth;

import com.balancewise.common.Result;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
// 嵌套在测试类内的 controller 会被 Spring Boot 的 TestTypeExcludeFilter 排除出组件扫描，
// 需显式 @Import 注册（简报假定同包可扫描，与 Spring Boot 3.x 实际行为不符，属有意偏差）
@Import(AuthApiTest.PingController.class)
class AuthApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @RestController
    static class PingController {
        @GetMapping("/api/test/ping")
        public Result<Long> ping(@CurrentUser Long userId) {
            return Result.ok(userId);
        }
    }

    @Test
    void noTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/test/ping"))
                .andExpect(status().is(401));
    }

    @Test
    void validTokenResolvesCurrentUser() throws Exception {
        String token = jwtUtil.generate(7L);
        MvcResult res = mockMvc.perform(get("/api/test/ping")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String body = res.getResponse().getContentAsString();
        assertTrue(body.contains("\"code\":0"));
        assertTrue(body.contains("\"data\":7"));
    }

    @Test
    void authEndpointsAreExcludedFromInterceptor() throws Exception {
        // /api/auth/** 无 token 也应到达 controller（此处 404 即可证明未被拦截器拦下）
        mockMvc.perform(get("/api/auth/not-exist"))
                .andExpect(status().is(404));
    }
}
