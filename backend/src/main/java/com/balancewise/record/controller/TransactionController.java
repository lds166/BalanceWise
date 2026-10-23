package com.balancewise.record.controller;

import com.balancewise.auth.CurrentUser;
import com.balancewise.common.PageResult;
import com.balancewise.common.Result;
import com.balancewise.record.dto.TransactionQuery;
import com.balancewise.record.dto.TransactionRequest;
import com.balancewise.record.entity.TransactionRecord;
import com.balancewise.record.service.TransactionService;
import com.balancewise.record.vo.TransactionVo;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @GetMapping
    public Result<PageResult<TransactionVo>> list(@CurrentUser Long userId, TransactionQuery q) {
        return Result.ok(transactionService.page(userId, q));
    }

    @PostMapping
    public Result<TransactionRecord> create(@CurrentUser Long userId,
                                            @Valid @RequestBody TransactionRequest req) {
        return Result.ok(transactionService.create(userId, req));
    }

    @PutMapping("/{id}")
    public Result<TransactionRecord> update(@CurrentUser Long userId, @PathVariable Long id,
                                            @Valid @RequestBody TransactionRequest req) {
        return Result.ok(transactionService.update(userId, id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@CurrentUser Long userId, @PathVariable Long id) {
        transactionService.delete(userId, id);
        return Result.ok();
    }

    @GetMapping("/export")
    public void export(@CurrentUser Long userId, TransactionQuery q,
                       HttpServletResponse response) throws IOException {
        String csv = transactionService.exportCsv(userId, q);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=transactions.csv");
        response.getWriter().write(csv);
    }
}
