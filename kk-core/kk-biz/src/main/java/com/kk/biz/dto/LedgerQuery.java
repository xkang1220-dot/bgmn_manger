package com.kk.biz.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class LedgerQuery {
    private long page = 1;
    private long pageSize = 20;
    private String bizType;
    private String accountType;
    private Long userId;
    private Long companyId;
    private Long poolId;
    private Long projectId;
    private Long channelId;
    private BigDecimal minAmount;
    private BigDecimal maxAmount;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String keyword;
    /** 本人钱包流水等场景：已按 userId 限定本人，不再套公司数据范围 */
    private boolean skipCompanyScope;
}
