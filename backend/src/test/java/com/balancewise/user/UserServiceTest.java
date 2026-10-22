package com.balancewise.user;

import com.balancewise.common.BizException;
import com.balancewise.user.dto.LoginRequest;
import com.balancewise.user.dto.RegisterRequest;
import com.balancewise.user.service.UserService;
import com.balancewise.user.vo.LoginResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class UserServiceTest {

    @Autowired
    private UserService userService;

    private RegisterRequest regReq(String username, String password) {
        RegisterRequest r = new RegisterRequest();
        r.setUsername(username);
        r.setNickname("测试员");
        r.setPassword(password);
        r.setConfirmPassword(password);
        return r;
    }

    @Test
    void registerThenLoginSuccess() {
        userService.register(regReq("alice", "secret123"));
        LoginRequest login = new LoginRequest();
        login.setUsername("alice");
        login.setPassword("secret123");
        LoginResponse res = userService.login(login);
        assertEquals("alice", res.getUsername());
        assertEquals("测试员", res.getNickname());
        assertNotNull(res.getToken());
    }

    @Test
    void duplicateUsernameRejected() {
        userService.register(regReq("bob", "secret123"));
        BizException e = assertThrows(BizException.class,
                () -> userService.register(regReq("bob", "other456")));
        assertEquals("用户名已存在", e.getMessage());
    }

    @Test
    void passwordMismatchRejected() {
        RegisterRequest r = regReq("carol", "secret123");
        r.setConfirmPassword("different");
        BizException e = assertThrows(BizException.class, () -> userService.register(r));
        assertEquals("两次输入的密码不一致", e.getMessage());
    }

    @Test
    void wrongPasswordLoginRejected() {
        userService.register(regReq("dave", "secret123"));
        LoginRequest login = new LoginRequest();
        login.setUsername("dave");
        login.setPassword("wrong-pass");
        BizException e = assertThrows(BizException.class, () -> userService.login(login));
        assertEquals("用户名或密码错误", e.getMessage());
    }
}
