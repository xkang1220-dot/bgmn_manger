package com.kk.biz.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ManualShareItem {

    @NotNull(message = "人员不能为空")
    private Long userId;

    @NotNull(message = "金额不能为空")
    private BigDecimal amount;

    /** 分层说明，写入流水摘要 */
    private String layer;
}
