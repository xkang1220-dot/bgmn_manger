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
import com.kk.biz.entity.PmProject;
import com.kk.biz.mapper.HrSalaryItemMapper;
import com.kk.biz.mapper.HrSalaryRunLineMapper;
import com.kk.biz.mapper.HrSalaryRunMapper;
import com.kk.biz.mapper.HrSalaryScheduleMapper;
import com.kk.biz.mapper.PmProjectMapper;
import com.kk.biz.service.FinProjectAccountService;
import com.kk.biz.service.HrSalaryService;
import com.kk.biz.service.WfApprovalService;
import com.kk.biz.workflow.ApprovalTypes;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class HrSalaryServiceImpl implements HrSalaryService {

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy-MM");

    private final HrSalaryItemMapper itemMapper;
    private final HrSalaryScheduleMapper scheduleMapper;
    private final HrSalaryRunMapper runMapper;
    private final HrSalaryRunLineMapper lineMapper;
    private final PmProjectMapper projectMapper;
    private final FinProjectAccountService projectAccountService;
    private final SysUserService userService;
    private final SysDeptService deptService;
    private final DataScopeService dataScopeService;
    private final SysNotificationService notificationService;
    private final WfApprovalService approvalService;
    private final PlatformTransactionManager transactionManager;
    private final ObjectProvider<HrSalaryService> selfProvider;

    @Override
    public List<HrSalaryItem> listItems(Long companyId, Long userId) {
        if (companyId == null) {
            throw new BusinessException("请选择公司");
        }
        assertCompanyVisible(companyId);
        List<HrSalaryItem> list = itemMapper.selectList(new LambdaQueryWrapper<HrSalaryItem>()
                .eq(HrSalaryItem::getCompanyId, companyId)
                .eq(userId != null, HrSalaryItem::getUserId, userId)
                .orderByAsc(HrSalaryItem::getUserId)
                .orderByAsc(HrSalaryItem::getId));
        fillItemNames(list);
        return list;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveItem(HrSalaryItem item) {
        if (item.getCompanyId() == null || item.getUserId() == null || item.getProjectId() == null) {
            throw new BusinessException("公司、收款人、项目不能为空");
        }
        if (item.getAmount() == null || item.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("月薪金额必须大于 0");
        }
        assertCompanyVisible(item.getCompanyId());
        PmProject project = projectMapper.selectById(item.getProjectId());
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        if (!Objects.equals(project.getCompanyId(), item.getCompanyId())) {
            throw new BusinessException("项目不属于该公司");
        }
        if (!ProjectScales.isSalaryEligible(project)) {
            throw new BusinessException("仅可为重点项目（含重大下的小项目）配置工资；常规与重大外壳不可选");
        }
        if (item.getEnabled() == null) {
            item.setEnabled(1);
        }
        HrSalaryItem dup = itemMapper.selectOne(new LambdaQueryWrapper<HrSalaryItem>()
                .eq(HrSalaryItem::getCompanyId, item.getCompanyId())
                .eq(HrSalaryItem::getUserId, item.getUserId())
                .eq(HrSalaryItem::getProjectId, item.getProjectId())
                .ne(item.getId() != null, HrSalaryItem::getId, item.getId())
                .last("LIMIT 1"));
        if (dup != null) {
            throw new BusinessException("该用户在此项目已有工资配置");
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
            schedule.setPayDay(20);
            schedule.setPayHour(9);
            schedule.setPayMinute(0);
            schedule.setPreviewDays(3);
            schedule.setEnabled(1);
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
        int day = schedule.getPayDay() == null ? 20 : schedule.getPayDay();
        if (day < 1 || day > 28) {
            throw new BusinessException("发薪日仅支持 1–28");
        }
        int hour = schedule.getPayHour() == null ? 9 : schedule.getPayHour();
        int minute = schedule.getPayMinute() == null ? 0 : schedule.getPayMinute();
        int preview = schedule.getPreviewDays() == null ? 3 : schedule.getPreviewDays();
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            throw new BusinessException("发薪时间不正确");
        }
        if (preview < 1 || preview > 27) {
            throw new BusinessException("预告提前天数需在 1–27");
        }
        schedule.setPayDay(day);
        schedule.setPayHour(hour);
        schedule.setPayMinute(minute);
        schedule.setPreviewDays(preview);
        if (schedule.getEnabled() == null) {
            schedule.setEnabled(1);
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
        String ym = StringUtils.hasText(yearMonth) ? yearMonth : YearMonth.now().format(YM);
        List<HrSalaryRun> previews = runMapper.selectList(new LambdaQueryWrapper<HrSalaryRun>()
                .eq(HrSalaryRun::getYearMonth, ym)
                .eq(HrSalaryRun::getPhase, "PREVIEW")
                .eq(HrSalaryRun::getStatus, "DONE")
                .orderByDesc(HrSalaryRun::getId));
        List<Map<String, Object>> result = new ArrayList<>();
        Set<Long> seenCompanies = new java.util.HashSet<>();
        for (HrSalaryRun run : previews) {
            if (!seenCompanies.add(run.getCompanyId())) {
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
            row.put("yearMonth", ym);
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
            throw new BusinessException("本月发薪已开始，无法撤销确认");
        }
        line.setStatus("PENDING_CONFIRM");
        line.setConfirmedAt(null);
        lineMapper.updateById(line);
    }

    @Override
    public HrSalaryRun runPreview(Long companyId, String yearMonth, boolean manual) {
        if (companyId == null) {
            throw new BusinessException("请选择公司");
        }
        String ym = normalizeYm(yearMonth);
        if (!manual) {
            assertCompanyExists(companyId);
        } else {
            assertCompanyVisible(companyId);
        }
        HrSalaryRun existing = latestDone(companyId, ym, "PREVIEW");
        if (existing != null && !manual) {
            return existing;
        }

        Map<Long, List<HrSalaryItem>> byUser = enabledItemsByUser(companyId);
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
            Map<String, Object> payload = buildPayload(userId, ym, e.getValue());
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
        String ym = normalizeYm(yearMonth);
        if (manual) {
            assertCompanyVisible(companyId);
        } else {
            assertCompanyExists(companyId);
        }

        // 本月是否已有发薪批次：后续补跑不再给「未确认」重复记跳过
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
        Map<Long, List<HrSalaryItem>> byUser = enabledItemsByUser(companyId);
        int created = 0;
        int skipped = 0;
        TransactionTemplate requiresNew = requiresNewTx();

        for (Map.Entry<Long, List<HrSalaryItem>> e : byUser.entrySet()) {
            Long userId = e.getKey();
            if (hasApprovalCreated(companyId, ym, userId)) {
                continue;
            }
            HrSalaryRunLine previewLine = findPreviewLine(companyId, ym, userId);
            Map<String, Object> payload = buildPayload(userId, ym, e.getValue());

            if (previewLine == null || !"CONFIRMED".equals(previewLine.getStatus())) {
                if (firstPayWave) {
                    insertPayLine(requiresNew, run.getId(), userId, payload, "SKIPPED", "未确认工资", null, null);
                    skipped++;
                }
                continue;
            }
            String balanceReason = checkBalances(e.getValue());
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
                    req.setTitle("月度工资 · " + userDisplayName(userId) + " · " + ym);
                    req.setPayload(payload);
                    req.setRemark("月度工资 " + ym);
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
                log.warn("月度工资生成审批失败 userId={}: {}", userId, ex.getMessage());
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
            try {
                String previewYm = resolvePreviewYearMonth(schedule, now);
                if (previewYm != null && latestDone(schedule.getCompanyId(), previewYm, "PREVIEW") == null) {
                    self.runPreview(schedule.getCompanyId(), previewYm, false);
                }
                String payYm = resolvePayYearMonth(schedule, now);
                if (payYm != null && shouldCronPay(schedule.getCompanyId(), payYm)) {
                    self.runPay(schedule.getCompanyId(), payYm, false);
                }
            } catch (Exception e) {
                log.error("工资定时任务失败 companyId={}: {}", schedule.getCompanyId(), e.getMessage());
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
     * 预告日可能落在「发薪月」的上月末（例如发薪日=1、提前3天）。
     * @return 应对齐的发薪月份 yyyy-MM；今天不是预告日则 null
     */
    private String resolvePreviewYearMonth(HrSalarySchedule s, LocalDateTime now) {
        if (!timeReached(s, now)) {
            return null;
        }
        LocalDate today = now.toLocalDate();
        YearMonth cur = YearMonth.from(now);
        for (YearMonth ym : List.of(cur, cur.plusMonths(1))) {
            if (today.equals(previewDateOf(ym, s))) {
                return ym.format(YM);
            }
        }
        return null;
    }

    private String resolvePayYearMonth(HrSalarySchedule s, LocalDateTime now) {
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

    private boolean timeReached(HrSalarySchedule s, LocalDateTime now) {
        return !now.toLocalTime().isBefore(java.time.LocalTime.of(nzHour(s.getPayHour()), nzMinute(s.getPayMinute())));
    }

    private LocalDate payDateOf(YearMonth ym, HrSalarySchedule s) {
        return ym.atDay(clampDay(s.getPayDay()));
    }

    private LocalDate previewDateOf(YearMonth ym, HrSalarySchedule s) {
        int days = s.getPreviewDays() == null ? 3 : s.getPreviewDays();
        return payDateOf(ym, s).minusDays(days);
    }

    private boolean shouldCronPay(Long companyId, String ym) {
        Map<Long, List<HrSalaryItem>> byUser = enabledItemsByUser(companyId);
        for (Long userId : byUser.keySet()) {
            if (hasApprovalCreated(companyId, ym, userId)) {
                continue;
            }
            HrSalaryRunLine preview = findPreviewLine(companyId, ym, userId);
            if (preview != null && "CONFIRMED".equals(preview.getStatus())) {
                return true;
            }
        }
        // 首轮：把未确认记为跳过
        return latestDone(companyId, ym, "PAY") == null && !byUser.isEmpty();
    }

    private int clampDay(Integer day) {
        int d = day == null ? 20 : day;
        return Math.max(1, Math.min(28, d));
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

    private Map<Long, List<HrSalaryItem>> enabledItemsByUser(Long companyId) {
        List<HrSalaryItem> items = itemMapper.selectList(new LambdaQueryWrapper<HrSalaryItem>()
                .eq(HrSalaryItem::getCompanyId, companyId)
                .eq(HrSalaryItem::getEnabled, 1));
        Set<Long> projectIds = items.stream().map(HrSalaryItem::getProjectId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, PmProject> projects = projectIds.isEmpty() ? Map.of()
                : projectMapper.selectBatchIds(projectIds).stream()
                .collect(Collectors.toMap(PmProject::getId, p -> p, (a, b) -> a));
        Map<Long, List<HrSalaryItem>> map = new LinkedHashMap<>();
        for (HrSalaryItem item : items) {
            PmProject project = projects.get(item.getProjectId());
            if (!ProjectScales.isSalaryEligible(project)) {
                continue;
            }
            map.computeIfAbsent(item.getUserId(), k -> new ArrayList<>()).add(item);
        }
        return map;
    }

    private Map<String, Object> buildPayload(Long userId, String ym, List<HrSalaryItem> items) {
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (HrSalaryItem item : items) {
            PmProject p = projectMapper.selectById(item.getProjectId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("projectId", item.getProjectId());
            row.put("projectName", p == null ? ("#" + item.getProjectId()) : p.getName());
            row.put("amount", item.getAmount());
            rows.add(row);
            total = total.add(item.getAmount());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("userName", userDisplayName(userId));
        payload.put("yearMonth", ym);
        payload.put("totalAmount", total);
        payload.put("items", rows);
        return payload;
    }

    private String buildPreviewContent(Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder();
        sb.append("本月工资合计 ").append(payload.get("totalAmount")).append(" 元：");
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
        sb.append("。请尽快在「工资确认」中确认，未确认将不进入本月发薪。");
        return sb.toString();
    }

    private String checkBalances(List<HrSalaryItem> items) {
        StringBuilder sb = new StringBuilder();
        for (HrSalaryItem item : items) {
            FinProjectAccount account = projectAccountService.getOrCreate(item.getProjectId());
            BigDecimal bal = account.getBalance() == null ? BigDecimal.ZERO : account.getBalance();
            if (bal.compareTo(item.getAmount()) < 0) {
                PmProject p = projectMapper.selectById(item.getProjectId());
                String name = p == null ? ("#" + item.getProjectId()) : p.getName();
                if (sb.length() > 0) {
                    sb.append("；");
                }
                sb.append("项目 ").append(name).append(" 余额不足：需 ").append(item.getAmount())
                        .append("，现 ").append(bal);
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
        for (HrSalaryRun run : runs) {
            HrSalaryRunLine line = lineMapper.selectOne(new LambdaQueryWrapper<HrSalaryRunLine>()
                    .eq(HrSalaryRunLine::getRunId, run.getId())
                    .eq(HrSalaryRunLine::getUserId, userId)
                    .last("LIMIT 1"));
            if (line != null) {
                return line;
            }
        }
        return null;
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

    private String normalizeYm(String yearMonth) {
        if (!StringUtils.hasText(yearMonth)) {
            return YearMonth.now().format(YM);
        }
        try {
            return YearMonth.parse(yearMonth.trim(), YM).format(YM);
        } catch (Exception e) {
            throw new BusinessException("月份格式应为 yyyy-MM");
        }
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
