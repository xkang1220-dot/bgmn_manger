package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.kk.biz.entity.HrSalaryItem;
import com.kk.biz.entity.HrSalaryRun;
import com.kk.biz.entity.HrSalaryRunLine;
import com.kk.biz.entity.HrSalarySchedule;
import com.kk.biz.service.HrSalaryService;
import com.kk.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/hr/salary")
@RequiredArgsConstructor
public class HrSalaryController {

    private final HrSalaryService salaryService;

    @GetMapping("/items")
    @SaCheckPermission("hr:salary:config")
    public Result<List<HrSalaryItem>> items(
            @RequestParam Long companyId,
            @RequestParam(required = false) Long userId) {
        return Result.ok(salaryService.listItems(companyId, userId));
    }

    @PostMapping("/item")
    @SaCheckPermission(value = {"hr:salary:edit", "hr:salary:config"}, mode = cn.dev33.satoken.annotation.SaMode.OR)
    public Result<Void> saveItem(@RequestBody HrSalaryItem item) {
        salaryService.saveItem(item);
        return Result.ok();
    }

    @PutMapping("/item")
    @SaCheckPermission(value = {"hr:salary:edit", "hr:salary:config"}, mode = cn.dev33.satoken.annotation.SaMode.OR)
    public Result<Void> updateItem(@RequestBody HrSalaryItem item) {
        salaryService.saveItem(item);
        return Result.ok();
    }

    @DeleteMapping("/item/{id}")
    @SaCheckPermission(value = {"hr:salary:edit", "hr:salary:config"}, mode = cn.dev33.satoken.annotation.SaMode.OR)
    public Result<Void> deleteItem(@PathVariable Long id) {
        salaryService.deleteItem(id);
        return Result.ok();
    }

    @GetMapping("/schedule")
    @SaCheckPermission("hr:salary:config")
    public Result<HrSalarySchedule> schedule(@RequestParam Long companyId) {
        return Result.ok(salaryService.getSchedule(companyId));
    }

    @PutMapping("/schedule")
    @SaCheckPermission("hr:salary:schedule")
    public Result<Void> saveSchedule(@RequestBody HrSalarySchedule schedule) {
        salaryService.saveSchedule(schedule);
        return Result.ok();
    }

    @GetMapping("/runs")
    @SaCheckPermission("hr:salary:run")
    public Result<List<HrSalaryRun>> runs(
            @RequestParam Long companyId,
            @RequestParam(required = false) String yearMonth) {
        return Result.ok(salaryService.listRuns(companyId, yearMonth));
    }

    @GetMapping("/runs/{runId}/lines")
    @SaCheckPermission("hr:salary:run")
    public Result<List<HrSalaryRunLine>> lines(@PathVariable Long runId) {
        return Result.ok(salaryService.listRunLines(runId));
    }

    @PostMapping("/preview")
    @SaCheckPermission("hr:salary:run:manual")
    public Result<HrSalaryRun> preview(@RequestBody Map<String, Object> body) {
        Long companyId = asLong(body.get("companyId"));
        String yearMonth = body.get("yearMonth") == null ? null : String.valueOf(body.get("yearMonth"));
        return Result.ok(salaryService.runPreview(companyId, yearMonth, true));
    }

    @GetMapping("/prepare-draft")
    @SaCheckPermission("hr:salary:run:manual")
    public Result<List<Map<String, Object>>> prepareDraft(
            @RequestParam Long companyId,
            @RequestParam(required = false) String yearMonth) {
        return Result.ok(salaryService.preparePayDraft(companyId, yearMonth));
    }

    @PostMapping("/prepare-confirm")
    @SaCheckPermission("hr:salary:run:manual")
    @SuppressWarnings("unchecked")
    public Result<HrSalaryRun> prepareConfirm(@RequestBody Map<String, Object> body) {
        Long companyId = asLong(body.get("companyId"));
        String yearMonth = body.get("yearMonth") == null ? null : String.valueOf(body.get("yearMonth"));
        List<Map<String, Object>> lines = body.get("lines") instanceof List<?> list
                ? (List<Map<String, Object>>) list
                : List.of();
        return Result.ok(salaryService.preparePayConfirm(companyId, yearMonth, lines));
    }

    @PostMapping("/pay")
    @SaCheckPermission("hr:salary:run:manual")
    public Result<HrSalaryRun> pay(@RequestBody Map<String, Object> body) {
        Long companyId = asLong(body.get("companyId"));
        String yearMonth = body.get("yearMonth") == null ? null : String.valueOf(body.get("yearMonth"));
        return Result.ok(salaryService.runPay(companyId, yearMonth, true));
    }

    @GetMapping("/my-confirm")
    public Result<List<Map<String, Object>>> myConfirm(
            @RequestParam(required = false) String yearMonth) {
        return Result.ok(salaryService.myConfirmQueue(yearMonth));
    }

    @PostMapping("/my-confirm/{lineId}")
    public Result<Void> confirm(@PathVariable Long lineId) {
        salaryService.confirmMine(lineId);
        return Result.ok();
    }

    @PostMapping("/my-confirm/{lineId}/revoke")
    public Result<Void> revoke(@PathVariable Long lineId) {
        salaryService.revokeMine(lineId);
        return Result.ok();
    }

    private Long asLong(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number n) {
            return n.longValue();
        }
        return Long.valueOf(String.valueOf(raw));
    }
}
