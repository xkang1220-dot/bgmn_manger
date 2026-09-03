package com.kk.biz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class LedgerCreateRequest {

    @NotBlank(message = "业务类型不能为空")
    private String bizType;

    @NotBlank(message = "账户类型不能为空")
    private String accountType;

    private Long poolId;

    private Long channelId;

    private Long userId;

    /** 入账填写「到账总额」；有手续费时实际入池为净额 */
    @NotNull(message = "金额不能为空")
    private BigDecimal amount;

    /** FIXED / PERCENT；为空表示无手续费 */
    private String feeMode;

    /** 固定金额，或百分比数值（如 0.6 表示 0.6%） */
    private BigDecimal feeValue;

    private Long projectId;

    private String title;

    private String remark;

    /** 进出账凭证文件 ID（先上传后绑定） */
    private List<Long> voucherFileIds;

    /** 关联审批单（审批通过后动账时写入流水） */
    private Long approvalId;
}
