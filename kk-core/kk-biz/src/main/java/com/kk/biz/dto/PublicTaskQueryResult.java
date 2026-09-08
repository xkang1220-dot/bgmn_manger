package com.kk.biz.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class PublicTaskQueryResult {

    private String nickname;
    private List<PublicTaskItem> tasks = new ArrayList<>();
}
