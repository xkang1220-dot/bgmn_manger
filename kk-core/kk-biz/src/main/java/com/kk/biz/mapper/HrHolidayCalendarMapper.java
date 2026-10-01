package com.kk.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kk.biz.entity.HrHolidayCalendar;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface HrHolidayCalendarMapper extends BaseMapper<HrHolidayCalendar> {

    @Delete("DELETE FROM hr_holiday_calendar WHERE data_year = #{year}")
    int deleteByYear(@Param("year") int year);
}
