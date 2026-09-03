package com.kk.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kk.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("pm_task_flow")
public class PmTaskFlow extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    /** CREATE / ASSIGN / STATUS / TRANSFER */
    private String action;

    private Long fromUserId;

    private Long toUserId;

    private Integer fromStatus;

    private Integer toStatus;

    private String remark;

    @TableField(exist = false)
    private String operatorName;

    @TableField(exist = false)
    private String fromUserName;

    @TableField(exist = false)
    private String toUserName;

    @TableField(exist = false)
    private String actionLabel;

    @TableField(exist = false)
    private String summary;

    @TableField(exist = false)
    private List<SysFile> images;

    @TableField(exist = false)
    private List<Long> imageFileIds;
}
