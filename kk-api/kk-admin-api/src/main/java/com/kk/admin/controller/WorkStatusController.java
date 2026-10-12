package com.kk.admin.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.kk.biz.dto.WorkStatus;
import com.kk.biz.service.UserWorkStatusService;
import com.kk.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/work-status")
@RequiredArgsConstructor
public class WorkStatusController {
    private final UserWorkStatusService workStatusService;

    @GetMapping
    public Result<WorkStatus> status() { return Result.ok(workStatusService.getStatus(StpUtil.getLoginIdAsLong())); }

    @PostMapping("/clock-out")
    public Result<WorkStatus> clockOut() { return Result.ok(workStatusService.clockOut(StpUtil.getLoginIdAsLong())); }

    @PostMapping("/clock-in")
    public Result<WorkStatus> clockIn() { return Result.ok(workStatusService.clockIn(StpUtil.getLoginIdAsLong())); }
}
