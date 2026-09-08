package com.kk.biz.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kk.biz.entity.WfApprovalFlow;
import com.kk.biz.mapper.WfApprovalFlowMapper;
import com.kk.biz.service.WfApprovalFlowService;
import com.kk.biz.workflow.ApprovalTypes;
import com.kk.common.exception.BusinessException;
import com.kk.system.entity.SysDept;
import com.kk.system.entity.SysUser;
import com.kk.system.service.DataScopeService;
import com.kk.system.service.SysDeptService;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WfApprovalFlowServiceImpl extends ServiceImpl<WfApprovalFlowMapper, WfApprovalFlow>
        implements WfApprovalFlowService {

    private final DataScopeService dataScopeService;
    private final SysDeptService deptService;
    private final SysUserService userService;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<WfApprovalFlow> listByCompany(Long companyId) {
        if (companyId == null) {
            throw new BusinessException("请选择所属公司");
        }
        assertCanManageCompany(companyId);
        List<WfApprovalFlow> list = list(new LambdaQueryWrapper<WfApprovalFlow>()
                .eq(WfApprovalFlow::getCompanyId, companyId)
                .orderByAsc(WfApprovalFlow::getSort)
                .orderByAsc(WfApprovalFlow::getId));
        fillCompanyNames(list);
        list.forEach(this::fillView);
        return list;
    }

    @Override
    public WfApprovalFlow getByType(String type, Long companyId) {
        if (!StringUtils.hasText(type) || companyId == null) {
            return null;
        }
        WfApprovalFlow flow = getOne(new LambdaQueryWrapper<WfApprovalFlow>()
                .eq(WfApprovalFlow::getType, type.trim().toUpperCase())
                .eq(WfApprovalFlow::getCompanyId, companyId)
                .last("LIMIT 1"));
        if (flow != null) {
            fillView(flow);
            fillCompanyNames(List.of(flow));
        }
        return flow;
    }

    @Override
    public WfApprovalFlow requireEnabled(String type, Long companyId) {
        if (companyId == null) {
            throw new BusinessException("请选择所属公司");
        }
        WfApprovalFlow flow = getByType(type, companyId);
        if (flow == null) {
            String label = ApprovalTypes.label(type);
            throw new BusinessException("该公司未配置审批类型「" + label + "」，请先在审批配置中按公司添加");
        }
        if (flow.getStatus() != null && flow.getStatus() == 0) {
            throw new BusinessException("审批类型「" + flow.getName() + "」已停用，请先在审批配置中启用");
        }
        fillView(flow);
        return flow;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveFlow(WfApprovalFlow flow) {
        if (flow == null || !StringUtils.hasText(flow.getType())) {
            throw new BusinessException("审批类型不能为空");
        }
        if (flow.getCompanyId() == null) {
            throw new BusinessException("请选择所属公司");
        }
        assertCanManageCompany(flow.getCompanyId());
        flow.setType(flow.getType().trim().toUpperCase());
        if (!flow.getType().matches("^[A-Z][A-Z0-9_]*$")) {
            throw new BusinessException("类型编码需大写字母开头，仅含大写字母/数字/下划线");
        }
        if (!StringUtils.hasText(flow.getName())) {
            flow.setName(ApprovalTypes.label(flow.getType()));
        }
        String passMode = StringUtils.hasText(flow.getPassMode()) ? flow.getPassMode().toUpperCase() : "ALL";
        if (!List.of("ALL", "ANY").contains(passMode)) {
            throw new BusinessException("通过方式仅支持会签(ALL)或或签(ANY)");
        }
        flow.setPassMode(passMode);
        if (flow.getUserIdList() != null) {
            flow.setUserIds(flow.getUserIdList().stream()
                    .filter(id -> id != null && id > 0)
                    .distinct()
                    .map(String::valueOf)
                    .collect(Collectors.joining(",")));
        }
        if (flow.getRoleCodeList() != null) {
            flow.setRoleCodes(flow.getRoleCodeList().stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .distinct()
                    .collect(Collectors.joining(",")));
        }
        if (!StringUtils.hasText(flow.getRoleCodes()) && !StringUtils.hasText(flow.getUserIds())) {
            throw new BusinessException("请至少选择一个审批角色或指定审批人");
        }
        validateAssigneesInCompany(flow);
        if (flow.getTimeoutHours() == null || flow.getTimeoutHours() < 0) {
            flow.setTimeoutHours(0);
        }
        if (flow.getStatus() == null) {
            flow.setStatus(1);
        }

        WfApprovalFlow exist = getByType(flow.getType(), flow.getCompanyId());
        if (exist != null) {
            if (flow.getId() == null) {
                throw new BusinessException("该公司已存在该审批类型，请直接编辑");
            }
            if (!exist.getId().equals(flow.getId())) {
                throw new BusinessException("审批类型编码冲突");
            }
            WfApprovalFlow db = getById(flow.getId());
            if (db == null || !Objects.equals(db.getCompanyId(), flow.getCompanyId())) {
                throw new BusinessException("不能修改所属公司");
            }
            flow.setCompanyId(db.getCompanyId());
            updateById(flow);
            return;
        }

        if (flow.getId() != null) {
            WfApprovalFlow db = getById(flow.getId());
            if (db == null) {
                throw new BusinessException("配置不存在");
            }
            assertCanManageCompany(db.getCompanyId());
            if (!Objects.equals(db.getCompanyId(), flow.getCompanyId())) {
                throw new BusinessException("不能修改所属公司");
            }
            updateById(flow);
            return;
        }

        // 软删行仍占唯一键：先物理清掉再新建
        Long tombId = findAnyIdByTypeCompany(flow.getType(), flow.getCompanyId());
        if (tombId != null) {
            physicalDeleteById(tombId);
        }
        save(flow);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteFlow(Long id) {
        WfApprovalFlow flow = getById(id);
        if (flow == null) {
            throw new BusinessException("配置不存在");
        }
        assertCanManageCompany(flow.getCompanyId());
        // 物理删除，避免 uk_type_company 被软删行占住导致无法再建
        physicalDeleteById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int copyFlows(Long fromCompanyId, Long toCompanyId) {
        if (fromCompanyId == null || toCompanyId == null) {
            throw new BusinessException("请选择源公司和目标公司");
        }
        if (Objects.equals(fromCompanyId, toCompanyId)) {
            throw new BusinessException("源公司与目标公司不能相同");
        }
        assertCanManageCompany(fromCompanyId);
        assertCanManageCompany(toCompanyId);
        List<WfApprovalFlow> source = list(new LambdaQueryWrapper<WfApprovalFlow>()
                .eq(WfApprovalFlow::getCompanyId, fromCompanyId)
                .eq(WfApprovalFlow::getStatus, 1)
                .orderByAsc(WfApprovalFlow::getSort)
                .orderByAsc(WfApprovalFlow::getId));
        int copied = 0;
        for (WfApprovalFlow src : source) {
            WfApprovalFlow exist = getByType(src.getType(), toCompanyId);
            if (exist != null) {
                continue;
            }
            WfApprovalFlow neo = new WfApprovalFlow();
            neo.setCompanyId(toCompanyId);
            neo.setType(src.getType());
            neo.setName(src.getName());
            neo.setPassMode(src.getPassMode());
            neo.setRoleCodes(src.getRoleCodes());
            neo.setUserIds(null);
            neo.setTimeoutHours(src.getTimeoutHours());
            neo.setStatus(src.getStatus());
            neo.setSort(src.getSort());
            neo.setRemark(src.getRemark());
            Long tombId = findAnyIdByTypeCompany(src.getType(), toCompanyId);
            if (tombId != null) {
                physicalDeleteById(tombId);
            }
            save(neo);
            copied++;
        }
        return copied;
    }

    private Long findAnyIdByTypeCompany(String type, Long companyId) {
        List<Long> ids = jdbcTemplate.query(
                "SELECT id FROM wf_approval_flow WHERE type = ? AND company_id = ? AND deleted = 1 ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> rs.getLong(1),
                type, companyId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void physicalDeleteById(Long id) {
        jdbcTemplate.update("DELETE FROM wf_approval_flow WHERE id = ?", id);
    }

    @Override
    public List<Long> resolveAssigneeIds(WfApprovalFlow flow, Long companyId) {
        Set<Long> ids = new LinkedHashSet<>();
        if (flow != null && StringUtils.hasText(flow.getRoleCodes())) {
            for (String code : flow.getRoleCodes().split(",")) {
                if (!StringUtils.hasText(code)) {
                    continue;
                }
                ids.addAll(dataScopeService.listUserIdsByRoleCodeInCompany(code.trim(), companyId));
            }
        }
        if (flow != null && StringUtils.hasText(flow.getUserIds())) {
            for (String part : flow.getUserIds().split(",")) {
                if (!StringUtils.hasText(part)) {
                    continue;
                }
                try {
                    Long uid = Long.parseLong(part.trim());
                    if (companyId == null
                            || dataScopeService.isGlobalAdmin(uid)
                            || dataScopeService.visibleCompanyIds(uid).contains(companyId)) {
                        ids.add(uid);
                    }
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        return new ArrayList<>(ids);
    }

    @Override
    public Map<String, Object> describeEnabled(String type, Long companyId) {
        if (companyId == null) {
            throw new BusinessException("请选择所属公司");
        }
        long loginId = StpUtil.getLoginIdAsLong();
        if (!dataScopeService.isGlobalAdmin(loginId)
                && !dataScopeService.visibleCompanyIds(loginId).contains(companyId)) {
            throw new BusinessException("无权查看该公司审批配置");
        }
        WfApprovalFlow flow = requireEnabled(type, companyId);
        fillView(flow);
        List<Long> assigneeIds = resolveAssigneeIds(flow, companyId);
        boolean any = "ANY".equalsIgnoreCase(flow.getPassMode());
        String passModeLabel = any ? "或签（一人通过即可）" : "会签（须全部通过）";
        int hours = flow.getTimeoutHours() == null ? 0 : flow.getTimeoutHours();
        String timeoutLabel = hours > 0 ? hours + "小时未操作自动通过" : "无超时自动通过";
        List<String> assigneeNames = List.of();
        if (!assigneeIds.isEmpty()) {
            Map<Long, SysUser> users = userService.listByIds(assigneeIds).stream()
                    .collect(Collectors.toMap(SysUser::getId, u -> u, (a, b) -> a));
            assigneeNames = assigneeIds.stream()
                    .map(users::get)
                    .filter(Objects::nonNull)
                    .map(u -> StringUtils.hasText(u.getNickname()) ? u.getNickname() : u.getUsername())
                    .filter(StringUtils::hasText)
                    .distinct()
                    .collect(Collectors.toList());
        }
        String tip = "将按审批配置提交：" + passModeLabel + "，" + timeoutLabel;
        if (!assigneeNames.isEmpty()) {
            tip += "；审批人：" + String.join("、", assigneeNames);
        } else {
            tip += "（共 " + assigneeIds.size() + " 人）";
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", flow.getType());
        map.put("name", flow.getName());
        map.put("passMode", flow.getPassMode() == null ? "ALL" : flow.getPassMode());
        map.put("passModeLabel", passModeLabel);
        map.put("timeoutHours", hours);
        map.put("timeoutLabel", timeoutLabel);
        map.put("assigneeCount", assigneeIds.size());
        map.put("assigneeNames", assigneeNames);
        map.put("tip", tip);
        return map;
    }

    private void validateAssigneesInCompany(WfApprovalFlow flow) {
        if (!StringUtils.hasText(flow.getUserIds()) || flow.getCompanyId() == null) {
            return;
        }
        for (String part : flow.getUserIds().split(",")) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            Long uid;
            try {
                uid = Long.parseLong(part.trim());
            } catch (NumberFormatException e) {
                throw new BusinessException("指定审批人无效");
            }
            if (dataScopeService.isGlobalAdmin(uid)) {
                continue;
            }
            if (!dataScopeService.visibleCompanyIds(uid).contains(flow.getCompanyId())) {
                throw new BusinessException("指定审批人不属于该公司");
            }
        }
    }

    private void assertCanManageCompany(Long companyId) {
        if (companyId == null) {
            throw new BusinessException("请选择所属公司");
        }
        long loginId = StpUtil.getLoginIdAsLong();
        if (dataScopeService.isGlobalAdmin(loginId)) {
            return;
        }
        if (!dataScopeService.visibleCompanyIds(loginId).contains(companyId)) {
            throw new BusinessException("无权操作该公司的审批配置");
        }
    }

    private void fillCompanyNames(List<WfApprovalFlow> flows) {
        if (flows == null || flows.isEmpty()) {
            return;
        }
        Set<Long> ids = flows.stream()
                .map(WfApprovalFlow::getCompanyId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, String> nameMap = deptService.listByIds(ids).stream()
                .collect(Collectors.toMap(SysDept::getId, SysDept::getName, (a, b) -> a));
        for (WfApprovalFlow flow : flows) {
            if (flow.getCompanyId() != null) {
                flow.setCompanyName(nameMap.get(flow.getCompanyId()));
            }
        }
    }

    private void fillView(WfApprovalFlow flow) {
        if (flow == null) {
            return;
        }
        flow.setPassModeLabel("ANY".equalsIgnoreCase(flow.getPassMode()) ? "或签（一人通过即可）" : "会签（须全部通过）");
        if (StringUtils.hasText(flow.getUserIds())) {
            List<Long> ids = new ArrayList<>();
            for (String part : flow.getUserIds().split(",")) {
                if (!StringUtils.hasText(part)) {
                    continue;
                }
                try {
                    ids.add(Long.parseLong(part.trim()));
                } catch (NumberFormatException ignored) {
                    // skip bad id
                }
            }
            flow.setUserIdList(ids);
        } else {
            flow.setUserIdList(new ArrayList<>());
        }
        if (StringUtils.hasText(flow.getRoleCodes())) {
            flow.setRoleCodeList(Arrays.stream(flow.getRoleCodes().split(","))
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .collect(Collectors.toList()));
        } else {
            flow.setRoleCodeList(new ArrayList<>());
        }
    }
}
