package com.kk.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kk.system.entity.SysRole;
import com.kk.system.entity.SysUser;
import com.kk.system.entity.SysUserDept;
import com.kk.system.entity.SysUserRole;
import com.kk.system.mapper.SysUserDeptMapper;
import com.kk.system.mapper.SysUserRoleMapper;
import com.kk.system.service.DataScopeService;
import com.kk.system.service.SysDeptService;
import com.kk.system.service.SysRoleService;
import com.kk.system.service.SysUserService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DataScopeServiceImpl implements DataScopeService {

    private final SysUserDeptMapper userDeptMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysDeptService deptService;
    private final SysRoleService roleService;
    private final SysUserService userService;

    public DataScopeServiceImpl(SysUserDeptMapper userDeptMapper,
                                SysUserRoleMapper userRoleMapper,
                                SysDeptService deptService,
                                SysRoleService roleService,
                                @Lazy SysUserService userService) {
        this.userDeptMapper = userDeptMapper;
        this.userRoleMapper = userRoleMapper;
        this.deptService = deptService;
        this.roleService = roleService;
        this.userService = userService;
    }

    @Override
    public boolean isGlobalAdmin(Long userId) {
        if (userId == null) {
            return false;
        }
        List<Long> roleIds = userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, userId)
                        .and(w -> w.isNull(SysUserRole::getCompanyId).or().eq(SysUserRole::getCompanyId, GLOBAL_COMPANY_ID)))
                .stream()
                .map(SysUserRole::getRoleId)
                .filter(Objects::nonNull)
                .toList();
        if (roleIds.isEmpty()) {
            return false;
        }
        return roleService.listByIds(roleIds).stream()
                .anyMatch(r -> "admin".equals(r.getCode()));
    }

    @Override
    public Set<Long> visibleCompanyIds(Long userId) {
        Set<Long> ids = new HashSet<>();
        if (userId == null) {
            return ids;
        }
        if (isGlobalAdmin(userId)) {
            deptService.listCompanies().forEach(d -> ids.add(d.getId()));
            return ids;
        }
        for (SysUserDept row : listUserDepts(userId)) {
            Long companyId = deptService.resolveCompanyId(row.getDeptId());
            if (companyId != null) {
                ids.add(companyId);
            }
        }
        return ids;
    }

    @Override
    public Set<Long> visibleUserIds(Long userId) {
        Set<Long> userIds = new HashSet<>();
        if (userId == null) {
            return userIds;
        }
        if (isGlobalAdmin(userId)) {
            userService.list(new LambdaQueryWrapper<SysUser>().select(SysUser::getId))
                    .forEach(u -> userIds.add(u.getId()));
            return userIds;
        }
        for (Long companyId : visibleCompanyIds(userId)) {
            userIds.addAll(visibleUserIdsInCompany(userId, companyId));
        }
        if (userIds.isEmpty()) {
            userIds.add(userId);
        }
        return userIds;
    }

    @Override
    public Set<Long> visibleUserIdsInCompany(Long userId, Long companyId) {
        Set<Long> userIds = new HashSet<>();
        if (userId == null || companyId == null) {
            return userIds;
        }
        if (isGlobalAdmin(userId)) {
            userIds.addAll(listUserIdsInCompany(companyId));
            userIds.add(userId);
            return userIds;
        }
        boolean belongs = false;
        for (SysUserDept row : listUserDepts(userId)) {
            Long deptId = row.getDeptId();
            if (deptId == null) {
                continue;
            }
            Long rowCompanyId = deptService.resolveCompanyId(deptId);
            if (!Objects.equals(rowCompanyId, companyId)) {
                continue;
            }
            belongs = true;
            Integer scope = row.getDataScope() == null ? 5 : row.getDataScope();
            if (scope == 1) {
                userIds.addAll(listUserIdsInCompany(companyId));
            } else if (scope == 3) {
                userIds.addAll(listUserIdsInDepts(List.of(deptId)));
            } else if (scope == 4) {
                List<Long> childIds = deptService.listChildIds(deptId);
                if (childIds != null && !childIds.isEmpty()) {
                    userIds.addAll(listUserIdsInDepts(childIds));
                }
            }
        }
        if (!belongs) {
            return Set.of();
        }
        userIds.add(userId);
        return userIds;
    }

    @Override
    public boolean hasRoleInCompany(Long userId, String roleCode, Long companyId) {
        if (userId == null || !StringUtils.hasText(roleCode)) {
            return false;
        }
        if ("admin".equals(roleCode) && isGlobalAdmin(userId)) {
            return true;
        }
        List<Long> roleIds = roleService.list(new LambdaQueryWrapper<SysRole>().eq(SysRole::getCode, roleCode))
                .stream().map(SysRole::getId).toList();
        if (roleIds.isEmpty()) {
            return false;
        }
        LambdaQueryWrapper<SysUserRole> w = new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, userId)
                .in(SysUserRole::getRoleId, roleIds);
        if (companyId == null) {
            return userRoleMapper.selectCount(w) > 0;
        }
        // 仅 admin 的 company_id=0 表示全局；其它角色必须精确绑公司
        if ("admin".equals(roleCode)) {
            w.and(q -> q.eq(SysUserRole::getCompanyId, companyId)
                    .or().eq(SysUserRole::getCompanyId, GLOBAL_COMPANY_ID));
        } else {
            w.eq(SysUserRole::getCompanyId, companyId);
        }
        return userRoleMapper.selectCount(w) > 0;
    }

    @Override
    public List<Long> listUserIdsByRoleCodeInCompany(String roleCode, Long companyId) {
        if (!StringUtils.hasText(roleCode)) {
            return List.of();
        }
        List<Long> roleIds = roleService.list(new LambdaQueryWrapper<SysRole>().eq(SysRole::getCode, roleCode))
                .stream().map(SysRole::getId).toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<SysUserRole> w = new LambdaQueryWrapper<SysUserRole>()
                .in(SysUserRole::getRoleId, roleIds);
        if (companyId != null) {
            if ("admin".equals(roleCode)) {
                w.and(q -> q.eq(SysUserRole::getCompanyId, companyId)
                        .or().eq(SysUserRole::getCompanyId, GLOBAL_COMPANY_ID));
            } else {
                w.eq(SysUserRole::getCompanyId, companyId);
            }
        }
        return userRoleMapper.selectList(w).stream()
                .map(SysUserRole::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private List<SysUserDept> listUserDepts(Long userId) {
        return userDeptMapper.selectList(new LambdaQueryWrapper<SysUserDept>()
                .eq(SysUserDept::getUserId, userId));
    }

    private Set<Long> listUserIdsInDepts(List<Long> deptIds) {
        if (deptIds == null || deptIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> ids = new HashSet<>();
        userService.list(new LambdaQueryWrapper<SysUser>()
                        .in(SysUser::getDeptId, deptIds)
                        .select(SysUser::getId))
                .forEach(u -> ids.add(u.getId()));
        userDeptMapper.selectList(new LambdaQueryWrapper<SysUserDept>()
                        .in(SysUserDept::getDeptId, deptIds)
                        .select(SysUserDept::getUserId))
                .forEach(d -> {
                    if (d.getUserId() != null) {
                        ids.add(d.getUserId());
                    }
                });
        return ids;
    }

    private Set<Long> listUserIdsInCompany(Long companyId) {
        Set<Long> deptIds = new HashSet<>();
        for (var dept : deptService.list()) {
            if (Objects.equals(deptService.resolveCompanyId(dept.getId()), companyId)) {
                deptIds.add(dept.getId());
            }
        }
        return listUserIdsInDepts(new ArrayList<>(deptIds));
    }
}
