package com.kk.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kk.system.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("""
            SELECT DISTINCT m.permission
            FROM sys_menu m
            INNER JOIN sys_role_menu rm ON m.id = rm.menu_id
            INNER JOIN sys_user_role ur ON rm.role_id = ur.role_id
            WHERE ur.user_id = #{userId}
              AND m.deleted = 0
              AND m.status = 1
              AND m.permission IS NOT NULL
              AND m.permission <> ''
            """)
    List<String> selectPermissions(@Param("userId") Long userId);

    @Select("""
            SELECT r.code
            FROM sys_role r
            INNER JOIN sys_user_role ur ON r.id = ur.role_id
            WHERE ur.user_id = #{userId}
              AND r.deleted = 0
              AND r.status = 1
            """)
    List<String> selectRoleCodes(@Param("userId") Long userId);

    @Select("""
            SELECT r.id
            FROM sys_role r
            INNER JOIN sys_user_role ur ON r.id = ur.role_id
            WHERE ur.user_id = #{userId}
              AND r.deleted = 0
            """)
    List<Long> selectRoleIds(@Param("userId") Long userId);

    @Select("""
            SELECT DISTINCT ur.user_id
            FROM sys_user_role ur
            INNER JOIN sys_role r ON r.id = ur.role_id
            INNER JOIN sys_user u ON u.id = ur.user_id
            WHERE r.code = #{roleCode}
              AND r.deleted = 0
              AND r.status = 1
              AND u.deleted = 0
              AND u.status = 1
            """)
    List<Long> selectUserIdsByRoleCode(@Param("roleCode") String roleCode);
}
