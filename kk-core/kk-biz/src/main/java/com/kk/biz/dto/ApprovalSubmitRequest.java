package com.kk.biz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
public class ApprovalSubmitRequest {

    @NotBlank(message = "审批类型不能为空")
    private String type;

    private String title;

    private BigDecimal amount;

    private Long projectId;

    private Long poolId;

    private String remark;

    /** 业务载荷，按类型不同字段不同 */
    private Map<String, Object> payload;

    /** 凭证文件 */
    private List<Long> voucherFileIds;
}
