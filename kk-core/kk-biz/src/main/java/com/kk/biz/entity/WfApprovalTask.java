package com.kk.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kk.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wf_approval_task")
public class WfApprovalTask extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long approvalId;

    private Long assigneeId;

    /** PENDING / APPROVE / REJECT / SKIP */
    private String action;

    private String comment;

    private LocalDateTime actTime;

    @TableField(exist = false)
    private String assigneeName;
}
