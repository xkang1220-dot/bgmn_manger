package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.kk.biz.entity.HrArchive;
import com.kk.biz.entity.HrPayMethod;
import com.kk.biz.service.HrArchiveService;
import com.kk.common.result.PageResult;
import com.kk.common.result.Result;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
        Page<HrArchive> result = archiveService.pageArchives(page, pageSize, realName, employeeNo);
        maskAttendanceIfNeeded(result.getRecords());
        return Result.ok(PageResult.of(result));
    }

    /** 当前登录人可用的个人收款方式（申请工资/报销用） */
    @GetMapping("/my-pay-methods")
    public Result<List<HrPayMethod>> myPayMethods() {
        return Result.ok(archiveService.listMyPayMethods(StpUtil.getLoginIdAsLong()));
    }

    /** 当前登录人自己的员工档案 */
    @GetMapping("/mine")
    public Result<HrArchive> mine() {
        HrArchive archive = archiveService.getMine(StpUtil.getLoginIdAsLong());
        archive.setAttendanceEnabled(null);
        return Result.ok(archive);
    }

    /** 当前登录人保存自己的员工档案（同步到人事档案） */
    @PutMapping("/mine")
    public Result<Void> saveMine(@RequestBody HrArchive archive) {
        archiveService.saveMine(StpUtil.getLoginIdAsLong(), archive);
        return Result.ok();
    }

    @GetMapping("/{id}")
    @SaCheckPermission("hr:archive:list")
    public Result<HrArchive> get(@PathVariable Long id) {
        HrArchive archive = archiveService.getDetail(id);
        maskAttendanceIfNeeded(List.of(archive));
        return Result.ok(archive);
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

    @PutMapping("/{id}/attendance-enabled")
    @SaCheckPermission("hr:attendance:edit")
    public Result<Void> setAttendanceEnabled(@PathVariable Long id, @RequestBody AttendanceEnabledBody body) {
        archiveService.setAttendanceEnabled(id, body.enabled());
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("hr:archive:remove")
    public Result<Void> delete(@PathVariable Long id) {
        archiveService.deleteArchive(id);
        return Result.ok();
    }

    private void maskAttendanceIfNeeded(List<HrArchive> archives) {
        if (!StpUtil.hasPermission("hr:attendance:edit")) {
            archives.forEach(archive -> archive.setAttendanceEnabled(null));
        }
    }

    public record AttendanceEnabledBody(Integer enabled) {}
}
