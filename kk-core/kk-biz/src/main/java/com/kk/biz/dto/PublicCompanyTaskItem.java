package com.kk.biz.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
public class PublicCompanyTaskItem {

    private Long id;
    private String title;
    private String content;
    private Integer status;
    private String statusLabel;
    private Integer priority;
    private LocalDate startDate;
    private LocalDate dueDate;
    private Integer progress;
    private String projectName;
    private List<String> participantNames = new ArrayList<>();
    private Boolean overdue;
    /** 这条任务挂在哪些公司在职人员名下（参与人或创建人） */
    private List<Long> relatedUserIds = new ArrayList<>();
    private List<PublicCompanyTaskComment> comments = new ArrayList<>();
}
