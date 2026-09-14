package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.kk.biz.dto.LedgerCreateRequest;
import com.kk.biz.dto.LedgerQuery;
import com.kk.biz.dto.LedgerRegisterResult;
import com.kk.biz.dto.LedgerThresholdSaveRequest;
import com.kk.biz.dto.ProjectManualSettleRequest;
import com.kk.biz.dto.ProjectSettleRequest;
import com.kk.biz.dto.ProjectShareSaveRequest;
import com.kk.biz.dto.WithdrawTaxCalcResult;
import com.kk.biz.dto.WithdrawTaxTierSaveRequest;
import com.kk.biz.entity.FinLedger;
import com.kk.biz.entity.FinLedgerThreshold;
import com.kk.biz.entity.FinPool;
import com.kk.biz.entity.FinWalletWithdrawTaxTier;
import com.kk.biz.entity.HrWallet;
import com.kk.biz.entity.PmProject;
import com.kk.biz.service.FinanceService;
import com.kk.biz.service.HrWalletService;
import com.kk.biz.service.PmProjectService;
import com.kk.biz.service.SysFileService;
import com.kk.biz.service.WalletWithdrawTaxService;
import com.kk.biz.entity.SysFile;
import com.kk.common.result.PageResult;
import com.kk.common.result.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
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
    private final WalletWithdrawTaxService walletWithdrawTaxService;

    @Value("${wallet.withdraw.tax-rate:0.2}")
    private BigDecimal walletWithdrawTaxRate;

    @GetMapping("/summary")
    @SaCheckPermission(value = {"finance:pool:list", "finance:ledger:list"}, mode = SaMode.OR)
    public Result<Map<String, Object>> summary() {
        return Result.ok(financeService.summary());
    }

    @GetMapping("/pool/list")
    @SaCheckPermission(value = {"finance:pool:list", "finance:ledger:list", "finance:ledger:add"}, mode = SaMode.OR)
    public Result<List<FinPool>> poolList() {
        return Result.ok(financeService.listVisiblePools());
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
    @SaCheckPermission("finance:wallet:list")
    public Result<HrWallet> myWallet() {
        Long userId = cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong();
        return Result.ok(walletService.getOrCreate(userId));
    }

    @GetMapping("/wallet/mine/ledger")
    @SaCheckPermission("finance:wallet:list")
    public Result<PageResult<FinLedger>> myWalletLedger(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            String bizType, Long companyId, Long projectId,
            BigDecimal minAmount, BigDecimal maxAmount,
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
        q.setCompanyId(companyId);
        q.setProjectId(projectId);
        q.setMinAmount(minAmount);
        q.setMaxAmount(maxAmount);
        q.setStartTime(startTime);
        q.setEndTime(endTime);
        q.setKeyword(keyword);
        q.setSkipCompanyScope(true);
        return Result.ok(PageResult.of(financeService.pageLedger(q)));
    }

    /** 个人中心钱包看板（仅本人，与全员看板 /finance/wallet-board 分离） */
    @GetMapping("/wallet/mine/board")
    @SaCheckPermission("finance:wallet:list")
    public Result<Map<String, Object>> myWalletBoard(
            @RequestParam(defaultValue = "daily") String period) {
        Long userId = cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong();
        Map<String, Object> board = financeService.myWalletBoard(userId, period);
        board.put("withdrawTaxRate", withdrawTaxRate());
        return Result.ok(board);
    }

    @GetMapping("/wallet/mine/withdraw-config")
    @SaCheckPermission("finance:wallet:list")
    public Result<Map<String, Object>> withdrawConfig(
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) BigDecimal amount) {
        Map<String, Object> body = new HashMap<>();
        BigDecimal flat = withdrawTaxRate();
        body.put("defaultTaxRate", flat);
        List<FinWalletWithdrawTaxTier> tiers = List.of();
        if (companyId != null) {
            tiers = walletWithdrawTaxService.listByCompany(companyId);
        }
        body.put("tiers", tiers);
        if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
            WithdrawTaxCalcResult calc = walletWithdrawTaxService.calculate(companyId, amount);
            body.put("taxMode", calc.getTaxMode());
            body.put("taxRate", calc.getTaxRate() != null ? calc.getTaxRate() : flat);
            body.put("tax", calc.getTax());
            body.put("net", calc.getNet());
            body.put("breakdown", calc.getBreakdown());
        } else {
            body.put("taxMode", tiers.isEmpty() ? "FLAT" : "TIER");
            body.put("taxRate", flat);
        }
        return Result.ok(body);
    }

    @GetMapping("/wallet-withdraw-tax")
    @SaCheckPermission(value = {"finance:ledger:list", "finance:ledger:add", "finance:pool:edit"}, mode = SaMode.OR)
    public Result<Map<String, Object>> getWithdrawTaxTiers(@RequestParam Long companyId) {
        List<FinWalletWithdrawTaxTier> tiers = walletWithdrawTaxService.listByCompany(companyId);
        Map<String, Object> body = new HashMap<>();
        body.put("companyId", companyId);
        body.put("tiers", tiers);
        body.put("defaultTaxRate", withdrawTaxRate());
        body.put("taxMode", tiers.isEmpty() ? "FLAT" : "TIER");
        return Result.ok(body);
    }

    @PutMapping("/wallet-withdraw-tax")
    @SaCheckPermission(value = {"finance:pool:edit", "finance:ledger:add"}, mode = SaMode.OR)
    public Result<Void> saveWithdrawTaxTiers(@RequestBody WithdrawTaxTierSaveRequest request) {
        walletWithdrawTaxService.saveTiers(request);
        return Result.ok();
    }

    private BigDecimal withdrawTaxRate() {
        return walletWithdrawTaxRate == null ? new BigDecimal("0.2") : walletWithdrawTaxRate;
    }

    /** 全员看板：查看某人钱包资金来源明细 */
    @GetMapping("/wallet/{userId}/ledger")
    @SaCheckPermission(value = {"finance:wallet:board", "finance:wallet:list", "finance:ledger:list"}, mode = SaMode.OR)
    public Result<PageResult<FinLedger>> walletUserLedger(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        LedgerQuery q = new LedgerQuery();
        q.setPage(page);
        q.setPageSize(pageSize);
        q.setAccountType("WALLET");
        q.setUserId(userId);
        return Result.ok(PageResult.of(financeService.pageLedger(q)));
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
        throw new com.kk.common.exception.BusinessException("请使用 /finance/ledger/register 登记总账");
    }

    /** 公司总账登记：入账走财务审批；出账按公司阈值分流 */
    @PostMapping("/ledger/register")
    @SaCheckPermission("finance:ledger:add")
    public Result<LedgerRegisterResult> registerLedger(@Valid @RequestBody LedgerCreateRequest request) {
        return Result.ok(financeService.registerCompanyLedger(request));
    }

    @GetMapping("/ledger-threshold")
    @SaCheckPermission(value = {"finance:ledger:list", "finance:ledger:add", "finance:pool:edit"}, mode = SaMode.OR)
    public Result<FinLedgerThreshold> getLedgerThreshold(@RequestParam Long companyId) {
        return Result.ok(financeService.getLedgerThreshold(companyId));
    }

    @PutMapping("/ledger-threshold")
    @SaCheckPermission(value = {"finance:pool:edit", "finance:ledger:add"}, mode = SaMode.OR)
    public Result<Void> saveLedgerThreshold(@RequestBody LedgerThresholdSaveRequest request) {
        financeService.saveLedgerThreshold(request);
        return Result.ok();
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
        throw new com.kk.common.exception.BusinessException("分成配置须提交审批，请走审批中心");
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
