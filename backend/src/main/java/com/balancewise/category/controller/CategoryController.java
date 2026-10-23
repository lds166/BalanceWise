package com.balancewise.category.controller;

import com.balancewise.auth.CurrentUser;
import com.balancewise.category.dto.CategoryRequest;
import com.balancewise.category.dto.RenameCategoryRequest;
import com.balancewise.category.entity.Category;
import com.balancewise.category.service.CategoryService;
import com.balancewise.category.vo.CategoryVo;
import com.balancewise.common.BizException;
import com.balancewise.common.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public Result<List<CategoryVo>> tree(@CurrentUser Long userId,
                                         @RequestParam(required = false) String kind) {
        // 台账 #1：缺 kind 曾抛 MissingServletRequestParameterException 落入全局 500
        if (kind == null || kind.isBlank()) {
            throw new BizException("缺少必填参数：kind");
        }
        if (!"expense".equals(kind) && !"income".equals(kind)) {
            throw new BizException("kind 必须是 income 或 expense");
        }
        return Result.ok(categoryService.tree(userId, kind));
    }

    @PostMapping
    public Result<Category> create(@CurrentUser Long userId,
                                   @Valid @RequestBody CategoryRequest req) {
        return Result.ok(categoryService.create(userId, req));
    }

    @PutMapping("/{id}")
    public Result<Void> rename(@CurrentUser Long userId, @PathVariable Long id,
                               @Valid @RequestBody RenameCategoryRequest req) {
        categoryService.rename(userId, id, req.getName());
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@CurrentUser Long userId, @PathVariable Long id) {
        categoryService.delete(userId, id);
        return Result.ok();
    }
}
