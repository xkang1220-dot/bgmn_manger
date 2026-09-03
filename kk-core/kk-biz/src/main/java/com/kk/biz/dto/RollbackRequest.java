package com.kk.biz.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class RollbackRequest {

    @NotNull(message = "原审批单不能为空")
    private Long approvalId;

    /** FULL / PARTIAL */
    @NotNull(message = "回退模式不能为空")
    private String mode;

    private BigDecimal amount;

    private String reason;
}
