package com.kk.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kk.biz.entity.HrLeaveRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

public interface HrLeaveRecordMapper extends BaseMapper<HrLeaveRecord> {

    /** 覆盖某日考勤时必须物理删除，避免逻辑删除值与历史唯一索引冲突。 */
    @Delete("DELETE FROM hr_leave_record WHERE leave_date = #{leaveDate}")
    int deleteAttendanceDay(@Param("leaveDate") LocalDate leaveDate);
}
