package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.kk.biz.entity.FaDeprCategory;
import com.kk.biz.service.FaDeprCategoryService;
import com.kk.common.result.PageResult;
import com.kk.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/fa/category")
@RequiredArgsConstructor
public class FaDeprCategoryController {

    private final FaDeprCategoryService categoryService;

    @GetMapping("/page")
    @SaCheckPermission("fa:category:list")
    public Result<PageResult<FaDeprCategory>> page(@RequestParam(defaultValue = "1") long page,
                                                   @RequestParam(defaultValue = "12") long pageSize,
                                                   @RequestParam(required = false) Long companyId,
                                                   @RequestParam(required = false) String name) {
        return Result.ok(PageResult.of(categoryService.pageCategories(page, pageSize, companyId, name)));
    }

    @GetMapping("/list")
    @SaCheckPermission(value = {"fa:category:list", "fa:asset:list", "fa:asset:add"}, mode = SaMode.OR)
    public Result<List<FaDeprCategory>> list(@RequestParam(required = false) Long companyId) {
        return Result.ok(categoryService.listEnabled(companyId));
    }

    @PostMapping
    @SaCheckPermission("fa:category:add")
    public Result<Void> create(@RequestBody FaDeprCategory category) {
        categoryService.createCategory(category);
        return Result.ok();
    }

    @PutMapping
    @SaCheckPermission("fa:category:edit")
    public Result<Void> update(@RequestBody FaDeprCategory category) {
        categoryService.updateCategory(category);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("fa:category:remove")
    public Result<Void> delete(@PathVariable Long id) {
        categoryService.deleteCategory(id);
        return Result.ok();
    }
}
