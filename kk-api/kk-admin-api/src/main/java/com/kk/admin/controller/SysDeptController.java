package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.kk.common.result.Result;
import com.kk.system.entity.SysDept;
import com.kk.system.service.DataScopeService;
import com.kk.system.service.SysDeptService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/sys/dept")
@RequiredArgsConstructor
public class SysDeptController {

    private final SysDeptService deptService;
    private final DataScopeService dataScopeService;

    @GetMapping("/tree")
    public Result<List<SysDept>> tree() {
        return Result.ok(deptService.tree());
    }

    @GetMapping("/companies")
    public Result<List<SysDept>> companies() {
        return Result.ok(deptService.listCompanies());
    }

    /** 当前登录人可见公司（业务创建用）；全局 admin 返回全部 */
    @GetMapping("/my-companies")
    public Result<List<SysDept>> myCompanies() {
        long loginId = StpUtil.getLoginIdAsLong();
        List<SysDept> all = deptService.listCompanies();
        if (dataScopeService.isGlobalAdmin(loginId)) {
            return Result.ok(all);
        }
        Set<Long> visible = dataScopeService.visibleCompanyIds(loginId);
        return Result.ok(all.stream().filter(d -> visible.contains(d.getId())).toList());
    }

    @PostMapping
    @SaCheckPermission("system:dept:add")
    public Result<Void> create(@RequestBody SysDept dept) {
        deptService.createDept(dept);
        return Result.ok();
    }

    @PutMapping
    @SaCheckPermission("system:dept:edit")
    public Result<Void> update(@RequestBody SysDept dept) {
        deptService.updateDept(dept);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("system:dept:remove")
    public Result<Void> delete(@PathVariable Long id) {
        deptService.deleteDept(id);
        return Result.ok();
    }
}
