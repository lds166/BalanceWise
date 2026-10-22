package com.balancewise.category.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RenameCategoryRequest {
    @NotBlank
    @Size(max = 32)
    private String name;
}
