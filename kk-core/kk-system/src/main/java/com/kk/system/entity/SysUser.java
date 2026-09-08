package com.kk.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kk.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long deptId;

    @TableField(exist = false)
    private String deptName;

    private String username;

    private String password;

    private String nickname;

    private String avatar;

    private String email;

    private String phone;

    /** 性别(0未知 1男 2女) */
    private Integer gender;

    /** 状态(0禁用 1启用) */
    private Integer status;

    private String remark;

    /** TOTP 密钥（加密存储） */
    private String totpSecretKey;

    /** 是否启用 TOTP(0否 1是) */
    private Integer totpEnabled;

    /** TOTP 绑定时间 */
    private LocalDateTime totpVerifyTime;

    /** 任务公开查询码（4 位，全员唯一） */
    private String taskQueryCode;

    @TableField(exist = false)
    private List<Long> roleIds;

    @TableField(exist = false)
    private List<String> roleNames;

    /** 多部门归属 */
    @TableField(exist = false)
    private List<SysUserDept> userDepts;

    /** 角色绑定（含公司） */
    @TableField(exist = false)
    private List<SysUserRole> roleBindings;
}
