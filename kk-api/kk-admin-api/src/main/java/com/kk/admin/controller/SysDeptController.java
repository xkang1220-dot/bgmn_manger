package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.kk.common.result.Result;
import com.kk.system.entity.SysDept;
import com.kk.system.service.SysDeptService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sys/dept")
@RequiredArgsConstructor
public class SysDeptController {

    private final SysDeptService deptService;

    @GetMapping("/tree")
    public Result<List<SysDept>> tree() {
        return Result.ok(deptService.tree());
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
