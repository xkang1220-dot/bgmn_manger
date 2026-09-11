package com.kk.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kk.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("pm_project_flow")
public class PmProjectFlow extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    /** CREATE / UPDATE / DELETE / MEMBER / STATUS / SCALE */
    private String action;

    private String fromValue;

    private String toValue;

    private Long approvalId;

    private String remark;

    @TableField(exist = false)
    private String operatorName;

    @TableField(exist = false)
    private String actionLabel;

    @TableField(exist = false)
    private String summary;
}
