package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.kk.biz.ticket.entity.TicketDevCycle;
import com.kk.biz.ticket.service.TicketDevCycleService;
import com.kk.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ticket/cycle")
@RequiredArgsConstructor
public class TicketCycleController {

    private final TicketDevCycleService cycleService;

    @GetMapping("/list")
    @SaCheckPermission("ticket:manage:list")
    public Result<List<TicketDevCycle>> list(@RequestParam Long companyId) {
        return Result.ok(cycleService.list(companyId));
    }

    @GetMapping("/active")
    @SaCheckPermission("ticket:manage:list")
    public Result<TicketDevCycle> active(@RequestParam Long companyId) {
        return Result.ok(cycleService.active(companyId));
    }

    @PostMapping
    @SaCheckPermission("ticket:manage:cycle")
    public Result<TicketDevCycle> create(@RequestBody TicketDevCycle cycle) {
        return Result.ok(cycleService.create(cycle));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("ticket:manage:cycle")
    public Result<TicketDevCycle> update(@PathVariable Long id, @RequestBody TicketDevCycle cycle) {
        return Result.ok(cycleService.update(id, cycle));
    }
}
