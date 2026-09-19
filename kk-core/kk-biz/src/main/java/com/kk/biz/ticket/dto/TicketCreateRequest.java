package com.kk.biz.ticket.dto;

import lombok.Data;

@Data
public class TicketCreateRequest {
    private Long companyId;
    /** 关联项目（可选） */
    private Long projectId;
    private String title;
    private String type;
    private String urgency;
    private String description;
}
