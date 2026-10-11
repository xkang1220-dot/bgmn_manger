package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.kk.biz.entity.HrLeaveRecord;
import com.kk.biz.entity.HrDutyRecord;
import com.kk.biz.service.HrDutyService;
import com.kk.biz.service.HrLeaveService;
import com.kk.biz.service.HrHolidayCalendarService;
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
    private final HrHolidayCalendarService holidayCalendarService;
    private final HrDutyService dutyService;

    @GetMapping("/mine")
    @SaCheckPermission("hr:leave:mine")
    public Result<List<HrLeaveRecord>> mine(
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate end) {
        return Result.ok(leaveService.listMine(companyId, start, end));
    }

    @PostMapping("/mine")
    @SaCheckPermission("hr:leave:mine")
    public Result<Map<String, Object>> submitMine(@RequestBody Map<String, Object> body) {
        Long companyId = body.get("companyId") == null ? null : Long.valueOf(String.valueOf(body.get("companyId")));
        LocalDate startDate = parseDate(body.get("startDate"));
        LocalDate endDate = parseDate(body.get("endDate"));
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        return Result.ok(leaveService.submitMine(companyId, startDate, endDate, reason));
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

    @GetMapping("/holiday-calendar")
    @SaCheckPermission("hr:attendance:list")
    public Result<List<Map<String, Object>>> holidayCalendar(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate start,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate end) {
        return Result.ok(holidayCalendarService.listCalendar(start, end));
    }

    @GetMapping("/attendance-duty")
    @SaCheckPermission("hr:attendance:list")
    public Result<List<HrDutyRecord>> attendanceDuty(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate end) {
        return Result.ok(dutyService.listDuty(start, end));
    }

    @PutMapping("/attendance-duty-day")
    @SaCheckPermission("hr:attendance:edit")
    public Result<Void> setAttendanceDutyDay(@RequestBody Map<String, Object> body) {
        LocalDate date = parseDate(body.get("date"));
        Object raw = body.get("userIds");
        List<Long> userIds = raw instanceof List<?> values
                ? values.stream().map(v -> Long.valueOf(String.valueOf(v))).toList()
                : List.of();
        dutyService.setDutyUsers(date, userIds);
        return Result.ok();
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
