package com.kk.biz.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class ProjectManualSettleRequest {

    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    private String remark;

    @NotEmpty(message = "请至少指定一名参与人的分钱金额")
    @Valid
    private List<ManualShareItem> items;
}
