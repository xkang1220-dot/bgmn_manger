package com.kk.biz.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class PublicTaskItem {

    private Long id;
    private Long projectId;
    private String title;
    private Integer status;
    private String statusLabel;
    private Integer priority;
    private LocalDate startDate;
    private LocalDate dueDate;
    private Integer progress;
    private String projectName;
    private String assigneeName;
    private List<String> participantNames;
    private Boolean overdue;
    /** 点击跳转管理端任务详情的完整地址 */
    private String jumpUrl;
}
