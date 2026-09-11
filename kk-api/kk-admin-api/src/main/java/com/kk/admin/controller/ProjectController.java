package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.kk.biz.dto.ApprovalSubmitRequest;
import com.kk.biz.entity.PmProject;
import com.kk.biz.entity.PmProjectFlow;
import com.kk.biz.entity.WfApproval;
import com.kk.biz.service.PmProjectService;
import com.kk.biz.service.WfApprovalService;
import com.kk.biz.workflow.ApprovalTypes;
import com.kk.biz.workflow.ProjectScales;
import com.kk.common.exception.BusinessException;
import com.kk.common.result.PageResult;
import com.kk.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
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
        // 财务相关权限选项目时看全部已生效；其余走可见范围，避免无关项目泄漏
        boolean financePick = StpUtil.hasPermission("finance:project:list")
                || StpUtil.hasPermission("finance:ledger:list")
                || StpUtil.hasPermission("finance:ledger:add")
                || StpUtil.hasPermission("finance:share:edit");
        return Result.ok(financePick ? projectService.listApproved() : projectService.listVisible());
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

    @GetMapping("/{id:\\d+}/flows")
    @SaCheckPermission("project:list")
    public Result<List<PmProjectFlow>> flows(@PathVariable Long id) {
        return Result.ok(projectService.listFlows(id));
    }

    /** 预览下一项目编号（按公司拼音前缀 + 序号，不占号） */
    @GetMapping("/next-code")
    @SaCheckPermission("project:add")
    public Result<String> nextCode(@RequestParam Long companyId) {
        return Result.ok(projectService.allocateNextCode(companyId));
    }

    /**
     * 新建项目：常规档直存；重点/重大走审批（审批人/会签或签/超时以该公司审批配置为准）
     */
    @PostMapping
    @SaCheckPermission("project:add")
    public Result<?> create(@RequestBody PmProject project) {
        String scale = ProjectScales.normalize(project.getScale());
        project.setScale(scale);
        if (!ProjectScales.needsApproval(scale)) {
            project.setApproveStatus(1);
            projectService.createProject(project);
            return Result.ok(projectService.getDetail(project.getId()));
        }
        ApprovalSubmitRequest req = new ApprovalSubmitRequest();
        req.setType(ApprovalTypes.PROJECT_CREATE);
        req.setTitle("创建项目 · " + project.getName());
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", project.getName());
        payload.put("ownerId", project.getOwnerId());
        payload.put("companyId", project.getCompanyId());
        payload.put("status", project.getStatus() == null ? 1 : project.getStatus());
        payload.put("scale", scale);
        payload.put("description", project.getDescription());
        payload.put("reserveAmount", project.getReserveAmount());
        payload.put("budget", project.getBudget());
        payload.put("poolId", project.getPoolId());
        payload.put("startDate", project.getStartDate() == null ? null : project.getStartDate().toString());
        payload.put("endDate", project.getEndDate() == null ? null : project.getEndDate().toString());
        payload.put("actualEndDate", project.getActualEndDate() == null ? null : project.getActualEndDate().toString());
        if (project.getMembers() != null) {
            payload.put("members", project.getMembers());
        }
        req.setCompanyId(project.getCompanyId());
        req.setPayload(payload);
        return Result.ok(approvalService.submit(req));
    }

    /**
     * 编辑项目：规模不变或降到常规直存；改到重点/重大（含互切）提交规模变更审批
     */
    @PutMapping
    @SaCheckPermission("project:edit")
    @Transactional(rollbackFor = Exception.class)
    public Result<?> update(@RequestBody PmProject project) {
        String[] pendingScale = projectService.updateProjectMaybeScaleApproval(project);
        if (pendingScale == null) {
            return Result.ok();
        }
        PmProject existing = projectService.getById(project.getId());
        if (existing == null) {
            throw new BusinessException("项目不存在");
        }
        ApprovalSubmitRequest req = new ApprovalSubmitRequest();
        req.setType(ApprovalTypes.PROJECT_SCALE_CHANGE);
        req.setTitle("变更项目规模 · " + existing.getName());
        req.setProjectId(existing.getId());
        req.setCompanyId(existing.getCompanyId());
        Map<String, Object> payload = new HashMap<>();
        payload.put("projectId", existing.getId());
        payload.put("name", existing.getName());
        payload.put("fromScale", pendingScale[0]);
        payload.put("toScale", pendingScale[1]);
        req.setPayload(payload);
        return Result.ok(approvalService.submit(req));
    }

    /** 删除项目：提交审批（审批人/会签或签以该公司审批配置为准） */
    @DeleteMapping("/{id:\\d+}")
    @SaCheckPermission("project:remove")
    public Result<WfApproval> delete(@PathVariable Long id) {
        PmProject project = projectService.getDetail(id);
        ApprovalSubmitRequest req = new ApprovalSubmitRequest();
        req.setType(ApprovalTypes.PROJECT_DELETE);
        req.setTitle("删除项目 · " + project.getName());
        req.setProjectId(id);
        req.setCompanyId(project.getCompanyId());
        Map<String, Object> payload = new HashMap<>();
        payload.put("projectId", id);
        payload.put("name", project.getName());
        payload.put("companyId", project.getCompanyId());
        req.setPayload(payload);
        return Result.ok(approvalService.submit(req));
    }
}
