package com.kk.biz.ticket.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class TicketUpdateRequest {
    private String title;
    private String type;
    private String urgency;
    private String description;
    private String status;
    private Integer progress;
    private LocalDate expectedCompleteDate;
    private Boolean clearExpectedCompleteDate;
    /** 关联项目；传 null 且 clearProject=true 时解绑 */
    private Long projectId;
    private Boolean clearProject;
}
