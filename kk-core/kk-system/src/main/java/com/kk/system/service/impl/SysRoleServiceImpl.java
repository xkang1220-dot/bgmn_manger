package com.kk.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kk.common.exception.BusinessException;
import com.kk.system.entity.SysRole;
import com.kk.system.entity.SysRoleDept;
import com.kk.system.entity.SysRoleMenu;
import com.kk.system.entity.SysUserRole;
import com.kk.system.mapper.SysRoleDeptMapper;
import com.kk.system.mapper.SysRoleMapper;
import com.kk.system.mapper.SysRoleMenuMapper;
import com.kk.system.mapper.SysUserRoleMapper;
import com.kk.system.service.SysRoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SysRoleServiceImpl extends ServiceImpl<SysRoleMapper, SysRole> implements SysRoleService {

    private final SysRoleMenuMapper roleMenuMapper;
    private final SysRoleDeptMapper roleDeptMapper;
    private final SysUserRoleMapper userRoleMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createRole(SysRole role) {
        checkCode(role.getCode(), null);
        if (role.getStatus() == null) {
            role.setStatus(1);
        }
        if (role.getDataScope() == null) {
            role.setDataScope(1);
        }
        save(role);
        saveMenus(role.getId(), role.getMenuIds());
        saveDepts(role.getId(), role.getDeptIds());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRole(SysRole role) {
        checkCode(role.getCode(), role.getId());
        updateById(role);
        if (role.getMenuIds() != null) {
            saveMenus(role.getId(), role.getMenuIds());
        }
        if (role.getDeptIds() != null) {
            saveDepts(role.getId(), role.getDeptIds());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(Long id) {
        SysRole role = getById(id);
        if (role != null && "admin".equals(role.getCode())) {
            throw new BusinessException("不能删除超级管理员角色");
        }
        long used = userRoleMapper.selectCount(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, id));
        if (used > 0) {
            throw new BusinessException("角色已分配给用户，无法删除");
        }
        removeById(id);
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, id));
        roleDeptMapper.delete(new LambdaQueryWrapper<SysRoleDept>().eq(SysRoleDept::getRoleId, id));
    }

    @Override
    public SysRole getDetail(Long id) {
        SysRole role = getById(id);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }
        role.setMenuIds(getMenuIds(id));
        List<SysRoleDept> depts = roleDeptMapper.selectList(new LambdaQueryWrapper<SysRoleDept>().eq(SysRoleDept::getRoleId, id));
        role.setDeptIds(depts.stream().map(SysRoleDept::getDeptId).toList());
        return role;
    }

    @Override
    public List<Long> getMenuIds(Long roleId) {
        return roleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, roleId))
                .stream().map(SysRoleMenu::getMenuId).toList();
    }

    private void checkCode(String code, Long id) {
        SysRole exist = getOne(new LambdaQueryWrapper<SysRole>().eq(SysRole::getCode, code).last("LIMIT 1"));
        if (exist != null && (id == null || !exist.getId().equals(id))) {
            throw new BusinessException("角色编码已存在");
        }
    }

    private void saveMenus(Long roleId, List<Long> menuIds) {
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, roleId));
        if (menuIds == null) {
            return;
        }
        for (Long menuId : menuIds) {
            SysRoleMenu rel = new SysRoleMenu();
            rel.setRoleId(roleId);
            rel.setMenuId(menuId);
            roleMenuMapper.insert(rel);
        }
    }

    private void saveDepts(Long roleId, List<Long> deptIds) {
        roleDeptMapper.delete(new LambdaQueryWrapper<SysRoleDept>().eq(SysRoleDept::getRoleId, roleId));
        if (deptIds == null) {
            return;
        }
        for (Long deptId : deptIds) {
            SysRoleDept rel = new SysRoleDept();
            rel.setRoleId(roleId);
            rel.setDeptId(deptId);
            roleDeptMapper.insert(rel);
        }
    }
}
