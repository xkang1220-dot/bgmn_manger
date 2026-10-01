package com.kk.biz.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface HrHolidayCalendarService {

    List<Map<String, Object>> listCalendar(LocalDate start, LocalDate end);

    Map<LocalDate, Map<String, Object>> calendarByDate(LocalDate start, LocalDate end);

    int syncYear(int year);

    boolean hasYear(int year);
}
