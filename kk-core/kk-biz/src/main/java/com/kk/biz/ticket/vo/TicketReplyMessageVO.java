package com.kk.biz.ticket.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TicketReplyMessageVO {
    private Long id;
    private Long ticketId;
    private String senderType;
    private Long senderId;
    private String senderName;
    private String content;
    private String contentType;
    private String contentPreview;
    private LocalDateTime createTime;
}
