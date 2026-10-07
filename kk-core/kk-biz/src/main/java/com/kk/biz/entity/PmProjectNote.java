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
@TableName("pm_project_note")
public class PmProjectNote extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private String content;

    @TableField(exist = false)
    private String authorName;

    @TableField(exist = false)
    private List<SysFile> attachments;
}
