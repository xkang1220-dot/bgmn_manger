package com.kk.biz.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kk.biz.entity.FinPool;
import com.kk.biz.entity.PmProject;
import com.kk.biz.entity.PmProjectMember;
import com.kk.biz.entity.PmTask;
import com.kk.biz.entity.PmTaskMember;
import com.kk.biz.mapper.FinPoolMapper;
import com.kk.biz.mapper.PmProjectMapper;
import com.kk.biz.mapper.PmProjectMemberMapper;
import com.kk.biz.mapper.PmTaskMapper;
import com.kk.biz.mapper.PmTaskMemberMapper;
import com.kk.biz.service.PmProjectService;
import com.kk.common.exception.BusinessException;
import com.kk.system.entity.SysDept;
import com.kk.system.entity.SysUser;
import com.kk.system.service.DataScopeService;
import com.kk.system.service.SysDeptService;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PmProjectServiceImpl extends ServiceImpl<PmProjectMapper, PmProject> implements PmProjectService {

    private final PmProjectMemberMapper memberMapper;
    private final PmTaskMapper taskMapper;
    private final PmTaskMemberMapper taskMemberMapper;
    private final SysUserService userService;
    private final SysDeptService deptService;
    private final DataScopeService dataScopeService;
    private final FinPoolMapper poolMapper;

    @Override
    public Page<PmProject> pageProjects(long page, long pageSize, String name, Integer status) {
        long userId = StpUtil.getLoginIdAsLong();
        LambdaQueryWrapper<PmProject> wrapper = new LambdaQueryWrapper<PmProject>()
                .like(StringUtils.hasText(name), PmProject::getName, name)
                .eq(status != null, PmProject::getStatus, status)
                .and(w -> w.isNull(PmProject::getApproveStatus).or().eq(PmProject::getApproveStatus, 1));
        applyVisibleScope(wrapper, userId);
        wrapper.orderByDesc(PmProject::getId);
        Page<PmProject> result = page(new Page<>(page, pageSize), wrapper);
        List<PmProject> records = result.getRecords() == null ? List.of() : result.getRecords();
        fillExtras(records);
        // 项目管理接口始终隐藏财务字段，完整数据走 /finance/project-share
        records.forEach(this::maskFinanceFields);
        result.setRecords(records);
        return result;
    }

    @Override
    public List<PmProject> listMine(Long userId) {
        if (userId == null) {
            return List.of();
        }
        LambdaQueryWrapper<PmProject> wrapper = new LambdaQueryWrapper<PmProject>()
                .and(w -> w.isNull(PmProject::getApproveStatus).or().eq(PmProject::getApproveStatus, 1));
        applyVisibleScope(wrapper, userId);
        wrapper.orderByDesc(PmProject::getId);
        List<PmProject> list = list(wrapper);
        fillExtras(list);
        list.forEach(this::maskFinanceFields);
        return list;
    }

    @Override
    public List<PmProject> listVisible() {
        return listMine(StpUtil.getLoginIdAsLong());
    }

    @Override
    public List<PmProject> listApproved() {
        long userId = StpUtil.getLoginIdAsLong();
        LambdaQueryWrapper<PmProject> wrapper = new LambdaQueryWrapper<PmProject>()
                .and(w -> w.isNull(PmProject::getApproveStatus).or().eq(PmProject::getApproveStatus, 1));
        applyVisibleScope(wrapper, userId);
        wrapper.orderByDesc(PmProject::getId);
        List<PmProject> list = list(wrapper);
        fillExtras(list);
        list.forEach(this::maskFinanceFields);
        return list;
    }

    @Override
    public PmProject getDetail(Long id) {
        PmProject project = loadProject(id);
        assertCanView(project, StpUtil.getLoginIdAsLong());
        fillExtras(List.of(project));
        maskFinanceFields(project);
        return project;
    }

    @Override
    public PmProject getShareDetail(Long id) {
        PmProject project = loadProject(id);
        assertCanView(project, StpUtil.getLoginIdAsLong());
        fillExtras(List.of(project));
        project.setMembers(loadMembers(id));
        return project;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createProject(PmProject project) {
        if (project.getStatus() == null) {
            project.setStatus(1);
        }
        if (project.getSettledAmount() == null) {
            project.setSettledAmount(BigDecimal.ZERO);
        }
        // 项目管理侧不配置分钱：忽略成员/预算/资金池，由财务「项目分层」页设置
        project.setMembers(null);
        project.setPoolId(null);
        project.setBudget(BigDecimal.ZERO);
        project.setSettledAmount(BigDecimal.ZERO);
        Long ownerId = project.getOwnerId() != null ? project.getOwnerId() : StpUtil.getLoginIdAsLong();
        project.setOwnerId(ownerId);
        Long companyId = project.getCompanyId();
        if (companyId == null) {
            throw new BusinessException("请选择所属公司");
        }
        long loginId = StpUtil.getLoginIdAsLong();
        if (!dataScopeService.isGlobalAdmin(loginId)
                && !dataScopeService.visibleCompanyIds(loginId).contains(companyId)) {
            throw new BusinessException("不能跨公司创建项目");
        }
        assertUserInCompany(ownerId, companyId);
        project.setCompanyId(companyId);
        save(project);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateProject(PmProject project) {
        if (project.getId() == null) {
            throw new BusinessException("项目 ID 不能为空");
        }
        PmProject existing = getById(project.getId());
        if (existing == null) {
            throw new BusinessException("项目不存在");
        }
        assertCanView(existing, StpUtil.getLoginIdAsLong());
        // 项目管理侧不可改财务字段与公司
        project.setPoolId(null);
        project.setBudget(null);
        project.setSettledAmount(null);
        project.setMembers(null);
        project.setCompanyId(null);
        if (project.getOwnerId() != null) {
            assertUserInCompany(project.getOwnerId(), existing.getCompanyId());
        }
        updateById(project);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveShareConfig(Long projectId, Long poolId, BigDecimal budget, List<PmProjectMember> members) {
        if (projectId == null) {
            throw new BusinessException("项目 ID 不能为空");
        }
        PmProject project = getById(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        Long companyId = project.getCompanyId();
        if (companyId == null) {
            throw new BusinessException("项目缺少所属公司，无法配置分成");
        }
        validateMembers(members, companyId);
        if (poolId != null) {
            FinPool pool = poolMapper.selectById(poolId);
            if (pool == null) {
                throw new BusinessException("资金池不存在");
            }
            if (pool.getCompanyId() != null && !Objects.equals(pool.getCompanyId(), companyId)) {
                throw new BusinessException("资金池与项目不属于同一公司");
            }
        }
        PmProject update = new PmProject();
        update.setId(projectId);
        update.setPoolId(poolId);
        update.setBudget(budget == null ? BigDecimal.ZERO : budget);
        updateById(update);
        saveMembers(projectId, members);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteProject(Long id) {
        removeById(id);
        memberMapper.delete(new LambdaQueryWrapper<PmProjectMember>().eq(PmProjectMember::getProjectId, id));
    }

    private PmProject loadProject(Long id) {
        PmProject project = getById(id);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        return project;
    }

    private List<PmProjectMember> loadMembers(Long projectId) {
        List<PmProjectMember> members = memberMapper.selectList(new LambdaQueryWrapper<PmProjectMember>()
                .eq(PmProjectMember::getProjectId, projectId)
                .orderByAsc(PmProjectMember::getId));
        fillMembers(members);
        return members;
    }

    private void validateMembers(List<PmProjectMember> members, Long companyId) {
        if (members == null || members.isEmpty()) {
            throw new BusinessException("请至少添加一名分成参与人");
        }
        Set<Long> userIds = new HashSet<>();
        for (PmProjectMember member : members) {
            if (member.getUserId() == null) {
                throw new BusinessException("分成参与人不能为空");
            }
            if (!userIds.add(member.getUserId())) {
                throw new BusinessException("分成参与人不能重复");
            }
            assertUserInCompany(member.getUserId(), companyId);
        }
        BigDecimal sum = members.stream()
                .map(m -> m.getPercent() == null ? BigDecimal.ZERO : m.getPercent())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(new BigDecimal("100")) != 0) {
            throw new BusinessException("参与人分成合计必须为 100%，当前为 " + sum + "%");
        }
    }

    void assertUserInCompany(Long userId, Long companyId) {
        if (userId == null) {
            return;
        }
        if (companyId == null) {
            throw new BusinessException("缺少所属公司，无法校验参与人");
        }
        if (dataScopeService.isGlobalAdmin(userId)) {
            return;
        }
        if (!dataScopeService.visibleCompanyIds(userId).contains(companyId)) {
            throw new BusinessException("不能跨公司添加参与人/负责人");
        }
    }

    private void saveMembers(Long projectId, List<PmProjectMember> members) {
        memberMapper.delete(new LambdaQueryWrapper<PmProjectMember>().eq(PmProjectMember::getProjectId, projectId));
        for (PmProjectMember member : members) {
            member.setId(null);
            member.setProjectId(projectId);
            memberMapper.insert(member);
        }
    }

    private void maskFinanceFields(PmProject project) {
        project.setPoolId(null);
        project.setPoolName(null);
        project.setBudget(null);
        project.setSettledAmount(null);
        project.setReserveAmount(null);
        project.setExpensePercent(null);
        project.setReservePercent(null);
        project.setSettlePercent(null);
        project.setMembers(null);
    }

    /** 全局 admin 看全部；否则限定可见公司 + 数据范围内相关人员 */
    private void applyVisibleScope(LambdaQueryWrapper<PmProject> wrapper, Long userId) {
        if (dataScopeService.isGlobalAdmin(userId)) {
            return;
        }
        Set<Long> companies = dataScopeService.visibleCompanyIds(userId);
        Set<Long> visibleUserIds = dataScopeService.visibleUserIds(userId);
        if (companies.isEmpty() || visibleUserIds.isEmpty()) {
            wrapper.eq(PmProject::getId, -1L);
            return;
        }
        Set<Long> relatedIds = relatedProjectIdsByUsers(visibleUserIds);
        wrapper.in(PmProject::getCompanyId, companies).and(w -> {
            w.in(PmProject::getOwnerId, visibleUserIds)
                    .or().in(PmProject::getCreateBy, visibleUserIds);
            if (!relatedIds.isEmpty()) {
                w.or().in(PmProject::getId, relatedIds);
            }
        });
    }

    private void assertCanView(PmProject project, Long userId) {
        if (dataScopeService.isGlobalAdmin(userId)) {
            return;
        }
        Set<Long> companies = dataScopeService.visibleCompanyIds(userId);
        if (project.getCompanyId() != null && !companies.contains(project.getCompanyId())) {
            throw new BusinessException("无权查看该项目");
        }
        Set<Long> visibleUserIds = dataScopeService.visibleUserIds(userId);
        Long ownerId = project.getOwnerId();
        Long createBy = project.getCreateBy();
        if ((ownerId != null && visibleUserIds.contains(ownerId))
                || (createBy != null && visibleUserIds.contains(createBy))) {
            return;
        }
        if (project.getId() != null && relatedProjectIdsByUsers(visibleUserIds).contains(project.getId())) {
            return;
        }
        throw new BusinessException("无权查看该项目");
    }

    private Set<Long> relatedProjectIdsByUsers(Set<Long> userIds) {
        Set<Long> ids = new HashSet<>();
        if (userIds == null || userIds.isEmpty()) {
            return ids;
        }
        memberMapper.selectList(new LambdaQueryWrapper<PmProjectMember>()
                        .in(PmProjectMember::getUserId, userIds)
                        .select(PmProjectMember::getProjectId))
                .stream()
                .map(PmProjectMember::getProjectId)
                .filter(Objects::nonNull)
                .forEach(ids::add);

        taskMapper.selectList(new LambdaQueryWrapper<PmTask>()
                        .in(PmTask::getCreateBy, userIds)
                        .select(PmTask::getProjectId))
                .stream()
                .map(PmTask::getProjectId)
                .filter(Objects::nonNull)
                .forEach(ids::add);

        Set<Long> taskIds = taskMemberMapper.selectList(new LambdaQueryWrapper<PmTaskMember>()
                        .in(PmTaskMember::getUserId, userIds)
                        .select(PmTaskMember::getTaskId))
                .stream()
                .map(PmTaskMember::getTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (!taskIds.isEmpty()) {
            taskMapper.selectList(new LambdaQueryWrapper<PmTask>()
                            .in(PmTask::getId, taskIds)
                            .select(PmTask::getProjectId))
                    .stream()
                    .map(PmTask::getProjectId)
                    .filter(Objects::nonNull)
                    .forEach(ids::add);
        }
        return ids;
    }

    private void fillExtras(List<PmProject> projects) {
        if (projects == null || projects.isEmpty()) {
            return;
        }
        Set<Long> ownerIds = new HashSet<>();
        Set<Long> poolIds = new HashSet<>();
        Set<Long> companyIds = new HashSet<>();
        for (PmProject project : projects) {
            if (project.getOwnerId() != null) {
                ownerIds.add(project.getOwnerId());
            }
            if (project.getPoolId() != null) {
                poolIds.add(project.getPoolId());
            }
            if (project.getCompanyId() != null) {
                companyIds.add(project.getCompanyId());
            }
        }
        Map<Long, SysUser> userMap = ownerIds.isEmpty() ? Map.of()
                : userService.listByIds(ownerIds).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> u, (a, b) -> a));
        Map<Long, FinPool> poolMap = poolIds.isEmpty() ? Map.of()
                : poolMapper.selectList(new LambdaQueryWrapper<FinPool>().in(FinPool::getId, poolIds)).stream()
                .collect(Collectors.toMap(FinPool::getId, p -> p, (a, b) -> a));
        Map<Long, String> companyNameMap = companyIds.isEmpty() ? Map.of()
                : deptService.listByIds(companyIds).stream()
                .collect(Collectors.toMap(SysDept::getId, SysDept::getName, (a, b) -> a));
        for (PmProject project : projects) {
            if (project.getOwnerId() != null) {
                SysUser owner = userMap.get(project.getOwnerId());
                if (owner != null) {
                    project.setOwnerName(owner.getNickname() != null ? owner.getNickname() : owner.getUsername());
                }
            }
            if (project.getPoolId() != null) {
                FinPool pool = poolMap.get(project.getPoolId());
                if (pool != null) {
                    project.setPoolName(pool.getName());
                }
            }
            if (project.getCompanyId() != null) {
                project.setCompanyName(companyNameMap.get(project.getCompanyId()));
            }
        }
    }

    private void fillMembers(List<PmProjectMember> members) {
        if (members == null || members.isEmpty()) {
            return;
        }
        Set<Long> userIds = members.stream()
                .map(PmProjectMember::getUserId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        Map<Long, SysUser> userMap = userIds.isEmpty() ? Map.of()
                : userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> u, (a, b) -> a));
        for (PmProjectMember member : members) {
            SysUser user = userMap.get(member.getUserId());
            if (user != null) {
                member.setUserName(user.getUsername());
                member.setNickname(user.getNickname());
            }
        }
    }
}
