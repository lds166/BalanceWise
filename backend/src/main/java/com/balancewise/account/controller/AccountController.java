package com.balancewise.account.controller;

import com.balancewise.account.dto.AccountRequest;
import com.balancewise.account.dto.ArchiveRequest;
import com.balancewise.account.entity.Account;
import com.balancewise.account.service.AccountService;
import com.balancewise.account.vo.AccountSummaryVo;
import com.balancewise.auth.CurrentUser;
import com.balancewise.common.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public Result<AccountSummaryVo> list(@CurrentUser Long userId) {
        return Result.ok(accountService.summary(userId));
    }

    @PostMapping
    public Result<Account> create(@CurrentUser Long userId, @Valid @RequestBody AccountRequest req) {
        return Result.ok(accountService.create(userId, req));
    }

    @PutMapping("/{id}")
    public Result<Account> update(@CurrentUser Long userId, @PathVariable Long id,
                                  @Valid @RequestBody AccountRequest req) {
        return Result.ok(accountService.update(userId, id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@CurrentUser Long userId, @PathVariable Long id) {
        accountService.delete(userId, id);
        return Result.ok();
    }

    @PutMapping("/{id}/archive")
    public Result<Void> archive(@CurrentUser Long userId, @PathVariable Long id,
                                @Valid @RequestBody ArchiveRequest req) {
        accountService.archive(userId, id, req.getIsArchived());
        return Result.ok();
    }
}
