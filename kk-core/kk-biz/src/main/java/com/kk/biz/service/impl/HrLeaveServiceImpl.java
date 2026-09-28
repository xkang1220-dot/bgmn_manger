package com.kk.biz.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kk.biz.dto.ApprovalSubmitRequest;
import com.kk.biz.entity.HrLeaveRecord;
import com.kk.biz.entity.WfApproval;
import com.kk.biz.mapper.HrLeaveRecordMapper;
import com.kk.biz.mapper.HrArchiveMapper;
import com.kk.biz.mapper.WfApprovalMapper;
import com.kk.biz.service.HrHolidayCalendarService;
import com.kk.biz.service.HrLeaveService;
import com.kk.biz.service.WfApprovalService;
import com.kk.biz.workflow.ApprovalTypes;
import com.kk.common.exception.BusinessException;
import com.kk.system.service.DataScopeService;
import com.kk.system.service.SysDeptService;
import com.kk.system.service.SysUserService;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class HrLeaveServiceImpl extends ServiceImpl<HrLeaveRecordMapper, HrLeaveRecord>
        implements HrLeaveService {

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy-MM");

    private final DataScopeService dataScopeService;
    private final SysUserService userService;
    private final HrArchiveMapper archiveMapper;
    private final HrHolidayCalendarService holidayCalendarService;
    private final SysDeptService deptService;
    private final WfApprovalMapper approvalMapper;
    @Lazy
    private final WfApprovalService approvalService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submitMine(Long companyId, LocalDate startDate, LocalDate endDate, String reason) {
        long loginId = StpUtil.getLoginIdAsLong();
        if (companyId == null) {
            throw new BusinessException("请选择公司");
        }
        if (startDate == null || endDate == null) {
            throw new BusinessException("请选择请假起止日期");
        }
        if (endDate.isBefore(startDate)) {
            throw new BusinessException("结束日期不能早于开始日期");
        }
        if (startDate.plusDays(60).isBefore(endDate)) {
            throw new BusinessException("单次请假不超过 60 天");
        }
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException("请填写请假事由");
        }
        assertCompanyVisible(loginId, companyId);
        List<LocalDate> days = expandDays(startDate, endDate);
        if (days.isEmpty()) {
            throw new BusinessException("请假日期无效");
        }
        assertLeaveRangeAvailable(companyId, loginId, startDate, endDate);
        ApprovalSubmitRequest req = new ApprovalSubmitRequest();
        req.setType(ApprovalTypes.LEAVE_APPLY);
        req.setCompanyId(companyId);
        req.setTitle("请假 · " + startDate + " ~ " + endDate);
        req.setRemark(reason.trim());
        Map<String, Object> payload = new HashMap<>();
        payload.put("startDate", startDate.toString());
        payload.put("endDate", endDate.toString());
        payload.put("leaveDays", days.size());
        payload.put("reason", reason.trim());
        req.setPayload(payload);
        var detail = approvalService.submit(req);
        Map<String, Object> result = new HashMap<>();
        result.put("approvalId", detail.getId());
        result.put("bizNo", detail.getBizNo());
        result.put("leaveDays", days.size());
        result.put("status", detail.getStatus());
        result.put("flowTip", detail.getFlowTip());
        return result;
    }

    @Override
    public List<HrLeaveRecord> listMine(Long companyId, LocalDate start, LocalDate end) {
        long loginId = StpUtil.getLoginIdAsLong();
        LambdaQueryWrapper<HrLeaveRecord> q = new LambdaQueryWrapper<HrLeaveRecord>()
                .eq(HrLeaveRecord::getUserId, loginId)
                .ge(start != null, HrLeaveRecord::getLeaveDate, start)
                .le(end != null, HrLeaveRecord::getLeaveDate, end)
                .orderByDesc(HrLeaveRecord::getLeaveDate);
        List<HrLeaveRecord> list = list(q);
        fillNames(list);
        return list;
    }

    @Override
    public List<HrLeaveRecord> listAttendance(LocalDate start, LocalDate end) {
        Set<Long> attendanceUserIds = attendanceUserIds();
        if (attendanceUserIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<HrLeaveRecord> q = new LambdaQueryWrapper<HrLeaveRecord>()
                .in(HrLeaveRecord::getUserId, attendanceUserIds)
                .ge(start != null, HrLeaveRecord::getLeaveDate, start)
                .le(end != null, HrLeaveRecord::getLeaveDate, end)
                .orderByDesc(HrLeaveRecord::getLeaveDate)
                .orderByAsc(HrLeaveRecord::getUserId);
        List<HrLeaveRecord> list = list(q);
        fillNames(list);
        return list;
    }

    @Override
    public List<Map<String, Object>> listAttendanceUsers() {
        List<com.kk.biz.entity.HrArchive> attendanceArchives = archiveMapper.selectList(
                new LambdaQueryWrapper<com.kk.biz.entity.HrArchive>()
                        .select(com.kk.biz.entity.HrArchive::getUserId,
                                com.kk.biz.entity.HrArchive::getAttendanceCycleDay)
                        .eq(com.kk.biz.entity.HrArchive::getAttendanceEnabled, 1));
        Set<Long> attendanceUserIds = attendanceArchives.stream()
                .map(com.kk.biz.entity.HrArchive::getUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (attendanceUserIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Integer> cycleDayByUser = attendanceArchives.stream()
                .filter(archive -> archive.getUserId() != null)
                .collect(Collectors.toMap(com.kk.biz.entity.HrArchive::getUserId,
                        archive -> normalizedCycleDay(archive.getAttendanceCycleDay()),
                        (left, right) -> left));
        return userService.list(new LambdaQueryWrapper<com.kk.system.entity.SysUser>()
                        .in(com.kk.system.entity.SysUser::getId, attendanceUserIds)
                        .eq(com.kk.system.entity.SysUser::getStatus, 1)
                        .orderByAsc(com.kk.system.entity.SysUser::getNickname)
                        .orderByAsc(com.kk.system.entity.SysUser::getUsername))
                .stream().map(u -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("id", u.getId());
                    row.put("name", StringUtils.hasText(u.getNickname()) ? u.getNickname() : u.getUsername());
                    row.put("username", u.getUsername());
                    row.put("attendanceCycleDay", cycleDayByUser.getOrDefault(u.getId(), 1));
                    return row;
                }).toList();
    }

    @Override
    public Map<String, Object> monthlyAttendanceDetail(String month) {
        YearMonth yearMonth;
        try {
            yearMonth = YearMonth.parse(month, YM);
        } catch (Exception e) {
            throw new BusinessException("月份格式应为 yyyy-MM");
        }
        LocalDate monthStart = yearMonth.atDay(1);
        LocalDate monthEnd = yearMonth.atEndOfMonth();
        List<com.kk.biz.entity.HrArchive> attendanceArchives = archiveMapper.selectList(
                new LambdaQueryWrapper<com.kk.biz.entity.HrArchive>()
                        .select(com.kk.biz.entity.HrArchive::getUserId,
                                com.kk.biz.entity.HrArchive::getAttendanceCycleDay)
                        .eq(com.kk.biz.entity.HrArchive::getAttendanceEnabled, 1));
        Map<Long, Integer> cycleDayByUser = attendanceArchives.stream()
                .filter(archive -> archive.getUserId() != null)
                .collect(Collectors.toMap(com.kk.biz.entity.HrArchive::getUserId,
                        archive -> normalizedCycleDay(archive.getAttendanceCycleDay()),
                        (left, right) -> left));
        LocalDate queryStart = cycleDayByUser.isEmpty() ? monthStart : yearMonth.minusMonths(1).atDay(1);
        Map<LocalDate, Map<String, Object>> calendar = holidayCalendarService.calendarByDate(queryStart, monthEnd);
        // 考勤采用自然日口径：整月每天默认出勤，周末、节假日及尚未到来的日期也计入应出勤。
        int expectedDays = yearMonth.lengthOfMonth();
        Set<Long> attendanceUserIds = cycleDayByUser.keySet();
        if (attendanceUserIds.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("month", yearMonth.toString());
            empty.put("workdayCount", expectedDays);
            empty.put("calendar", new ArrayList<>(calendar.values()));
            empty.put("employees", List.of());
            return empty;
        }
        List<com.kk.system.entity.SysUser> enabledUsers = userService.list(
                new LambdaQueryWrapper<com.kk.system.entity.SysUser>()
                        .in(com.kk.system.entity.SysUser::getId, attendanceUserIds)
                        .eq(com.kk.system.entity.SysUser::getStatus, 1)
                        .orderByAsc(com.kk.system.entity.SysUser::getNickname)
                        .orderByAsc(com.kk.system.entity.SysUser::getUsername));
        List<HrLeaveRecord> absences = list(new LambdaQueryWrapper<HrLeaveRecord>()
                .ge(HrLeaveRecord::getLeaveDate, queryStart)
                .le(HrLeaveRecord::getLeaveDate, monthEnd)
                .orderByAsc(HrLeaveRecord::getLeaveDate));
        Map<Long, Map<LocalDate, HrLeaveRecord>> absenceByUser = absences.stream()
                .filter(row -> row.getUserId() != null && row.getLeaveDate() != null)
                .collect(Collectors.groupingBy(HrLeaveRecord::getUserId,
                        Collectors.toMap(HrLeaveRecord::getLeaveDate, row -> row, (left, right) -> left)));
        Set<Long> operatorIds = absences.stream().map(HrLeaveRecord::getCreateBy)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> operatorNames = new HashMap<>();
        if (!operatorIds.isEmpty()) {
            userService.listByIds(operatorIds).forEach(user -> operatorNames.put(user.getId(),
                    StringUtils.hasText(user.getNickname()) ? user.getNickname() : user.getUsername()));
        }
        List<Map<String, Object>> employees = new ArrayList<>();
        for (com.kk.system.entity.SysUser user : enabledUsers) {
            int cycleDay = cycleDayByUser.getOrDefault(user.getId(), 1);
            // 周期日是上一个周期的截止日，下一周期应从次日开始，避免首尾都包含而多算一天。
            LocalDate start = atCycleDay(yearMonth.minusMonths(1), cycleDay).plusDays(1);
            LocalDate end = atCycleDay(yearMonth, cycleDay);
            int employeeExpectedDays = (int) (end.toEpochDay() - start.toEpochDay() + 1);
            Map<LocalDate, HrLeaveRecord> userAbsences = absenceByUser.getOrDefault(user.getId(), Map.of());
            List<Map<String, Object>> days = new ArrayList<>();
            List<String> absentDates = new ArrayList<>();
            int absentWorkdays = 0;
            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                HrLeaveRecord absence = userAbsences.get(date);
                Map<String, Object> calendarDay = calendar.get(date);
                if (absence != null) {
                    absentDates.add(date.toString());
                    absentWorkdays++;
                }
                Map<String, Object> day = new LinkedHashMap<>();
                day.put("date", date.toString());
                day.put("weekday", "星期" + "一二三四五六日".charAt(date.getDayOfWeek().getValue() - 1));
                day.put("workday", true);
                day.put("holidayName", calendarDay == null ? "" : calendarDay.get("name"));
                day.put("dayType", calendarDay == null ? "WORKDAY" : calendarDay.get("type"));
                day.put("status", absence == null ? "PRESENT" : "ABSENT");
                day.put("reason", absence == null ? null : absence.getReason());
                day.put("operatorName", absence == null ? null : operatorNames.get(absence.getCreateBy()));
                day.put("registeredAt", absence == null ? null : absence.getCreateTime());
                days.add(day);
            }
            Map<String, Object> employee = new LinkedHashMap<>();
            employee.put("userId", user.getId());
            employee.put("employeeName", StringUtils.hasText(user.getNickname()) ? user.getNickname() : user.getUsername());
            employee.put("username", user.getUsername());
            employee.put("attendanceCycleDay", cycleDay);
            employee.put("periodStart", start.toString());
            employee.put("periodEnd", end.toString());
            employee.put("expectedDays", employeeExpectedDays);
            employee.put("presentDays", Math.max(0, employeeExpectedDays - absentWorkdays));
            employee.put("absentDays", absentWorkdays);
            employee.put("attendanceRate", employeeExpectedDays == 0 ? 100D
                    : Math.round((employeeExpectedDays - absentWorkdays) * 10000D / employeeExpectedDays) / 100D);
            employee.put("absentDates", absentDates);
            employee.put("days", days);
            employees.add(employee);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month", yearMonth.toString());
        result.put("workdayCount", expectedDays);
        result.put("calendar", new ArrayList<>(calendar.values()));
        result.put("employees", employees);
        return result;
    }

    private static int normalizedCycleDay(Integer cycleDay) {
        return cycleDay == null || cycleDay < 1 || cycleDay > 31 ? 1 : cycleDay;
    }

    private static LocalDate atCycleDay(YearMonth month, int cycleDay) {
        return month.atDay(Math.min(cycleDay, month.lengthOfMonth()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setAbsentUsers(LocalDate leaveDate, List<Long> userIds) {
        if (leaveDate == null) {
            throw new BusinessException("请选择日期");
        }
        Set<Long> attendanceUserIds = attendanceUserIds();
        Set<Long> enabledUsers = attendanceUserIds.isEmpty() ? Set.of() : userService.list(new LambdaQueryWrapper<com.kk.system.entity.SysUser>()
                        .select(com.kk.system.entity.SysUser::getId)
                        .in(com.kk.system.entity.SysUser::getId, attendanceUserIds)
                        .eq(com.kk.system.entity.SysUser::getStatus, 1))
                .stream().map(com.kk.system.entity.SysUser::getId).collect(Collectors.toSet());
        Set<Long> selected = userIds == null ? Set.of() : userIds.stream()
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (!enabledUsers.containsAll(selected)) {
            throw new BusinessException("所选员工不存在或已停用");
        }
        baseMapper.deleteAttendanceDay(leaveDate);
        for (Long userId : selected) {
            HrLeaveRecord row = new HrLeaveRecord();
            row.setCompanyId(0L);
            row.setUserId(userId);
            row.setLeaveDate(leaveDate);
            row.setReason("考勤管理员登记未出勤");
            save(row);
        }
    }

    @Override
    public int countLeaveDays(Long companyId, Long userId, String periodKey) {
        return listLeaveDates(companyId, userId, periodKey).size();
    }

    @Override
    public List<LocalDate> listLeaveDates(Long companyId, Long userId, String periodKey) {
        LocalDate[] range = periodRange(periodKey);
        if (range == null || userId == null) {
            return List.of();
        }
        return list(new LambdaQueryWrapper<HrLeaveRecord>()
                .eq(HrLeaveRecord::getUserId, userId)
                .ge(HrLeaveRecord::getLeaveDate, range[0])
                .le(HrLeaveRecord::getLeaveDate, range[1])
                .orderByAsc(HrLeaveRecord::getLeaveDate))
                .stream().map(HrLeaveRecord::getLeaveDate).filter(Objects::nonNull).distinct().toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void effectLeaveApproval(Long approvalId, Long companyId, Long userId,
                                    LocalDate startDate, LocalDate endDate, String reason) {
        if (companyId == null || userId == null || startDate == null || endDate == null) {
            throw new BusinessException("请假审批缺少必要字段");
        }
        if (endDate.isBefore(startDate)) {
            throw new BusinessException("请假日期无效");
        }
        for (LocalDate day : expandDays(startDate, endDate)) {
            Long exists = baseMapper.selectCount(new LambdaQueryWrapper<HrLeaveRecord>()
                    .eq(HrLeaveRecord::getUserId, userId)
                    .eq(HrLeaveRecord::getLeaveDate, day));
            if (exists != null && exists > 0) {
                continue;
            }
            HrLeaveRecord row = new HrLeaveRecord();
            row.setCompanyId(companyId);
            row.setUserId(userId);
            row.setLeaveDate(day);
            row.setApprovalId(approvalId);
            row.setReason(StringUtils.hasText(reason) ? reason.trim() : null);
            save(row);
        }
    }

    @Override
    public void assertLeaveRangeAvailable(Long companyId, Long userId, LocalDate startDate, LocalDate endDate) {
        if (userId == null || startDate == null || endDate == null) {
            throw new BusinessException("请假日期无效");
        }
        if (endDate.isBefore(startDate)) {
            throw new BusinessException("结束日期不能早于开始日期");
        }
        List<LocalDate> days = expandDays(startDate, endDate);
        for (LocalDate day : days) {
            Long exists = baseMapper.selectCount(new LambdaQueryWrapper<HrLeaveRecord>()
                    .eq(HrLeaveRecord::getUserId, userId)
                    .eq(HrLeaveRecord::getLeaveDate, day));
            if (exists != null && exists > 0) {
                throw new BusinessException(day + " 已有请假记录，请勿重复提交");
            }
        }
        List<WfApproval> pending = approvalMapper.selectList(new LambdaQueryWrapper<WfApproval>()
                .eq(WfApproval::getApplicantId, userId)
                .eq(WfApproval::getType, ApprovalTypes.LEAVE_APPLY)
                .eq(WfApproval::getStatus, "PENDING")
                .eq(companyId != null, WfApproval::getCompanyId, companyId)
                .orderByDesc(WfApproval::getId)
                .last("LIMIT 50"));
        for (WfApproval row : pending) {
            if (!StringUtils.hasText(row.getPayload())) {
                continue;
            }
            try {
                JSONObject p = JSONUtil.parseObj(row.getPayload());
                LocalDate ps = parsePayloadDate(p.get("startDate"));
                LocalDate pe = parsePayloadDate(p.get("endDate"));
                if (ps == null || pe == null) {
                    continue;
                }
                if (!pe.isBefore(startDate) && !ps.isAfter(endDate)) {
                    throw new BusinessException("已有在途请假审批（" + ps + " ~ " + pe + "），请勿重复提交");
                }
            } catch (BusinessException e) {
                throw e;
            } catch (Exception ignored) {
                // 历史脏 payload 忽略
            }
        }
    }

    private LocalDate parsePayloadDate(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof LocalDate d) {
            return d;
        }
        String text = String.valueOf(raw).trim();
        if (!StringUtils.hasText(text) || "null".equalsIgnoreCase(text)) {
            return null;
        }
        if (text.length() >= 10) {
            return LocalDate.parse(text.substring(0, 10));
        }
        return LocalDate.parse(text);
    }

    private Set<Long> attendanceUserIds() {
        return archiveMapper.selectList(new LambdaQueryWrapper<com.kk.biz.entity.HrArchive>()
                        .select(com.kk.biz.entity.HrArchive::getUserId)
                        .eq(com.kk.biz.entity.HrArchive::getAttendanceEnabled, 1))
                .stream().map(com.kk.biz.entity.HrArchive::getUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
    }

    public static LocalDate[] periodRange(String periodKey) {
        if (!StringUtils.hasText(periodKey)) {
            return null;
        }
        String key = periodKey.trim();
        if (key.matches("^\\d{4}-W\\d{2}$")) {
            int year = Integer.parseInt(key.substring(0, 4));
            int week = Integer.parseInt(key.substring(6, 8));
            LocalDate monday = LocalDate.of(year, 1, 4)
                    .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week)
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            return new LocalDate[]{monday, monday.plusDays(6)};
        }
        if (key.matches("^\\d{4}-\\d{2}$")) {
            // 月薪考勤：上月 21 日 ~ 本月 20 日（2026-09 → 2026-08-21 ~ 2026-09-20）
            YearMonth ym = YearMonth.parse(key, YM);
            return new LocalDate[]{ym.minusMonths(1).atDay(21), ym.atDay(20)};
        }
        return null;
    }

    private List<LocalDate> expandDays(LocalDate start, LocalDate end) {
        List<LocalDate> days = new ArrayList<>();
        LocalDate cur = start;
        while (!cur.isAfter(end)) {
            days.add(cur);
            cur = cur.plusDays(1);
        }
        return days;
    }

    private void assertCompanyVisible(long userId, Long companyId) {
        if (dataScopeService.isGlobalAdmin(userId)) {
            return;
        }
        if (companyId == null || !dataScopeService.visibleCompanyIds(userId).contains(companyId)) {
            throw new BusinessException("无权操作该公司");
        }
    }

    private void fillNames(List<HrLeaveRecord> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Set<Long> userIds = list.stream().map(HrLeaveRecord::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> companyIds = list.stream().map(HrLeaveRecord::getCompanyId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> userNames = new HashMap<>();
        if (!userIds.isEmpty()) {
            userService.listByIds(userIds).forEach(u ->
                    userNames.put(u.getId(), StringUtils.hasText(u.getNickname()) ? u.getNickname() : u.getUsername()));
        }
        Map<Long, String> companyNames = new HashMap<>();
        if (!companyIds.isEmpty()) {
            deptService.listByIds(companyIds).forEach(d -> companyNames.put(d.getId(), d.getName()));
        }
        for (HrLeaveRecord row : list) {
            row.setUserName(userNames.get(row.getUserId()));
            row.setCompanyName(companyNames.get(row.getCompanyId()));
        }
    }
}
