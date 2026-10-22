package com.balancewise.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.balancewise.account.service.AccountService;
import com.balancewise.auth.JwtUtil;
import com.balancewise.category.service.CategoryService;
import com.balancewise.common.BizException;
import com.balancewise.user.dto.ChangeNicknameRequest;
import com.balancewise.user.dto.ChangePasswordRequest;
import com.balancewise.user.dto.LoginRequest;
import com.balancewise.user.dto.RegisterRequest;
import com.balancewise.user.entity.User;
import com.balancewise.user.mapper.UserMapper;
import com.balancewise.user.vo.LoginResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService extends ServiceImpl<UserMapper, User> {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final JwtUtil jwtUtil;
    private final CategoryService categoryService;
    private final AccountService accountService;

    public UserService(JwtUtil jwtUtil, CategoryService categoryService, AccountService accountService) {
        this.jwtUtil = jwtUtil;
        this.categoryService = categoryService;
        this.accountService = accountService;
    }

    @Transactional
    public void register(RegisterRequest req) {
        if (!req.getPassword().equals(req.getConfirmPassword())) {
            throw new BizException("两次输入的密码不一致");
        }
        long exists = count(new LambdaQueryWrapper<User>().eq(User::getUsername, req.getUsername()));
        if (exists > 0) {
            throw new BizException("用户名已存在");
        }
        User u = new User();
        u.setUsername(req.getUsername());
        u.setNickname(req.getNickname() == null || req.getNickname().isBlank()
                ? req.getUsername() : req.getNickname());
        u.setPasswordHash(encoder.encode(req.getPassword()));
        save(u);
        categoryService.seedForUser(u.getId());
        accountService.createDefault(u.getId());
    }

    public LoginResponse login(LoginRequest req) {
        User u = getOne(new LambdaQueryWrapper<User>().eq(User::getUsername, req.getUsername()));
        if (u == null || !encoder.matches(req.getPassword(), u.getPasswordHash())) {
            throw new BizException("用户名或密码错误");
        }
        return new LoginResponse(u.getId(), u.getUsername(), u.getNickname(), jwtUtil.generate(u.getId()));
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest req) {
        User u = getById(userId);
        if (u == null || !encoder.matches(req.getOldPassword(), u.getPasswordHash())) {
            throw new BizException("原密码不正确");
        }
        u.setPasswordHash(encoder.encode(req.getNewPassword()));
        updateById(u);
    }

    @Transactional
    public void changeNickname(Long userId, ChangeNicknameRequest req) {
        User u = getById(userId);
        if (u == null) {
            throw new BizException("用户不存在");
        }
        u.setNickname(req.getNickname());
        updateById(u);
    }
}
