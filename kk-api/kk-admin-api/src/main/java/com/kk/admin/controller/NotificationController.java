package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.kk.common.result.PageResult;
import com.kk.common.result.Result;
import com.kk.system.entity.SysNotification;
import com.kk.system.service.SysNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/notification")
@RequiredArgsConstructor
@SaCheckLogin
public class NotificationController {

    private final SysNotificationService notificationService;

    @GetMapping("/page")
    public Result<PageResult<SysNotification>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            Boolean unreadOnly) {
        return Result.ok(PageResult.of(notificationService.pageMine(page, pageSize, unreadOnly)));
    }

    @GetMapping("/unread-count")
    public Result<Map<String, Long>> unreadCount() {
        long count = notificationService.unreadCount(StpUtil.getLoginIdAsLong());
        return Result.ok(Map.of("count", count));
    }

    @PostMapping("/{id}/read")
    public Result<Void> markRead(@PathVariable Long id) {
        notificationService.markRead(StpUtil.getLoginIdAsLong(), id);
        return Result.ok();
    }

    @PostMapping("/read-all")
    public Result<Void> markAllRead() {
        notificationService.markAllRead(StpUtil.getLoginIdAsLong());
        return Result.ok();
    }
}
