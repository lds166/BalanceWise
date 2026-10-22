package com.balancewise.category.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CategoryRequest {
    @NotBlank
    @Size(max = 32)
    private String name;

    @NotBlank
    @Pattern(regexp = "^(income|expense)$", message = "kind 只能是 income 或 expense")
    private String kind;

    private Long parentId; // 为空表示一级分类
}
