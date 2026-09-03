package com.kk.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kk.biz.entity.WfApprovalFlow;
import com.kk.biz.mapper.WfApprovalFlowMapper;
import com.kk.biz.service.WfApprovalFlowService;
import com.kk.biz.workflow.ApprovalTypes;
import com.kk.common.exception.BusinessException;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WfApprovalFlowServiceImpl extends ServiceImpl<WfApprovalFlowMapper, WfApprovalFlow>
        implements WfApprovalFlowService {

    private final SysUserService userService;

    @Override
    public List<WfApprovalFlow> listAll() {
        List<WfApprovalFlow> list = list(new LambdaQueryWrapper<WfApprovalFlow>()
                .orderByAsc(WfApprovalFlow::getSort)
                .orderByAsc(WfApprovalFlow::getId));
        list.forEach(this::fillView);
        return list;
    }

    @Override
    public WfApprovalFlow getByType(String type) {
        if (!StringUtils.hasText(type)) {
            return null;
        }
        WfApprovalFlow flow = getOne(new LambdaQueryWrapper<WfApprovalFlow>()
                .eq(WfApprovalFlow::getType, type)
                .last("LIMIT 1"));
        if (flow != null) {
            fillView(flow);
        }
        return flow;
    }

    @Override
    public WfApprovalFlow requireEnabled(String type) {
        WfApprovalFlow flow = getByType(type);
        if (flow == null) {
            flow = defaultFlow(type);
        }
        if (flow.getStatus() != null && flow.getStatus() == 0) {
            throw new BusinessException("审批类型「" + flow.getName() + "」已停用，请先在审批配置中启用");
        }
        fillView(flow);
        return flow;
    }

    @Override
    public void saveFlow(WfApprovalFlow flow) {
        if (flow == null || !StringUtils.hasText(flow.getType())) {
            throw new BusinessException("审批类型不能为空");
        }
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
        if (flow.getTimeoutHours() == null || flow.getTimeoutHours() < 0) {
            flow.setTimeoutHours(0);
        }
        if (flow.getStatus() == null) {
            flow.setStatus(1);
        }
        WfApprovalFlow exist = getByType(flow.getType());
        if (exist != null) {
            // 新增时禁止覆盖已有；编辑时 id 必须匹配
            if (flow.getId() == null) {
                throw new BusinessException("该审批类型已存在，请直接编辑");
            }
            if (!exist.getId().equals(flow.getId())) {
                throw new BusinessException("审批类型编码冲突");
            }
            flow.setId(exist.getId());
            updateById(flow);
        } else {
            flow.setId(null);
            save(flow);
        }
    }

    @Override
    public List<Long> resolveAssigneeIds(WfApprovalFlow flow) {
        Set<Long> ids = new LinkedHashSet<>();
        if (flow != null && StringUtils.hasText(flow.getRoleCodes())) {
            for (String code : flow.getRoleCodes().split(",")) {
                if (!StringUtils.hasText(code)) {
                    continue;
                }
                ids.addAll(userService.listUserIdsByRoleCode(code.trim()));
            }
        }
        if (flow != null && StringUtils.hasText(flow.getUserIds())) {
            for (String part : flow.getUserIds().split(",")) {
                if (!StringUtils.hasText(part)) {
                    continue;
                }
                try {
                    ids.add(Long.parseLong(part.trim()));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        if (ids.isEmpty()) {
            ids.addAll(userService.listUserIdsByRoleCode("admin"));
        }
        return new ArrayList<>(ids);
    }

    private WfApprovalFlow defaultFlow(String type) {
        WfApprovalFlow flow = new WfApprovalFlow();
        flow.setType(type);
        flow.setName(ApprovalTypes.label(type));
        flow.setStatus(1);
        flow.setTimeoutHours(0);
        if (ApprovalTypes.needAllShareholders(type) || ApprovalTypes.ROLLBACK.equals(type)) {
            flow.setPassMode("ALL");
            flow.setRoleCodes("shareholder");
            flow.setTimeoutHours(72);
        } else {
            flow.setPassMode("ANY");
            flow.setRoleCodes("finance");
        }
        return flow;
    }

    private void fillView(WfApprovalFlow flow) {
        if (flow == null) {
            return;
        }
        flow.setPassModeLabel("ANY".equalsIgnoreCase(flow.getPassMode()) ? "或签（一人通过）" : "会签（全部通过）");
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
