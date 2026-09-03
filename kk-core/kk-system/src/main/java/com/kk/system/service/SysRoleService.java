package com.kk.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kk.system.entity.SysRole;

import java.util.List;

public interface SysRoleService extends IService<SysRole> {

    void createRole(SysRole role);

    void updateRole(SysRole role);

    void deleteRole(Long id);

    SysRole getDetail(Long id);

    List<Long> getMenuIds(Long roleId);
}
