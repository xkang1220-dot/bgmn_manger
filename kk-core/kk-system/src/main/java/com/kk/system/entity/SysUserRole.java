package com.kk.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sys_user_role")
public class SysUserRole {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long roleId;

    /** 0=全局(仅admin)；其它=顶层部门(公司)id */
    private Long companyId;

    @TableField(exist = false)
    private String roleName;

    @TableField(exist = false)
    private String roleCode;

    @TableField(exist = false)
    private String companyName;
}
