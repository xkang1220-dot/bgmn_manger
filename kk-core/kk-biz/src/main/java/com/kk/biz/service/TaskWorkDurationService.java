package com.kk.biz.service;

import java.time.Duration;
import java.time.LocalDateTime;

public interface TaskWorkDurationService {
    Duration calculate(Long taskId, Long userId, LocalDateTime taskStartedAt, LocalDateTime taskEndedAt);
}
