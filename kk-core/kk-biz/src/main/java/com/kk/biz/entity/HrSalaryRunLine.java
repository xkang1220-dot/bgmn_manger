package com.kk.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kk.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_salary_run_line")
public class HrSalaryRunLine extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;

    private Long userId;

    /** PENDING_CONFIRM / CONFIRMED / APPROVAL_CREATED / SKIPPED / FAILED */
    private String status;

    private String skipReason;

    private LocalDateTime confirmedAt;

    private Long approvalId;

    private String payloadSnapshot;

    private BigDecimal totalAmount;

    @TableField(exist = false)
    private String userName;
}
