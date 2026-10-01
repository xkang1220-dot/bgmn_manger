package com.kk.biz.service.impl;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kk.biz.entity.HrHolidayCalendar;
import com.kk.biz.mapper.HrHolidayCalendarMapper;
import com.kk.biz.service.HrHolidayCalendarService;
import com.kk.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class HrHolidayCalendarServiceImpl implements HrHolidayCalendarService {

    private static final String SOURCE =
            "https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/%d.json";

    private final HrHolidayCalendarMapper calendarMapper;

    @Override
    public List<Map<String, Object>> listCalendar(LocalDate start, LocalDate end) {
        if (start == null || end == null || end.isBefore(start)) {
            throw new BusinessException("日期范围不正确");
        }
        if (start.plusYears(2).isBefore(end)) {
            throw new BusinessException("节假日查询范围不能超过两年");
        }
        Map<LocalDate, HrHolidayCalendar> exceptions = calendarMapper.selectList(
                        new LambdaQueryWrapper<HrHolidayCalendar>()
                                .ge(HrHolidayCalendar::getCalendarDate, start)
                                .le(HrHolidayCalendar::getCalendarDate, end))
                .stream().collect(Collectors.toMap(HrHolidayCalendar::getCalendarDate,
                        Function.identity(), (left, right) -> left));
        List<Map<String, Object>> result = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            HrHolidayCalendar special = exceptions.get(date);
            boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY
                    || date.getDayOfWeek() == DayOfWeek.SUNDAY;
            boolean offDay = special == null ? weekend : special.getOffDay() == 1;
            String type = special == null
                    ? (weekend ? "WEEKEND" : "WORKDAY")
                    : (offDay ? "HOLIDAY" : "ADJUSTED_WORKDAY");
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", date.toString());
            day.put("name", special == null ? (weekend ? "周末" : "") : special.getHolidayName());
            day.put("type", type);
            day.put("workday", !offDay);
            day.put("offDay", offDay);
            result.add(day);
        }
        return result;
    }

    @Override
    public Map<LocalDate, Map<String, Object>> calendarByDate(LocalDate start, LocalDate end) {
        return listCalendar(start, end).stream().collect(Collectors.toMap(
                row -> LocalDate.parse(String.valueOf(row.get("date"))), Function.identity()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int syncYear(int year) {
        String sourceUrl = SOURCE.formatted(year);
        String body;
        try {
            body = HttpRequest.get(sourceUrl).timeout(15_000).execute().body();
        } catch (Exception e) {
            throw new BusinessException("同步 " + year + " 年节假日失败：无法访问数据源");
        }
        JSONObject root;
        try {
            root = JSONUtil.parseObj(body);
        } catch (Exception e) {
            throw new BusinessException("同步 " + year + " 年节假日失败：数据格式错误");
        }
        if (!Integer.valueOf(year).equals(root.getInt("year"))) {
            throw new BusinessException("节假日数据年份不匹配");
        }
        JSONArray days = root.getJSONArray("days");
        if (days == null || days.isEmpty()) {
            throw new BusinessException(year + " 年节假日数据尚未发布");
        }
        List<HrHolidayCalendar> rows = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (Object value : days) {
            JSONObject item = JSONUtil.parseObj(value);
            LocalDate date = LocalDate.parse(item.getStr("date"));
            if (date.getYear() != year) continue;
            HrHolidayCalendar row = new HrHolidayCalendar();
            row.setCalendarDate(date);
            row.setHolidayName(item.getStr("name", ""));
            row.setOffDay(Boolean.TRUE.equals(item.getBool("isOffDay")) ? 1 : 0);
            row.setDataYear(year);
            row.setSourceUrl(sourceUrl);
            row.setCreateTime(now);
            row.setUpdateTime(now);
            rows.add(row);
        }
        if (rows.isEmpty()) {
            throw new BusinessException(year + " 年节假日数据为空");
        }
        calendarMapper.deleteByYear(year);
        rows.forEach(calendarMapper::insert);
        return rows.size();
    }

    @Override
    public boolean hasYear(int year) {
        return calendarMapper.selectCount(new LambdaQueryWrapper<HrHolidayCalendar>()
                .eq(HrHolidayCalendar::getDataYear, year)) > 0;
    }
}
