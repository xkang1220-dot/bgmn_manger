package com.kk.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("wf_approval_log")
public class WfApprovalLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long approvalId;

    private Long operatorId;

    private String action;

    private String remark;

    private LocalDateTime createTime;

    @TableField(exist = false)
    private String operatorName;
}
