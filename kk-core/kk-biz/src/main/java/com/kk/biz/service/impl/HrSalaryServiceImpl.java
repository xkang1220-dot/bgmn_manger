package com.kk.biz.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kk.biz.dto.ApprovalSubmitRequest;
import com.kk.biz.entity.FinProjectAccount;
import com.kk.biz.entity.HrSalaryItem;
import com.kk.biz.entity.HrSalaryRun;
import com.kk.biz.entity.HrSalaryRunLine;
import com.kk.biz.entity.HrSalarySchedule;
import com.kk.biz.entity.HrArchive;
import com.kk.biz.entity.HrDutyRecord;
import com.kk.biz.entity.PmProject;
import com.kk.biz.mapper.HrSalaryItemMapper;
import com.kk.biz.mapper.HrSalaryRunLineMapper;
import com.kk.biz.mapper.HrSalaryRunMapper;
import com.kk.biz.mapper.HrSalaryScheduleMapper;
import com.kk.biz.mapper.HrArchiveMapper;
import com.kk.biz.mapper.HrDutyRecordMapper;
import com.kk.biz.mapper.PmProjectMapper;
import com.kk.biz.service.FinProjectAccountService;
import com.kk.biz.service.HrLeaveService;
import com.kk.biz.service.HrHolidayCalendarService;
import com.kk.biz.service.HrSalaryService;
import com.kk.biz.service.WfApprovalService;
import com.kk.biz.workflow.ApprovalTypes;
import com.kk.biz.workflow.ProjectFundTypes;
import com.kk.biz.workflow.ProjectScales;
import com.kk.common.exception.BusinessException;
import com.kk.system.entity.SysDept;
import com.kk.system.entity.SysUser;
import com.kk.system.service.DataScopeService;
import com.kk.system.service.SysDeptService;
import com.kk.system.service.SysNotificationService;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class HrSalaryServiceImpl implements HrSalaryService {

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final Pattern ISO_WEEK = Pattern.compile("^(\\d{4})-W(\\d{2})$");
    private static final String CYCLE_MONTHLY = "MONTHLY";
    private static final String CYCLE_WEEKLY = "WEEKLY";
    private static final WeekFields ISO_WEEKS = WeekFields.ISO;

    private final HrSalaryItemMapper itemMapper;
    private final HrSalaryScheduleMapper scheduleMapper;
    private final HrSalaryRunMapper runMapper;
    private final HrSalaryRunLineMapper lineMapper;
    private final HrArchiveMapper archiveMapper;
    private final HrDutyRecordMapper dutyRecordMapper;
    private final HrHolidayCalendarService holidayCalendarService;
    private final PmProjectMapper projectMapper;
    private final FinProjectAccountService projectAccountService;
    private final HrLeaveService leaveService;
    private final SysUserService userService;
    private final SysDeptService deptService;
    private final DataScopeService dataScopeService;
    private final SysNotificationService notificationService;
    private final WfApprovalService approvalService;
    private final PlatformTransactionManager transactionManager;
    private final ObjectProvider<HrSalaryService> selfProvider;

    @Override
    public List<HrSalaryItem> listItems(Long companyId, Long userId) {
        Set<Long> attendanceUsers = attendanceUserIds();
        if (attendanceUsers.isEmpty()) {
            return List.of();
        }
        List<HrSalaryItem> list = itemMapper.selectList(new LambdaQueryWrapper<HrSalaryItem>()
                .eq(companyId != null, HrSalaryItem::getCompanyId, companyId)
                .eq(userId != null, HrSalaryItem::getUserId, userId)
                .in(HrSalaryItem::getUserId, attendanceUsers)
                .orderByAsc(HrSalaryItem::getUserId)
                .orderByAsc(HrSalaryItem::getId));
        fillItemNames(list);
        for (HrSalaryItem item : list) {
            if (!StringUtils.hasText(item.getCycleType())) {
                item.setCycleType(CYCLE_MONTHLY);
            }
        }
        return list;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveItem(HrSalaryItem item) {
        if (item.getUserId() == null || item.getProjectId() == null) {
            throw new BusinessException("员工、出款项目不能为空");
        }
        PmProject project = projectMapper.selectById(item.getProjectId());
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        item.setCompanyId(project.getCompanyId());
        assertCompanyVisible(item.getCompanyId());
        if (!ProjectScales.isSalaryEligible(project)) {
            throw new BusinessException("仅可为重点项目（含重大下的小项目）配置工资；常规与重大外壳不可选");
        }
        if (item.getEnabled() == null) {
            item.setEnabled(1);
        }
        item.setCycleType(CYCLE_MONTHLY);
        String payMode = "DAILY".equalsIgnoreCase(item.getPayMode()) ? "DAILY" : "MONTHLY";
        item.setPayMode(payMode);
        int payDay = item.getPayDay() == null ? 20 : item.getPayDay();
        if (payDay < 1 || payDay > 28) {
            throw new BusinessException("发薪日仅支持每月 1–28 日");
        }
        item.setPayDay(payDay);
        HrArchive archive = archiveMapper.selectOne(new LambdaQueryWrapper<HrArchive>()
                .eq(HrArchive::getUserId, item.getUserId()).last("LIMIT 1"));
        item.setAttendanceEnabled(archive != null && Objects.equals(archive.getAttendanceEnabled(), 1) ? 1 : 0);
        if (item.getAttendanceEnabled() != 1) {
            throw new BusinessException("只能选择人员档案中已开启考勤的员工");
        }
        if ("MONTHLY".equals(payMode)) {
            if (item.getAmount() == null || item.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("月薪必须大于 0");
            }
            BigDecimal normal = item.getNormalCoefficient() == null ? BigDecimal.ONE : item.getNormalCoefficient();
            BigDecimal rest = item.getRestCoefficient() == null ? BigDecimal.ZERO : item.getRestCoefficient();
            if (normal.compareTo(BigDecimal.ZERO) < 0 || rest.compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException("工资系数不能为负数");
            }
            item.setNormalCoefficient(normal); item.setRestCoefficient(rest);
            item.setNormalDayRate(null); item.setRestDayRate(null);
        } else {
            if (item.getNormalDayRate() == null || item.getNormalDayRate().compareTo(BigDecimal.ZERO) < 0
                    || item.getRestDayRate() == null || item.getRestDayRate().compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException("请配置平常上班和周末节假日的日薪");
            }
            item.setAmount(BigDecimal.ZERO); item.setNormalRatio(BigDecimal.ZERO); item.setRestRatio(BigDecimal.ZERO);
        }
        HrSalaryItem dup = itemMapper.selectOne(new LambdaQueryWrapper<HrSalaryItem>()
                .eq(HrSalaryItem::getUserId, item.getUserId())
                .ne(item.getId() != null, HrSalaryItem::getId, item.getId())
                .last("LIMIT 1"));
        if (dup != null) {
            throw new BusinessException("该员工已有工资配置，请直接编辑原配置");
        }
        if (item.getId() == null) {
            itemMapper.insert(item);
        } else {
            itemMapper.updateById(item);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteItem(Long id) {
        HrSalaryItem existing = itemMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException("配置不存在");
        }
        assertCompanyVisible(existing.getCompanyId());
        itemMapper.deleteById(id);
    }

    @Override
    public HrSalarySchedule getSchedule(Long companyId) {
        if (companyId == null) {
            throw new BusinessException("请选择公司");
        }
        assertCompanyVisible(companyId);
        HrSalarySchedule schedule = scheduleMapper.selectOne(new LambdaQueryWrapper<HrSalarySchedule>()
                .eq(HrSalarySchedule::getCompanyId, companyId)
                .last("LIMIT 1"));
        if (schedule == null) {
            schedule = new HrSalarySchedule();
            schedule.setCompanyId(companyId);
            schedule.setCycleType("BOTH");
            schedule.setPayDay(20);
            schedule.setWeeklyPayDay(1);
            schedule.setPayHour(9);
            schedule.setPayMinute(0);
            schedule.setPreviewDays(3);
            schedule.setWeeklyPreviewDays(1);
            schedule.setEnabled(1);
            schedule.setPreviewEnabled(0);
        } else {
            if (!StringUtils.hasText(schedule.getCycleType())) {
                schedule.setCycleType("BOTH");
            }
            if (schedule.getWeeklyPayDay() == null) {
                schedule.setWeeklyPayDay(1);
            }
            if (schedule.getWeeklyPreviewDays() == null) {
                schedule.setWeeklyPreviewDays(1);
            }
            if (schedule.getPreviewEnabled() == null) {
                schedule.setPreviewEnabled(0);
            }
        }
        fillScheduleNames(schedule);
        return schedule;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveSchedule(HrSalarySchedule schedule) {
        if (schedule.getCompanyId() == null) {
            throw new BusinessException("请选择公司");
        }
        assertCompanyVisible(schedule.getCompanyId());
        // 公司维度月结+周结双开
        schedule.setCycleType("BOTH");
        int monthDay = schedule.getPayDay() == null ? 20 : schedule.getPayDay();
        if (monthDay < 1 || monthDay > 28) {
            throw new BusinessException("月结发薪日仅支持 1–28");
        }
        int weekDay = schedule.getWeeklyPayDay() == null ? 1 : schedule.getWeeklyPayDay();
        if (weekDay < 1 || weekDay > 7) {
            throw new BusinessException("周结发薪日仅支持周一至周日（1–7）");
        }
        int hour = schedule.getPayHour() == null ? 9 : schedule.getPayHour();
        int minute = schedule.getPayMinute() == null ? 0 : schedule.getPayMinute();
        int monthPreview = schedule.getPreviewDays() == null ? 3 : schedule.getPreviewDays();
        int weekPreview = schedule.getWeeklyPreviewDays() == null ? 1 : schedule.getWeeklyPreviewDays();
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            throw new BusinessException("发薪时间不正确");
        }
        if (monthPreview < 1 || monthPreview > 27) {
            throw new BusinessException("月结预告提前天数需在 1–27");
        }
        if (weekPreview < 1 || weekPreview > 6) {
            throw new BusinessException("周结确认提前天数需在 1–6");
        }
        schedule.setPayDay(monthDay);
        schedule.setWeeklyPayDay(weekDay);
        schedule.setPayHour(hour);
        schedule.setPayMinute(minute);
        schedule.setPreviewDays(monthPreview);
        schedule.setWeeklyPreviewDays(weekPreview);
        if (schedule.getEnabled() == null) {
            schedule.setEnabled(1);
        }
        if (schedule.getPreviewEnabled() == null) {
            schedule.setPreviewEnabled(0);
        } else if (schedule.getPreviewEnabled() != 0 && schedule.getPreviewEnabled() != 1) {
            throw new BusinessException("定时预告开关仅支持 0/1");
        }
        HrSalarySchedule existing = scheduleMapper.selectOne(new LambdaQueryWrapper<HrSalarySchedule>()
                .eq(HrSalarySchedule::getCompanyId, schedule.getCompanyId())
                .last("LIMIT 1"));
        if (existing == null) {
            scheduleMapper.insert(schedule);
        } else {
            schedule.setId(existing.getId());
            scheduleMapper.updateById(schedule);
        }
    }

    @Override
    public List<HrSalaryRun> listRuns(Long companyId, String yearMonth) {
        if (companyId == null) {
            throw new BusinessException("请选择公司");
        }
        assertCompanyVisible(companyId);
        List<HrSalaryRun> list = runMapper.selectList(new LambdaQueryWrapper<HrSalaryRun>()
                .eq(HrSalaryRun::getCompanyId, companyId)
                .eq(StringUtils.hasText(yearMonth), HrSalaryRun::getYearMonth, yearMonth)
                .orderByDesc(HrSalaryRun::getId));
        Map<Long, String> companyNames = deptService.listCompanies().stream()
                .collect(Collectors.toMap(SysDept::getId, SysDept::getName, (a, b) -> a));
        for (HrSalaryRun run : list) {
            run.setCompanyName(companyNames.get(run.getCompanyId()));
        }
        return list;
    }

    @Override
    public List<HrSalaryRunLine> listRunLines(Long runId) {
        HrSalaryRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new BusinessException("跑批记录不存在");
        }
        assertCompanyVisible(run.getCompanyId());
        List<HrSalaryRunLine> lines = lineMapper.selectList(new LambdaQueryWrapper<HrSalaryRunLine>()
                .eq(HrSalaryRunLine::getRunId, runId)
                .orderByAsc(HrSalaryRunLine::getId));
        fillLineNames(lines);
        return lines;
    }

    @Override
    public List<Map<String, Object>> myConfirmQueue(String yearMonth) {
        long uid = StpUtil.getLoginIdAsLong();
        List<String> periods;
        if (StringUtils.hasText(yearMonth)) {
            periods = List.of(normalizePeriod(null, yearMonth.trim()));
        } else {
            periods = List.of(YearMonth.now().format(YM), formatIsoWeek(LocalDate.now()));
        }
        List<HrSalaryRun> previews = runMapper.selectList(new LambdaQueryWrapper<HrSalaryRun>()
                .in(HrSalaryRun::getYearMonth, periods)
                .eq(HrSalaryRun::getPhase, "PREVIEW")
                .eq(HrSalaryRun::getStatus, "DONE")
                .orderByDesc(HrSalaryRun::getId));
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (HrSalaryRun run : previews) {
            String seenKey = run.getCompanyId() + "|" + run.getYearMonth();
            if (!seen.add(seenKey)) {
                continue;
            }
            HrSalaryRunLine line = lineMapper.selectOne(new LambdaQueryWrapper<HrSalaryRunLine>()
                    .eq(HrSalaryRunLine::getRunId, run.getId())
                    .eq(HrSalaryRunLine::getUserId, uid)
                    .last("LIMIT 1"));
            if (line == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("lineId", line.getId());
            row.put("runId", run.getId());
            row.put("companyId", run.getCompanyId());
            SysDept company = deptService.getById(run.getCompanyId());
            row.put("companyName", company == null ? null : company.getName());
            row.put("yearMonth", run.getYearMonth());
            row.put("cycleType", isWeeklyPeriod(run.getYearMonth()) ? CYCLE_WEEKLY : CYCLE_MONTHLY);
            row.put("status", line.getStatus());
            row.put("totalAmount", line.getTotalAmount());
            row.put("confirmedAt", line.getConfirmedAt());
            row.put("payload", StringUtils.hasText(line.getPayloadSnapshot())
                    ? JSONUtil.parseObj(line.getPayloadSnapshot())
                    : Map.of());
            result.add(row);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmMine(Long lineId) {
        HrSalaryRunLine line = requireMyPreviewLine(lineId);
        if ("CONFIRMED".equals(line.getStatus())) {
            return;
        }
        if (!"PENDING_CONFIRM".equals(line.getStatus())) {
            throw new BusinessException("当前状态不可确认");
        }
        line.setStatus("CONFIRMED");
        line.setConfirmedAt(LocalDateTime.now());
        line.setSkipReason(null);
        lineMapper.updateById(line);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeMine(Long lineId) {
        HrSalaryRunLine line = requireMyPreviewLine(lineId);
        if (!"CONFIRMED".equals(line.getStatus())) {
            throw new BusinessException("仅已确认记录可撤销");
        }
        HrSalaryRun run = runMapper.selectById(line.getRunId());
        if (run != null && payPhaseStarted(run.getCompanyId(), run.getYearMonth())) {
            throw new BusinessException(periodLabel(run.getYearMonth()) + "发薪已开始，无法撤销确认");
        }
        line.setStatus("PENDING_CONFIRM");
        line.setConfirmedAt(null);
        lineMapper.updateById(line);
    }

    @Override
    public List<Map<String, Object>> preparePayDraft(Long companyId, String yearMonth) {
        if (companyId == null) {
            throw new BusinessException("请选择公司");
        }
        assertCompanyVisible(companyId);
        String ym = normalizePeriod(companyId, yearMonth);
        String cycle = cycleOfPeriod(companyId, ym);
        Map<Long, List<HrSalaryItem>> byUser = enabledItemsByUser(companyId, cycle);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Long, List<HrSalaryItem>> e : byUser.entrySet()) {
            Long userId = e.getKey();
            if (hasApprovalCreated(companyId, ym, userId)) {
                continue;
            }
            Map<String, Object> payload = buildPayload(userId, ym, cycle, e.getValue());
            List<LocalDate> leaveDates = salaryLeaveDates(e.getValue().get(0), userId, ym);
            int leaveDays = leaveDates.size();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", userId);
            row.put("userName", payload.get("userName"));
            row.put("yearMonth", ym);
            row.put("cycleType", cycle);
            row.put("grossAmount", payload.get("totalAmount"));
            row.put("items", payload.get("items"));
            row.put("leaveDays", leaveDays);
            row.put("leaveDates", leaveDates.stream().map(LocalDate::toString).toList());
            row.put("fullAttendance", leaveDays <= 0);
            row.put("deductionAmount", BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            row.put("deductionRemark", "");
            row.put("netAmount", payload.get("totalAmount"));
            HrSalaryRunLine preview = findPreviewLine(companyId, ym, userId);
            if (preview != null) {
                row.put("previewStatus", preview.getStatus());
                row.put("previewLineId", preview.getId());
            }
            rows.add(row);
        }
        return rows;
    }

    @Override
    public HrSalaryRun preparePayConfirm(Long companyId, String yearMonth, List<Map<String, Object>> lines) {
        if (companyId == null) {
            throw new BusinessException("请选择公司");
        }
        assertCompanyVisible(companyId);
        if (lines == null || lines.isEmpty()) {
            throw new BusinessException("请至少选择一名发薪人员");
        }
        String ym = normalizePeriod(companyId, yearMonth);
        String cycle = cycleOfPeriod(companyId, ym);
        String periodWord = CYCLE_WEEKLY.equals(cycle) ? "周度" : "月度";
        Map<Long, List<HrSalaryItem>> byUser = enabledItemsByUser(companyId, cycle);

        // 先完整校验，避免中途失败留下 RUNNING 批次 / 作废旧确认却看不到新确认
        List<Map<String, Object>> prepared = new ArrayList<>();
        Set<Long> seenUsers = new HashSet<>();
        for (Map<String, Object> input : lines) {
            Long userId = toLong(input.get("userId"));
            if (userId == null) {
                throw new BusinessException("发薪确认缺少人员");
            }
            if (!seenUsers.add(userId)) {
                throw new BusinessException(userDisplayName(userId) + " 重复提交，请合并为一条");
            }
            List<HrSalaryItem> items = byUser.get(userId);
            if (items == null || items.isEmpty()) {
                throw new BusinessException("用户 " + userDisplayName(userId) + " 当期无启用工资配置");
            }
            if (hasApprovalCreated(companyId, ym, userId)) {
                throw new BusinessException(userDisplayName(userId) + " 本期已生成审批，勿重复确认");
            }
            Map<String, Object> base = buildPayload(userId, ym, cycle, items);
            BigDecimal gross = asAmount(base.get("totalAmount"));
            BigDecimal deduction = asAmount(input.get("deductionAmount"));
            String remark = input.get("deductionRemark") == null ? "" : String.valueOf(input.get("deductionRemark")).trim();
            List<LocalDate> leaveDates = salaryLeaveDates(items.get(0), userId, ym);
            int leaveDays = leaveDates.size();
            if (deduction.compareTo(BigDecimal.ZERO) > 0 && !StringUtils.hasText(remark)) {
                throw new BusinessException(userDisplayName(userId) + " 扣款请填写备注");
            }
            if (deduction.compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException(userDisplayName(userId) + " 扣款不能为负");
            }
            if (deduction.compareTo(gross) > 0) {
                throw new BusinessException(userDisplayName(userId) + " 扣款不能超过应发 ¥" + gross.toPlainString());
            }
            Map<String, Object> payload = applySalaryDeduction(base, deduction, remark, leaveDays, leaveDates);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", userId);
            row.put("payload", payload);
            prepared.add(row);
        }

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        HrSalaryRun run = tx.execute(status -> {
            HrSalaryRun r = new HrSalaryRun();
            r.setCompanyId(companyId);
            r.setYearMonth(ym);
            r.setPhase("PREVIEW");
            r.setStatus("RUNNING");
            r.setTriggerType("MANUAL");
            r.setStartedAt(LocalDateTime.now());
            runMapper.insert(r);
            return r;
        });
        if (run == null || run.getId() == null) {
            throw new BusinessException("创建发薪确认批次失败");
        }

        TransactionTemplate requiresNew = requiresNewTx();
        int notified = 0;
        try {
            for (Map<String, Object> row : prepared) {
                Long userId = toLong(row.get("userId"));
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = (Map<String, Object>) row.get("payload");
                // 作废同周期旧待确认/已确认预告，以本次财务确认为准
                invalidateOldPreviewLines(requiresNew, companyId, ym, userId);

                Long lineId = requiresNew.execute(status -> {
                    HrSalaryRunLine line = new HrSalaryRunLine();
                    line.setRunId(run.getId());
                    line.setUserId(userId);
                    line.setStatus("PENDING_CONFIRM");
                    line.setTotalAmount(asAmount(payload.get("totalAmount")));
                    line.setPayloadSnapshot(JSONUtil.toJsonStr(payload));
                    lineMapper.insert(line);
                    return line.getId();
                });
                String content = buildPayConfirmContent(payload);
                notificationService.notifyUser(
                        userId,
                        "工资确认 · " + ym,
                        truncate(content, 480),
                        "salary_preview",
                        lineId,
                        "/account/salary-confirm?yearMonth=" + ym);
                notified++;
            }

            int finalNotified = notified;
            tx.executeWithoutResult(status -> {
                HrSalaryRun fresh = runMapper.selectById(run.getId());
                if (fresh == null) {
                    return;
                }
                fresh.setStatus("DONE");
                fresh.setFinishedAt(LocalDateTime.now());
                fresh.setMessage(periodWord + "发薪确认已发送 " + finalNotified + " 人");
                runMapper.updateById(fresh);
                run.setStatus(fresh.getStatus());
                run.setFinishedAt(fresh.getFinishedAt());
                run.setMessage(fresh.getMessage());
            });
            return run;
        } catch (RuntimeException ex) {
            int finalNotified = notified;
            tx.executeWithoutResult(status -> {
                HrSalaryRun fresh = runMapper.selectById(run.getId());
                if (fresh == null || !"RUNNING".equals(fresh.getStatus())) {
                    return;
                }
                // 已写入的明细挂在本批次上：有成功发出的则标 DONE，避免员工确认队列读不到
                if (finalNotified > 0) {
                    fresh.setStatus("DONE");
                    fresh.setFinishedAt(LocalDateTime.now());
                    fresh.setMessage(periodWord + "发薪确认部分发送 " + finalNotified
                            + " 人后中断：" + truncate(ex.getMessage(), 160));
                } else {
                    fresh.setStatus("FAILED");
                    fresh.setFinishedAt(LocalDateTime.now());
                    fresh.setMessage("发薪确认失败：" + truncate(ex.getMessage(), 200));
                }
                runMapper.updateById(fresh);
            });
            throw ex;
        }
    }

    private void invalidateOldPreviewLines(TransactionTemplate requiresNew, Long companyId, String ym, Long userId) {
        requiresNew.executeWithoutResult(s -> {
            List<HrSalaryRun> previewRuns = runMapper.selectList(new LambdaQueryWrapper<HrSalaryRun>()
                    .eq(HrSalaryRun::getCompanyId, companyId)
                    .eq(HrSalaryRun::getYearMonth, ym)
                    .eq(HrSalaryRun::getPhase, "PREVIEW"));
            if (previewRuns.isEmpty()) {
                return;
            }
            List<Long> runIds = previewRuns.stream().map(HrSalaryRun::getId).toList();
            List<HrSalaryRunLine> oldLines = lineMapper.selectList(new LambdaQueryWrapper<HrSalaryRunLine>()
                    .in(HrSalaryRunLine::getRunId, runIds)
                    .eq(HrSalaryRunLine::getUserId, userId)
                    .in(HrSalaryRunLine::getStatus, "PENDING_CONFIRM", "CONFIRMED"));
            for (HrSalaryRunLine old : oldLines) {
                old.setStatus("SKIPPED");
                old.setSkipReason("已被新的发薪确认覆盖");
                lineMapper.updateById(old);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> applySalaryDeduction(Map<String, Object> base, BigDecimal deduction, String remark,
                                                     int leaveDays, List<LocalDate> leaveDates) {
        Map<String, Object> payload = new LinkedHashMap<>(base);
        BigDecimal gross = asAmount(base.get("totalAmount")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal ded = deduction == null ? BigDecimal.ZERO : deduction.setScale(2, RoundingMode.HALF_UP);
        BigDecimal net = gross.subtract(ded).setScale(2, RoundingMode.HALF_UP);
        List<Map<String, Object>> items = (List<Map<String, Object>>) base.get("items");
        List<Map<String, Object>> adjusted = new ArrayList<>();
        if (items != null && !items.isEmpty()) {
            if (ded.compareTo(BigDecimal.ZERO) == 0 || gross.compareTo(BigDecimal.ZERO) == 0) {
                for (Map<String, Object> item : items) {
                    adjusted.add(new LinkedHashMap<>(item));
                }
            } else {
                BigDecimal allocated = BigDecimal.ZERO;
                for (int i = 0; i < items.size(); i++) {
                    Map<String, Object> src = items.get(i);
                    Map<String, Object> row = new LinkedHashMap<>(src);
                    BigDecimal origin = asAmount(src.get("amount")).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal next;
                    if (i == items.size() - 1) {
                        next = net.subtract(allocated).setScale(2, RoundingMode.HALF_UP);
                    } else {
                        next = origin.multiply(net).divide(gross, 2, RoundingMode.HALF_UP);
                        allocated = allocated.add(next);
                    }
                    if (next.compareTo(BigDecimal.ZERO) < 0) {
                        next = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                    }
                    row.put("amount", next);
                    row.put("originAmount", origin);
                    adjusted.add(row);
                }
            }
        }
        payload.put("items", adjusted);
        payload.put("grossAmount", gross);
        payload.put("deductionAmount", ded);
        payload.put("deductionRemark", StringUtils.hasText(remark) ? remark : null);
        payload.put("leaveDays", leaveDays);
        payload.put("leaveDates", leaveDates == null ? List.of()
                : leaveDates.stream().map(LocalDate::toString).toList());
        payload.put("fullAttendance", leaveDays <= 0);
        payload.put("totalAmount", net);
        return payload;
    }

    private String buildPayConfirmContent(Map<String, Object> payload) {
        boolean weekly = CYCLE_WEEKLY.equals(String.valueOf(payload.get("cycleType")));
        String scope = weekly ? "本周" : "本月";
        StringBuilder sb = new StringBuilder();
        sb.append(scope).append("应发 ").append(payload.get("grossAmount")).append(" 元");
        BigDecimal ded = asAmount(payload.get("deductionAmount"));
        if (ded.compareTo(BigDecimal.ZERO) > 0) {
            sb.append("，扣款 ").append(ded).append(" 元");
            Object remark = payload.get("deductionRemark");
            if (remark != null && StringUtils.hasText(String.valueOf(remark))) {
                sb.append("（").append(remark).append("）");
            }
        }
        sb.append("，实发 ").append(payload.get("totalAmount")).append(" 元，请确认");
        return sb.toString();
    }

    private Long toLong(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public HrSalaryRun runPreview(Long companyId, String yearMonth, boolean manual) {
        if (companyId == null) {
            throw new BusinessException("请选择公司");
        }
        String ym = normalizePeriod(companyId, yearMonth);
        String cycle = cycleOfPeriod(companyId, ym);
        if (!manual) {
            assertCompanyExists(companyId);
        } else {
            assertCompanyVisible(companyId);
        }
        HrSalaryRun existing = latestDone(companyId, ym, "PREVIEW");
        if (existing != null && !manual) {
            return existing;
        }

        Map<Long, List<HrSalaryItem>> byUser = enabledItemsByUser(companyId, cycle);
        boolean hasNew = byUser.keySet().stream().anyMatch(uid -> findPreviewLine(companyId, ym, uid) == null);
        if (!hasNew) {
            if (existing != null) {
                return existing;
            }
            // 无配置也记一笔空跑，避免 cron 反复插入
        }

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        HrSalaryRun run = tx.execute(status -> {
            HrSalaryRun r = new HrSalaryRun();
            r.setCompanyId(companyId);
            r.setYearMonth(ym);
            r.setPhase("PREVIEW");
            r.setStatus("RUNNING");
            r.setTriggerType(manual ? "MANUAL" : "CRON");
            r.setStartedAt(LocalDateTime.now());
            runMapper.insert(r);
            return r;
        });
        if (run == null || run.getId() == null) {
            throw new BusinessException("创建预告批次失败");
        }

        int notified = 0;
        TransactionTemplate requiresNew = requiresNewTx();
        for (Map.Entry<Long, List<HrSalaryItem>> e : byUser.entrySet()) {
            Long userId = e.getKey();
            if (findPreviewLine(companyId, ym, userId) != null) {
                continue;
            }
            Map<String, Object> payload = buildPayload(userId, ym, cycle, e.getValue());
            Long lineId = requiresNew.execute(status -> {
                HrSalaryRunLine line = new HrSalaryRunLine();
                line.setRunId(run.getId());
                line.setUserId(userId);
                line.setStatus("PENDING_CONFIRM");
                line.setTotalAmount(asAmount(payload.get("totalAmount")));
                line.setPayloadSnapshot(JSONUtil.toJsonStr(payload));
                lineMapper.insert(line);
                return line.getId();
            });
            notificationService.notifyUser(
                    userId,
                    "工资确认 · " + ym,
                    truncate(buildPreviewContent(payload), 480),
                    "salary_preview",
                    lineId,
                    "/account/salary-confirm?yearMonth=" + ym);
            notified++;
        }
        int finalNotified = notified;
        tx.executeWithoutResult(status -> {
            HrSalaryRun fresh = runMapper.selectById(run.getId());
            if (fresh == null) {
                return;
            }
            fresh.setStatus("DONE");
            fresh.setFinishedAt(LocalDateTime.now());
            fresh.setMessage("预告完成，新增待确认 " + finalNotified + " 人");
            runMapper.updateById(fresh);
            run.setStatus(fresh.getStatus());
            run.setFinishedAt(fresh.getFinishedAt());
            run.setMessage(fresh.getMessage());
        });
        return run;
    }

    @Override
    public HrSalaryRun runPay(Long companyId, String yearMonth, boolean manual) {
        if (companyId == null) {
            throw new BusinessException("请选择公司");
        }
        String ym = normalizePeriod(companyId, yearMonth);
        String cycle = cycleOfPeriod(companyId, ym);
        String periodWord = CYCLE_WEEKLY.equals(cycle) ? "周度" : "月度";
        if (manual) {
            assertCompanyVisible(companyId);
        } else {
            assertCompanyExists(companyId);
        }

        // 本周期是否已有发薪批次：后续补跑不再给「未确认」重复记跳过
        boolean firstPayWave = latestDone(companyId, ym, "PAY") == null;

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        HrSalaryRun run = tx.execute(status -> {
            HrSalaryRun r = new HrSalaryRun();
            r.setCompanyId(companyId);
            r.setYearMonth(ym);
            r.setPhase("PAY");
            r.setStatus("RUNNING");
            r.setTriggerType(manual ? "MANUAL" : "CRON");
            r.setStartedAt(LocalDateTime.now());
            runMapper.insert(r);
            return r;
        });
        if (run == null || run.getId() == null) {
            throw new BusinessException("创建发薪批次失败");
        }

        Long submitterId = resolveSubmitter(companyId, manual);
        Map<Long, List<HrSalaryItem>> byUser = enabledItemsByUser(companyId, cycle);
        int created = 0;
        int skipped = 0;
        TransactionTemplate requiresNew = requiresNewTx();

        for (Map.Entry<Long, List<HrSalaryItem>> e : byUser.entrySet()) {
            Long userId = e.getKey();
            if (hasApprovalCreated(companyId, ym, userId)) {
                continue;
            }
            HrSalaryRunLine previewLine = findPreviewLine(companyId, ym, userId);
            Map<String, Object> payload;
            if (previewLine != null && StringUtils.hasText(previewLine.getPayloadSnapshot())) {
                @SuppressWarnings("unchecked")
                Map<String, Object> snap = JSONUtil.parseObj(previewLine.getPayloadSnapshot());
                payload = snap;
            } else {
                payload = buildPayload(userId, ym, cycle, e.getValue());
            }

            if (previewLine == null || !"CONFIRMED".equals(previewLine.getStatus())) {
                if (firstPayWave) {
                    insertPayLine(requiresNew, run.getId(), userId, payload, "SKIPPED", "未确认工资", null, null);
                    skipped++;
                }
                continue;
            }
            String balanceReason = checkPayloadBalances(payload);
            if (balanceReason != null) {
                insertPayLine(requiresNew, run.getId(), userId, payload, "SKIPPED", balanceReason, null, previewLine.getConfirmedAt());
                skipped++;
                continue;
            }
            try {
                payload.put("confirmedAt", previewLine.getConfirmedAt() == null
                        ? null
                        : previewLine.getConfirmedAt().toString());
                Long approvalId = requiresNew.execute(status -> {
                    ApprovalSubmitRequest req = new ApprovalSubmitRequest();
                    req.setType(ApprovalTypes.SALARY_MONTHLY);
                    req.setCompanyId(companyId);
                    req.setAmount(asAmount(payload.get("totalAmount")));
                    req.setTitle(periodWord + "工资 · " + userDisplayName(userId) + " · " + ym);
                    req.setPayload(payload);
                    req.setRemark(periodWord + "工资 " + ym);
                    var approval = approvalService.submitAs(submitterId, req);
                    HrSalaryRunLine line = new HrSalaryRunLine();
                    line.setRunId(run.getId());
                    line.setUserId(userId);
                    line.setStatus("APPROVAL_CREATED");
                    line.setApprovalId(approval.getId());
                    line.setConfirmedAt(previewLine.getConfirmedAt());
                    line.setTotalAmount(asAmount(payload.get("totalAmount")));
                    line.setPayloadSnapshot(JSONUtil.toJsonStr(payload));
                    lineMapper.insert(line);
                    return approval.getId();
                });
                if (approvalId != null) {
                    created++;
                }
            } catch (Exception ex) {
                log.warn("{}工资生成审批失败 userId={}: {}", periodWord, userId, ex.getMessage());
                insertPayLine(requiresNew, run.getId(), userId, payload, "FAILED",
                        truncate(ex.getMessage(), 480), null, previewLine.getConfirmedAt());
                skipped++;
            }
        }

        // 若本轮无人可处理且非首轮，避免留下空批次噪音：仍标记 DONE 方便追溯
        int finalCreated = created;
        int finalSkipped = skipped;
        tx.executeWithoutResult(status -> {
            HrSalaryRun fresh = runMapper.selectById(run.getId());
            if (fresh == null) {
                return;
            }
            fresh.setStatus("DONE");
            fresh.setFinishedAt(LocalDateTime.now());
            fresh.setMessage("发薪完成：生成审批 " + finalCreated + "，跳过/失败 " + finalSkipped);
            runMapper.updateById(fresh);
            run.setStatus(fresh.getStatus());
            run.setFinishedAt(fresh.getFinishedAt());
            run.setMessage(fresh.getMessage());
        });
        return run;
    }

    @Override
    public void scanSchedules() {
        LocalDateTime now = LocalDateTime.now();
        HrSalaryService self = selfProvider.getObject();
        List<HrSalarySchedule> schedules = scheduleMapper.selectList(new LambdaQueryWrapper<HrSalarySchedule>()
                .eq(HrSalarySchedule::getEnabled, 1));
        for (HrSalarySchedule schedule : schedules) {
            Long companyId = schedule.getCompanyId();
            try {
                String monthlyPreview = resolveMonthlyPreview(schedule, now);
                if (monthlyPreview != null
                        && Integer.valueOf(1).equals(schedule.getPreviewEnabled())
                        && latestDone(companyId, monthlyPreview, "PREVIEW") == null) {
                    self.runPreview(companyId, monthlyPreview, false);
                }
                String monthlyPay = resolveMonthlyPay(schedule, now);
                if (monthlyPay != null && shouldCronPay(companyId, monthlyPay)) {
                    self.runPay(companyId, monthlyPay, false);
                }
            } catch (Exception e) {
                log.error("月结工资定时任务失败 companyId={}: {}", companyId, e.getMessage());
            }
        }
    }

    private TransactionTemplate requiresNewTx() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }

    private void insertPayLine(TransactionTemplate requiresNew, Long runId, Long userId,
                               Map<String, Object> payload, String status, String reason,
                               Long approvalId, LocalDateTime confirmedAt) {
        requiresNew.executeWithoutResult(s -> {
            HrSalaryRunLine line = new HrSalaryRunLine();
            line.setRunId(runId);
            line.setUserId(userId);
            line.setStatus(status);
            line.setSkipReason(reason);
            line.setApprovalId(approvalId);
            line.setConfirmedAt(confirmedAt);
            line.setTotalAmount(asAmount(payload.get("totalAmount")));
            line.setPayloadSnapshot(JSONUtil.toJsonStr(payload));
            lineMapper.insert(line);
        });
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private BigDecimal asAmount(Object raw) {
        if (raw == null) {
            return BigDecimal.ZERO;
        }
        if (raw instanceof BigDecimal bd) {
            return bd;
        }
        if (raw instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return new BigDecimal(String.valueOf(raw));
    }

    /**
     * 月结预告日可能落在上月末。
     */
    private String resolveMonthlyPreview(HrSalarySchedule s, LocalDateTime now) {
        if (!timeReached(s, now)) {
            return null;
        }
        LocalDate today = now.toLocalDate();
        YearMonth cur = YearMonth.from(now);
        for (YearMonth ym : List.of(cur, cur.plusMonths(1))) {
            if (today.equals(monthlyPreviewDateOf(ym, s))) {
                return ym.format(YM);
            }
        }
        return null;
    }

    private String resolveMonthlyPay(HrSalarySchedule s, LocalDateTime now) {
        if (!timeReached(s, now)) {
            return null;
        }
        LocalDate today = now.toLocalDate();
        YearMonth cur = YearMonth.from(now);
        if (today.getDayOfMonth() == clampDay(s.getPayDay()) && today.equals(payDateOf(cur, s))) {
            return cur.format(YM);
        }
        return null;
    }

    private String resolveWeeklyPreview(HrSalarySchedule s, LocalDateTime now) {
        if (!timeReached(s, now)) {
            return null;
        }
        LocalDate today = now.toLocalDate();
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        for (int offset = 0; offset <= 1; offset++) {
            LocalDate monday = weekStart.plusWeeks(offset);
            LocalDate payDate = monday.plusDays(clampWeekDay(s.getWeeklyPayDay()) - 1L);
            LocalDate previewDate = payDate.minusDays(weeklyPreviewDaysOf(s));
            if (today.equals(previewDate)) {
                return formatIsoWeek(payDate);
            }
        }
        return null;
    }

    private String resolveWeeklyPay(HrSalarySchedule s, LocalDateTime now) {
        if (!timeReached(s, now)) {
            return null;
        }
        LocalDate today = now.toLocalDate();
        if (today.getDayOfWeek().getValue() == clampWeekDay(s.getWeeklyPayDay())) {
            return formatIsoWeek(today);
        }
        return null;
    }

    private boolean timeReached(HrSalarySchedule s, LocalDateTime now) {
        return !now.toLocalTime().isBefore(java.time.LocalTime.of(nzHour(s.getPayHour()), nzMinute(s.getPayMinute())));
    }

    private LocalDate payDateOf(YearMonth ym, HrSalarySchedule s) {
        return ym.atDay(clampDay(s.getPayDay()));
    }

    private LocalDate monthlyPreviewDateOf(YearMonth ym, HrSalarySchedule s) {
        return payDateOf(ym, s).minusDays(monthlyPreviewDaysOf(s));
    }

    private int monthlyPreviewDaysOf(HrSalarySchedule s) {
        return s.getPreviewDays() == null ? 3 : s.getPreviewDays();
    }

    private int weeklyPreviewDaysOf(HrSalarySchedule s) {
        return s.getWeeklyPreviewDays() == null ? 1 : s.getWeeklyPreviewDays();
    }

    private boolean shouldCronPay(Long companyId, String ym) {
        String cycle = isWeeklyPeriod(ym) ? CYCLE_WEEKLY : CYCLE_MONTHLY;
        Map<Long, List<HrSalaryItem>> byUser = enabledItemsByUser(companyId, cycle);
        for (Long userId : byUser.keySet()) {
            if (hasApprovalCreated(companyId, ym, userId)) {
                continue;
            }
            HrSalaryRunLine preview = findPreviewLine(companyId, ym, userId);
            if (preview != null && "CONFIRMED".equals(preview.getStatus())) {
                return true;
            }
        }
        // 无人确认则不跑定时发薪，避免空批次把未确认全标成「跳过」
        return false;
    }

    private int clampDay(Integer day) {
        int d = day == null ? 20 : day;
        return Math.max(1, Math.min(28, d));
    }

    private int clampWeekDay(Integer day) {
        int d = day == null ? 1 : day;
        return Math.max(1, Math.min(7, d));
    }

    private int nzHour(Integer h) {
        return h == null ? 9 : h;
    }

    private int nzMinute(Integer m) {
        return m == null ? 0 : m;
    }

    private Long resolveSubmitter(Long companyId, boolean manual) {
        if (manual && StpUtil.isLogin()) {
            return StpUtil.getLoginIdAsLong();
        }
        HrSalarySchedule schedule = scheduleMapper.selectOne(new LambdaQueryWrapper<HrSalarySchedule>()
                .eq(HrSalarySchedule::getCompanyId, companyId)
                .last("LIMIT 1"));
        if (schedule != null && schedule.getSubmitterUserId() != null) {
            return schedule.getSubmitterUserId();
        }
        List<Long> admins = dataScopeService.listUserIdsByRoleCodeInCompany("admin", companyId);
        if (!admins.isEmpty()) {
            return admins.get(0);
        }
        List<Long> finance = dataScopeService.listUserIdsByRoleCodeInCompany("finance", companyId);
        if (!finance.isEmpty()) {
            return finance.get(0);
        }
        throw new BusinessException("未配置发薪代提交人，且公司无管理员/财务");
    }

    private Map<Long, List<HrSalaryItem>> enabledItemsByUser(Long companyId, String cycleType) {
        String cycle = normalizeCycleType(cycleType);
        Set<Long> attendanceUsers = attendanceUserIds();
        if (attendanceUsers.isEmpty()) {
            return Map.of();
        }
        List<HrSalaryItem> items = itemMapper.selectList(new LambdaQueryWrapper<HrSalaryItem>()
                .eq(HrSalaryItem::getCompanyId, companyId)
                .in(HrSalaryItem::getUserId, attendanceUsers)
                .eq(HrSalaryItem::getEnabled, 1));
        Set<Long> projectIds = items.stream().map(HrSalaryItem::getProjectId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, PmProject> projects = projectIds.isEmpty() ? Map.of()
                : projectMapper.selectBatchIds(projectIds).stream()
                .collect(Collectors.toMap(PmProject::getId, p -> p, (a, b) -> a));
        Map<Long, List<HrSalaryItem>> map = new LinkedHashMap<>();
        for (HrSalaryItem item : items) {
            String itemCycle = StringUtils.hasText(item.getCycleType())
                    ? normalizeCycleType(item.getCycleType())
                    : CYCLE_MONTHLY;
            if (!cycle.equals(itemCycle)) {
                continue;
            }
            PmProject project = projects.get(item.getProjectId());
            if (!ProjectScales.isSalaryEligible(project)) {
                continue;
            }
            map.computeIfAbsent(item.getUserId(), k -> new ArrayList<>()).add(item);
        }
        return map;
    }

    private Set<Long> attendanceUserIds() {
        return archiveMapper.selectList(new LambdaQueryWrapper<HrArchive>()
                        .select(HrArchive::getUserId)
                        .eq(HrArchive::getAttendanceEnabled, 1))
                .stream().map(HrArchive::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private Map<String, Object> buildPayload(Long userId, String ym, String cycleType, List<HrSalaryItem> items) {
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (HrSalaryItem item : items) {
            PmProject p = projectMapper.selectById(item.getProjectId());
            Map<String, Object> row = new LinkedHashMap<>();
            SalaryAmount calculated = calculateSalary(item, userId, ym);
            BigDecimal actualAmount = calculated.amount();
            FinProjectAccount account = projectAccountService.getOrCreate(item.getProjectId());
            String fundType = ProjectFundTypes.SHARE_PENDING;
            try {
                fundType = ProjectFundTypes.pickExpensePool(
                        account.getSharePendingBalance(), account.getNonShareBalance(), actualAmount);
            } catch (BusinessException ignored) {
                // 余额不足时仍带默认池，发薪阶段 checkBalances 会跳过
            }
            row.put("projectId", item.getProjectId());
            row.put("projectName", p == null ? ("#" + item.getProjectId()) : p.getName());
            row.put("amount", actualAmount);
            row.put("payMode", item.getPayMode());
            row.put("attendanceEnabled", item.getAttendanceEnabled());
            row.put("normalDays", calculated.normalDays());
            row.put("restDays", calculated.restDays());
            row.put("absentNormalDays", calculated.absentNormalDays());
            row.put("absentRestDays", calculated.absentRestDays());
            row.put("fundType", fundType);
            rows.add(row);
            total = total.add(actualAmount);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("userName", userDisplayName(userId));
        payload.put("yearMonth", ym);
        payload.put("cycleType", cycleType);
        payload.put("totalAmount", total);
        payload.put("items", rows);
        return payload;
    }

    private SalaryAmount calculateSalary(HrSalaryItem item, Long userId, String period) {
        String mode = "DAILY".equalsIgnoreCase(item.getPayMode()) ? "DAILY" : "MONTHLY";
        HrArchive archive = archiveMapper.selectOne(new LambdaQueryWrapper<HrArchive>()
                .eq(HrArchive::getUserId, userId).last("LIMIT 1"));
        boolean attendanceEnabled = archive != null && Objects.equals(archive.getAttendanceEnabled(), 1);
        if (!attendanceEnabled) {
            BigDecimal amount = "DAILY".equals(mode) ? BigDecimal.ZERO : item.getAmount();
            return new SalaryAmount(nz(amount), 0, 0, 0, 0);
        }
        LocalDate[] range = salaryPeriodRange(item, period);
        Map<LocalDate, Map<String, Object>> calendar = holidayCalendarService.calendarByDate(range[0], range[1]);
        Set<LocalDate> dutyDays = dutyRecordMapper.selectList(new LambdaQueryWrapper<HrDutyRecord>()
                        .eq(HrDutyRecord::getUserId, userId)
                        .ge(HrDutyRecord::getDutyDate, range[0]).le(HrDutyRecord::getDutyDate, range[1]))
                .stream().map(HrDutyRecord::getDutyDate).collect(Collectors.toSet());
        Set<LocalDate> absent = new HashSet<>(salaryLeaveDates(item, userId, period));
        int normal = 0, rest = 0, absentNormal = 0, absentRest = 0;
        for (LocalDate d = range[0]; !d.isAfter(range[1]); d = d.plusDays(1)) {
            Map<String, Object> day = calendar.get(d);
            boolean isNormal = dutyDays.contains(d) || (day != null && Boolean.TRUE.equals(day.get("workday")));
            if (isNormal) { normal++; if (absent.contains(d)) absentNormal++; }
            else { rest++; if (absent.contains(d)) absentRest++; }
        }
        BigDecimal result;
        if ("DAILY".equals(mode)) {
            result = nz(item.getNormalDayRate()).multiply(BigDecimal.valueOf(normal - absentNormal))
                    .add(nz(item.getRestDayRate()).multiply(BigDecimal.valueOf(rest - absentRest)));
        } else {
            int calendarDays = YearMonth.parse(period, YM).lengthOfMonth();
            BigDecimal dailyBase = nz(item.getAmount()).divide(BigDecimal.valueOf(calendarDays), 8, RoundingMode.HALF_UP);
            BigDecimal weightedDays = nz(item.getNormalCoefficient()).multiply(BigDecimal.valueOf(normal - absentNormal))
                    .add(nz(item.getRestCoefficient()).multiply(BigDecimal.valueOf(rest - absentRest)));
            result = dailyBase.multiply(weightedDays);
        }
        return new SalaryAmount(result.setScale(2, RoundingMode.HALF_UP), normal, rest, absentNormal, absentRest);
    }

    private BigDecimal nz(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

    private LocalDate[] salaryPeriodRange(HrSalaryItem item, String period) {
        YearMonth current = YearMonth.parse(period, YM);
        int payDay = item.getPayDay() == null ? 20 : item.getPayDay();
        return new LocalDate[]{current.minusMonths(1).atDay(payDay).plusDays(1), current.atDay(payDay)};
    }

    private List<LocalDate> salaryLeaveDates(HrSalaryItem item, Long userId, String period) {
        LocalDate[] range = salaryPeriodRange(item, period);
        return leaveService.listAttendance(range[0], range[1]).stream()
                .filter(row -> Objects.equals(row.getUserId(), userId))
                .map(row -> row.getLeaveDate()).filter(Objects::nonNull).distinct().sorted().toList();
    }

    private record SalaryAmount(BigDecimal amount, int normalDays, int restDays,
                                int absentNormalDays, int absentRestDays) {}

    private String buildPreviewContent(Map<String, Object> payload) {
        boolean weekly = CYCLE_WEEKLY.equals(String.valueOf(payload.get("cycleType")));
        String scope = weekly ? "本周" : "本月";
        StringBuilder sb = new StringBuilder();
        sb.append(scope).append("工资合计 ").append(payload.get("totalAmount")).append(" 元：");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                Map<String, Object> it = items.get(i);
                if (i > 0) {
                    sb.append("；");
                }
                sb.append(it.get("projectName")).append(" ").append(it.get("amount"));
            }
        }
        sb.append("。请尽快在「工资确认」中确认，未确认将不进入").append(scope).append("发薪。");
        return sb.toString();
    }

    private String checkBalances(List<HrSalaryItem> items) {
        StringBuilder sb = new StringBuilder();
        for (HrSalaryItem item : items) {
            FinProjectAccount account = projectAccountService.getOrCreate(item.getProjectId());
            try {
                ProjectFundTypes.pickExpensePool(
                        account.getSharePendingBalance(), account.getNonShareBalance(), item.getAmount());
            } catch (BusinessException ex) {
                PmProject p = projectMapper.selectById(item.getProjectId());
                String name = p == null ? ("#" + item.getProjectId()) : p.getName();
                if (sb.length() > 0) {
                    sb.append("；");
                }
                sb.append("项目 ").append(name).append(" ").append(ex.getMessage());
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String checkPayloadBalances(Map<String, Object> payload) {
        if (payload == null) {
            return "工资明细为空";
        }
        Object rawItems = payload.get("items");
        if (!(rawItems instanceof List<?> list) || list.isEmpty()) {
            return "工资明细为空";
        }
        StringBuilder sb = new StringBuilder();
        for (Object raw : list) {
            Map<String, Object> row;
            if (raw instanceof Map<?, ?> m) {
                row = (Map<String, Object>) m;
            } else {
                continue;
            }
            Long projectId = toLong(row.get("projectId"));
            BigDecimal amount = asAmount(row.get("amount"));
            if (projectId == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            FinProjectAccount account = projectAccountService.getOrCreate(projectId);
            try {
                ProjectFundTypes.pickExpensePool(
                        account.getSharePendingBalance(), account.getNonShareBalance(), amount);
            } catch (BusinessException ex) {
                String name = row.get("projectName") == null ? ("#" + projectId) : String.valueOf(row.get("projectName"));
                if (sb.length() > 0) {
                    sb.append("；");
                }
                sb.append("项目 ").append(name).append(" ").append(ex.getMessage());
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private HrSalaryRunLine findPreviewLine(Long companyId, String ym, Long userId) {
        List<HrSalaryRun> runs = runMapper.selectList(new LambdaQueryWrapper<HrSalaryRun>()
                .eq(HrSalaryRun::getCompanyId, companyId)
                .eq(HrSalaryRun::getYearMonth, ym)
                .eq(HrSalaryRun::getPhase, "PREVIEW")
                .eq(HrSalaryRun::getStatus, "DONE")
                .orderByDesc(HrSalaryRun::getId));
        HrSalaryRunLine fallback = null;
        for (HrSalaryRun run : runs) {
            HrSalaryRunLine line = lineMapper.selectOne(new LambdaQueryWrapper<HrSalaryRunLine>()
                    .eq(HrSalaryRunLine::getRunId, run.getId())
                    .eq(HrSalaryRunLine::getUserId, userId)
                    .in(HrSalaryRunLine::getStatus, "PENDING_CONFIRM", "CONFIRMED")
                    .orderByDesc(HrSalaryRunLine::getId)
                    .last("LIMIT 1"));
            if (line != null) {
                if ("CONFIRMED".equals(line.getStatus())) {
                    return line;
                }
                if (fallback == null) {
                    fallback = line;
                }
            }
        }
        return fallback;
    }

    private boolean hasApprovalCreated(Long companyId, String ym, Long userId) {
        List<HrSalaryRun> runs = runMapper.selectList(new LambdaQueryWrapper<HrSalaryRun>()
                .eq(HrSalaryRun::getCompanyId, companyId)
                .eq(HrSalaryRun::getYearMonth, ym)
                .eq(HrSalaryRun::getPhase, "PAY")
                .orderByDesc(HrSalaryRun::getId));
        for (HrSalaryRun run : runs) {
            Long cnt = lineMapper.selectCount(new LambdaQueryWrapper<HrSalaryRunLine>()
                    .eq(HrSalaryRunLine::getRunId, run.getId())
                    .eq(HrSalaryRunLine::getUserId, userId)
                    .eq(HrSalaryRunLine::getStatus, "APPROVAL_CREATED"));
            if (cnt != null && cnt > 0) {
                return true;
            }
        }
        return false;
    }

    private boolean payPhaseStarted(Long companyId, String ym) {
        Long cnt = runMapper.selectCount(new LambdaQueryWrapper<HrSalaryRun>()
                .eq(HrSalaryRun::getCompanyId, companyId)
                .eq(HrSalaryRun::getYearMonth, ym)
                .eq(HrSalaryRun::getPhase, "PAY"));
        return cnt != null && cnt > 0;
    }

    private HrSalaryRun latestDone(Long companyId, String ym, String phase) {
        return runMapper.selectOne(new LambdaQueryWrapper<HrSalaryRun>()
                .eq(HrSalaryRun::getCompanyId, companyId)
                .eq(HrSalaryRun::getYearMonth, ym)
                .eq(HrSalaryRun::getPhase, phase)
                .eq(HrSalaryRun::getStatus, "DONE")
                .orderByDesc(HrSalaryRun::getId)
                .last("LIMIT 1"));
    }

    private HrSalaryRunLine requireMyPreviewLine(Long lineId) {
        long uid = StpUtil.getLoginIdAsLong();
        HrSalaryRunLine line = lineMapper.selectById(lineId);
        if (line == null || !Objects.equals(line.getUserId(), uid)) {
            throw new BusinessException("记录不存在");
        }
        HrSalaryRun run = runMapper.selectById(line.getRunId());
        if (run == null || !"PREVIEW".equals(run.getPhase())) {
            throw new BusinessException("仅预告记录可操作");
        }
        return line;
    }

    private String normalizePeriod(Long companyId, String period) {
        if (!StringUtils.hasText(period)) {
            // 双开后空参数默认当前自然月；周结请显式传 yyyy-Www
            return YearMonth.now().format(YM);
        }
        String text = period.trim();
        Matcher week = ISO_WEEK.matcher(text);
        if (week.matches()) {
            throw new BusinessException("暂不支持周薪，仅支持月薪");
        }
        try {
            return YearMonth.parse(text, YM).format(YM);
        } catch (Exception e) {
            throw new BusinessException("月份格式应为 yyyy-MM");
        }
    }

    private String normalizeCycleType(String raw) {
        if (!StringUtils.hasText(raw) || CYCLE_MONTHLY.equalsIgnoreCase(raw.trim())) {
            return CYCLE_MONTHLY;
        }
        if (CYCLE_WEEKLY.equalsIgnoreCase(raw.trim())) {
            return CYCLE_WEEKLY;
        }
        throw new BusinessException("结算周期仅支持月结或周结");
    }

    private boolean isWeeklyPeriod(String period) {
        return StringUtils.hasText(period) && ISO_WEEK.matcher(period.trim()).matches();
    }

    private String cycleOfPeriod(Long companyId, String period) {
        return isWeeklyPeriod(period) ? CYCLE_WEEKLY : CYCLE_MONTHLY;
    }

    private String formatIsoWeek(LocalDate date) {
        int weekYear = date.get(ISO_WEEKS.weekBasedYear());
        int week = date.get(ISO_WEEKS.weekOfWeekBasedYear());
        return String.format(Locale.ROOT, "%04d-W%02d", weekYear, week);
    }

    private String periodLabel(String period) {
        return isWeeklyPeriod(period) ? "本周" : "本月";
    }

    private void assertCompanyVisible(Long companyId) {
        long uid = StpUtil.getLoginIdAsLong();
        if (dataScopeService.isGlobalAdmin(uid)) {
            return;
        }
        if (!dataScopeService.visibleCompanyIds(uid).contains(companyId)) {
            throw new BusinessException("无权操作该公司");
        }
    }

    private void assertCompanyExists(Long companyId) {
        SysDept dept = deptService.getById(companyId);
        if (dept == null) {
            throw new BusinessException("公司不存在");
        }
    }

    private void fillItemNames(List<HrSalaryItem> list) {
        if (list.isEmpty()) {
            return;
        }
        Set<Long> userIds = list.stream().map(HrSalaryItem::getUserId).collect(Collectors.toSet());
        Set<Long> projectIds = list.stream().map(HrSalaryItem::getProjectId).collect(Collectors.toSet());
        Map<Long, SysUser> users = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> u, (a, b) -> a));
        Map<Long, PmProject> projects = projectMapper.selectBatchIds(projectIds).stream()
                .collect(Collectors.toMap(PmProject::getId, p -> p, (a, b) -> a));
        for (HrSalaryItem item : list) {
            SysUser u = users.get(item.getUserId());
            if (u != null) {
                item.setUserName(StringUtils.hasText(u.getNickname()) ? u.getNickname() : u.getUsername());
            }
            PmProject p = projects.get(item.getProjectId());
            if (p != null) {
                item.setProjectName(p.getName());
            }
        }
    }

    private void fillScheduleNames(HrSalarySchedule schedule) {
        if (schedule.getCompanyId() != null) {
            SysDept d = deptService.getById(schedule.getCompanyId());
            if (d != null) {
                schedule.setCompanyName(d.getName());
            }
        }
        if (schedule.getSubmitterUserId() != null) {
            schedule.setSubmitterName(userDisplayName(schedule.getSubmitterUserId()));
        }
    }

    private void fillLineNames(List<HrSalaryRunLine> lines) {
        if (lines.isEmpty()) {
            return;
        }
        Set<Long> ids = lines.stream().map(HrSalaryRunLine::getUserId).collect(Collectors.toSet());
        Map<Long, SysUser> users = userService.listByIds(ids).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> u, (a, b) -> a));
        for (HrSalaryRunLine line : lines) {
            SysUser u = users.get(line.getUserId());
            if (u != null) {
                line.setUserName(StringUtils.hasText(u.getNickname()) ? u.getNickname() : u.getUsername());
            }
        }
    }

    private String userDisplayName(Long userId) {
        if (userId == null) {
            return "用户";
        }
        SysUser u = userService.getById(userId);
        if (u == null) {
            return "用户" + userId;
        }
        return StringUtils.hasText(u.getNickname()) ? u.getNickname() : u.getUsername();
    }
}
