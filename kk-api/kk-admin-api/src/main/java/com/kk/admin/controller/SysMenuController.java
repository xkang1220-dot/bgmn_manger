package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.kk.common.result.Result;
import com.kk.system.entity.SysMenu;
import com.kk.system.service.SysMenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sys/menu")
@RequiredArgsConstructor
public class SysMenuController {

    private final SysMenuService menuService;

    @GetMapping("/tree")
    @SaCheckPermission("system:menu:list")
    public Result<List<SysMenu>> tree() {
        return Result.ok(menuService.tree());
    }

    @PostMapping
    @SaCheckPermission("system:menu:add")
    public Result<Void> create(@RequestBody SysMenu menu) {
        menuService.createMenu(menu);
        return Result.ok();
    }

    @PutMapping
    @SaCheckPermission("system:menu:edit")
    public Result<Void> update(@RequestBody SysMenu menu) {
        menuService.updateMenu(menu);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("system:menu:remove")
    public Result<Void> delete(@PathVariable Long id) {
        menuService.deleteMenu(id);
        return Result.ok();
    }
}
