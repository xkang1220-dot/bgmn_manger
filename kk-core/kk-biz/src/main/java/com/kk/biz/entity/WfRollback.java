package com.kk.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kk.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wf_rollback")
public class WfRollback extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String bizNo;

    private Long approvalId;

    private Long rollbackApprovalId;

    /** FULL / PARTIAL */
    private String mode;

    private BigDecimal amount;

    private String status;

    private String reason;

    @TableField(exist = false)
    private String approvalBizNo;
}
