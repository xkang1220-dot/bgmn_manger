package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.kk.biz.dto.LedgerCreateRequest;
import com.kk.biz.dto.LedgerQuery;
import com.kk.biz.dto.ProjectManualSettleRequest;
import com.kk.biz.dto.ProjectSettleRequest;
import com.kk.biz.dto.ProjectShareSaveRequest;
import com.kk.biz.entity.FinLedger;
import com.kk.biz.entity.FinPool;
import com.kk.biz.entity.HrWallet;
import com.kk.biz.entity.PmProject;
import com.kk.biz.service.FinanceService;
import com.kk.biz.service.HrWalletService;
import com.kk.biz.service.PmProjectService;
import com.kk.biz.service.SysFileService;
import com.kk.biz.entity.SysFile;
import com.kk.common.result.PageResult;
import com.kk.common.result.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/finance")
@RequiredArgsConstructor
public class FinanceController {

    private final FinanceService financeService;
    private final HrWalletService walletService;
    private final SysFileService fileService;
    private final PmProjectService projectService;

    @GetMapping("/summary")
    @SaCheckPermission(value = {"finance:pool:list", "finance:ledger:list"}, mode = SaMode.OR)
    public Result<Map<String, Object>> summary() {
        return Result.ok(financeService.summary());
    }

    @GetMapping("/pool/list")
    @SaCheckPermission(value = {"finance:pool:list", "finance:ledger:list", "finance:ledger:add"}, mode = SaMode.OR)
    public Result<List<FinPool>> poolList() {
        return Result.ok(financeService.list());
    }

    @PostMapping("/pool")
    @SaCheckPermission("finance:pool:edit")
    public Result<Void> createPool(@RequestBody FinPool pool) {
        financeService.createPool(pool);
        return Result.ok();
    }

    @PutMapping("/pool")
    @SaCheckPermission("finance:pool:edit")
    public Result<Void> updatePool(@RequestBody FinPool pool) {
        financeService.updatePool(pool);
        return Result.ok();
    }

    @GetMapping("/wallet/page")
    @SaCheckPermission(value = {"finance:wallet:list", "finance:wallet:board"}, mode = SaMode.OR)
    public Result<PageResult<HrWallet>> walletPage(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            Long userId) {
        return Result.ok(PageResult.of(walletService.pageWallets(page, pageSize, userId)));
    }

    @GetMapping("/wallet/mine")
    public Result<HrWallet> myWallet() {
        Long userId = cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong();
        return Result.ok(walletService.getOrCreate(userId));
    }

    @GetMapping("/wallet/mine/ledger")
    public Result<PageResult<FinLedger>> myWalletLedger(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            String bizType, BigDecimal minAmount, BigDecimal maxAmount,
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime,
            String keyword) {
        Long userId = cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong();
        LedgerQuery q = new LedgerQuery();
        q.setPage(page);
        q.setPageSize(pageSize);
        q.setAccountType("WALLET");
        q.setUserId(userId);
        q.setBizType(bizType);
        q.setMinAmount(minAmount);
        q.setMaxAmount(maxAmount);
        q.setStartTime(startTime);
        q.setEndTime(endTime);
        q.setKeyword(keyword);
        return Result.ok(PageResult.of(financeService.pageLedger(q)));
    }

    /** 全员看板：查看某人钱包资金来源明细 */
    @GetMapping("/wallet/{userId}/ledger")
    @SaCheckPermission(value = {"finance:wallet:board", "finance:wallet:list", "finance:ledger:list"}, mode = SaMode.OR)
    public Result<PageResult<FinLedger>> walletUserLedger(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok(PageResult.of(financeService.pageLedger(page, pageSize, null, "WALLET", userId, null, null)));
    }

    @GetMapping("/ledger/page")
    @SaCheckPermission("finance:ledger:list")
    public Result<PageResult<FinLedger>> ledgerPage(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            String bizType, String accountType, Long userId, Long poolId, Long projectId, Long channelId,
            BigDecimal minAmount, BigDecimal maxAmount,
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime,
            String keyword) {
        LedgerQuery q = new LedgerQuery();
        q.setPage(page);
        q.setPageSize(pageSize);
        q.setBizType(bizType);
        q.setAccountType(accountType);
        q.setUserId(userId);
        q.setPoolId(poolId);
        q.setProjectId(projectId);
        q.setChannelId(channelId);
        q.setMinAmount(minAmount);
        q.setMaxAmount(maxAmount);
        q.setStartTime(startTime);
        q.setEndTime(endTime);
        q.setKeyword(keyword);
        return Result.ok(PageResult.of(financeService.pageLedger(q)));
    }

    @PostMapping("/ledger")
    @SaCheckPermission("finance:ledger:add")
    public Result<Void> createLedger(@Valid @RequestBody LedgerCreateRequest request) {
        throw new com.kk.common.exception.BusinessException("公司总账登记须提交审批，请走审批中心");
    }

    @PostMapping("/ledger/voucher")
    @SaCheckPermission("finance:ledger:add")
    public Result<SysFile> uploadLedgerVoucher(@RequestParam("file") MultipartFile file) {
        return Result.ok(fileService.upload(file, "ledger_voucher", null));
    }

    @GetMapping("/project-share/{projectId}")
    @SaCheckPermission(value = {"finance:project:list", "finance:share:edit"}, mode = SaMode.OR)
    public Result<PmProject> projectShareDetail(@PathVariable Long projectId) {
        return Result.ok(projectService.getShareDetail(projectId));
    }

    @PutMapping("/project-share")
    @SaCheckPermission("finance:share:edit")
    public Result<Void> saveProjectShare(@Valid @RequestBody ProjectShareSaveRequest request) {
        throw new com.kk.common.exception.BusinessException("分成配置须提交审批（全体股东会签），请走审批中心");
    }

    @PostMapping("/settle")
    @SaCheckPermission(value = {"finance:ledger:add", "finance:share:edit"}, mode = SaMode.OR)
    public Result<Void> settle(@Valid @RequestBody ProjectSettleRequest request) {
        throw new com.kk.common.exception.BusinessException("项目分钱须提交审批，请走审批中心");
    }

    @PostMapping("/settle/manual")
    @SaCheckPermission("finance:ledger:add")
    public Result<Void> settleManual(@Valid @RequestBody ProjectManualSettleRequest request) {
        throw new com.kk.common.exception.BusinessException("项目分钱须提交审批，请走审批中心");
    }
}
