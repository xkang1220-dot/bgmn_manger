package com.kk.biz.service;

import com.kk.biz.dto.WorkStatus;

public interface UserWorkStatusService {
    WorkStatus getStatus(Long userId);
    boolean isWorking(Long userId);
    WorkStatus clockOut(Long userId);
    WorkStatus clockIn(Long userId);
}
