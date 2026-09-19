package com.kk.biz.ticket.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kk.biz.ticket.dto.TicketMarkReadRequest;
import com.kk.biz.ticket.dto.TicketReplyRequest;
import com.kk.biz.ticket.entity.TicketReplyMessage;
import com.kk.biz.ticket.entity.TicketReplyReadCursor;
import com.kk.biz.ticket.entity.TicketWorkOrder;
import com.kk.biz.ticket.mapper.TicketReplyMessageMapper;
import com.kk.biz.ticket.mapper.TicketReplyReadCursorMapper;
import com.kk.biz.ticket.mapper.TicketWorkOrderMapper;
import com.kk.biz.ticket.service.TicketReplyService;
import com.kk.biz.ticket.support.TicketAccessHelper;
import com.kk.biz.ticket.support.TicketCompanyGuard;
import com.kk.biz.ticket.support.TicketReplyContentHelper;
import com.kk.biz.ticket.support.TicketValidation;
import com.kk.biz.ticket.vo.TicketReplyMessageVO;
import com.kk.biz.ticket.vo.TicketReplySummaryVO;
import com.kk.biz.ticket.vo.TicketVO;
import com.kk.common.exception.BusinessException;
import com.kk.system.entity.SysUser;
import com.kk.system.service.SysNotificationService;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TicketReplyServiceImpl implements TicketReplyService {

    private final TicketReplyMessageMapper messageMapper;
    private final TicketReplyReadCursorMapper cursorMapper;
    private final TicketWorkOrderMapper workOrderMapper;
    private final TicketCompanyGuard companyGuard;
    private final TicketAccessHelper accessHelper;
    private final SysUserService userService;
    private final SysNotificationService notificationService;

    @Override
    public Page<TicketReplyMessageVO> pageReplies(Long ticketId, long page, long pageSize, String order) {
        TicketWorkOrder ticket = requireAccessibleTicket(ticketId);
        boolean asc = !"desc".equalsIgnoreCase(order);
        Page<TicketReplyMessage> raw = messageMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<TicketReplyMessage>()
                        .eq(TicketReplyMessage::getTicketId, ticket.getId())
                        .orderBy(true, asc, TicketReplyMessage::getId));
        Page<TicketReplyMessageVO> result = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        result.setRecords(raw.getRecords().stream().map(this::toMessageVo).collect(Collectors.toList()));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketReplyMessageVO reply(Long ticketId, TicketReplyRequest request) {
        if (request == null) {
            throw new BusinessException(400, "参数不能为空");
        }
        TicketValidation.requireDescription(request.getContent());
        TicketWorkOrder ticket = requireAccessibleTicket(ticketId);
        long loginId = companyGuard.loginId();
        boolean isSubmitter = Objects.equals(ticket.getSubmitterId(), loginId);
        boolean isHandler = StpUtil.hasPermission("ticket:manage:list");
        boolean isAssignee = accessHelper.isAssignee(ticketId, loginId);
        if (!isSubmitter && !isHandler && !isAssignee) {
            throw new BusinessException(403, "无权回复该工单");
        }
        String senderType = isSubmitter ? "submitter" : "handler";

        SysUser user = userService.getById(loginId);
        String name = user != null && StringUtils.hasText(user.getNickname())
                ? user.getNickname() : (user != null ? user.getUsername() : String.valueOf(loginId));

        TicketReplyMessage msg = new TicketReplyMessage();
        msg.setTicketId(ticketId);
        msg.setSenderType(senderType);
        msg.setSenderId(loginId);
        msg.setSenderName(name);
        msg.setContent(request.getContent());
        msg.setContentType(TicketReplyContentHelper.detectContentType(request.getContent()));
        messageMapper.insert(msg);

        upsertCursor(ticketId, loginId, msg.getId());

        if ("handler".equals(senderType) && !Objects.equals(ticket.getSubmitterId(), loginId)) {
            notificationService.notifyUser(
                    ticket.getSubmitterId(),
                    "工单有新回复",
                    "工单「" + ticket.getTitle() + "」收到处理人回复",
                    "ticket_reply",
                    ticket.getId(),
                    "/ticket/submissions");
        }

        return toMessageVo(msg);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long ticketId, TicketMarkReadRequest request) {
        requireAccessibleTicket(ticketId);
        long loginId = companyGuard.loginId();
        Long targetId = request != null ? request.getMessageId() : null;
        if (targetId == null) {
            TicketReplyMessage latest = messageMapper.selectOne(new LambdaQueryWrapper<TicketReplyMessage>()
                    .eq(TicketReplyMessage::getTicketId, ticketId)
                    .orderByDesc(TicketReplyMessage::getId)
                    .last("LIMIT 1"));
            targetId = latest != null ? latest.getId() : 0L;
        }
        upsertCursor(ticketId, loginId, targetId);
    }

    @Override
    public Map<Long, TicketReplySummaryVO> unreadSummary(List<Long> ticketIds) {
        if (CollectionUtils.isEmpty(ticketIds)) {
            return Map.of();
        }
        List<TicketVO> stubs = ticketIds.stream().map(id -> {
            TicketVO v = new TicketVO();
            v.setId(id);
            return v;
        }).collect(Collectors.toList());
        // load submitter for identity
        List<TicketWorkOrder> orders = workOrderMapper.selectBatchIds(ticketIds);
        Map<Long, Long> submitterMap = orders.stream()
                .collect(Collectors.toMap(TicketWorkOrder::getId, TicketWorkOrder::getSubmitterId, (a, b) -> a));
        for (TicketVO v : stubs) {
            v.setSubmitterId(submitterMap.get(v.getId()));
        }
        fillReplySummaries(stubs);
        Map<Long, TicketReplySummaryVO> map = new HashMap<>();
        for (TicketVO v : stubs) {
            map.put(v.getId(), v.getReplySummary());
        }
        return map;
    }

    @Override
    public Map<String, Long> unreadCount(String scope) {
        long loginId = companyGuard.loginId();
        Set<Long> visible = companyGuard.visibleCompanies();
        LambdaQueryWrapper<TicketWorkOrder> qw = new LambdaQueryWrapper<>();
        if (visible != null) {
            if (visible.isEmpty()) {
                return Map.of("unreadTicketCount", 0L, "unreadMessageCount", 0L);
            }
            qw.in(TicketWorkOrder::getCompanyId, visible);
        }
        if ("my".equalsIgnoreCase(scope)) {
            Set<Long> assignedIds = accessHelper.assignedTicketIds(loginId);
            qw.and(w -> {
                w.eq(TicketWorkOrder::getSubmitterId, loginId);
                if (!assignedIds.isEmpty()) {
                    w.or().in(TicketWorkOrder::getId, assignedIds);
                }
            });
        } else if (!StpUtil.hasPermission("ticket:manage:list")) {
            Set<Long> assignedIds = accessHelper.assignedTicketIds(loginId);
            qw.and(w -> {
                w.eq(TicketWorkOrder::getSubmitterId, loginId);
                if (!assignedIds.isEmpty()) {
                    w.or().in(TicketWorkOrder::getId, assignedIds);
                }
            });
        }
        List<TicketWorkOrder> tickets = workOrderMapper.selectList(qw);
        if (tickets.isEmpty()) {
            return Map.of("unreadTicketCount", 0L, "unreadMessageCount", 0L);
        }
        List<TicketVO> vos = tickets.stream().map(t -> {
            TicketVO v = new TicketVO();
            v.setId(t.getId());
            v.setSubmitterId(t.getSubmitterId());
            return v;
        }).collect(Collectors.toList());
        fillReplySummaries(vos);
        long ticketCount = 0;
        long messageCount = 0;
        for (TicketVO v : vos) {
            if (v.getReplySummary() != null && v.getReplySummary().isHasUnreadReply()) {
                ticketCount++;
                messageCount += v.getReplySummary().getUnreadReplyCount();
            }
        }
        return Map.of("unreadTicketCount", ticketCount, "unreadMessageCount", messageCount);
    }

    @Override
    public void fillReplySummaries(List<TicketVO> tickets) {
        if (CollectionUtils.isEmpty(tickets)) {
            return;
        }
        long loginId = companyGuard.loginId();
        Set<Long> ticketIds = tickets.stream().map(TicketVO::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (ticketIds.isEmpty()) {
            return;
        }

        List<TicketReplyMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<TicketReplyMessage>()
                .in(TicketReplyMessage::getTicketId, ticketIds)
                .orderByAsc(TicketReplyMessage::getId));
        Map<Long, List<TicketReplyMessage>> byTicket = messages.stream()
                .collect(Collectors.groupingBy(TicketReplyMessage::getTicketId));

        List<TicketReplyReadCursor> cursors = cursorMapper.selectList(new LambdaQueryWrapper<TicketReplyReadCursor>()
                .eq(TicketReplyReadCursor::getUserId, loginId)
                .in(TicketReplyReadCursor::getTicketId, ticketIds));
        Map<Long, Long> cursorMap = cursors.stream()
                .collect(Collectors.toMap(TicketReplyReadCursor::getTicketId,
                        c -> c.getLastReadMessageId() == null ? 0L : c.getLastReadMessageId(), (a, b) -> a));

        for (TicketVO ticket : tickets) {
            List<TicketReplyMessage> list = byTicket.getOrDefault(ticket.getId(), List.of());
            TicketReplySummaryVO summary = new TicketReplySummaryVO();
            summary.setReplyCount(list.size());
            long lastRead = cursorMap.getOrDefault(ticket.getId(), 0L);
            String mySide = Objects.equals(ticket.getSubmitterId(), loginId) ? "submitter" : "handler";
            long unread = list.stream()
                    .filter(m -> m.getId() != null && m.getId() > lastRead)
                    .filter(m -> !Objects.equals(m.getSenderType(), mySide))
                    .count();
            summary.setUnreadReplyCount(unread);
            summary.setHasUnreadReply(unread > 0);
            if (!list.isEmpty()) {
                summary.setLatestReply(toMessageVo(list.get(list.size() - 1)));
            }
            ticket.setReplySummary(summary);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cascadeDeleteByTicketIds(List<Long> ticketIds) {
        if (CollectionUtils.isEmpty(ticketIds)) {
            return;
        }
        messageMapper.delete(new LambdaQueryWrapper<TicketReplyMessage>()
                .in(TicketReplyMessage::getTicketId, ticketIds));
        cursorMapper.delete(new LambdaQueryWrapper<TicketReplyReadCursor>()
                .in(TicketReplyReadCursor::getTicketId, ticketIds));
    }

    private void upsertCursor(Long ticketId, long userId, Long messageId) {
        TicketReplyReadCursor cursor = cursorMapper.selectOne(new LambdaQueryWrapper<TicketReplyReadCursor>()
                .eq(TicketReplyReadCursor::getTicketId, ticketId)
                .eq(TicketReplyReadCursor::getUserId, userId)
                .last("LIMIT 1"));
        LocalDateTime now = LocalDateTime.now();
        if (cursor == null) {
            cursor = new TicketReplyReadCursor();
            cursor.setTicketId(ticketId);
            cursor.setUserId(userId);
            cursor.setLastReadMessageId(messageId);
            cursor.setLastReadTime(now);
            cursor.setUpdateTime(now);
            cursorMapper.insert(cursor);
        } else {
            Long current = cursor.getLastReadMessageId() == null ? 0L : cursor.getLastReadMessageId();
            if (messageId != null && messageId >= current) {
                cursor.setLastReadMessageId(messageId);
                cursor.setLastReadTime(now);
                cursor.setUpdateTime(now);
                cursorMapper.updateById(cursor);
            }
        }
    }

    private TicketWorkOrder requireAccessibleTicket(Long ticketId) {
        TicketWorkOrder ticket = workOrderMapper.selectById(ticketId);
        if (ticket == null) {
            throw new BusinessException(404, "工单不存在");
        }
        if (!companyGuard.canSeeCompany(ticket.getCompanyId())) {
            throw new BusinessException(403, "无权访问该工单");
        }
        long loginId = companyGuard.loginId();
        boolean isSubmitter = Objects.equals(ticket.getSubmitterId(), loginId);
        boolean isHandler = StpUtil.hasPermission("ticket:manage:list");
        boolean isAssignee = accessHelper.isAssignee(ticketId, loginId);
        if (!isSubmitter && !isHandler && !isAssignee) {
            throw new BusinessException(403, "无权访问该工单");
        }
        return ticket;
    }

    private TicketReplyMessageVO toMessageVo(TicketReplyMessage msg) {
        TicketReplyMessageVO vo = new TicketReplyMessageVO();
        vo.setId(msg.getId());
        vo.setTicketId(msg.getTicketId());
        vo.setSenderType(msg.getSenderType());
        vo.setSenderId(msg.getSenderId());
        vo.setSenderName(msg.getSenderName());
        vo.setContent(msg.getContent());
        vo.setContentType(msg.getContentType());
        vo.setContentPreview(TicketReplyContentHelper.preview(msg.getContent(), 80));
        vo.setCreateTime(msg.getCreateTime());
        return vo;
    }
}
