package com.kk.biz.ticket.vo;

import lombok.Data;

@Data
public class TicketReplySummaryVO {
    private long replyCount;
    private boolean hasUnreadReply;
    private long unreadReplyCount;
    private TicketReplyMessageVO latestReply;
}
