package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
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

    @GetMapping("/mine")
    @SaCheckPermission("hr:leave:mine")
    public Result<List<HrLeaveRecord>> mine(
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate end) {
        return Result.ok(leaveService.listMine(companyId, start, end));
    }

    @GetMapping("/attendance")
    @SaCheckPermission("hr:attendance:list")
    public Result<List<HrLeaveRecord>> attendance(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate end) {
        return Result.ok(leaveService.listAttendance(start, end));
    }

    @GetMapping("/attendance-users")
    @SaCheckPermission("hr:attendance:list")
    public Result<List<Map<String, Object>>> attendanceUsers() {
        return Result.ok(leaveService.listAttendanceUsers());
    }

    @GetMapping("/attendance-monthly-detail")
    @SaCheckPermission("hr:attendance:list")
    public Result<Map<String, Object>> attendanceMonthlyDetail(@RequestParam String month) {
        return Result.ok(leaveService.monthlyAttendanceDetail(month));
    }

    @PutMapping("/attendance-day")
    @SaCheckPermission("hr:attendance:edit")
    public Result<Void> setAttendanceDay(@RequestBody Map<String, Object> body) {
        LocalDate date = parseDate(body.get("date"));
        Object raw = body.get("userIds");
        List<Long> userIds = raw instanceof List<?> values
                ? values.stream().map(v -> Long.valueOf(String.valueOf(v))).toList()
                : List.of();
        leaveService.setAbsentUsers(date, userIds);
        return Result.ok();
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
