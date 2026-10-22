package com.balancewise.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChangeNicknameRequest {
    @NotBlank
    @Size(max = 32)
    private String nickname;
}
