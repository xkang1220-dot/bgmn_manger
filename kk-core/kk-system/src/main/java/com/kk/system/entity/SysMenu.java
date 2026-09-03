package com.kk.system.entity;

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
@TableName("sys_menu")
public class SysMenu extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long parentId;

    private String name;

    /** 类型(1目录 2菜单 3按钮) */
    private Integer type;

    private String path;

    private String component;

    private String permission;

    private String icon;

    private Integer sort;

    /** 是否可见(0隐藏 1显示) */
    private Integer visible;

    /** 状态(0禁用 1启用) */
    private Integer status;

    @TableField(exist = false)
    private List<SysMenu> children;
}
