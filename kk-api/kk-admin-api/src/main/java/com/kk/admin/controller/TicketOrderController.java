package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.kk.biz.entity.SysFile;
import com.kk.biz.service.SysFileService;
import com.kk.biz.ticket.dto.TicketAssignRequest;
import com.kk.biz.ticket.dto.TicketBatchUpdateRequest;
import com.kk.biz.ticket.dto.TicketCreateRequest;
import com.kk.biz.ticket.dto.TicketProgressRequest;
import com.kk.biz.ticket.dto.TicketUpdateRequest;
import com.kk.biz.ticket.service.TicketWorkOrderService;
import com.kk.biz.ticket.vo.TicketVO;
import com.kk.common.result.PageResult;
import com.kk.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ticket/order")
@RequiredArgsConstructor
public class TicketOrderController {

    private final TicketWorkOrderService workOrderService;
    private final SysFileService fileService;

    @PostMapping("/upload/image")
    @SaCheckPermission(value = {"ticket:submit", "ticket:manage:update"}, mode = SaMode.OR)
    public Result<SysFile> uploadImage(@RequestParam("file") MultipartFile file) {
        return Result.ok(fileService.upload(file, "ticket", null));
    }

    @PostMapping
    @SaCheckPermission("ticket:submit")
    public Result<TicketVO> create(@RequestBody TicketCreateRequest request) {
        return Result.ok(workOrderService.create(request));
    }

    @GetMapping("/page")
    @SaCheckPermission("ticket:manage:list")
    public Result<PageResult<TicketVO>> page(@RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "20") long pageSize,
                                             @RequestParam(required = false) Long companyId,
                                             @RequestParam(required = false) String title,
                                             @RequestParam(required = false) String type,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String urgency,
                                             @RequestParam(required = false) Long developerId,
                                             @RequestParam(required = false) Long cycleId,
                                             @RequestParam(required = false) Long submitterId,
                                             @RequestParam(required = false) Long projectId) {
        return Result.ok(PageResult.of(workOrderService.page(
                page, pageSize, companyId, title, type, status, urgency, developerId, cycleId, submitterId, projectId)));
    }

    @GetMapping("/my")
    @SaCheckPermission("ticket:submit")
    public Result<Map<String, Object>> my(@RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "50") long pageSize,
                                          @RequestParam(required = false) Long companyId,
                                          @RequestParam(required = false) Long projectId) {
        return Result.ok(workOrderService.mySubmissions(page, pageSize, companyId, projectId));
    }

    @GetMapping("/{id:\\d+}")
    @SaCheckLogin
    public Result<TicketVO> detail(@PathVariable Long id) {
        return Result.ok(workOrderService.detail(id));
    }

    @PutMapping("/{id:\\d+}")
    @SaCheckPermission("ticket:manage:update")
    public Result<TicketVO> update(@PathVariable Long id, @RequestBody TicketUpdateRequest request) {
        return Result.ok(workOrderService.update(id, request));
    }

    @PutMapping("/batch/update")
    @SaCheckPermission("ticket:manage:update")
    public Result<Void> batchUpdate(@RequestBody TicketBatchUpdateRequest request) {
        workOrderService.batchUpdate(request);
        return Result.ok();
    }

    @DeleteMapping("/{id:\\d+}")
    @SaCheckPermission("ticket:manage:delete")
    public Result<Void> delete(@PathVariable Long id) {
        workOrderService.delete(id);
        return Result.ok();
    }

    @DeleteMapping("/batch")
    @SaCheckPermission("ticket:manage:delete")
    public Result<Void> batchDelete(@RequestBody List<Long> ids) {
        workOrderService.batchDelete(ids);
        return Result.ok();
    }

    @PutMapping("/{id:\\d+}/progress")
    @SaCheckPermission(value = {"ticket:manage:progress", "ticket:manage:list", "ticket:submit"}, mode = SaMode.OR)
    public Result<TicketVO> progress(@PathVariable Long id, @RequestBody TicketProgressRequest request) {
        return Result.ok(workOrderService.updateProgress(id, request));
    }

    @PutMapping("/{id:\\d+}/assign")
    @SaCheckPermission("ticket:manage:assign")
    public Result<TicketVO> assign(@PathVariable Long id, @RequestBody TicketAssignRequest request) {
        return Result.ok(workOrderService.assign(id, request));
    }
}
