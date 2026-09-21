package com.kk.biz.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kk.biz.dto.ApprovalSubmitRequest;
import com.kk.biz.entity.HrLeaveRecord;
import com.kk.biz.entity.WfApproval;
import com.kk.biz.mapper.HrLeaveRecordMapper;
import com.kk.biz.mapper.WfApprovalMapper;
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
                .eq(companyId != null, HrLeaveRecord::getCompanyId, companyId)
                .ge(start != null, HrLeaveRecord::getLeaveDate, start)
                .le(end != null, HrLeaveRecord::getLeaveDate, end)
                .orderByDesc(HrLeaveRecord::getLeaveDate);
        List<HrLeaveRecord> list = list(q);
        fillNames(list);
        return list;
    }

    @Override
    public List<HrLeaveRecord> listByCompany(Long companyId, LocalDate start, LocalDate end) {
        long loginId = StpUtil.getLoginIdAsLong();
        if (companyId == null) {
            throw new BusinessException("请选择公司");
        }
        assertCompanyVisible(loginId, companyId);
        LambdaQueryWrapper<HrLeaveRecord> q = new LambdaQueryWrapper<HrLeaveRecord>()
                .eq(HrLeaveRecord::getCompanyId, companyId)
                .ge(start != null, HrLeaveRecord::getLeaveDate, start)
                .le(end != null, HrLeaveRecord::getLeaveDate, end)
                .orderByDesc(HrLeaveRecord::getLeaveDate)
                .orderByAsc(HrLeaveRecord::getUserId);
        List<HrLeaveRecord> list = list(q);
        fillNames(list);
        return list;
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
                .eq(companyId != null, HrLeaveRecord::getCompanyId, companyId)
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
