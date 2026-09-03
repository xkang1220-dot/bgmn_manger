package com.kk.system.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kk.common.exception.BusinessException;
import com.kk.system.entity.SysDept;
import com.kk.system.entity.SysUser;
import com.kk.system.entity.SysUserRole;
import com.kk.system.mapper.SysDeptMapper;
import com.kk.system.mapper.SysUserMapper;
import com.kk.system.mapper.SysUserRoleMapper;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    private final SysUserRoleMapper userRoleMapper;
    private final SysDeptMapper deptMapper;

    @Override
    public Page<SysUser> pageUsers(long page, long pageSize, String username, String nickname, Long deptId, Integer status) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .like(StringUtils.hasText(username), SysUser::getUsername, username)
                .like(StringUtils.hasText(nickname), SysUser::getNickname, nickname)
                .eq(deptId != null, SysUser::getDeptId, deptId)
                .eq(status != null, SysUser::getStatus, status)
                .orderByDesc(SysUser::getId);
        Page<SysUser> result = page(new Page<>(page, pageSize), wrapper);
        result.getRecords().forEach(this::fillExtra);
        return result;
    }

    @Override
    public SysUser getByUsername(String username) {
        return getOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username).last("LIMIT 1"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createUser(SysUser user) {
        if (getByUsername(user.getUsername()) != null) {
            throw new BusinessException("用户名已存在");
        }
        if (!StringUtils.hasText(user.getPassword())) {
            user.setPassword("123456");
        }
        user.setPassword(BCrypt.hashpw(user.getPassword()));
        if (user.getStatus() == null) {
            user.setStatus(1);
        }
        save(user);
        saveRoles(user.getId(), user.getRoleIds());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateUser(SysUser user) {
        SysUser db = getById(user.getId());
        if (db == null) {
            throw new BusinessException("用户不存在");
        }
        SysUser exist = getByUsername(user.getUsername());
        if (exist != null && !exist.getId().equals(user.getId())) {
            throw new BusinessException("用户名已存在");
        }
        user.setPassword(null);
        updateById(user);
        if (user.getRoleIds() != null) {
            saveRoles(user.getId(), user.getRoleIds());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteUser(Long id) {
        if (isAdmin(id)) {
            throw new BusinessException("不能删除超级管理员");
        }
        removeById(id);
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, id));
    }

    @Override
    public void resetPassword(Long id, String password) {
        SysUser user = getById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        SysUser update = new SysUser();
        update.setId(id);
        update.setPassword(BCrypt.hashpw(StringUtils.hasText(password) ? password : "123456"));
        updateById(update);
    }

    @Override
    public void changeStatus(Long id, Integer status) {
        if (isAdmin(id) && status != null && status == 0) {
            throw new BusinessException("不能禁用超级管理员");
        }
        SysUser update = new SysUser();
        update.setId(id);
        update.setStatus(status);
        updateById(update);
    }

    @Override
    public List<String> getPermissions(Long userId) {
        if (isAdmin(userId)) {
            return List.of("*:*:*");
        }
        return baseMapper.selectPermissions(userId);
    }

    @Override
    public List<String> getRoleCodes(Long userId) {
        return baseMapper.selectRoleCodes(userId);
    }

    @Override
    public List<Long> getRoleIds(Long userId) {
        return baseMapper.selectRoleIds(userId);
    }

    @Override
    public boolean isAdmin(Long userId) {
        List<String> codes = getRoleCodes(userId);
        return codes != null && codes.contains("admin");
    }

    @Override
    public List<SysUser> listSimple() {
        List<SysUser> list = list(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getStatus, 1)
                .orderByAsc(SysUser::getId));
        list.forEach(u -> u.setPassword(null));
        return list;
    }

    @Override
    public List<Long> listUserIdsByRoleCode(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return List.of();
        }
        return baseMapper.selectUserIdsByRoleCode(roleCode);
    }

    private void fillExtra(SysUser user) {
        user.setPassword(null);
        user.setRoleIds(baseMapper.selectRoleIds(user.getId()));
        if (user.getDeptId() != null) {
            SysDept dept = deptMapper.selectById(user.getDeptId());
            if (dept != null) {
                user.setDeptName(dept.getName());
            }
        }
    }

    private void saveRoles(Long userId, List<Long> roleIds) {
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
        if (roleIds == null) {
            return;
        }
        for (Long roleId : roleIds) {
            SysUserRole rel = new SysUserRole();
            rel.setUserId(userId);
            rel.setRoleId(roleId);
            userRoleMapper.insert(rel);
        }
    }
}
