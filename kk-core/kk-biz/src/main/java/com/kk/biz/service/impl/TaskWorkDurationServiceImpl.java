package com.kk.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kk.biz.config.WorkTimeProperties;
import com.kk.biz.entity.UserWorkBreak;
import com.kk.biz.entity.PmTaskFlow;
import com.kk.biz.mapper.PmTaskFlowMapper;
import com.kk.biz.mapper.UserWorkBreakMapper;
import com.kk.biz.service.TaskWorkDurationService;
import com.kk.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;

@Service
@RequiredArgsConstructor
public class TaskWorkDurationServiceImpl implements TaskWorkDurationService {
    private final WorkTimeProperties properties;
    private final UserWorkBreakMapper workBreakMapper;
    private final PmTaskFlowMapper taskFlowMapper;

    @Override
    public Duration calculate(Long taskId, Long userId, LocalDateTime taskStartedAt, LocalDateTime taskEndedAt) {
        if (userId == null || taskStartedAt == null || taskEndedAt == null || !taskEndedAt.isAfter(taskStartedAt)) return Duration.ZERO;
        if (!properties.getEnd().isAfter(properties.getStart())) throw new BusinessException("工作结束时间必须晚于开始时间");
        List<UserWorkBreak> breaks = workBreakMapper.selectList(new LambdaQueryWrapper<UserWorkBreak>()
                .eq(UserWorkBreak::getUserId, userId)
                .lt(UserWorkBreak::getStartedAt, taskEndedAt)
                .and(w -> w.isNull(UserWorkBreak::getEndedAt).or().gt(UserWorkBreak::getEndedAt, taskStartedAt))
                .orderByAsc(UserWorkBreak::getStartedAt));
        List<TimeRange> activeRanges = resolveActiveRanges(taskId, taskStartedAt, taskEndedAt);
        Duration total = Duration.ZERO;
        for (TimeRange range : activeRanges) {
            total = total.plus(calculateRange(range.start(), range.end(), breaks));
        }
        return total;
    }

    private Duration calculateRange(LocalDateTime rangeStart, LocalDateTime rangeEnd, List<UserWorkBreak> breaks) {
        Duration total = Duration.ZERO;
        for (LocalDate date = rangeStart.toLocalDate(); !date.isAfter(rangeEnd.toLocalDate()); date = date.plusDays(1)) {
            LocalDateTime effectiveStart = later(rangeStart, date.atTime(properties.getStart()));
            LocalDateTime effectiveEnd = earlier(rangeEnd, date.atTime(properties.getEnd()));
            if (!effectiveEnd.isAfter(effectiveStart)) continue;
            Duration dayDuration = Duration.between(effectiveStart, effectiveEnd);
            for (UserWorkBreak workBreak : breaks) {
                LocalDateTime breakEnd = workBreak.getEndedAt() == null ? rangeEnd : workBreak.getEndedAt();
                LocalDateTime overlapStart = later(effectiveStart, workBreak.getStartedAt());
                LocalDateTime overlapEnd = earlier(effectiveEnd, breakEnd);
                if (overlapEnd.isAfter(overlapStart)) dayDuration = dayDuration.minus(Duration.between(overlapStart, overlapEnd));
            }
            if (!dayDuration.isNegative()) total = total.plus(dayDuration);
        }
        return total;
    }

    /** 根据状态流转还原每段进行中区间；startedAt 始终保留首次开始时间。 */
    private List<TimeRange> resolveActiveRanges(Long taskId, LocalDateTime startedAt, LocalDateTime endedAt) {
        if (taskId == null) return List.of(new TimeRange(startedAt, endedAt));
        List<PmTaskFlow> flows = taskFlowMapper.selectList(new LambdaQueryWrapper<PmTaskFlow>()
                .eq(PmTaskFlow::getTaskId, taskId)
                .isNotNull(PmTaskFlow::getToStatus)
                .le(PmTaskFlow::getCreateTime, endedAt)
                .orderByAsc(PmTaskFlow::getCreateTime)
                .orderByAsc(PmTaskFlow::getId));
        List<TimeRange> ranges = new ArrayList<>();
        boolean running = true;
        LocalDateTime activeStart = startedAt;
        for (PmTaskFlow flow : flows) {
            LocalDateTime changedAt = flow.getCreateTime();
            if (changedAt == null || changedAt.isBefore(startedAt)) continue;
            if (changedAt.isAfter(endedAt)) break;
            boolean nextRunning = Integer.valueOf(1).equals(flow.getToStatus());
            if (running && !nextRunning) {
                if (changedAt.isAfter(activeStart)) ranges.add(new TimeRange(activeStart, changedAt));
                running = false;
            } else if (!running && nextRunning) {
                activeStart = changedAt;
                running = true;
            }
        }
        if (running && endedAt.isAfter(activeStart)) ranges.add(new TimeRange(activeStart, endedAt));
        return ranges;
    }

    private record TimeRange(LocalDateTime start, LocalDateTime end) {}

    private LocalDateTime later(LocalDateTime a, LocalDateTime b) { return a.isAfter(b) ? a : b; }
    private LocalDateTime earlier(LocalDateTime a, LocalDateTime b) { return a.isBefore(b) ? a : b; }
}
