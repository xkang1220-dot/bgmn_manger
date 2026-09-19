package com.kk.biz.dto;

import lombok.Data;

@Data
public class PublicCompanyTaskComment {

    private String authorName;
    private String content;
    /** yyyy-MM-dd HH:mm */
    private String createTime;
}
