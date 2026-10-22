package com.balancewise.category.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CategoryVo {
    private Long id;
    private String name;
    private String kind;
    private Integer isSystem;
    private List<CategoryVo> children = new ArrayList<>();
}
