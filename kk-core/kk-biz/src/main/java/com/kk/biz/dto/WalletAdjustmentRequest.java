package com.kk.biz.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class WalletAdjustmentRequest {
    @NotBlank(message = "调账方向不能为空")
    private String direction;

    @NotNull(message = "调账金额不能为空")
    @DecimalMin(value = "0.01", message = "调账金额必须大于0")
    @Digits(integer = 12, fraction = 2, message = "调账金额最多12位整数、2位小数")
    private BigDecimal amount;

    @NotBlank(message = "调账原因不能为空")
    @Size(max = 128, message = "调账原因不能超过128个字符")
    private String reason;

    @Size(max = 255, message = "备注不能超过255个字符")
    private String remark;

    private List<Long> voucherFileIds;
}
