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
@TableName("sys_file")
public class SysFile extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String originalName;

    private String storedName;

    private String path;

    private String url;

    private String storageType;

    private String contentType;

    private Long size;

    private String bizType;

    private Long bizId;

    @TableField(exist = false)
    private String uploaderName;
}
