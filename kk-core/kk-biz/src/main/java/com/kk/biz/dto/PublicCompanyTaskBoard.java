package com.kk.biz.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
public class PublicCompanyTaskBoard {

    private String companyName;
    private LocalDate from;
    private LocalDate to;
    private List<PublicCompanyTaskPerson> people = new ArrayList<>();
    private List<PublicCompanyTaskItem> tasks = new ArrayList<>();
}
