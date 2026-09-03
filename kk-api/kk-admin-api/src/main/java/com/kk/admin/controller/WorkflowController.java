package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.kk.biz.dto.ApprovalActionRequest;
import com.kk.biz.dto.ApprovalSubmitRequest;
import com.kk.biz.dto.RollbackRequest;
import com.kk.biz.entity.SysFile;
import com.kk.biz.entity.WfApproval;
import com.kk.biz.entity.WfApprovalFlow;
import com.kk.biz.service.SysFileService;
import com.kk.biz.service.WfApprovalFlowService;
import com.kk.biz.service.WfApprovalService;
import com.kk.common.result.PageResult;
import com.kk.common.result.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workflow")
@RequiredArgsConstructor
public class WorkflowController {

    private final WfApprovalService approvalService;
    private final WfApprovalFlowService approvalFlowService;
    private final SysFileService fileService;

    @GetMapping("/flow/list")
    @SaCheckPermission(value = {"workflow:flow:edit", "workflow:list"}, mode = SaMode.OR)
    public Result<List<WfApprovalFlow>> flowList() {
        return Result.ok(approvalFlowService.listAll());
    }

    @PutMapping("/flow")
    @SaCheckPermission("workflow:flow:edit")
    public Result<Void> saveFlow(@RequestBody WfApprovalFlow flow) {
        approvalFlowService.saveFlow(flow);
        return Result.ok();
    }

    @DeleteMapping("/flow/{id}")
    @SaCheckPermission("workflow:flow:edit")
    public Result<Void> deleteFlow(@PathVariable Long id) {
        approvalFlowService.removeById(id);
        return Result.ok();
    }

    @PostMapping("/approval")
    @SaCheckPermission("workflow:list")
    public Result<WfApproval> submit(@Valid @RequestBody ApprovalSubmitRequest request) {
        return Result.ok(approvalService.submit(request));
    }

    /** 审批附件（发票等），登录用户可上传，提交审批时再绑定 */
    @PostMapping("/voucher")
    @SaCheckPermission("workflow:list")
    public Result<SysFile> uploadVoucher(@RequestParam("file") MultipartFile file) {
        return Result.ok(fileService.upload(file, "approval_voucher", null));
    }

    @GetMapping("/approval/page")
    @SaCheckPermission("workflow:list")
    public Result<PageResult<WfApproval>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            String type, String status,
            @RequestParam(defaultValue = "all") String scope,
            Long projectId, Long poolId,
            java.math.BigDecimal minAmount, java.math.BigDecimal maxAmount,
            @org.springframework.format.annotation.DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") java.time.LocalDateTime startTime,
            @org.springframework.format.annotation.DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") java.time.LocalDateTime endTime,
            String keyword) {
        com.kk.biz.dto.ApprovalQuery q = new com.kk.biz.dto.ApprovalQuery();
        q.setPage(page);
        q.setPageSize(pageSize);
        q.setType(type);
        q.setStatus(status);
        q.setScope(scope);
        q.setProjectId(projectId);
        q.setPoolId(poolId);
        q.setMinAmount(minAmount);
        q.setMaxAmount(maxAmount);
        q.setStartTime(startTime);
        q.setEndTime(endTime);
        q.setKeyword(keyword);
        return Result.ok(PageResult.of(approvalService.page(q)));
    }

    @GetMapping("/approval/{id}")
    @SaCheckPermission("workflow:list")
    public Result<WfApproval> detail(@PathVariable Long id) {
        return Result.ok(approvalService.detail(id));
    }

    @PostMapping("/approval/{id}/approve")
    @SaCheckPermission(value = {"workflow:handle", "workflow:list"}, mode = SaMode.OR)
    public Result<Void> approve(@PathVariable Long id, @RequestBody(required = false) ApprovalActionRequest request) {
        approvalService.approve(id, request == null ? null : request.getComment());
        return Result.ok();
    }

    @PostMapping("/approval/{id}/reject")
    @SaCheckPermission(value = {"workflow:handle", "workflow:list"}, mode = SaMode.OR)
    public Result<Void> reject(@PathVariable Long id, @RequestBody(required = false) ApprovalActionRequest request) {
        approvalService.reject(id, request == null ? null : request.getComment());
        return Result.ok();
    }

    @PostMapping("/approval/{id}/withdraw")
    @SaCheckPermission("workflow:list")
    public Result<Void> withdraw(@PathVariable Long id) {
        approvalService.withdraw(id);
        return Result.ok();
    }

    @PostMapping("/approval/{id}/receipt")
    @SaCheckPermission(value = {"workflow:handle", "finance:ledger:add"}, mode = SaMode.OR)
    public Result<Void> receipt(@PathVariable Long id, @RequestBody Map<String, List<Long>> body) {
        approvalService.uploadReceipt(id, body.get("fileIds"));
        return Result.ok();
    }

    @PostMapping("/approval/{id}/confirm")
    @SaCheckPermission("workflow:list")
    public Result<Void> confirm(@PathVariable Long id) {
        approvalService.confirmReceived(id);
        return Result.ok();
    }

    @PostMapping("/approval/rollback")
    @SaCheckPermission("workflow:list")
    public Result<WfApproval> rollback(@Valid @RequestBody RollbackRequest request) {
        return Result.ok(approvalService.submitRollback(request));
    }

    @PostMapping("/approval/auto-pass")
    @SaCheckPermission("workflow:handle")
    public Result<Map<String, Integer>> autoPass() {
        return Result.ok(Map.of("count", approvalService.autoPassTimeout()));
    }
}
