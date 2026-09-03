package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.kk.common.result.Result;
import com.kk.system.entity.SysRole;
import com.kk.system.service.SysRoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sys/role")
@RequiredArgsConstructor
public class SysRoleController {

    private final SysRoleService roleService;

    @GetMapping("/list")
    @SaCheckPermission("system:role:list")
    public Result<List<SysRole>> list() {
        return Result.ok(roleService.list());
    }

    @GetMapping("/{id}")
    @SaCheckPermission("system:role:list")
    public Result<SysRole> get(@PathVariable Long id) {
        return Result.ok(roleService.getDetail(id));
    }

    @PostMapping
    @SaCheckPermission("system:role:add")
    public Result<Void> create(@RequestBody SysRole role) {
        roleService.createRole(role);
        return Result.ok();
    }

    @PutMapping
    @SaCheckPermission("system:role:edit")
    public Result<Void> update(@RequestBody SysRole role) {
        roleService.updateRole(role);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("system:role:remove")
    public Result<Void> delete(@PathVariable Long id) {
        roleService.deleteRole(id);
        return Result.ok();
    }
}
