package com.kk.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("hr_holiday_calendar")
public class HrHolidayCalendar {

    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDate calendarDate;

    private String holidayName;

    /** 1：放假；0：调休补班。 */
    private Integer offDay;

    private Integer dataYear;

    private String sourceUrl;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
