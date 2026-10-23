package com.balancewise.importbill.controller;

import com.balancewise.auth.CurrentUser;
import com.balancewise.common.Result;
import com.balancewise.importbill.dto.ImportConfirmRequest;
import com.balancewise.importbill.entity.ImportBatch;
import com.balancewise.importbill.service.ImportService;
import com.balancewise.importbill.vo.ImportBatchDetailVo;
import com.balancewise.importbill.vo.ImportBatchVo;
import com.balancewise.importbill.vo.ImportPreviewVo;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/imports")
public class ImportController {

    private final ImportService importService;

    public ImportController(ImportService importService) {
        this.importService = importService;
    }

    /** 上传解析预览（不落库）。multipart：platform + file + defaultAccountId(可选) */
    @PostMapping("/preview")
    public Result<ImportPreviewVo> preview(@CurrentUser Long userId,
                                           @RequestParam String platform,
                                           @RequestParam("file") MultipartFile file,
                                           @RequestParam(required = false) Long defaultAccountId)
            throws IOException {
        ImportPreviewVo vo = importService.preview(userId, platform, file.getBytes(), defaultAccountId);
        vo.setFileName(file.getOriginalFilename());
        return Result.ok(vo);
    }

    @PostMapping("/confirm")
    public Result<ImportBatch> confirm(@CurrentUser Long userId,
                                       @Valid @RequestBody ImportConfirmRequest req) {
        return Result.ok(importService.confirm(userId, req));
    }

    @GetMapping
    public Result<List<ImportBatchVo>> list(@CurrentUser Long userId) {
        return Result.ok(importService.list(userId));
    }

    @GetMapping("/{id}")
    public Result<ImportBatchDetailVo> detail(@CurrentUser Long userId, @PathVariable Long id) {
        return Result.ok(importService.detail(userId, id));
    }

    @PostMapping("/{id}/rollback")
    public Result<ImportBatch> rollback(@CurrentUser Long userId, @PathVariable Long id) {
        return Result.ok(importService.rollback(userId, id));
    }
}
