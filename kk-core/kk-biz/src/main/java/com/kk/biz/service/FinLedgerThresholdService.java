package com.kk.biz.service;

import com.kk.biz.dto.LedgerThresholdSaveRequest;
import com.kk.biz.entity.FinLedgerThreshold;

public interface FinLedgerThresholdService {

    /** 无记录时返回 enabled=0 的占位（含建议阈值） */
    FinLedgerThreshold getOrDefault(Long companyId);

    /** 仅返回已启用配置，否则 null */
    FinLedgerThreshold findEnabled(Long companyId);

    void save(LedgerThresholdSaveRequest request);
}
