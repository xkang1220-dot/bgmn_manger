package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.kk.biz.entity.FinLedger;
import com.kk.biz.entity.FinProjectAccount;
import com.kk.biz.service.FinProjectAccountService;
import com.kk.common.result.PageResult;
import com.kk.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/finance/project-account")
@RequiredArgsConstructor
public class ProjectAccountController {

    private final FinProjectAccountService projectAccountService;

    @GetMapping("/list")
    @SaCheckPermission("finance:project:list")
    public Result<List<FinProjectAccount>> list() {
        return Result.ok(projectAccountService.listAccounts());
    }

    @GetMapping("/{projectId}")
    @SaCheckPermission("finance:project:list")
    public Result<FinProjectAccount> detail(@PathVariable Long projectId) {
        return Result.ok(projectAccountService.getByProjectId(projectId));
    }

    @GetMapping("/{projectId}/ledger")
    @SaCheckPermission("finance:project:list")
    public Result<PageResult<FinLedger>> ledger(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            String bizType) {
        return Result.ok(PageResult.of(projectAccountService.pageProjectLedgers(page, pageSize, projectId, bizType)));
    }
}
