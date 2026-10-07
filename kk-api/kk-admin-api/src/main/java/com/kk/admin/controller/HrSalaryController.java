package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.kk.biz.entity.HrSalaryItem;
import com.kk.biz.entity.HrSalaryRun;
import com.kk.biz.entity.HrSalaryRunLine;
import com.kk.biz.entity.HrSalarySchedule;
import com.kk.biz.service.HrSalaryService;
import com.kk.common.exception.BusinessException;
import com.kk.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.util.LinkedHashMap;

@RestController
@RequestMapping("/api/hr/salary")
@RequiredArgsConstructor
public class HrSalaryController {

    private final HrSalaryService salaryService;

    @GetMapping("/items")
    @SaCheckPermission("hr:salary:config")
    public Result<List<HrSalaryItem>> items(
            @RequestParam(required = false) Long companyId,
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
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) String yearMonth) {
        return Result.ok(salaryService.listRuns(companyId, yearMonth));
    }

    @GetMapping("/runs/{runId}/lines")
    @SaCheckPermission("hr:salary:run")
    public Result<List<HrSalaryRunLine>> lines(@PathVariable Long runId) {
        return Result.ok(salaryService.listRunLines(runId));
    }

    @PostMapping("/runs/lines/{lineId}/void")
    @SaCheckPermission("hr:salary:run")
    public Result<Void> voidLine(@PathVariable Long lineId, @RequestBody Map<String, Object> body) {
        salaryService.voidRunLine(lineId, body.get("reason") == null ? null : String.valueOf(body.get("reason")));
        return Result.ok();
    }

    @PostMapping("/budget/preview")
    @SaCheckPermission("hr:salary:run")
    public Result<Map<String, Object>> previewBudget(@RequestBody Map<String, Object> body) {
        return Result.ok(salaryService.previewBudget(
                String.valueOf(body.get("yearMonth")), longList(body.get("itemIds")),
                decimalMap(body.get("taskRewardOverrides"))));
    }

    @GetMapping("/budget/items")
    @SaCheckPermission("hr:salary:run")
    public Result<List<HrSalaryItem>> budgetItems() {
        return Result.ok(salaryService.listBudgetItems());
    }

    @PostMapping("/budget/save")
    @SaCheckPermission("hr:salary:run")
    public Result<List<HrSalaryRun>> saveBudget(@RequestBody Map<String, Object> body) {
        return Result.ok(salaryService.saveBudget(
                String.valueOf(body.get("yearMonth")), longList(body.get("itemIds")),
                decimalMap(body.get("taskRewardOverrides"))));
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.valueOf(String.valueOf(value)); } catch (Exception ignored) { return null; }
    }

    private List<Long> longList(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().map(this::longValue).filter(java.util.Objects::nonNull).toList();
    }

    private Map<Long, BigDecimal> decimalMap(Object value) {
        if (!(value instanceof Map<?, ?> values)) return Map.of();
        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        values.forEach((key, amount) -> {
            Long id = longValue(key);
            if (id == null || amount == null) return;
            try {
                result.put(id, new BigDecimal(String.valueOf(amount)));
            } catch (NumberFormatException ignored) {
                throw new BusinessException("任务报酬调整值格式不正确");
            }
        });
        return result;
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

}
