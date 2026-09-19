package com.kk.biz.ticket.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kk.biz.ticket.dto.TicketMarkReadRequest;
import com.kk.biz.ticket.dto.TicketReplyRequest;
import com.kk.biz.ticket.vo.TicketReplyMessageVO;
import com.kk.biz.ticket.vo.TicketReplySummaryVO;

import java.util.List;
import java.util.Map;

public interface TicketReplyService {

    Page<TicketReplyMessageVO> pageReplies(Long ticketId, long page, long pageSize, String order);

    TicketReplyMessageVO reply(Long ticketId, TicketReplyRequest request);

    void markRead(Long ticketId, TicketMarkReadRequest request);

    Map<Long, TicketReplySummaryVO> unreadSummary(List<Long> ticketIds);

    Map<String, Long> unreadCount(String scope);

    void fillReplySummaries(List<com.kk.biz.ticket.vo.TicketVO> tickets);

    void cascadeDeleteByTicketIds(List<Long> ticketIds);
}
