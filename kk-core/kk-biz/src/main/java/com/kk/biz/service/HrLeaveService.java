package com.kk.biz.service;

import com.kk.biz.entity.HrLeaveRecord;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface HrLeaveService {

    /** 当前登录人提交请假审批 */
    Map<String, Object> submitMine(Long companyId, LocalDate startDate, LocalDate endDate, String reason);

    /** 我的请假记录 */
    List<HrLeaveRecord> listMine(Long companyId, LocalDate start, LocalDate end);

    /** 全局考勤一览（未出勤日） */
    List<HrLeaveRecord> listAttendance(LocalDate start, LocalDate end);

    /** 考勤管理员可维护的全部启用员工 */
    List<Map<String, Object>> listAttendanceUsers();

    /** 全局员工月度考勤汇总及逐日明细（自然日均计入应出勤） */
    Map<String, Object> monthlyAttendanceDetail(String month);

    /** 按天覆盖未出勤员工；空列表表示恢复为全勤 */
    void setAbsentUsers(LocalDate leaveDate, List<Long> userIds);

    /** 指定周期内请假天数（月薪：上月21日至本月20日；周薪：ISO 周一至周日） */
    int countLeaveDays(Long companyId, Long userId, String periodKey);

    List<LocalDate> listLeaveDates(Long companyId, Long userId, String periodKey);

    /** 审批通过后落库请假日 */
    void effectLeaveApproval(Long approvalId, Long companyId, Long userId,
                             LocalDate startDate, LocalDate endDate, String reason);

    /** 校验请假区间：已有考勤日 / 在途请假审批不可重叠 */
    void assertLeaveRangeAvailable(Long companyId, Long userId, LocalDate startDate, LocalDate endDate);
}
