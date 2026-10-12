package com.kk.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kk.biz.dto.WorkStatus;
import com.kk.biz.entity.UserWorkBreak;
import com.kk.biz.mapper.UserWorkBreakMapper;
import com.kk.biz.service.UserWorkStatusService;
import com.kk.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class UserWorkStatusServiceImpl implements UserWorkStatusService {
    private final UserWorkBreakMapper workBreakMapper;

    @Override
    public WorkStatus getStatus(Long userId) {
        UserWorkBreak openBreak = findOpenBreak(userId);
        return openBreak == null ? new WorkStatus(true, null) : new WorkStatus(false, openBreak.getStartedAt());
    }

    @Override
    public boolean isWorking(Long userId) {
        return findOpenBreak(userId) == null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkStatus clockOut(Long userId) {
        UserWorkBreak existing = findOpenBreak(userId);
        if (existing != null) return new WorkStatus(false, existing.getStartedAt());
        LocalDateTime now = LocalDateTime.now();
        UserWorkBreak workBreak = new UserWorkBreak();
        workBreak.setUserId(userId);
        workBreak.setStartedAt(now);
        try {
            workBreakMapper.insert(workBreak);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("当前已是下班状态，请勿重复操作");
        }
        return new WorkStatus(false, now);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkStatus clockIn(Long userId) {
        UserWorkBreak openBreak = findOpenBreak(userId);
        if (openBreak == null) return new WorkStatus(true, null);
        LocalDateTime now = LocalDateTime.now();
        openBreak.setEndedAt(now);
        workBreakMapper.updateById(openBreak);
        return new WorkStatus(true, now);
    }

    private UserWorkBreak findOpenBreak(Long userId) {
        return workBreakMapper.selectOne(new LambdaQueryWrapper<UserWorkBreak>()
                .eq(UserWorkBreak::getUserId, userId)
                .isNull(UserWorkBreak::getEndedAt)
                .orderByDesc(UserWorkBreak::getId)
                .last("LIMIT 1"));
    }
}
