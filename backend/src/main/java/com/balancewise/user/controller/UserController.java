package com.balancewise.user.controller;

import com.balancewise.auth.CurrentUser;
import com.balancewise.common.Result;
import com.balancewise.user.dto.ChangeNicknameRequest;
import com.balancewise.user.dto.ChangePasswordRequest;
import com.balancewise.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PutMapping("/password")
    public Result<Void> changePassword(@CurrentUser Long userId,
                                       @Valid @RequestBody ChangePasswordRequest req) {
        userService.changePassword(userId, req);
        return Result.ok();
    }

    @PutMapping("/nickname")
    public Result<Void> changeNickname(@CurrentUser Long userId,
                                       @Valid @RequestBody ChangeNicknameRequest req) {
        userService.changeNickname(userId, req);
        return Result.ok();
    }
}
