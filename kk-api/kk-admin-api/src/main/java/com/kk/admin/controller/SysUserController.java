package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.kk.common.result.PageResult;
import com.kk.common.result.Result;
import com.kk.system.entity.SysUser;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sys/user")
@RequiredArgsConstructor
public class SysUserController {

    private final SysUserService userService;

    @GetMapping("/page")
    @SaCheckPermission("system:user:list")
    public Result<PageResult<SysUser>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            String username, String nickname, Long deptId, Integer status) {
        return Result.ok(PageResult.of(userService.pageUsers(page, pageSize, username, nickname, deptId, status)));
    }

    @GetMapping("/list")
    public Result<List<SysUser>> list() {
        return Result.ok(userService.listSimple());
    }

    @GetMapping("/{id}")
    @SaCheckPermission("system:user:list")
    public Result<SysUser> get(@PathVariable Long id) {
        SysUser user = userService.getById(id);
        if (user != null) {
            user.setPassword(null);
            user.setRoleIds(userService.getRoleIds(id));
        }
        return Result.ok(user);
    }

    @PostMapping
    @SaCheckPermission("system:user:add")
    public Result<Void> create(@RequestBody SysUser user) {
        userService.createUser(user);
        return Result.ok();
    }

    @PutMapping
    @SaCheckPermission("system:user:edit")
    public Result<Void> update(@RequestBody SysUser user) {
        userService.updateUser(user);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("system:user:remove")
    public Result<Void> delete(@PathVariable Long id) {
        userService.deleteUser(id);
        return Result.ok();
    }

    @PutMapping("/reset-pwd")
    @SaCheckPermission("system:user:resetPwd")
    public Result<Void> resetPwd(@RequestBody Map<String, Object> body) {
        Long id = Long.valueOf(body.get("id").toString());
        String password = body.get("password") == null ? "123456" : body.get("password").toString();
        userService.resetPassword(id, password);
        return Result.ok();
    }

    @PutMapping("/status")
    @SaCheckPermission("system:user:edit")
    public Result<Void> status(@RequestBody Map<String, Object> body) {
        Long id = Long.valueOf(body.get("id").toString());
        Integer status = Integer.valueOf(body.get("status").toString());
        userService.changeStatus(id, status);
        return Result.ok();
    }
}
