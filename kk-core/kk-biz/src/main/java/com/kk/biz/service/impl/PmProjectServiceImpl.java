package com.kk.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kk.biz.entity.FinPool;
import com.kk.biz.entity.PmProject;
import com.kk.biz.entity.PmProjectMember;
import com.kk.biz.mapper.FinPoolMapper;
import com.kk.biz.mapper.PmProjectMapper;
import com.kk.biz.mapper.PmProjectMemberMapper;
import com.kk.biz.service.PmProjectService;
import com.kk.common.exception.BusinessException;
import com.kk.system.entity.SysUser;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PmProjectServiceImpl extends ServiceImpl<PmProjectMapper, PmProject> implements PmProjectService {

    private final PmProjectMemberMapper memberMapper;
    private final SysUserService userService;
    private final FinPoolMapper poolMapper;

    @Override
    public Page<PmProject> pageProjects(long page, long pageSize, String name, Integer status) {
        Page<PmProject> result = page(new Page<>(page, pageSize), new LambdaQueryWrapper<PmProject>()
                .like(StringUtils.hasText(name), PmProject::getName, name)
                .eq(status != null, PmProject::getStatus, status)
                .and(w -> w.isNull(PmProject::getApproveStatus).or().eq(PmProject::getApproveStatus, 1))
                .orderByDesc(PmProject::getId));
        fillExtras(result.getRecords());
        // 项目管理接口始终隐藏财务字段，完整数据走 /finance/project-share
        result.getRecords().forEach(this::maskFinanceFields);
        return result;
    }

    @Override
    public List<PmProject> listMine(Long userId) {
        if (userId == null) {
            return List.of();
        }
        List<String> roles = userService.getRoleCodes(userId);
        boolean seeAll = roles.contains("shareholder") || roles.contains("admin");
        List<PmProject> list;
        if (seeAll) {
            // 股东 / 管理员：个人中心可看全部已生效项目
            list = list(new LambdaQueryWrapper<PmProject>()
                    .and(w -> w.isNull(PmProject::getApproveStatus).or().eq(PmProject::getApproveStatus, 1))
                    .orderByDesc(PmProject::getId));
        } else {
            Set<Long> memberProjectIds = memberMapper.selectList(new LambdaQueryWrapper<PmProjectMember>()
                            .eq(PmProjectMember::getUserId, userId))
                    .stream()
                    .map(PmProjectMember::getProjectId)
                    .filter(id -> id != null)
                    .collect(Collectors.toSet());
            list = list(new LambdaQueryWrapper<PmProject>()
                    .and(w -> {
                        w.eq(PmProject::getOwnerId, userId);
                        if (!memberProjectIds.isEmpty()) {
                            w.or().in(PmProject::getId, memberProjectIds);
                        }
                    })
                    .and(w -> w.isNull(PmProject::getApproveStatus).or().eq(PmProject::getApproveStatus, 1))
                    .orderByDesc(PmProject::getId));
        }
        fillExtras(list);
        list.forEach(this::maskFinanceFields);
        return list;
    }

    @Override
    public PmProject getDetail(Long id) {
        PmProject project = loadProject(id);
        fillExtras(List.of(project));
        maskFinanceFields(project);
        return project;
    }

    @Override
    public PmProject getShareDetail(Long id) {
        PmProject project = loadProject(id);
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
        // 项目管理侧不可改财务字段
        project.setPoolId(null);
        project.setBudget(null);
        project.setSettledAmount(null);
        project.setMembers(null);
        updateById(project);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveShareConfig(Long projectId, Long poolId, BigDecimal budget, List<PmProjectMember> members) {
        if (projectId == null) {
            throw new BusinessException("项目 ID 不能为空");
        }
        if (getById(projectId) == null) {
            throw new BusinessException("项目不存在");
        }
        validateMembers(members);
        if (poolId != null && poolMapper.selectById(poolId) == null) {
            throw new BusinessException("资金池不存在");
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

    private void validateMembers(List<PmProjectMember> members) {
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
        }
        BigDecimal sum = members.stream()
                .map(m -> m.getPercent() == null ? BigDecimal.ZERO : m.getPercent())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(new BigDecimal("100")) != 0) {
            throw new BusinessException("参与人分成合计必须为 100%，当前为 " + sum + "%");
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

    private void fillExtras(List<PmProject> projects) {
        if (projects == null || projects.isEmpty()) {
            return;
        }
        Set<Long> ownerIds = new HashSet<>();
        Set<Long> poolIds = new HashSet<>();
        for (PmProject project : projects) {
            if (project.getOwnerId() != null) {
                ownerIds.add(project.getOwnerId());
            }
            if (project.getPoolId() != null) {
                poolIds.add(project.getPoolId());
            }
        }
        Map<Long, SysUser> userMap = ownerIds.isEmpty() ? Map.of()
                : userService.listByIds(ownerIds).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> u, (a, b) -> a));
        Map<Long, FinPool> poolMap = poolIds.isEmpty() ? Map.of()
                : poolMapper.selectList(new LambdaQueryWrapper<FinPool>().in(FinPool::getId, poolIds)).stream()
                .collect(Collectors.toMap(FinPool::getId, p -> p, (a, b) -> a));
        for (PmProject project : projects) {
            SysUser owner = userMap.get(project.getOwnerId());
            if (owner != null) {
                project.setOwnerName(owner.getNickname() != null ? owner.getNickname() : owner.getUsername());
            }
            FinPool pool = poolMap.get(project.getPoolId());
            if (pool != null) {
                project.setPoolName(pool.getName());
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
