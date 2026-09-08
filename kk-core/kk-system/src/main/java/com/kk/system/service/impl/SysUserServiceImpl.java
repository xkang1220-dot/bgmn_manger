package com.kk.system.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kk.common.exception.BusinessException;
import com.kk.system.config.StpInterfaceImpl;
import com.kk.system.entity.SysDept;
import com.kk.system.entity.SysRole;
import com.kk.system.entity.SysUser;
import com.kk.system.entity.SysUserDept;
import com.kk.system.entity.SysUserRole;
import com.kk.system.mapper.SysDeptMapper;
import com.kk.system.mapper.SysUserDeptMapper;
import com.kk.system.mapper.SysUserMapper;
import com.kk.system.mapper.SysUserRoleMapper;
import com.kk.system.service.SysDeptService;
import com.kk.system.service.SysRoleService;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    private final SysUserRoleMapper userRoleMapper;
    private final SysUserDeptMapper userDeptMapper;
    private final SysDeptMapper deptMapper;
    private final SysDeptService deptService;
    private final SysRoleService roleService;

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
        user.setTaskQueryCode(null);
        normalizeDeptsAndRoles(user);
        save(user);
        saveUserDepts(user.getId(), user.getUserDepts());
        saveRoleBindings(user.getId(), user.getRoleBindings());
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
        user.setTotpSecretKey(null);
        user.setTaskQueryCode(null);
        normalizeDeptsAndRoles(user);
        updateById(user);
        if (user.getUserDepts() != null) {
            saveUserDepts(user.getId(), user.getUserDepts());
        }
        if (user.getRoleBindings() != null || user.getRoleIds() != null) {
            saveRoleBindings(user.getId(), user.getRoleBindings());
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
        userDeptMapper.delete(new LambdaQueryWrapper<SysUserDept>().eq(SysUserDept::getUserId, id));
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
        list.forEach(u -> {
            u.setPassword(null);
            u.setTotpSecretKey(null);
            u.setTaskQueryCode(null);
        });
        return list;
    }

    @Override
    public SysUser getUserDetail(Long id) {
        SysUser user = getById(id);
        if (user != null) {
            fillExtra(user);
        }
        return user;
    }

    @Override
    public List<Long> listUserIdsByRoleCode(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return List.of();
        }
        return baseMapper.selectUserIdsByRoleCode(roleCode);
    }

    @Override
    public String generateTaskQueryCode(Long userId) {
        SysUser user = getById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        String current = user.getTaskQueryCode();
        for (int i = 0; i < 64; i++) {
            String code = randomQueryCode();
            if (isWeakQueryCode(code) || code.equals(current)) {
                continue;
            }
            if (count(new LambdaQueryWrapper<SysUser>().eq(SysUser::getTaskQueryCode, code)) > 0) {
                continue;
            }
            SysUser update = new SysUser();
            update.setId(userId);
            update.setTaskQueryCode(code);
            try {
                updateById(update);
                return code;
            } catch (DuplicateKeyException ignored) {
                // retry
            }
        }
        throw new BusinessException("查询码已用尽，请联系管理员");
    }

    @Override
    public SysUser getEnabledByTaskQueryCode(String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        return getOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getTaskQueryCode, code)
                .eq(SysUser::getStatus, 1)
                .last("LIMIT 1"));
    }

    private static String randomQueryCode() {
        return String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    private static boolean isWeakQueryCode(String code) {
        if (code == null || code.length() != 4) {
            return true;
        }
        if (code.chars().distinct().count() == 1) {
            return true;
        }
        return "0123".equals(code) || "1234".equals(code);
    }

    private void fillExtra(SysUser user) {
        user.setPassword(null);
        user.setTotpSecretKey(null);
        user.setTaskQueryCode(null);
        user.setRoleIds(baseMapper.selectRoleIds(user.getId()));
        if (user.getDeptId() != null) {
            SysDept dept = deptMapper.selectById(user.getDeptId());
            if (dept != null) {
                user.setDeptName(dept.getName());
            }
        }
        List<SysUserDept> depts = userDeptMapper.selectList(new LambdaQueryWrapper<SysUserDept>()
                .eq(SysUserDept::getUserId, user.getId())
                .orderByDesc(SysUserDept::getIsPrimary)
                .orderByAsc(SysUserDept::getId));
        for (SysUserDept d : depts) {
            SysDept dept = deptMapper.selectById(d.getDeptId());
            if (dept != null) {
                d.setDeptName(dept.getName());
            }
            d.setCompanyId(deptService.resolveCompanyId(d.getDeptId()));
        }
        user.setUserDepts(depts);

        List<SysUserRole> bindings = userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, user.getId())
                .orderByAsc(SysUserRole::getId));
        for (SysUserRole b : bindings) {
            SysRole role = roleService.getById(b.getRoleId());
            if (role != null) {
                b.setRoleName(role.getName());
                b.setRoleCode(role.getCode());
            }
            if (b.getCompanyId() != null && b.getCompanyId() != 0L) {
                SysDept company = deptMapper.selectById(b.getCompanyId());
                if (company != null) {
                    b.setCompanyName(company.getName());
                }
            } else {
                b.setCompanyName("全局");
            }
        }
        user.setRoleBindings(bindings);
        user.setRoleNames(bindings.stream()
                .map(SysUserRole::getRoleName)
                .filter(StringUtils::hasText)
                .distinct()
                .toList());
    }

    private void normalizeDeptsAndRoles(SysUser user) {
        List<SysUserDept> depts = user.getUserDepts();
        if (depts == null || depts.isEmpty()) {
            if (user.getDeptId() != null) {
                SysUserDept one = new SysUserDept();
                one.setDeptId(user.getDeptId());
                one.setDataScope(5);
                one.setIsPrimary(1);
                depts = List.of(one);
                user.setUserDepts(depts);
            } else {
                throw new BusinessException("请至少选择一个部门");
            }
        }
        long primaryCount = depts.stream().filter(d -> d.getIsPrimary() != null && d.getIsPrimary() == 1).count();
        if (primaryCount == 0) {
            depts.get(0).setIsPrimary(1);
        } else if (primaryCount > 1) {
            boolean first = true;
            for (SysUserDept d : depts) {
                if (d.getIsPrimary() != null && d.getIsPrimary() == 1) {
                    if (first) {
                        first = false;
                    } else {
                        d.setIsPrimary(0);
                    }
                }
            }
        }
        SysUserDept primary = depts.stream()
                .filter(d -> d.getIsPrimary() != null && d.getIsPrimary() == 1)
                .findFirst()
                .orElse(depts.get(0));
        user.setDeptId(primary.getDeptId());

        if (user.getRoleBindings() == null && user.getRoleIds() != null) {
            Long companyId = deptService.resolveCompanyId(primary.getDeptId());
            List<SysUserRole> bindings = new ArrayList<>();
            for (Long roleId : user.getRoleIds()) {
                SysUserRole b = new SysUserRole();
                b.setRoleId(roleId);
                SysRole role = roleService.getById(roleId);
                if (role != null && "admin".equals(role.getCode())) {
                    b.setCompanyId(0L);
                } else {
                    if (companyId == null) {
                        throw new BusinessException("非管理员角色必须绑定公司，请先配置主部门");
                    }
                    b.setCompanyId(companyId);
                }
                bindings.add(b);
            }
            user.setRoleBindings(bindings);
        }
    }

    private void saveUserDepts(Long userId, List<SysUserDept> depts) {
        userDeptMapper.delete(new LambdaQueryWrapper<SysUserDept>().eq(SysUserDept::getUserId, userId));
        if (depts == null) {
            return;
        }
        for (SysUserDept d : depts) {
            if (d.getDeptId() == null) {
                continue;
            }
            SysUserDept row = new SysUserDept();
            row.setUserId(userId);
            row.setDeptId(d.getDeptId());
            row.setDataScope(d.getDataScope() == null ? 5 : d.getDataScope());
            row.setIsPrimary(d.getIsPrimary() == null ? 0 : d.getIsPrimary());
            userDeptMapper.insert(row);
        }
    }

    private void saveRoleBindings(Long userId, List<SysUserRole> bindings) {
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
        if (bindings == null) {
            StpInterfaceImpl.clearPermissionCache(userId);
            return;
        }
        for (SysUserRole b : bindings) {
            if (b.getRoleId() == null) {
                continue;
            }
            SysRole role = roleService.getById(b.getRoleId());
            long companyId = b.getCompanyId() == null ? 0L : b.getCompanyId();
            if (role != null && !"admin".equals(role.getCode()) && companyId == 0L) {
                throw new BusinessException("非管理员角色必须绑定公司：" + role.getName());
            }
            if (role != null && "admin".equals(role.getCode())) {
                companyId = 0L;
            }
            SysUserRole row = new SysUserRole();
            row.setUserId(userId);
            row.setRoleId(b.getRoleId());
            row.setCompanyId(companyId);
            userRoleMapper.insert(row);
        }
        StpInterfaceImpl.clearPermissionCache(userId);
    }
}
