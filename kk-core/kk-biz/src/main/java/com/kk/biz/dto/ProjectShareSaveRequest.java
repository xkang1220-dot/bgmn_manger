package com.kk.biz.dto;

import com.kk.biz.entity.PmProjectMember;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ProjectShareSaveRequest {

    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    private Long poolId;

    private BigDecimal budget;

    @NotEmpty(message = "请至少配置一名分成参与人")
    @Valid
    private List<PmProjectMember> members;
}
