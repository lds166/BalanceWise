package com.balancewise.user.controller;

import com.balancewise.common.Result;
import com.balancewise.user.dto.LoginRequest;
import com.balancewise.user.dto.RegisterRequest;
import com.balancewise.user.service.UserService;
import com.balancewise.user.vo.LoginResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterRequest req) {
        userService.register(req);
        return Result.ok();
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return Result.ok(userService.login(req));
    }
}
