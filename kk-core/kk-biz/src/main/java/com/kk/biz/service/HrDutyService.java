package com.kk.biz.service;

import com.kk.biz.entity.HrDutyRecord;

import java.time.LocalDate;
import java.util.List;

public interface HrDutyService {

    List<HrDutyRecord> listDuty(LocalDate start, LocalDate end);

    void setDutyUsers(LocalDate dutyDate, List<Long> userIds);
}
