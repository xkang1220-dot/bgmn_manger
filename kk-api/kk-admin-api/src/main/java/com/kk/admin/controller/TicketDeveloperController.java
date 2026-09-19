package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.kk.biz.ticket.entity.TicketDeveloper;
import com.kk.biz.ticket.service.TicketDeveloperService;
import com.kk.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ticket/developer")
@RequiredArgsConstructor
public class TicketDeveloperController {

    private final TicketDeveloperService developerService;

    @GetMapping("/list")
    @SaCheckPermission("ticket:manage:list")
    public Result<List<TicketDeveloper>> list(@RequestParam Long companyId,
                                              @RequestParam(required = false) Integer status) {
        return Result.ok(developerService.list(companyId, status));
    }

    @PostMapping
    @SaCheckPermission("ticket:manage:developer")
    public Result<TicketDeveloper> create(@RequestBody TicketDeveloper developer) {
        return Result.ok(developerService.create(developer));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("ticket:manage:developer")
    public Result<TicketDeveloper> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return Result.ok(developerService.update(id, body));
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("ticket:manage:developer")
    public Result<Void> delete(@PathVariable Long id) {
        developerService.delete(id);
        return Result.ok();
    }

    @PutMapping("/{id}/status")
    @SaCheckPermission("ticket:manage:developer")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        developerService.updateStatus(id, body == null ? null : body.get("status"));
        return Result.ok();
    }
}
