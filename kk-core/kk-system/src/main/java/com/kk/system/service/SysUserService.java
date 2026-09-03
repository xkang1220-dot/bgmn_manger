package com.kk.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kk.system.entity.SysUser;

import java.util.List;

public interface SysUserService extends IService<SysUser> {

    Page<SysUser> pageUsers(long page, long pageSize, String username, String nickname, Long deptId, Integer status);

    SysUser getByUsername(String username);

    void createUser(SysUser user);

    void updateUser(SysUser user);

    void deleteUser(Long id);

    void resetPassword(Long id, String password);

    void changeStatus(Long id, Integer status);

    List<String> getPermissions(Long userId);

    List<String> getRoleCodes(Long userId);

    List<Long> getRoleIds(Long userId);

    boolean isAdmin(Long userId);

    List<SysUser> listSimple();

    /** 按角色编码查启用用户 ID */
    List<Long> listUserIdsByRoleCode(String roleCode);
}
