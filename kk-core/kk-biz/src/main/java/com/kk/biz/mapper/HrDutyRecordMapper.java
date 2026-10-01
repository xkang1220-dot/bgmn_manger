package com.kk.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kk.biz.entity.HrDutyRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

@Mapper
public interface HrDutyRecordMapper extends BaseMapper<HrDutyRecord> {

    @Delete("DELETE FROM hr_duty_record WHERE duty_date = #{dutyDate}")
    int deleteDutyDay(@Param("dutyDate") LocalDate dutyDate);
}
