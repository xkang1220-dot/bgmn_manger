package com.kk.biz.ticket.vo;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class TicketVO {
    private Long id;
    private Long companyId;
    private String companyName;
    private Long projectId;
    private String projectName;
    private String ticketNo;
    private String title;
    private String type;
    private String urgency;
    private String description;
    private String status;
    private Integer progress;
    private LocalDate expectedCompleteDate;
    private List<Long> developerIds = new ArrayList<>();
    private String developerNames;
    private Long cycleId;
    private String cycleName;
    private Long submitterId;
    private String submitterName;
    private LocalDateTime completedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private TicketReplySummaryVO replySummary;
    /** 当前登录用户是否可更新进度（管理员或被分配人） */
    private Boolean canUpdateProgress;
}
