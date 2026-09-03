package com.kk.biz.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ApprovalQuery {
    private long page = 1;
    private long pageSize = 10;
    private String type;
    private String status;
    private String scope = "all";
    private Long projectId;
    private Long poolId;
    private BigDecimal minAmount;
    private BigDecimal maxAmount;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String keyword;
}
