package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.kk.biz.dto.ApprovalSubmitRequest;
import com.kk.biz.entity.PmProject;
import com.kk.biz.entity.WfApproval;
import com.kk.biz.service.PmProjectService;
import com.kk.biz.service.WfApprovalService;
import com.kk.biz.workflow.ApprovalTypes;
import com.kk.common.result.PageResult;
import com.kk.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/project")
@RequiredArgsConstructor
public class ProjectController {

    private final PmProjectService projectService;
    private final WfApprovalService approvalService;

    @GetMapping("/page")
    @SaCheckPermission("project:list")
    public Result<PageResult<PmProject>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            String name, Integer status) {
        return Result.ok(PageResult.of(projectService.pageProjects(page, pageSize, name, status)));
    }

    @GetMapping("/list")
    public Result<List<PmProject>> list() {
        return Result.ok(projectService.list());
    }

    /** 个人中心：只返回当前用户负责或参与的项目 */
    @GetMapping("/mine")
    public Result<List<PmProject>> mine() {
        return Result.ok(projectService.listMine(StpUtil.getLoginIdAsLong()));
    }

    @GetMapping("/{id:\\d+}")
    @SaCheckPermission("project:list")
    public Result<PmProject> get(@PathVariable Long id) {
        return Result.ok(projectService.getDetail(id));
    }

    /** 新建项目：提交全体股东会签（3天超时自动通过） */
    @PostMapping
    @SaCheckPermission("project:add")
    public Result<WfApproval> create(@RequestBody PmProject project) {
        ApprovalSubmitRequest req = new ApprovalSubmitRequest();
        req.setType(ApprovalTypes.PROJECT_CREATE);
        req.setTitle("创建项目 · " + project.getName());
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", project.getName());
        payload.put("code", project.getCode());
        payload.put("ownerId", project.getOwnerId());
        payload.put("status", project.getStatus() == null ? 1 : project.getStatus());
        payload.put("description", project.getDescription());
        payload.put("reserveAmount", project.getReserveAmount());
        payload.put("budget", project.getBudget());
        payload.put("poolId", project.getPoolId());
        payload.put("startDate", project.getStartDate());
        payload.put("endDate", project.getEndDate());
        if (project.getMembers() != null) {
            payload.put("members", project.getMembers());
        }
        req.setPayload(payload);
        return Result.ok(approvalService.submit(req));
    }

    @PutMapping
    @SaCheckPermission("project:edit")
    public Result<Void> update(@RequestBody PmProject project) {
        projectService.updateProject(project);
        return Result.ok();
    }

    /** 删除项目：提交全体股东会签 */
    @DeleteMapping("/{id:\\d+}")
    @SaCheckPermission("project:remove")
    public Result<WfApproval> delete(@PathVariable Long id) {
        PmProject project = projectService.getDetail(id);
        ApprovalSubmitRequest req = new ApprovalSubmitRequest();
        req.setType(ApprovalTypes.PROJECT_DELETE);
        req.setTitle("删除项目 · " + project.getName());
        req.setProjectId(id);
        Map<String, Object> payload = new HashMap<>();
        payload.put("projectId", id);
        payload.put("name", project.getName());
        req.setPayload(payload);
        return Result.ok(approvalService.submit(req));
    }
}
