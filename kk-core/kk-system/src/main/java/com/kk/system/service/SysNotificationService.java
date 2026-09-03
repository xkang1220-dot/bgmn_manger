package com.kk.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kk.system.entity.SysNotification;

import java.util.Collection;

public interface SysNotificationService extends IService<SysNotification> {

    void notifyUser(Long userId, String title, String content, String bizType, Long bizId, String link);

    void notifyUsers(Collection<Long> userIds, String title, String content, String bizType, Long bizId, String link);

    Page<SysNotification> pageMine(long page, long pageSize, Boolean unreadOnly);

    long unreadCount(Long userId);

    void markRead(Long userId, Long id);

    void markAllRead(Long userId);
}
