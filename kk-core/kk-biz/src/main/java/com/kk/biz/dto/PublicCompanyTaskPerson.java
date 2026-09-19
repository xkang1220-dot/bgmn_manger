package com.kk.biz.dto;

import lombok.Data;

@Data
public class PublicCompanyTaskPerson {

    private Long userId;
    private String name;
    private Integer taskCount;
    private Integer overdueCount;
}
