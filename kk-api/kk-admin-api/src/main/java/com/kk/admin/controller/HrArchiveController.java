package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.kk.biz.entity.HrArchive;
import com.kk.biz.entity.HrPayMethod;
import com.kk.biz.service.HrArchiveService;
import com.kk.common.result.PageResult;
import com.kk.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/hr/archive")
@RequiredArgsConstructor
public class HrArchiveController {

    private final HrArchiveService archiveService;

    @GetMapping("/page")
    @SaCheckPermission("hr:archive:list")
    public Result<PageResult<HrArchive>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            String realName, String employeeNo) {
        return Result.ok(PageResult.of(archiveService.pageArchives(page, pageSize, realName, employeeNo)));
    }

    /** 当前登录人可用的个人收款方式（申请工资/报销用） */
    @GetMapping("/my-pay-methods")
    public Result<List<HrPayMethod>> myPayMethods() {
        return Result.ok(archiveService.listMyPayMethods(StpUtil.getLoginIdAsLong()));
    }

    @GetMapping("/{id}")
    @SaCheckPermission("hr:archive:list")
    public Result<HrArchive> get(@PathVariable Long id) {
        return Result.ok(archiveService.getDetail(id));
    }

    @PostMapping
    @SaCheckPermission("hr:archive:add")
    public Result<Void> create(@RequestBody HrArchive archive) {
        archiveService.createArchive(archive);
        return Result.ok();
    }

    @PutMapping
    @SaCheckPermission("hr:archive:edit")
    public Result<Void> update(@RequestBody HrArchive archive) {
        archiveService.updateArchive(archive);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("hr:archive:remove")
    public Result<Void> delete(@PathVariable Long id) {
        archiveService.deleteArchive(id);
        return Result.ok();
    }
}
