package com.kk.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sys_user_dept")
public class SysUserDept {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long deptId;

    /** 1公司全部 3本部门 4本部门及以下 5仅本人 */
    private Integer dataScope;

    private Integer isPrimary;

    @TableField(exist = false)
    private String deptName;

    @TableField(exist = false)
    private Long companyId;
}
