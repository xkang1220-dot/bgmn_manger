package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.kk.biz.entity.HrLeaveRecord;
import com.kk.biz.service.HrLeaveService;
import com.kk.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/hr/leave")
@RequiredArgsConstructor
public class HrLeaveController {

    private final HrLeaveService leaveService;

    @PostMapping("/mine")
    @SaCheckPermission("hr:leave:submit")
    public Result<Map<String, Object>> submitMine(@RequestBody Map<String, Object> body) {
        Long companyId = body.get("companyId") == null ? null : Long.valueOf(String.valueOf(body.get("companyId")));
        LocalDate start = parseDate(body.get("startDate"));
        LocalDate end = parseDate(body.get("endDate"));
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        return Result.ok(leaveService.submitMine(companyId, start, end, reason));
    }

    @GetMapping("/mine")
    @SaCheckPermission(value = {"hr:leave:mine", "hr:leave:submit"}, mode = SaMode.OR)
    public Result<List<HrLeaveRecord>> mine(
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate end) {
        return Result.ok(leaveService.listMine(companyId, start, end));
    }

    @GetMapping("/company")
    @SaCheckPermission("hr:attendance:list")
    public Result<List<HrLeaveRecord>> company(
            @RequestParam Long companyId,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate end) {
        return Result.ok(leaveService.listByCompany(companyId, start, end));
    }

    private LocalDate parseDate(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        return LocalDate.parse(text.substring(0, Math.min(10, text.length())));
    }
}
