package com.kk.biz.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProjectSettleRequest {

    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    @NotNull(message = "结算金额不能为空")
    private BigDecimal amount;

    private String remark;
}
