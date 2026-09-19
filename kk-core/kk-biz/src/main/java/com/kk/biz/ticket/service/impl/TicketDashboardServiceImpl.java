package com.kk.biz.ticket.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kk.biz.ticket.entity.TicketDevCycle;
import com.kk.biz.ticket.entity.TicketDeveloper;
import com.kk.biz.ticket.entity.TicketWorkOrder;
import com.kk.biz.ticket.entity.TicketWorkOrderAssignee;
import com.kk.biz.ticket.mapper.TicketDevCycleMapper;
import com.kk.biz.ticket.mapper.TicketDeveloperMapper;
import com.kk.biz.ticket.mapper.TicketWorkOrderAssigneeMapper;
import com.kk.biz.ticket.mapper.TicketWorkOrderMapper;
import com.kk.biz.ticket.service.TicketDashboardService;
import com.kk.biz.ticket.support.TicketCompanyGuard;
import com.kk.biz.ticket.support.TicketValidation;
import com.kk.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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
public class TicketDashboardServiceImpl implements TicketDashboardService {

    private final TicketWorkOrderMapper workOrderMapper;
    private final TicketDevCycleMapper cycleMapper;
    private final TicketDeveloperMapper developerMapper;
    private final TicketWorkOrderAssigneeMapper assigneeMapper;
    private final TicketCompanyGuard companyGuard;

    @Override
    public Map<String, Object> overview(Long companyId, Long cycleId, Long projectId) {
        if (companyId == null) {
            throw new BusinessException(400, "请选择所属公司");
        }
        companyGuard.assertCompanyVisible(companyId);

        final TicketDevCycle activeCycle;
        if (cycleId != null) {
            TicketDevCycle c = cycleMapper.selectById(cycleId);
            if (c == null || !Objects.equals(c.getCompanyId(), companyId)) {
                throw new BusinessException(404, "开发周期不存在");
            }
            activeCycle = c;
        } else {
            activeCycle = cycleMapper.selectOne(new LambdaQueryWrapper<TicketDevCycle>()
                    .eq(TicketDevCycle::getCompanyId, companyId)
                    .eq(TicketDevCycle::getStatus, "active")
                    .last("LIMIT 1"));
        }

        LambdaQueryWrapper<TicketWorkOrder> base = new LambdaQueryWrapper<TicketWorkOrder>()
                .eq(TicketWorkOrder::getCompanyId, companyId)
                .eq(projectId != null, TicketWorkOrder::getProjectId, projectId);
        List<TicketWorkOrder> all = workOrderMapper.selectList(base);
        final Long scopeCycleId = activeCycle == null ? null : activeCycle.getId();
        List<TicketWorkOrder> scoped = scopeCycleId == null ? all
                : all.stream().filter(t -> Objects.equals(t.getCycleId(), scopeCycleId)).collect(Collectors.toList());

        Map<String, Object> kpis = buildKpis(scoped);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeCycle", activeCycle);
        result.put("kpis", kpis);
        result.put("typeDistribution", distribution(scoped, TicketWorkOrder::getType));
        result.put("urgencyDistribution", distribution(scoped, TicketWorkOrder::getUrgency));
        result.put("statusDistribution", distribution(scoped, TicketWorkOrder::getStatus));
        result.put("bugTrend", bugTrend(all));
        result.put("ticketVolumeTrend", volumeTrend(all));
        result.put("avgFixDaysTrend", avgFixTrend(all));
        result.put("submitPeakHours", peakHours(all));
        result.put("assigneeDistribution", assigneeDistribution(companyId, scoped));
        result.put("cycleWorkload", cycleWorkload(companyId, all));
        result.put("warnings", warnings(companyId, scoped, activeCycle));
        result.put("cycleSnapshot", snapshotList(scoped, 8));
        result.put("mySubmissions", mySubmissionStats(companyId, projectId));
        return result;
    }

    @Override
    public Map<String, Object> cycleSnapshot(Long companyId, int limit) {
        if (companyId == null) {
            throw new BusinessException(400, "请选择所属公司");
        }
        companyGuard.assertCompanyVisible(companyId);
        TicketDevCycle active = cycleMapper.selectOne(new LambdaQueryWrapper<TicketDevCycle>()
                .eq(TicketDevCycle::getCompanyId, companyId)
                .eq(TicketDevCycle::getStatus, "active")
                .last("LIMIT 1"));
        List<TicketWorkOrder> list;
        String limitSql = "LIMIT " + Math.max(1, Math.min(limit, 50));
        if (active == null) {
            list = workOrderMapper.selectList(new LambdaQueryWrapper<TicketWorkOrder>()
                    .eq(TicketWorkOrder::getCompanyId, companyId)
                    .orderByDesc(TicketWorkOrder::getCreateTime)
                    .last(limitSql));
        } else {
            list = workOrderMapper.selectList(new LambdaQueryWrapper<TicketWorkOrder>()
                    .eq(TicketWorkOrder::getCompanyId, companyId)
                    .eq(TicketWorkOrder::getCycleId, active.getId())
                    .orderByDesc(TicketWorkOrder::getCreateTime)
                    .last(limitSql));
        }
        Map<String, Object> result = new HashMap<>();
        result.put("activeCycle", active);
        result.put("list", snapshotList(list, limit));
        return result;
    }

    private Map<String, Object> buildKpis(List<TicketWorkOrder> scoped) {
        long total = scoped.size();
        long inProgress = scoped.stream().filter(t -> "in_progress".equals(t.getStatus()) || "testing".equals(t.getStatus())).count();
        long pendingBugs = scoped.stream().filter(t -> "bug".equals(t.getType()) && "pending".equals(t.getStatus())).count();
        List<TicketWorkOrder> doneBugs = scoped.stream()
                .filter(t -> "bug".equals(t.getType()) && TicketValidation.isCompletedStatus(t.getStatus()) && t.getCompletedAt() != null && t.getCreateTime() != null)
                .collect(Collectors.toList());
        double avgFix = doneBugs.isEmpty() ? 0
                : doneBugs.stream().mapToDouble(t -> ChronoUnit.HOURS.between(t.getCreateTime(), t.getCompletedAt()) / 24.0).average().orElse(0);
        long completed = scoped.stream().filter(t -> TicketValidation.isCompletedStatus(t.getStatus())).count();
        int cycleRate = total == 0 ? 0 : (int) Math.round(completed * 100.0 / total);
        long slaOk = scoped.stream().filter(this::meetsSla).count();
        int slaRate = total == 0 ? 0 : (int) Math.round(slaOk * 100.0 / total);

        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("total", total);
        kpis.put("inProgress", inProgress);
        kpis.put("pendingBugs", pendingBugs);
        kpis.put("avgFixDays", Math.round(avgFix * 10) / 10.0);
        kpis.put("cycleCompleteRate", cycleRate);
        kpis.put("slaRate", slaRate);
        return kpis;
    }

    private boolean meetsSla(TicketWorkOrder t) {
        if (t.getCreateTime() == null) {
            return true;
        }
        if (TicketValidation.isCompletedStatus(t.getStatus())) {
            if (t.getCompletedAt() == null) {
                return true;
            }
            long hours = ChronoUnit.HOURS.between(t.getCreateTime(), t.getCompletedAt());
            if ("urgent".equals(t.getUrgency())) {
                return hours <= 48;
            }
            return hours <= 7 * 24;
        }
        long hours = ChronoUnit.HOURS.between(t.getCreateTime(), LocalDateTime.now());
        if ("urgent".equals(t.getUrgency())) {
            return hours <= 48;
        }
        return hours <= 7 * 24;
    }

    private List<Map<String, Object>> distribution(List<TicketWorkOrder> list, java.util.function.Function<TicketWorkOrder, String> getter) {
        Map<String, Long> counts = list.stream()
                .map(getter)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(s -> s, Collectors.counting()));
        List<Map<String, Object>> out = new ArrayList<>();
        counts.forEach((k, v) -> out.add(Map.of("name", k, "value", v)));
        return out;
    }

    private List<Map<String, Object>> bugTrend(List<TicketWorkOrder> all) {
        LocalDate today = LocalDate.now();
        Map<LocalDate, Long> map = new LinkedHashMap<>();
        for (int i = 13; i >= 0; i--) {
            map.put(today.minusDays(i), 0L);
        }
        for (TicketWorkOrder t : all) {
            if (!"bug".equals(t.getType()) || t.getCreateTime() == null) {
                continue;
            }
            LocalDate d = t.getCreateTime().toLocalDate();
            if (map.containsKey(d)) {
                map.put(d, map.get(d) + 1);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        map.forEach((d, v) -> out.add(Map.of("date", d.toString(), "count", v)));
        return out;
    }

    private List<Map<String, Object>> volumeTrend(List<TicketWorkOrder> all) {
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int w = 7; w >= 0; w--) {
            LocalDate end = today.minusWeeks(w);
            LocalDate start = end.minusDays(6);
            long bug = 0, req = 0, other = 0;
            for (TicketWorkOrder t : all) {
                if (t.getCreateTime() == null) continue;
                LocalDate d = t.getCreateTime().toLocalDate();
                if (d.isBefore(start) || d.isAfter(end)) continue;
                if ("bug".equals(t.getType())) {
                    bug++;
                } else if ("requirement".equals(t.getType())) {
                    req++;
                } else {
                    other++;
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("week", start + "~" + end);
            row.put("bug", bug);
            row.put("requirement", req);
            row.put("other", other);
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> avgFixTrend(List<TicketWorkOrder> all) {
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int w = 7; w >= 0; w--) {
            LocalDate end = today.minusWeeks(w);
            LocalDate start = end.minusDays(6);
            List<Double> days = new ArrayList<>();
            for (TicketWorkOrder t : all) {
                if (!"bug".equals(t.getType()) || t.getCompletedAt() == null || t.getCreateTime() == null) continue;
                LocalDate d = t.getCompletedAt().toLocalDate();
                if (d.isBefore(start) || d.isAfter(end)) continue;
                days.add(ChronoUnit.HOURS.between(t.getCreateTime(), t.getCompletedAt()) / 24.0);
            }
            double avg = days.isEmpty() ? 0 : days.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            out.add(Map.of("week", start + "~" + end, "avgFixDays", Math.round(avg * 10) / 10.0));
        }
        return out;
    }

    private List<Map<String, Object>> peakHours(List<TicketWorkOrder> all) {
        Map<Integer, Long> hours = new LinkedHashMap<>();
        for (int h = 8; h <= 19; h++) {
            hours.put(h, 0L);
        }
        for (TicketWorkOrder t : all) {
            if (t.getCreateTime() == null) continue;
            int h = t.getCreateTime().getHour();
            if (hours.containsKey(h)) {
                hours.put(h, hours.get(h) + 1);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        hours.forEach((h, c) -> out.add(Map.of("hour", h, "count", c)));
        return out;
    }

    private List<Map<String, Object>> assigneeDistribution(Long companyId, List<TicketWorkOrder> scoped) {
        Set<Long> ticketIds = scoped.stream().map(TicketWorkOrder::getId).collect(Collectors.toSet());
        if (ticketIds.isEmpty()) {
            return List.of();
        }
        List<TicketWorkOrderAssignee> assignees = assigneeMapper.selectList(new LambdaQueryWrapper<TicketWorkOrderAssignee>()
                .in(TicketWorkOrderAssignee::getTicketId, ticketIds));
        Map<Long, Long> counts = assignees.stream()
                .collect(Collectors.groupingBy(TicketWorkOrderAssignee::getDeveloperId, Collectors.counting()));
        Map<Long, String> names = developerMapper.selectList(new LambdaQueryWrapper<TicketDeveloper>()
                        .eq(TicketDeveloper::getCompanyId, companyId))
                .stream().collect(Collectors.toMap(TicketDeveloper::getId, TicketDeveloper::getName, (a, b) -> a));
        List<Map<String, Object>> out = new ArrayList<>();
        counts.forEach((id, c) -> out.add(Map.of(
                "developerId", id,
                "name", names.getOrDefault(id, String.valueOf(id)),
                "count", c)));
        return out;
    }

    private List<Map<String, Object>> cycleWorkload(Long companyId, List<TicketWorkOrder> all) {
        List<TicketDevCycle> cycles = cycleMapper.selectList(new LambdaQueryWrapper<TicketDevCycle>()
                .eq(TicketDevCycle::getCompanyId, companyId)
                .orderByDesc(TicketDevCycle::getStartDate));
        List<Map<String, Object>> out = new ArrayList<>();
        for (TicketDevCycle c : cycles) {
            long count = all.stream().filter(t -> Objects.equals(t.getCycleId(), c.getId())).count();
            out.add(Map.of("cycleId", c.getId(), "name", c.getName(), "count", count, "status", c.getStatus()));
        }
        return out;
    }

    private List<Map<String, Object>> warnings(Long companyId, List<TicketWorkOrder> scoped, TicketDevCycle cycle) {
        List<Map<String, Object>> warnings = new ArrayList<>();
        if (cycle != null && cycle.getEndDate() != null) {
            long daysLeft = ChronoUnit.DAYS.between(LocalDate.now(), cycle.getEndDate());
            long unfinished = scoped.stream().filter(t -> !TicketValidation.isCompletedStatus(t.getStatus())).count();
            if (daysLeft <= 3 && unfinished > 0) {
                warnings.add(Map.of("type", "deadline", "message", "周期「" + cycle.getName() + "」将尽，仍有 " + unfinished + " 单未完成"));
            }
        }
        for (TicketWorkOrder t : scoped) {
            if (!"bug".equals(t.getType()) || !"urgent".equals(t.getUrgency())) continue;
            if (TicketValidation.isCompletedStatus(t.getStatus()) || t.getCreateTime() == null) continue;
            if (ChronoUnit.HOURS.between(t.getCreateTime(), LocalDateTime.now()) > 48) {
                warnings.add(Map.of("type", "bug", "message", "紧急 BUG「" + t.getTitle() + "」已超时"));
            }
        }
        List<Map<String, Object>> loads = assigneeDistribution(companyId, scoped);
        if (!loads.isEmpty()) {
            double avg = loads.stream().mapToLong(m -> ((Number) m.get("count")).longValue()).average().orElse(0);
            for (Map<String, Object> m : loads) {
                long c = ((Number) m.get("count")).longValue();
                if (avg > 0 && c > avg * 1.5) {
                    warnings.add(Map.of("type", "overload", "message", m.get("name") + " 负载过高（" + c + " 单）"));
                }
            }
        }
        return warnings;
    }

    private List<Map<String, Object>> snapshotList(List<TicketWorkOrder> list, int limit) {
        return list.stream().limit(Math.max(1, limit)).map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("ticketNo", t.getTicketNo());
            m.put("title", t.getTitle());
            m.put("type", t.getType());
            m.put("urgency", t.getUrgency());
            m.put("status", t.getStatus());
            m.put("progress", t.getProgress());
            m.put("submitterName", t.getSubmitterName());
            return m;
        }).collect(Collectors.toList());
    }

    private Map<String, Object> mySubmissionStats(Long companyId, Long projectId) {
        long loginId = StpUtil.getLoginIdAsLong();
        List<TicketWorkOrder> mine = workOrderMapper.selectList(new LambdaQueryWrapper<TicketWorkOrder>()
                .eq(TicketWorkOrder::getCompanyId, companyId)
                .eq(projectId != null, TicketWorkOrder::getProjectId, projectId)
                .eq(TicketWorkOrder::getSubmitterId, loginId));
        long completed = mine.stream().filter(t -> TicketValidation.isCompletedStatus(t.getStatus())).count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", mine.size());
        m.put("completed", completed);
        m.put("rate", mine.isEmpty() ? 0 : (int) Math.round(completed * 100.0 / mine.size()));
        return m;
    }
}
