package com.balancewise.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    private JwtUtil jwtUtil() {
        return new JwtUtil("balancewise-dev-secret-key-please-change-in-production-0123456789abcdef", 72);
    }

    @Test
    void generateThenParseReturnsUserId() {
        String token = jwtUtil().generate(42L);
        assertEquals(42L, jwtUtil().parseUserId(token));
    }

    @Test
    void parseTamperedTokenReturnsNull() {
        String token = jwtUtil().generate(42L);
        assertNull(jwtUtil().parseUserId(token + "tampered"));
    }

    @Test
    void parseGarbageReturnsNull() {
        assertNull(jwtUtil().parseUserId("not-a-token"));
    }
}
