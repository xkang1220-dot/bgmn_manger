package com.kk.biz.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class LedgerThresholdSaveRequest {
    private Long companyId;
    /** 是否启用阈值 */
    private Boolean enabled;
    private BigDecimal notifyThreshold;
    private BigDecimal approveThreshold;
}
