package com.kk.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kk.common.exception.BusinessException;
import com.kk.system.entity.SysNotification;
import com.kk.system.mapper.SysNotificationMapper;
import com.kk.system.service.SysNotificationService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@Service
public class SysNotificationServiceImpl extends ServiceImpl<SysNotificationMapper, SysNotification>
        implements SysNotificationService {

    private static final String DEFAULT_LINK = "/workflow/center";

    @Override
    public void notifyUser(Long userId, String title, String content, String bizType, Long bizId, String link) {
        if (userId == null || !StringUtils.hasText(title)) {
            return;
        }
        SysNotification n = new SysNotification();
        n.setUserId(userId);
        n.setTitle(title);
        n.setContent(content);
        n.setBizType(StringUtils.hasText(bizType) ? bizType : "approval");
        n.setBizId(bizId);
        n.setLink(StringUtils.hasText(link) ? link : DEFAULT_LINK);
        n.setReadFlag(0);
        save(n);
    }

    @Override
    public void notifyUsers(Collection<Long> userIds, String title, String content, String bizType, Long bizId, String link) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        Set<Long> uniq = new HashSet<>();
        for (Long uid : userIds) {
            if (uid != null && uniq.add(uid)) {
                notifyUser(uid, title, content, bizType, bizId, link);
            }
        }
    }

    @Override
    public Page<SysNotification> pageMine(long page, long pageSize, Boolean unreadOnly) {
        Long userId = requireLogin();
        LambdaQueryWrapper<SysNotification> q = new LambdaQueryWrapper<SysNotification>()
                .eq(SysNotification::getUserId, userId)
                .eq(Boolean.TRUE.equals(unreadOnly), SysNotification::getReadFlag, 0)
                .orderByDesc(SysNotification::getId);
        return page(new Page<>(page, pageSize), q);
    }

    @Override
    public long unreadCount(Long userId) {
        if (userId == null) {
            return 0;
        }
        Long cnt = lambdaQuery()
                .eq(SysNotification::getUserId, userId)
                .eq(SysNotification::getReadFlag, 0)
                .count();
        return cnt == null ? 0 : cnt;
    }

    @Override
    public void markRead(Long userId, Long id) {
        SysNotification n = getById(id);
        if (n == null || !Objects.equals(n.getUserId(), userId)) {
            throw new BusinessException("通知不存在");
        }
        if (Integer.valueOf(1).equals(n.getReadFlag())) {
            return;
        }
        n.setReadFlag(1);
        updateById(n);
    }

    @Override
    public void markAllRead(Long userId) {
        if (userId == null) {
            return;
        }
        update(new LambdaUpdateWrapper<SysNotification>()
                .eq(SysNotification::getUserId, userId)
                .eq(SysNotification::getReadFlag, 0)
                .set(SysNotification::getReadFlag, 1));
    }

    private Long requireLogin() {
        try {
            return cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            throw new BusinessException("请先登录");
        }
    }
}
