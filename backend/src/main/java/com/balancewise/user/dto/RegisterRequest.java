package com.balancewise.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {
    @NotBlank
    @Size(min = 4, max = 32, message = "用户名长度须为 4-32 位")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只能包含字母、数字、下划线")
    private String username;

    @Size(max = 32)
    private String nickname;

    @NotBlank
    @Size(min = 6, max = 64, message = "密码长度须为 6-64 位")
    private String password;

    @NotBlank(message = "请再次输入密码")
    private String confirmPassword;
}
