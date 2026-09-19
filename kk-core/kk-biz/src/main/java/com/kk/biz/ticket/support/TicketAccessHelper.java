package com.kk.biz.ticket.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kk.biz.ticket.entity.TicketDeveloper;
import com.kk.biz.ticket.entity.TicketWorkOrderAssignee;
import com.kk.biz.ticket.mapper.TicketDeveloperMapper;
import com.kk.biz.ticket.mapper.TicketWorkOrderAssigneeMapper;
import com.kk.system.entity.SysUser;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工单访问：提交人 / 管理权限 / 被分配开发人员。
 * <p>优先按 sys_user_id 绑定；未绑定时允许「开发人员姓名 = 登录昵称」兜底匹配（只读，不自动改库）。
 */
@Component
@RequiredArgsConstructor
public class TicketAccessHelper {

    private final TicketDeveloperMapper developerMapper;
    private final TicketWorkOrderAssigneeMapper assigneeMapper;
    private final SysUserService userService;

    public Set<Long> developerIdsOfUser(long loginId) {
        Set<Long> ids = new HashSet<>();
        List<TicketDeveloper> bound = developerMapper.selectList(new LambdaQueryWrapper<TicketDeveloper>()
                .eq(TicketDeveloper::getSysUserId, loginId)
                .eq(TicketDeveloper::getStatus, 1));
        if (!CollectionUtils.isEmpty(bound)) {
            bound.forEach(d -> ids.add(d.getId()));
        }

        SysUser user = userService.getById(loginId);
        String nickname = user != null && StringUtils.hasText(user.getNickname())
                ? user.getNickname().trim() : null;
        if (nickname != null) {
            // 未绑定账号时，按姓名=昵称兜底，避免漏看已分配工单
            List<TicketDeveloper> byName = developerMapper.selectList(new LambdaQueryWrapper<TicketDeveloper>()
                    .eq(TicketDeveloper::getStatus, 1)
                    .eq(TicketDeveloper::getName, nickname)
                    .isNull(TicketDeveloper::getSysUserId));
            if (!CollectionUtils.isEmpty(byName)) {
                byName.forEach(d -> ids.add(d.getId()));
            }
        }
        return ids;
    }

    public Set<Long> assignedTicketIds(long loginId) {
        Set<Long> developerIds = developerIdsOfUser(loginId);
        if (developerIds.isEmpty()) {
            return Set.of();
        }
        List<TicketWorkOrderAssignee> assignees = assigneeMapper.selectList(
                new LambdaQueryWrapper<TicketWorkOrderAssignee>()
                        .in(TicketWorkOrderAssignee::getDeveloperId, developerIds));
        if (CollectionUtils.isEmpty(assignees)) {
            return Set.of();
        }
        return assignees.stream().map(TicketWorkOrderAssignee::getTicketId).collect(Collectors.toSet());
    }

    public boolean isAssignee(Long ticketId, long loginId) {
        if (ticketId == null) {
            return false;
        }
        Set<Long> developerIds = developerIdsOfUser(loginId);
        if (developerIds.isEmpty()) {
            return false;
        }
        Long count = assigneeMapper.selectCount(new LambdaQueryWrapper<TicketWorkOrderAssignee>()
                .eq(TicketWorkOrderAssignee::getTicketId, ticketId)
                .in(TicketWorkOrderAssignee::getDeveloperId, developerIds));
        return count != null && count > 0;
    }
}
