package com.balancewise.importbill.controller;

import com.balancewise.auth.CurrentUser;
import com.balancewise.common.Result;
import com.balancewise.importbill.entity.ImportAlias;
import com.balancewise.importbill.service.ImportAliasService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 导入映射记忆管理（UC-4.10）：查看/删除用户确认过的分类与账户映射。 */
@RestController
@RequestMapping("/api/imports/aliases")
public class ImportAliasController {

    private final ImportAliasService importAliasService;

    public ImportAliasController(ImportAliasService importAliasService) {
        this.importAliasService = importAliasService;
    }

    @GetMapping
    public Result<List<ImportAlias>> list(@CurrentUser Long userId) {
        return Result.ok(importAliasService.list(userId));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@CurrentUser Long userId, @PathVariable Long id) {
        importAliasService.delete(userId, id);
        return Result.ok();
    }
}
