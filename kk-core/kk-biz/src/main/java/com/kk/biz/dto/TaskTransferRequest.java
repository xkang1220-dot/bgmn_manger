package com.kk.biz.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TaskTransferRequest {

    @NotNull(message = "请选择移交对象")
    private Long assigneeId;

    private String remark;
}
