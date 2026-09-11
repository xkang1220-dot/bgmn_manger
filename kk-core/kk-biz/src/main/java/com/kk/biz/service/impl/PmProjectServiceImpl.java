package com.kk.biz.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kk.biz.entity.FinPool;
import com.kk.biz.entity.PmProject;
import com.kk.biz.entity.PmProjectFlow;
import com.kk.biz.entity.PmProjectMember;
import com.kk.biz.entity.PmTask;
import com.kk.biz.entity.PmTaskMember;
import com.kk.biz.entity.WfApproval;
import com.kk.biz.mapper.FinPoolMapper;
import com.kk.biz.mapper.PmProjectFlowMapper;
import com.kk.biz.mapper.PmProjectMapper;
import com.kk.biz.mapper.PmProjectMemberMapper;
import com.kk.biz.mapper.PmTaskMapper;
import com.kk.biz.mapper.PmTaskMemberMapper;
import com.kk.biz.mapper.WfApprovalMapper;
import com.kk.biz.service.PmProjectService;
import com.kk.biz.util.ProjectCodeUtil;
import com.kk.biz.workflow.ApprovalTypes;
import com.kk.biz.workflow.ProjectScales;
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
import java.util.ArrayList;
import java.util.HashMap;
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
    private final PmProjectFlowMapper flowMapper;
    private final WfApprovalMapper approvalMapper;
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
        project.setMembers(loadMembers(id));
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
        String scale = ProjectScales.normalize(project.getScale());
        project.setScale(scale);
        if (project.getApproveStatus() == null) {
            project.setApproveStatus(1);
        }
        // 项目管理侧不配置分钱：忽略预算/资金池；参与人可在创建时带入
        List<PmProjectMember> incomingMembers = project.getMembers();
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
        project.setCode(allocateNextCode(companyId));
        save(project);
        if (incomingMembers != null && !incomingMembers.isEmpty()) {
            syncCollaborationMembers(project.getId(), companyId, incomingMembers);
        }
        recordFlow(project.getId(), "CREATE", null, ProjectScales.label(scale), null,
                "创建项目（" + ProjectScales.label(scale) + "）");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateProject(PmProject project) {
        updateProjectMaybeScaleApproval(project);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String[] updateProjectMaybeScaleApproval(PmProject project) {
        if (project.getId() == null) {
            throw new BusinessException("项目 ID 不能为空");
        }
        PmProject existing = getById(project.getId());
        if (existing == null) {
            throw new BusinessException("项目不存在");
        }
        assertCanView(existing, StpUtil.getLoginIdAsLong());
        List<PmProjectMember> incomingMembers = project.getMembers();
        if (project.getOwnerId() != null) {
            assertUserInCompany(project.getOwnerId(), existing.getCompanyId());
        }

        String oldScale = StringUtils.hasText(existing.getScale()) ? existing.getScale() : ProjectScales.NORMAL;
        String requestedScale = project.getScale() != null ? ProjectScales.normalize(project.getScale()) : oldScale;
        boolean scaleChanged = !Objects.equals(oldScale, requestedScale);
        boolean scaleNeedsApproval = scaleChanged && ProjectScales.needsApproval(requestedScale);
        if (scaleNeedsApproval) {
            assertNoPendingScaleChange(existing.getId());
        }
        String applyScale = scaleNeedsApproval ? oldScale : requestedScale;

        Integer oldStatus = existing.getStatus();
        Integer newStatus = project.getStatus();
        boolean statusChanged = newStatus != null && !Objects.equals(oldStatus, newStatus);

        boolean metaChanged = !Objects.equals(nullToEmpty(existing.getName()), nullToEmpty(project.getName()))
                || !Objects.equals(existing.getOwnerId(), project.getOwnerId())
                || !Objects.equals(nullToEmpty(existing.getDescription()), nullToEmpty(project.getDescription()))
                || !Objects.equals(existing.getStartDate(), project.getStartDate())
                || !Objects.equals(existing.getEndDate(), project.getEndDate())
                || !Objects.equals(existing.getActualEndDate(), project.getActualEndDate());

        // 不可改公司/编号/财务；日期允许置空（updateById 默认跳过 null）
        lambdaUpdate()
                .eq(PmProject::getId, existing.getId())
                .set(PmProject::getName, project.getName())
                .set(PmProject::getOwnerId, project.getOwnerId())
                .set(PmProject::getStatus, project.getStatus())
                .set(PmProject::getScale, applyScale)
                .set(PmProject::getDescription, project.getDescription())
                .set(PmProject::getStartDate, project.getStartDate())
                .set(PmProject::getEndDate, project.getEndDate())
                .set(PmProject::getActualEndDate, project.getActualEndDate())
                .update();

        if (statusChanged) {
            recordFlow(existing.getId(), "STATUS",
                    statusLabel(oldStatus), statusLabel(newStatus), null, null);
        }
        if (scaleChanged && !scaleNeedsApproval) {
            recordFlow(existing.getId(), "SCALE",
                    ProjectScales.label(oldScale), ProjectScales.label(requestedScale), null, null);
        }
        if (metaChanged) {
            recordFlow(existing.getId(), "UPDATE", null, null, null, buildUpdateRemark(existing, project));
        }

        if (incomingMembers != null) {
            List<PmProjectMember> before = loadMembers(existing.getId());
            syncCollaborationMembers(existing.getId(), existing.getCompanyId(), incomingMembers);
            List<PmProjectMember> after = loadMembers(existing.getId());
            if (!memberSignature(before).equals(memberSignature(after))) {
                recordFlow(existing.getId(), "MEMBER",
                        memberSignature(before), memberSignature(after), null, null);
            }
        }

        if (scaleNeedsApproval) {
            return new String[]{oldScale, requestedScale};
        }
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyScaleChange(Long projectId, String toScale, Long approvalId) {
        applyScaleChange(projectId, toScale, approvalId, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyScaleChange(Long projectId, String toScale, Long approvalId, Long operatorId) {
        PmProject existing = getById(projectId);
        if (existing == null) {
            throw new BusinessException("项目不存在");
        }
        String from = StringUtils.hasText(existing.getScale()) ? existing.getScale() : ProjectScales.NORMAL;
        String to = ProjectScales.normalize(toScale);
        if (Objects.equals(from, to)) {
            return;
        }
        lambdaUpdate()
                .eq(PmProject::getId, projectId)
                .set(PmProject::getScale, to)
                .update();
        recordFlow(projectId, "SCALE",
                ProjectScales.label(from), ProjectScales.label(to), approvalId, null, operatorId);
    }

    @Override
    public void recordFlow(Long projectId, String action, String fromValue, String toValue,
                           Long approvalId, String remark) {
        recordFlow(projectId, action, fromValue, toValue, approvalId, remark, null);
    }

    @Override
    public void recordFlow(Long projectId, String action, String fromValue, String toValue,
                           Long approvalId, String remark, Long operatorId) {
        if (projectId == null || !StringUtils.hasText(action)) {
            return;
        }
        PmProjectFlow flow = new PmProjectFlow();
        flow.setProjectId(projectId);
        flow.setAction(action);
        flow.setFromValue(clip(fromValue, 255));
        flow.setToValue(clip(toValue, 255));
        flow.setApprovalId(approvalId);
        flow.setRemark(clip(remark, 500));
        if (operatorId != null) {
            flow.setCreateBy(operatorId);
            flow.setUpdateBy(operatorId);
        }
        flowMapper.insert(flow);
        if (operatorId != null && flow.getId() != null) {
            flowMapper.update(null, new LambdaUpdateWrapper<PmProjectFlow>()
                    .eq(PmProjectFlow::getId, flow.getId())
                    .set(PmProjectFlow::getCreateBy, operatorId)
                    .set(PmProjectFlow::getUpdateBy, operatorId));
        }
    }

    private void assertNoPendingScaleChange(Long projectId) {
        Long cnt = approvalMapper.selectCount(new LambdaQueryWrapper<WfApproval>()
                .eq(WfApproval::getProjectId, projectId)
                .eq(WfApproval::getType, ApprovalTypes.PROJECT_SCALE_CHANGE)
                .eq(WfApproval::getStatus, "PENDING"));
        if (cnt != null && cnt > 0) {
            throw new BusinessException("该项目已有进行中的规模变更审批，请先处理后再提交");
        }
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(0, max - 1)) + "…";
    }

    @Override
    public List<PmProjectFlow> listFlows(Long projectId) {
        PmProject project = loadProject(projectId);
        assertCanView(project, StpUtil.getLoginIdAsLong());
        List<PmProjectFlow> list = flowMapper.selectList(new LambdaQueryWrapper<PmProjectFlow>()
                .eq(PmProjectFlow::getProjectId, projectId)
                .orderByDesc(PmProjectFlow::getId));
        fillFlows(list);
        return list;
    }

    private void fillFlows(List<PmProjectFlow> flows) {
        if (flows == null || flows.isEmpty()) {
            return;
        }
        Set<Long> userIds = flows.stream()
                .map(PmProjectFlow::getCreateBy)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, SysUser> userMap = userIds.isEmpty() ? Map.of()
                : userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> u, (a, b) -> a));
        for (PmProjectFlow flow : flows) {
            SysUser op = userMap.get(flow.getCreateBy());
            if (op != null) {
                flow.setOperatorName(op.getNickname() != null ? op.getNickname() : op.getUsername());
            }
            flow.setActionLabel(projectActionLabel(flow.getAction()));
            flow.setSummary(buildProjectFlowSummary(flow));
        }
    }

    private static String projectActionLabel(String action) {
        if (action == null) {
            return "操作";
        }
        return switch (action) {
            case "CREATE" -> "创建项目";
            case "UPDATE" -> "编辑项目";
            case "DELETE" -> "删除项目";
            case "MEMBER" -> "变更成员";
            case "STATUS" -> "变更状态";
            case "SCALE" -> "变更规模";
            default -> action;
        };
    }

    private static String buildProjectFlowSummary(PmProjectFlow flow) {
        if (StringUtils.hasText(flow.getRemark())) {
            return flow.getRemark();
        }
        String action = flow.getAction();
        if ("CREATE".equals(action)) {
            return "创建了项目" + (StringUtils.hasText(flow.getToValue()) ? "（" + flow.getToValue() + "）" : "");
        }
        if ("DELETE".equals(action)) {
            return "删除了项目";
        }
        if ("STATUS".equals(action) || "SCALE".equals(action) || "MEMBER".equals(action)) {
            String from = StringUtils.hasText(flow.getFromValue()) ? flow.getFromValue() : "—";
            String to = StringUtils.hasText(flow.getToValue()) ? flow.getToValue() : "—";
            return from + " → " + to;
        }
        if ("UPDATE".equals(action)) {
            return StringUtils.hasText(flow.getRemark()) ? flow.getRemark() : "更新了项目信息";
        }
        return flow.getActionLabel() != null ? flow.getActionLabel() : "操作";
    }

    private static String statusLabel(Integer status) {
        if (status == null) {
            return "—";
        }
        return switch (status) {
            case 0 -> "筹备";
            case 1 -> "进行中";
            case 2 -> "已完成";
            case 3 -> "已关闭";
            default -> String.valueOf(status);
        };
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String buildUpdateRemark(PmProject before, PmProject after) {
        List<String> parts = new ArrayList<>();
        if (!Objects.equals(nullToEmpty(before.getName()), nullToEmpty(after.getName()))) {
            parts.add("名称");
        }
        if (!Objects.equals(before.getOwnerId(), after.getOwnerId())) {
            parts.add("负责人");
        }
        if (!Objects.equals(nullToEmpty(before.getDescription()), nullToEmpty(after.getDescription()))) {
            parts.add("说明");
        }
        if (!Objects.equals(before.getStartDate(), after.getStartDate())
                || !Objects.equals(before.getEndDate(), after.getEndDate())
                || !Objects.equals(before.getActualEndDate(), after.getActualEndDate())) {
            parts.add("日期");
        }
        if (parts.isEmpty()) {
            return "更新了项目信息";
        }
        return "更新了" + String.join("、", parts);
    }

    private static String memberSignature(List<PmProjectMember> members) {
        if (members == null || members.isEmpty()) {
            return "无";
        }
        return members.stream()
                .sorted((a, b) -> Long.compare(
                        a.getUserId() == null ? 0L : a.getUserId(),
                        b.getUserId() == null ? 0L : b.getUserId()))
                .map(m -> {
                    String name = m.getNickname() != null ? m.getNickname()
                            : (m.getUserName() != null ? m.getUserName() : String.valueOf(m.getUserId()));
                    String layer = m.getLayer() == null ? "" : m.getLayer();
                    return name + "(" + layer + ")";
                })
                .collect(Collectors.joining("、"));
    }

    /** 按公司名拼音首字母生成下一编号，如 XYGS-001 */
    @Override
    public String allocateNextCode(Long companyId) {
        if (companyId == null) {
            throw new BusinessException("缺少所属公司，无法生成编号");
        }
        SysDept company = deptService.getById(companyId);
        String prefix = ProjectCodeUtil.companyPrefix(company == null ? null : company.getName());
        List<PmProject> coded = list(new LambdaQueryWrapper<PmProject>()
                .eq(PmProject::getCompanyId, companyId)
                .likeRight(PmProject::getCode, prefix + "-")
                .select(PmProject::getCode));
        int maxSeq = 0;
        String marker = prefix + "-";
        for (PmProject p : coded) {
            if (p == null || !StringUtils.hasText(p.getCode()) || !p.getCode().startsWith(marker)) {
                continue;
            }
            String tail = p.getCode().substring(marker.length()).trim();
            if (tail.matches("\\d+")) {
                try {
                    maxSeq = Math.max(maxSeq, Integer.parseInt(tail));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        return ProjectCodeUtil.formatCode(prefix, maxSeq + 1);
    }

    @Override
    public Set<Long> eligibleTaskParticipantIds(Long projectId) {
        Set<Long> ids = new HashSet<>();
        if (projectId == null) {
            return ids;
        }
        PmProject project = getById(projectId);
        if (project == null) {
            return ids;
        }
        if (project.getOwnerId() != null) {
            ids.add(project.getOwnerId());
        }
        memberMapper.selectList(new LambdaQueryWrapper<PmProjectMember>()
                        .eq(PmProjectMember::getProjectId, projectId)
                        .select(PmProjectMember::getUserId))
                .stream()
                .map(PmProjectMember::getUserId)
                .filter(Objects::nonNull)
                .forEach(ids::add);
        return ids;
    }

    /** 同步项目参与人：职责必填；保留已有分成比例 */
    private void syncCollaborationMembers(Long projectId, Long companyId, List<PmProjectMember> incoming) {
        Map<Long, PmProjectMember> existingByUser = loadMembers(projectId).stream()
                .filter(m -> m.getUserId() != null)
                .collect(Collectors.toMap(PmProjectMember::getUserId, m -> m, (a, b) -> a));
        List<PmProjectMember> next = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (PmProjectMember member : incoming) {
            if (member == null || member.getUserId() == null || !seen.add(member.getUserId())) {
                continue;
            }
            assertUserInCompany(member.getUserId(), companyId);
            String duty = member.getLayer() == null ? "" : member.getLayer().trim();
            if (!StringUtils.hasText(duty)) {
                throw new BusinessException("请填写项目参与人职责");
            }
            if (duty.length() > 64) {
                throw new BusinessException("参与人职责不能超过 64 字");
            }
            PmProjectMember row = new PmProjectMember();
            row.setProjectId(projectId);
            row.setUserId(member.getUserId());
            row.setLayer(duty);
            PmProjectMember old = existingByUser.get(member.getUserId());
            if (old != null) {
                row.setPercent(old.getPercent() == null ? BigDecimal.ZERO : old.getPercent());
                row.setRemark(old.getRemark());
            } else {
                row.setPercent(BigDecimal.ZERO);
                row.setRemark(member.getRemark());
            }
            next.add(row);
        }
        saveMembers(projectId, next);
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

    /** 全局 admin 看全部；否则按公司分别套 data_scope，再匹配负责人/创建人/相关成员 */
    private void applyVisibleScope(LambdaQueryWrapper<PmProject> wrapper, Long userId) {
        if (dataScopeService.isGlobalAdmin(userId)) {
            return;
        }
        Set<Long> companies = dataScopeService.visibleCompanyIds(userId);
        if (companies.isEmpty()) {
            wrapper.eq(PmProject::getId, -1L);
            return;
        }
        wrapper.and(outer -> {
            boolean any = false;
            for (Long companyId : companies) {
                if (dataScopeService.hasRoleInCompany(userId, "control", companyId)) {
                    outer.or().eq(PmProject::getCompanyId, companyId);
                    any = true;
                    continue;
                }
                Set<Long> visibleUserIds = dataScopeService.visibleUserIdsInCompany(userId, companyId);
                if (visibleUserIds.isEmpty()) {
                    continue;
                }
                Set<Long> relatedIds = relatedProjectIdsByUsers(visibleUserIds, companyId);
                outer.or(w -> {
                    w.eq(PmProject::getCompanyId, companyId).and(inner -> {
                        inner.in(PmProject::getOwnerId, visibleUserIds)
                                .or().in(PmProject::getCreateBy, visibleUserIds);
                        if (!relatedIds.isEmpty()) {
                            inner.or().in(PmProject::getId, relatedIds);
                        }
                    });
                });
                any = true;
            }
            if (!any) {
                outer.eq(PmProject::getId, -1L);
            }
        });
    }

    private void assertCanView(PmProject project, Long userId) {
        if (dataScopeService.isGlobalAdmin(userId)) {
            return;
        }
        Long companyId = project.getCompanyId();
        Set<Long> companies = dataScopeService.visibleCompanyIds(userId);
        if (companyId == null || !companies.contains(companyId)) {
            throw new BusinessException("无权查看该项目");
        }
        if (dataScopeService.hasRoleInCompany(userId, "control", companyId)) {
            return;
        }
        Set<Long> visibleUserIds = dataScopeService.visibleUserIdsInCompany(userId, companyId);
        Long ownerId = project.getOwnerId();
        Long createBy = project.getCreateBy();
        if ((ownerId != null && visibleUserIds.contains(ownerId))
                || (createBy != null && visibleUserIds.contains(createBy))) {
            return;
        }
        if (project.getId() != null && relatedProjectIdsByUsers(visibleUserIds, companyId).contains(project.getId())) {
            return;
        }
        throw new BusinessException("无权查看该项目");
    }

    private Set<Long> relatedProjectIdsByUsers(Set<Long> userIds, Long companyId) {
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

        LambdaQueryWrapper<PmTask> taskByCreator = new LambdaQueryWrapper<PmTask>()
                .in(PmTask::getCreateBy, userIds)
                .select(PmTask::getProjectId);
        if (companyId != null) {
            taskByCreator.eq(PmTask::getCompanyId, companyId);
        }
        taskMapper.selectList(taskByCreator)
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
            LambdaQueryWrapper<PmTask> taskByMember = new LambdaQueryWrapper<PmTask>()
                    .in(PmTask::getId, taskIds)
                    .select(PmTask::getProjectId);
            if (companyId != null) {
                taskByMember.eq(PmTask::getCompanyId, companyId);
            }
            taskMapper.selectList(taskByMember)
                    .stream()
                    .map(PmTask::getProjectId)
                    .filter(Objects::nonNull)
                    .forEach(ids::add);
        }
        if (companyId != null && !ids.isEmpty()) {
            Set<Long> inCompany = list(new LambdaQueryWrapper<PmProject>()
                    .in(PmProject::getId, ids)
                    .eq(PmProject::getCompanyId, companyId)
                    .select(PmProject::getId))
                    .stream()
                    .map(PmProject::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            return inCompany;
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
        Set<Long> projectIds = new HashSet<>();
        for (PmProject project : projects) {
            if (project.getId() != null) {
                projectIds.add(project.getId());
            }
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
        Map<Long, List<PmProjectMember>> membersByProject = new HashMap<>();
        Set<Long> memberUserIds = new HashSet<>();
        if (!projectIds.isEmpty()) {
            for (PmProjectMember member : memberMapper.selectList(new LambdaQueryWrapper<PmProjectMember>()
                    .in(PmProjectMember::getProjectId, projectIds)
                    .orderByAsc(PmProjectMember::getId))) {
                membersByProject.computeIfAbsent(member.getProjectId(), k -> new ArrayList<>()).add(member);
                if (member.getUserId() != null) {
                    memberUserIds.add(member.getUserId());
                }
            }
        }
        Set<Long> allUserIds = new HashSet<>(ownerIds);
        allUserIds.addAll(memberUserIds);
        Map<Long, SysUser> userMap = allUserIds.isEmpty() ? Map.of()
                : userService.listByIds(allUserIds).stream()
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
            List<PmProjectMember> members = membersByProject.getOrDefault(project.getId(), List.of());
            if (members.isEmpty()) {
                project.setParticipantNames(List.of());
            } else {
                List<String> names = new ArrayList<>(members.size());
                for (PmProjectMember member : members) {
                    SysUser user = userMap.get(member.getUserId());
                    String name = user == null ? null
                            : (user.getNickname() != null ? user.getNickname() : user.getUsername());
                    if (!StringUtils.hasText(name)) {
                        continue;
                    }
                    if (StringUtils.hasText(member.getLayer())) {
                        names.add(name + "（" + member.getLayer().trim() + "）");
                    } else {
                        names.add(name);
                    }
                }
                project.setParticipantNames(names);
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
