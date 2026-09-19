package com.kk.biz.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
public class CompanyTaskShareState {

    private Long companyId;
    private String companyName;
    private Boolean active;
    /** 相对路径，如 /public/company-tasks/{token}；未生成时为 null */
    private String path;
    /** 这条链接指定给老板看的人员 */
    private List<Long> userIds = new ArrayList<>();
    /** 发起人指定的日期，老板不能改 */
    private LocalDate dateFrom;
    private LocalDate dateTo;
}
