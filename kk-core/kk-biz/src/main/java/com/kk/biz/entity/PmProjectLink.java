package com.kk.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kk.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("pm_project_link")
public class PmProjectLink extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    /** REPOSITORY / WEBSITE */
    private String type;
    private String title;
    private String url;
    private String remark;
}
