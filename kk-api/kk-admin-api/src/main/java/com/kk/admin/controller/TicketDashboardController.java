package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.kk.biz.ticket.service.TicketDashboardService;
import com.kk.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/ticket/dashboard")
@RequiredArgsConstructor
public class TicketDashboardController {

    private final TicketDashboardService dashboardService;

    @GetMapping("/overview")
    @SaCheckPermission("ticket:dashboard:view")
    public Result<Map<String, Object>> overview(@RequestParam Long companyId,
                                                @RequestParam(required = false) Long cycleId,
                                                @RequestParam(required = false) Long projectId) {
        return Result.ok(dashboardService.overview(companyId, cycleId, projectId));
    }

    @GetMapping("/cycle-snapshot")
    @SaCheckPermission("ticket:dashboard:view")
    public Result<Map<String, Object>> cycleSnapshot(@RequestParam Long companyId,
                                                     @RequestParam(defaultValue = "6") int limit) {
        return Result.ok(dashboardService.cycleSnapshot(companyId, limit));
    }
}
