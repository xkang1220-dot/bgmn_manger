package com.kk.biz.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kk.biz.entity.PmProject;
import com.kk.biz.entity.PmProjectMember;
import com.kk.biz.entity.PmTask;
import com.kk.biz.entity.PmTaskComment;
import com.kk.biz.entity.PmTaskFlow;
import com.kk.biz.entity.PmTaskMember;
import com.kk.biz.entity.SysFile;
import com.kk.biz.mapper.PmProjectMapper;
import com.kk.biz.mapper.PmProjectMemberMapper;
import com.kk.biz.mapper.PmTaskCommentMapper;
import com.kk.biz.mapper.PmTaskFlowMapper;
import com.kk.biz.mapper.PmTaskMapper;
import com.kk.biz.mapper.PmTaskMemberMapper;
import com.kk.biz.service.PmTaskService;
import com.kk.biz.service.SysFileService;
import com.kk.biz.workflow.ProjectScales;
import com.kk.common.exception.BusinessException;
import com.kk.system.entity.SysUser;
import com.kk.system.service.DataScopeService;
import com.kk.system.service.SysNotificationService;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.DayOfWeek;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PmTaskServiceImpl extends ServiceImpl<PmTaskMapper, PmTask> implements PmTaskService {

    private static final String TASK_IMAGE_BIZ = "task";
    private static final String TASK_COMMENT_FILE_BIZ = "task_comment";
    private static final Safelist COMMENT_HTML_SAFELIST = Safelist.basic()
            .addTags("p", "div", "br", "ul", "ol", "li")
            .addAttributes("a", "target", "rel")
            .addProtocols("a", "href", "http", "https", "mailto");

    private final PmProjectMapper projectMapper;
    private final PmProjectMemberMapper projectMemberMapper;
    private final PmTaskMemberMapper taskMemberMapper;
    private final PmTaskCommentMapper commentMapper;
    private final PmTaskFlowMapper flowMapper;
    private final SysUserService userService;
    private final SysFileService fileService;
    private final DataScopeService dataScopeService;
    private final SysNotificationService notificationService;

    private static final Map<Integer, String> STATUS_LABEL = Map.of(
            0, "待办",
            1, "进行中",
            2, "已完成",
            3, "已关闭",
            4, "待确认完成"
    );

    @Override
    public Page<PmTask> pageTasks(long page, long pageSize, Long projectId, Integer status, String statuses,
                                  Integer priority, Long participantId, String title, Boolean overdue) {
        return pageTasksInternal(page, pageSize, projectId, status, statuses, priority, participantId, title, overdue,
                false, null, null, null, null, null, null);
    }

    @Override
    public Page<PmTask> pageManagementTasks(long page, long pageSize, Long projectId, Integer status, String statuses,
                                            Integer priority, Long participantId, String title, Boolean overdue,
                                            String dashboardCategory, Long dashboardOwnerId,
                                            String dashboardFrom, String dashboardTo,
                                            String periodFrom, String periodTo) {
        return pageTasksInternal(page, pageSize, projectId, status, statuses, priority, participantId, title, overdue, true,
                dashboardCategory, dashboardOwnerId, dashboardFrom, dashboardTo, periodFrom, periodTo);
    }

    private Page<PmTask> pageTasksInternal(long page, long pageSize, Long projectId, Integer status, String statuses,
                                           Integer priority, Long participantId, String title, Boolean overdue,
                                           boolean managementScope, String dashboardCategory, Long dashboardOwnerId,
                                           String dashboardFrom, String dashboardTo,
                                           String periodFrom, String periodTo) {
        boolean hasQueryCondition = projectId != null
                || status != null
                || StringUtils.hasText(statuses)
                || priority != null
                || participantId != null
                || StringUtils.hasText(title)
                || Boolean.TRUE.equals(overdue)
                || StringUtils.hasText(dashboardCategory);
        LambdaQueryWrapper<PmTask> wrapper = new LambdaQueryWrapper<PmTask>()
                .eq(priority != null, PmTask::getPriority, priority)
                .like(StringUtils.hasText(title), PmTask::getTitle, title);
        if (StringUtils.hasText(periodFrom) && StringUtils.hasText(periodTo)) {
            wrapper.ge(PmTask::getCreateTime, LocalDate.parse(periodFrom).atStartOfDay())
                    .lt(PmTask::getCreateTime, LocalDate.parse(periodTo).atStartOfDay());
        }
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        boolean dashboardDrill = Set.of("TOTAL", "OPEN", "OVERDUE", "DUE_SOON", "STALE", "NO_DUE_DATE", "PENDING",
                "DONE_30", "ON_TIME", "CYCLE", "OWNER_OPEN", "CREATED_RANGE", "COMPLETED_RANGE")
                .contains(dashboardCategory == null ? "" : dashboardCategory);
        if (dashboardDrill) {
            switch (dashboardCategory) {
                case "OPEN" -> wrapper.in(PmTask::getStatus, 0, 1, 4);
                case "OVERDUE" -> wrapper.lt(PmTask::getDueDate, today).in(PmTask::getStatus, 0, 1);
                case "DUE_SOON" -> wrapper.between(PmTask::getDueDate, today, today.plusDays(7)).in(PmTask::getStatus, 0, 1);
                case "STALE" -> wrapper.eq(PmTask::getStatus, 1).lt(PmTask::getLastActivityAt, now.minusDays(7));
                case "NO_DUE_DATE" -> wrapper.isNull(PmTask::getDueDate).in(PmTask::getStatus, 0, 1);
                case "PENDING" -> wrapper.eq(PmTask::getStatus, 4);
                case "DONE_30" -> {
                    wrapper.eq(PmTask::getStatus, 2);
                    if (!StringUtils.hasText(periodFrom) || !StringUtils.hasText(periodTo)) {
                        wrapper.ge(PmTask::getCompletedAt, now.minusDays(30));
                    }
                }
                case "ON_TIME" -> wrapper.eq(PmTask::getStatus, 2).isNotNull(PmTask::getCompletedAt).isNotNull(PmTask::getDueDate);
                case "CYCLE" -> wrapper.eq(PmTask::getStatus, 2).isNotNull(PmTask::getStartedAt).isNotNull(PmTask::getCompletedAt);
                case "OWNER_OPEN" -> {
                    applyTaskLoadUserFilter(wrapper, dashboardOwnerId);
                    wrapper.in(PmTask::getStatus, 0, 1, 4);
                }
                case "CREATED_RANGE" -> wrapper.ge(PmTask::getCreateTime, LocalDate.parse(dashboardFrom).atStartOfDay())
                        .lt(PmTask::getCreateTime, LocalDate.parse(dashboardTo).atStartOfDay());
                case "COMPLETED_RANGE" -> wrapper.ge(PmTask::getCompletedAt, LocalDate.parse(dashboardFrom).atStartOfDay())
                        .lt(PmTask::getCompletedAt, LocalDate.parse(dashboardTo).atStartOfDay());
                default -> { }
            }
        }
        // 逾期本身限定待办/进行中，避免再与 status/statuses 叠加成空结果
        if (!dashboardDrill && Boolean.TRUE.equals(overdue)) {
            wrapper.lt(PmTask::getDueDate, today).in(PmTask::getStatus, 0, 1);
        } else if (!dashboardDrill) {
            List<Integer> statusList = parseStatuses(statuses);
            if (!statusList.isEmpty()) {
                wrapper.in(PmTask::getStatus, statusList);
            } else if (status != null) {
                wrapper.eq(PmTask::getStatus, status);
            }
        }
        applyProjectIdFilter(wrapper, projectId);
        applyParticipantFilter(wrapper, participantId);
        applyVisibleScope(wrapper);
        // 驾驶舱分类下钻仅向 task_manager 开放全局范围；普通任务列表仍严格按本人任务收口。
        if (managementScope && !(dashboardDrill && StpUtil.hasRole("task_manager"))) {
            applyManagementProjectScope(wrapper);
        }
        if (hasQueryCondition) {
            wrapper.orderByAsc(PmTask::getPriority)
                    .orderByAsc(PmTask::getDueDate)
                    .orderByDesc(PmTask::getId);
        } else {
            wrapper.last("ORDER BY "
                    + "CASE "
                    + "WHEN due_date < CURRENT_DATE AND status IN (0, 1) THEN 0 "
                    + "WHEN status IN (0, 1) THEN 1 "
                    + "WHEN status = 4 THEN 2 "
                    + "ELSE 3 END ASC, "
                    + "CASE WHEN due_date < CURRENT_DATE AND status IN (0, 1) THEN due_date END ASC, "
                    + "priority ASC, create_time DESC, id DESC");
        }
        Page<PmTask> result = page(new Page<>(page, pageSize), wrapper);
        fillExtras(result.getRecords());
        return result;
    }

    private List<Integer> parseStatuses(String statuses) {
        if (!StringUtils.hasText(statuses)) {
            return List.of();
        }
        List<Integer> list = new ArrayList<>();
        for (String part : statuses.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                list.add(Integer.parseInt(s));
            } catch (NumberFormatException ignored) {
                // skip invalid token
            }
        }
        return list;
    }

    private void applyParticipantFilter(LambdaQueryWrapper<PmTask> wrapper, Long participantId) {
        if (participantId == null) {
            return;
        }
        Set<Long> taskIds = taskMemberMapper.selectList(new LambdaQueryWrapper<PmTaskMember>()
                        .eq(PmTaskMember::getUserId, participantId)
                        .select(PmTaskMember::getTaskId))
                .stream()
                .map(PmTaskMember::getTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (taskIds.isEmpty()) {
            wrapper.eq(PmTask::getId, -1L);
        } else {
            wrapper.in(PmTask::getId, taskIds);
        }
    }

    @Override
    public PmTask getDetail(Long id) {
        PmTask task = getById(id);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        assertCanAccessTask(task);
        fillExtras(List.of(task));
        task.setImages(fileService.listByBiz(TASK_IMAGE_BIZ, id));
        return task;
    }

    @Override
    public Map<String, Object> summary(Long projectId, Integer priority, Long participantId, String title) {
        return summaryInternal(projectId, priority, participantId, title, false);
    }

    @Override
    public Map<String, Object> managementSummary(Long projectId, Integer priority, Long participantId, String title) {
        return summaryInternal(projectId, priority, participantId, title, true);
    }

    @Override
    public Map<String, Object> managementDashboard(Long projectId, Integer priority, Long participantId, String title,
                                                   String periodFrom, String periodTo) {
        // 驾驶舱是任务管理员的全局视角，角色校验由 Controller 强制执行。
        return summaryInternal(projectId, priority, participantId, title, false, periodFrom, periodTo);
    }

    private Map<String, Object> summaryInternal(Long projectId, Integer priority, Long participantId, String title,
                                                boolean managementScope) {
        return summaryInternal(projectId, priority, participantId, title, managementScope, null, null);
    }

    private Map<String, Object> summaryInternal(Long projectId, Integer priority, Long participantId, String title,
                                                boolean managementScope, String periodFrom, String periodTo) {
        LambdaQueryWrapper<PmTask> wrapper = new LambdaQueryWrapper<PmTask>()
                .select(PmTask::getId, PmTask::getProjectId, PmTask::getAssigneeId, PmTask::getTitle,
                        PmTask::getStatus, PmTask::getPriority, PmTask::getStartDate,
                        PmTask::getDueDate, PmTask::getStartedAt, PmTask::getCompletedAt,
                        PmTask::getLastActivityAt, PmTask::getRiskLevel,
                        PmTask::getCreateTime, PmTask::getUpdateTime)
                .eq(priority != null, PmTask::getPriority, priority)
                .like(StringUtils.hasText(title), PmTask::getTitle, title);
        if (StringUtils.hasText(periodFrom) && StringUtils.hasText(periodTo)) {
            wrapper.ge(PmTask::getCreateTime, LocalDate.parse(periodFrom).atStartOfDay())
                    .lt(PmTask::getCreateTime, LocalDate.parse(periodTo).atStartOfDay());
        }
        applyProjectIdFilter(wrapper, projectId);
        applyParticipantFilter(wrapper, participantId);
        applyVisibleScope(wrapper);
        if (managementScope) {
            applyManagementProjectScope(wrapper);
        }
        List<PmTask> rows = list(wrapper);
        LocalDate today = LocalDate.now();
        long todo = 0, doing = 0, done = 0, cancelled = 0, pending = 0;
        long overdue = 0, dueSoon = 0, noDueDate = 0, stale = 0, done30 = 0;
        LocalDate dueSoonEnd = today.plusDays(7);
        LocalDateTime staleBefore = LocalDateTime.now().minusDays(7);
        LocalDateTime recentBefore = LocalDateTime.now().minusDays(30);
        Set<Long> taskIds = rows.stream().map(PmTask::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Set<Long>> taskMemberIds = taskIds.isEmpty() ? Map.of()
                : taskMemberMapper.selectList(new LambdaQueryWrapper<PmTaskMember>()
                        .in(PmTaskMember::getTaskId, taskIds)
                        .select(PmTaskMember::getTaskId, PmTaskMember::getUserId))
                .stream()
                .filter(member -> member.getTaskId() != null && member.getUserId() != null)
                .collect(Collectors.groupingBy(PmTaskMember::getTaskId,
                        Collectors.mapping(PmTaskMember::getUserId, Collectors.toSet())));
        Set<Long> recentlyCompletedTaskIds = taskIds.isEmpty() ? Set.of()
                : flowMapper.selectList(new LambdaQueryWrapper<PmTaskFlow>()
                        .in(PmTaskFlow::getTaskId, taskIds)
                        .eq(PmTaskFlow::getToStatus, 2)
                        .ge(PmTaskFlow::getCreateTime, recentBefore)
                        .select(PmTaskFlow::getTaskId))
                .stream().map(PmTaskFlow::getTaskId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> projectIds = rows.stream().map(PmTask::getProjectId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, PmProject> projectMap = projectIds.isEmpty() ? Map.of()
                : projectMapper.selectList(new LambdaQueryWrapper<PmProject>().in(PmProject::getId, projectIds)).stream()
                .collect(Collectors.toMap(PmProject::getId, p -> p, (a, b) -> a));
        Set<Long> ownerIds = projectMap.values().stream().map(PmProject::getOwnerId).filter(Objects::nonNull).collect(Collectors.toSet());
        rows.stream().map(PmTask::getAssigneeId).filter(Objects::nonNull).forEach(ownerIds::add);
        taskMemberIds.values().forEach(ownerIds::addAll);
        Map<Long, SysUser> ownerMap = loadUserMap(ownerIds);
        Map<Long, Map<String, Object>> healthByProject = new HashMap<>();
        List<Map<String, Object>> riskTasks = new ArrayList<>();
        Map<Long, Map<String, Object>> ownerLoadMap = new HashMap<>();
        Map<Long, Map<String, Object>> memberTaskStatsMap = new HashMap<>();
        long onTime = 0, completedWithDue = 0, cycleDays = 0, cycleCount = 0;
        for (PmTask row : rows) {
            Integer status = row.getStatus() == null ? 0 : row.getStatus();
            switch (status) {
                case 1 -> doing++;
                case 2 -> done++;
                case 3 -> cancelled++;
                case 4 -> pending++;
                default -> todo++;
            }
            boolean open = status == 0 || status == 1;
            boolean rowOverdue = row.getDueDate() != null && row.getDueDate().isBefore(today) && open;
            boolean rowDueSoon = row.getDueDate() != null && !row.getDueDate().isBefore(today)
                    && !row.getDueDate().isAfter(dueSoonEnd) && open;
            boolean rowNoDueDate = row.getDueDate() == null && open;
            boolean rowStale = status == 1 && row.getLastActivityAt() != null && row.getLastActivityAt().isBefore(staleBefore);
            if (rowOverdue) {
                overdue++;
            }
            if (rowDueSoon) dueSoon++;
            if (rowNoDueDate) noDueDate++;
            if (rowStale) stale++;
            if (status == 2 && (StringUtils.hasText(periodFrom) || recentlyCompletedTaskIds.contains(row.getId()))) done30++;
            if (status == 2 && row.getCompletedAt() != null && row.getDueDate() != null) {
                completedWithDue++;
                if (!row.getCompletedAt().toLocalDate().isAfter(row.getDueDate())) onTime++;
            }
            if (status == 2 && row.getStartedAt() != null && row.getCompletedAt() != null) {
                cycleDays += Math.max(0, ChronoUnit.DAYS.between(row.getStartedAt(), row.getCompletedAt()));
                cycleCount++;
            }

            // 负责人和参与人先按任务内人员集合去重，兼任两种角色也只统计一次。
            Set<Long> taskUserIds = new HashSet<>(taskMemberIds.getOrDefault(row.getId(), Set.of()));
            if (row.getAssigneeId() != null) taskUserIds.add(row.getAssigneeId());
            for (Long userId : taskUserIds) {
                SysUser member = ownerMap.get(userId);
                Map<String, Object> stats = memberTaskStatsMap.computeIfAbsent(userId, id -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("memberId", id);
                    item.put("memberName", member == null ? "用户" + id : userName(member));
                    item.put("total", 0L);
                    item.put("done", 0L);
                    item.put("doing", 0L);
                    item.put("overdue", 0L);
                    return item;
                });
                increment(stats, "total");
                if (status == 2) increment(stats, "done");
                if (status == 1) increment(stats, "doing");
                if (rowOverdue) increment(stats, "overdue");
            }
            if (open || status == 4) {
                // 负责人也可能同时存在于参与人中，按任务内人员集合去重。
                for (Long userId : taskUserIds) {
                    SysUser owner = ownerMap.get(userId);
                    Map<String, Object> load = ownerLoadMap.computeIfAbsent(userId, id -> {
                        Map<String, Object> item = new HashMap<>();
                        item.put("ownerId", id);
                        item.put("ownerName", owner == null ? "用户" + id : userName(owner));
                        item.put("open", 0L);
                        item.put("overdue", 0L);
                        item.put("dueSoon", 0L);
                        return item;
                    });
                    increment(load, "open");
                    if (rowOverdue) increment(load, "overdue");
                    if (rowDueSoon) increment(load, "dueSoon");
                }
            }

            PmProject project = projectMap.get(row.getProjectId());
            if (project != null && (open || status == 4)) {
                Map<String, Object> health = healthByProject.computeIfAbsent(project.getId(), id -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("projectId", id);
                    item.put("projectName", project.getName());
                    SysUser owner = ownerMap.get(project.getOwnerId());
                    item.put("ownerName", owner == null ? "未指定" : userName(owner));
                    item.put("open", 0L);
                    item.put("overdue", 0L);
                    item.put("dueSoon", 0L);
                    item.put("highRisk", 0L);
                    item.put("pending", 0L);
                    return item;
                });
                increment(health, "open");
                if (rowOverdue) increment(health, "overdue");
                if (rowDueSoon) increment(health, "dueSoon");
                if ("DANGER".equals(row.getRiskLevel())
                        || (Integer.valueOf(1).equals(row.getPriority()) && (rowOverdue || rowDueSoon || rowStale))) {
                    increment(health, "highRisk");
                }
                if (status == 4) increment(health, "pending");
            }

            if (rowOverdue || rowDueSoon || rowNoDueDate || rowStale || status == 4) {
                Map<String, Object> risk = new LinkedHashMap<>();
                risk.put("id", row.getId());
                risk.put("title", row.getTitle());
                risk.put("projectName", project == null ? "未关联项目" : project.getName());
                SysUser taskOwner = ownerMap.get(row.getAssigneeId());
                risk.put("ownerName", taskOwner == null ? "未指定" : userName(taskOwner));
                risk.put("dueDate", row.getDueDate());
                risk.put("priority", row.getPriority());
                risk.put("status", status);
                risk.put("riskType", rowOverdue ? "OVERDUE" : rowDueSoon ? "DUE_SOON"
                        : rowStale ? "STALE" : rowNoDueDate ? "NO_DUE_DATE" : "PENDING");
                risk.put("riskScore", rowOverdue ? 50 : rowDueSoon ? 40 : rowStale ? 30 : rowNoDueDate ? 20 : 10);
                riskTasks.add(risk);
            }
        }
        List<Map<String, Object>> projectHealth = new ArrayList<>(healthByProject.values());
        for (Map<String, Object> health : projectHealth) {
            long projectOverdue = ((Number) health.get("overdue")).longValue();
            long projectDueSoon = ((Number) health.get("dueSoon")).longValue();
            long highRisk = ((Number) health.get("highRisk")).longValue();
            String level = projectOverdue > 0 || highRisk > 0 ? "DANGER" : projectDueSoon > 0 ? "WARNING" : "HEALTHY";
            health.put("level", level);
            health.put("riskScore", projectOverdue * 10 + highRisk * 6 + projectDueSoon * 3);
        }
        projectHealth.sort(Comparator.comparingLong(item -> -((Number) item.get("riskScore")).longValue()));
        riskTasks.sort(Comparator.comparingInt(item -> -((Number) item.get("riskScore")).intValue()));
        List<Map<String, Object>> ownerLoad = new ArrayList<>(ownerLoadMap.values());
        ownerLoad.sort(Comparator.comparingLong(item -> -((Number) item.get("open")).longValue()));
        List<Map<String, Object>> memberTaskStats = new ArrayList<>(memberTaskStatsMap.values());
        memberTaskStats.forEach(item -> {
            Map<String, Object> load = ownerLoadMap.get(((Number) item.get("memberId")).longValue());
            item.put("open", load == null ? 0L : load.get("open"));
            item.put("dueSoon", load == null ? 0L : load.get("dueSoon"));
        });
        memberTaskStats.sort(Comparator
                .<Map<String, Object>>comparingLong(item -> -((Number) item.get("total")).longValue())
                .thenComparing(item -> String.valueOf(item.get("memberName"))));
        List<Map<String, Object>> trend = new ArrayList<>();
        LocalDate thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        for (int i = 5; i >= 0; i--) {
            LocalDate weekStart = thisMonday.minusWeeks(i);
            LocalDate weekEnd = weekStart.plusDays(7);
            long created = rows.stream().filter(row -> row.getCreateTime() != null
                    && !row.getCreateTime().toLocalDate().isBefore(weekStart)
                    && row.getCreateTime().toLocalDate().isBefore(weekEnd)).count();
            long completed = rows.stream().filter(row -> row.getCompletedAt() != null
                    && !row.getCompletedAt().toLocalDate().isBefore(weekStart)
                    && row.getCompletedAt().toLocalDate().isBefore(weekEnd)).count();
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("label", weekStart.getMonthValue() + "/" + weekStart.getDayOfMonth());
            point.put("from", weekStart);
            point.put("to", weekEnd);
            point.put("created", created);
            point.put("completed", completed);
            trend.add(point);
        }
        Set<Long> riskProjectIds = projectHealth.stream()
                .filter(item -> !"HEALTHY".equals(item.get("level")))
                .map(item -> ((Number) item.get("projectId")).longValue())
                .collect(Collectors.toSet());
        Map<String, Object> map = new HashMap<>();
        map.put("total", rows.size());
        map.put("todo", todo);
        map.put("doing", doing);
        map.put("done", done);
        map.put("done30", done30);
        map.put("pending", pending);
        map.put("cancelled", cancelled);
        map.put("overdue", overdue);
        map.put("dueSoon", dueSoon);
        map.put("noDueDate", noDueDate);
        map.put("stale", stale);
        map.put("onTimeRate", completedWithDue == 0 ? null : Math.round(onTime * 1000.0 / completedWithDue) / 10.0);
        map.put("avgCycleDays", cycleCount == 0 ? null : Math.round(cycleDays * 10.0 / cycleCount) / 10.0);
        map.put("riskProjects", riskProjectIds.size());
        map.put("projectHealth", projectHealth.stream().limit(8).toList());
        map.put("riskTasks", riskTasks.stream().limit(8).toList());
        map.put("ownerLoad", ownerLoad.stream().limit(8).toList());
        map.put("memberTaskStats", memberTaskStats);
        map.put("trend", trend);
        return map;
    }

    private void increment(Map<String, Object> item, String key) {
        item.put(key, ((Number) item.getOrDefault(key, 0L)).longValue() + 1);
    }

    private String userName(SysUser user) {
        return StringUtils.hasText(user.getNickname()) ? user.getNickname() : user.getUsername();
    }

    /**
     * 仅供 /project/task：无论用户同时是否为任务管理员，都只查看本人主责或直接参与的任务。
     *
     * 普通员工即使是某个项目的成员，也不应因此看到该项目下其他人的全部任务。
     */
    private void applyManagementProjectScope(LambdaQueryWrapper<PmTask> wrapper) {
        long loginId = StpUtil.getLoginIdAsLong();
        Set<Long> memberTaskIds = taskMemberMapper.selectList(new LambdaQueryWrapper<PmTaskMember>()
                        .eq(PmTaskMember::getUserId, loginId)
                        .select(PmTaskMember::getTaskId))
                .stream()
                .map(PmTaskMember::getTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (memberTaskIds.isEmpty()) {
            wrapper.eq(PmTask::getAssigneeId, loginId);
        } else {
            wrapper.and(w -> w.eq(PmTask::getAssigneeId, loginId)
                    .or().in(PmTask::getId, memberTaskIds));
        }
    }

    /** 任务负载人员口径：任务负责人或任务参与人。 */
    private void applyTaskLoadUserFilter(LambdaQueryWrapper<PmTask> wrapper, Long userId) {
        if (userId == null) {
            wrapper.eq(PmTask::getId, -1L);
            return;
        }
        Set<Long> memberTaskIds = taskMemberMapper.selectList(new LambdaQueryWrapper<PmTaskMember>()
                        .eq(PmTaskMember::getUserId, userId)
                        .select(PmTaskMember::getTaskId))
                .stream()
                .map(PmTaskMember::getTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        wrapper.and(condition -> {
            condition.eq(PmTask::getAssigneeId, userId);
            if (!memberTaskIds.isEmpty()) {
                condition.or().in(PmTask::getId, memberTaskIds);
            }
        });
    }

    private void applyProjectIdFilter(LambdaQueryWrapper<PmTask> wrapper, Long projectId) {
        if (projectId == null) {
            return;
        }
        PmProject project = projectMapper.selectById(projectId);
        if (project == null) {
            wrapper.eq(PmTask::getProjectId, -1L);
            return;
        }
        if (ProjectScales.isMajorShell(project)) {
            List<Long> childIds = projectMapper.selectList(new LambdaQueryWrapper<PmProject>()
                            .eq(PmProject::getParentId, projectId)
                            .and(w -> w.isNull(PmProject::getApproveStatus).or().eq(PmProject::getApproveStatus, 1))
                            .select(PmProject::getId))
                    .stream()
                    .map(PmProject::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            if (childIds.isEmpty()) {
                wrapper.eq(PmTask::getProjectId, -1L);
            } else {
                wrapper.in(PmTask::getProjectId, childIds);
            }
            return;
        }
        wrapper.eq(PmTask::getProjectId, projectId);
    }

    @Override
    public List<PmTask> listBoardTasks(Long projectId) {
        if (projectId == null) {
            throw new BusinessException("项目 ID 不能为空");
        }
        PmProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        long loginId = StpUtil.getLoginIdAsLong();
        if (!dataScopeService.isGlobalAdmin(loginId)) {
            Set<Long> companies = dataScopeService.visibleCompanyIds(loginId);
            if (project.getCompanyId() == null || !companies.contains(project.getCompanyId())) {
                throw new BusinessException("无权查看该项目任务");
            }
        }
        LambdaQueryWrapper<PmTask> wrapper = new LambdaQueryWrapper<PmTask>()
                .eq(PmTask::getProjectId, projectId)
                .ne(PmTask::getStatus, 3);
        applyVisibleScope(wrapper);
        List<PmTask> list = list(wrapper
                .orderByAsc(PmTask::getPriority)
                .orderByAsc(PmTask::getDueDate)
                .orderByDesc(PmTask::getId));
        fillExtras(list);
        return list;
    }

    @Override
    public List<PmTask> listRelatedTasks(Long userId) {
        if (userId == null) {
            return List.of();
        }
        // 与任务工作台 /task/management/page 个人口径一致：我负责或我参与。
        // 不再单独按 createBy 收口，避免「仅创建过、已不在参与人中」的任务出现在个人中心却进不了任务管理。
        List<Long> memberTaskIds = taskMemberMapper.selectList(new LambdaQueryWrapper<PmTaskMember>()
                        .eq(PmTaskMember::getUserId, userId)
                        .select(PmTaskMember::getTaskId))
                .stream()
                .map(PmTaskMember::getTaskId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        List<PmTask> list = list(new LambdaQueryWrapper<PmTask>()
                .and(w -> {
                    w.eq(PmTask::getAssigneeId, userId);
                    if (!memberTaskIds.isEmpty()) {
                        w.or().in(PmTask::getId, memberTaskIds);
                    }
                })
                .orderByAsc(PmTask::getPriority)
                .orderByAsc(PmTask::getDueDate)
                .orderByDesc(PmTask::getId));
        fillExtras(list);
        return list;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createTask(PmTask task) {
        if (task.getProjectId() == null) {
            throw new BusinessException("任务必须挂靠项目");
        }
        PmProject project = projectMapper.selectById(task.getProjectId());
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        if (ProjectScales.isMajorShell(project)) {
            throw new BusinessException("重大项目外壳不可挂任务，请在小项目上创建");
        }
        assertCanAccessProject(project);
        long loginId = StpUtil.getLoginIdAsLong();
        String scale = StringUtils.hasText(project.getScale())
                ? project.getScale().trim().toUpperCase()
                : ProjectScales.NORMAL;
        boolean restrictedScale = ProjectScales.KEY.equals(scale) || ProjectScales.MAJOR.equals(scale);
        boolean taskManager = StpUtil.hasPermission("project:task:add");
        if (restrictedScale && !taskManager && !Objects.equals(project.getOwnerId(), loginId)) {
            throw new BusinessException("重点和重大项目仅任务管理员或项目负责人可以创建任务");
        }
        task.setCompanyId(project.getCompanyId());
        if (project.getCompanyId() == null) {
            throw new BusinessException("项目缺少所属公司，无法创建任务");
        }
        Set<Long> eligible = eligibleTaskParticipantIds(project.getId());
        List<Long> participants = task.getParticipantIds() == null
                ? new ArrayList<>()
                : new ArrayList<>(task.getParticipantIds());
        if (eligible.contains(loginId) && !participants.contains(loginId)) {
            participants.add(loginId);
        }
        Long ownerId = task.getAssigneeId() != null ? task.getAssigneeId() : project.getOwnerId();
        if (ownerId == null && !participants.isEmpty()) ownerId = participants.get(0);
        if (ownerId == null) throw new BusinessException("请指定任务负责人");
        if (!participants.contains(ownerId)) participants.add(ownerId);
        task.setAssigneeId(ownerId);
        task.setParticipantIds(participants);
        assertUsersInCompany(project.getCompanyId(), ownerId, participants);
        assertParticipantsEligible(project.getId(), participants);
        if (task.getStatus() == null) {
            task.setStatus(0);
        }
        if (task.getPriority() == null) {
            task.setPriority(2);
        }
        validateDateRange(task);
        applyLifecycle(task, null);
        save(task);
        syncParticipants(task.getId(), task.getParticipantIds());
        syncTaskImages(task.getId(), task.getImageFileIds());
        recordFlow(task.getId(), "CREATE", null, null, null, task.getStatus(), "创建任务");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateTask(PmTask task) {
        if (task.getId() == null) {
            throw new BusinessException("任务 ID 不能为空");
        }
        PmTask existing = getById(task.getId());
        if (existing == null) {
            throw new BusinessException("任务不存在");
        }
        assertCanAccessTask(existing);
        assertTaskNotClosed(existing);
        assertCanWriteTask(existing);
        if (task.getStatus() != null && task.getStatus() == 3 && !Objects.equals(existing.getStatus(), 3)) {
            throw new BusinessException("关闭任务请使用关闭操作并填写原因");
        }
        Long companyId = existing.getCompanyId();
        if (companyId == null && existing.getProjectId() != null) {
            PmProject project = projectMapper.selectById(existing.getProjectId());
            if (project != null) {
                companyId = project.getCompanyId();
                task.setCompanyId(companyId);
            }
        }
        if (companyId == null) {
            throw new BusinessException("任务缺少所属公司，无法更新");
        }
        if (task.getAssigneeId() == null) task.setAssigneeId(existing.getAssigneeId());
        if (task.getAssigneeId() == null) throw new BusinessException("请指定任务负责人");
        assertUsersInCompany(companyId, task.getAssigneeId(), task.getParticipantIds());
        assertParticipantsEligible(existing.getProjectId(), List.of(task.getAssigneeId()));
        if (task.getParticipantIds() != null) {
            if (!StpUtil.hasPermission("project:task:add")) {
                Set<Long> existingParticipants = taskMemberMapper.selectList(new LambdaQueryWrapper<PmTaskMember>()
                                .eq(PmTaskMember::getTaskId, task.getId()))
                        .stream().map(PmTaskMember::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
                Set<Long> requestedParticipants = task.getParticipantIds().stream()
                        .filter(Objects::nonNull).collect(Collectors.toSet());
                if (!existingParticipants.equals(requestedParticipants)) {
                    throw new BusinessException("仅任务管理员可以分派任务参与人");
                }
            }
            assertParticipantsEligible(existing.getProjectId(), task.getParticipantIds());
        }
        if (Integer.valueOf(2).equals(task.getStatus())
                && !StpUtil.hasPermission("project:task:confirm")) {
            task.setStatus(4);
        }
        validateDateRange(task);
        applyLifecycle(task, existing);
        Integer oldStatus = existing.getStatus();
        updateById(task);
        if (task.getStatus() != null && task.getStatus() != 2 && Integer.valueOf(2).equals(oldStatus)) {
            lambdaUpdate().eq(PmTask::getId, task.getId()).setSql("completed_at = NULL").update();
        }
        if (task.getParticipantIds() != null) {
            syncParticipants(task.getId(), task.getParticipantIds());
        }
        if (task.getImageFileIds() != null) {
            syncTaskImages(task.getId(), task.getImageFileIds());
        }
        if (task.getStatus() != null && !task.getStatus().equals(oldStatus)) {
            recordFlow(task.getId(), "STATUS", null, null, oldStatus, task.getStatus(), "编辑时变更状态");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, Integer status) {
        updateStatus(id, status, null, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, Integer status, List<Long> imageFileIds) {
        updateStatus(id, status, imageFileIds, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, Integer status, List<Long> imageFileIds, String remark) {
        if (id == null) {
            throw new BusinessException("任务 ID 不能为空");
        }
        if (status == null || status < 0 || status > 3) {
            throw new BusinessException("状态不正确");
        }
        PmTask existing = getById(id);
        if (existing == null) {
            throw new BusinessException("任务不存在");
        }
        assertCanAccessTask(existing);
        Integer oldStatus = existing.getStatus();
        if (status.equals(oldStatus)) {
            return;
        }
        if (Objects.equals(oldStatus, 3)) {
            throw new BusinessException("已关闭的任务不可修改，仅可查看");
        }
        assertCanWriteTask(existing);
        String note = StringUtils.hasText(remark) ? remark.trim() : null;
        if (status == 3) {
            if (!StringUtils.hasText(note)) {
                throw new BusinessException("关闭任务请填写原因");
            }
            if (note.length() > 500) {
                throw new BusinessException("关闭原因不能超过 500 字");
            }
        }
        boolean completionManager = StpUtil.hasPermission("project:task:confirm");
        int targetStatus = status == 2 && !completionManager ? 4 : status;
        PmTask update = new PmTask();
        update.setId(id);
        update.setStatus(targetStatus);
        applyLifecycle(update, existing);
        updateById(update);
        if (targetStatus != 2 && Integer.valueOf(2).equals(oldStatus)) {
            lambdaUpdate().eq(PmTask::getId, id).setSql("completed_at = NULL").update();
        }
        String flowNote = targetStatus == 4 && !StringUtils.hasText(note) ? "提交完成，等待任务管理员确认" : note;
        recordFlow(id, targetStatus == 4 ? "COMPLETE_SUBMIT" : "STATUS", null, null,
                oldStatus, targetStatus, flowNote, imageFileIds);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reviewCompletion(Long id, boolean approved, String remark) {
        PmTask existing = getById(id);
        if (existing == null) {
            throw new BusinessException("任务不存在");
        }
        assertCanAccessTask(existing);
        if (!Integer.valueOf(4).equals(existing.getStatus())) {
            throw new BusinessException("仅待确认完成的任务可以审核");
        }
        String note = StringUtils.hasText(remark) ? remark.trim() : null;
        if (!approved && !StringUtils.hasText(note)) {
            throw new BusinessException("驳回完成申请请填写原因");
        }
        if (note != null && note.length() > 500) {
            throw new BusinessException("审核说明不能超过 500 字");
        }
        PmTask update = new PmTask();
        update.setId(id);
        update.setStatus(approved ? 2 : 1);
        applyLifecycle(update, existing);
        updateById(update);
        recordFlow(id, approved ? "COMPLETE_APPROVE" : "COMPLETE_REJECT", null, null,
                4, approved ? 2 : 1, note);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transfer(Long id, Long targetUserId, String remark) {
        transfer(id, targetUserId, remark, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transfer(Long id, Long targetUserId, String remark, List<Long> imageFileIds) {
        if (id == null) {
            throw new BusinessException("任务 ID 不能为空");
        }
        if (targetUserId == null) {
            throw new BusinessException("请选择移交对象");
        }
        PmTask existing = getById(id);
        if (existing == null) {
            throw new BusinessException("任务不存在");
        }
        assertCanAccessTask(existing);
        assertTaskNotClosed(existing);
        assertCanWriteTask(existing);
        assertCanTransfer(existing);
        SysUser target = userService.getById(targetUserId);
        if (target == null) {
            throw new BusinessException("移交对象不存在");
        }
        Long companyId = existing.getCompanyId();
        if (companyId == null && existing.getProjectId() != null) {
            PmProject project = projectMapper.selectById(existing.getProjectId());
            if (project != null) {
                companyId = project.getCompanyId();
            }
        }
        assertUsersInCompany(companyId, null, List.of(targetUserId));
        assertParticipantsEligible(existing.getProjectId(), List.of(targetUserId));
        String note = StringUtils.hasText(remark) ? remark.trim() : null;
        if (note != null && note.length() > 500) {
            throw new BusinessException("移交说明不能超过 500 字");
        }
        ensureParticipant(id, targetUserId);
        if (Objects.equals(existing.getAssigneeId(), targetUserId)) {
            return;
        }
        PmTask ownerUpdate = new PmTask();
        ownerUpdate.setId(id);
        ownerUpdate.setAssigneeId(targetUserId);
        ownerUpdate.setLastActivityAt(LocalDateTime.now());
        updateById(ownerUpdate);
        long fromUserId = StpUtil.getLoginIdAsLong();
        recordFlow(id, "TRANSFER", fromUserId, targetUserId, null, null, note, imageFileIds);
        if (!Objects.equals(fromUserId, targetUserId)) {
            String fromName = userDisplayName(fromUserId);
            String taskTitle = StringUtils.hasText(existing.getTitle()) ? existing.getTitle() : ("#" + id);
            String content = fromName + " 把任务「" + taskTitle + "」移交给你";
            if (note != null) {
                content += "。说明：" + note;
            }
            notificationService.notifyUser(
                    targetUserId,
                    "任务移交",
                    content,
                    "task",
                    id,
                    "/project/task?taskId=" + id);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTask(Long id) {
        throw new BusinessException("任务不支持删除，请关闭");
    }

    @Override
    public SysFile uploadImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择文件");
        }
        String contentType = file.getContentType();
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        boolean image = contentType != null && contentType.startsWith("image/");
        boolean video = contentType != null && contentType.startsWith("video/");
        if (!image && !video) {
            String lower = name.toLowerCase();
            image = lower.matches(".*\\.(png|jpe?g|gif|webp|bmp|svg)$");
            video = lower.matches(".*\\.(mp4|webm|mov|m4v|avi|mkv)$");
        }
        if (!image && !video) {
            throw new BusinessException("仅支持上传图片或视频");
        }
        return fileService.upload(file, TASK_IMAGE_BIZ, null);
    }

    @Override
    public void deleteTaskImage(Long fileId) {
        SysFile file = fileService.get(fileId);
        if (!TASK_IMAGE_BIZ.equals(file.getBizType())) {
            throw new BusinessException("无权删除该图片");
        }
        fileService.deleteFile(fileId);
    }

    @Override
    public List<PmTaskComment> listComments(Long taskId) {
        PmTask task = getById(taskId);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        assertCanAccessTask(task);
        List<PmTaskComment> list = commentMapper.selectList(new LambdaQueryWrapper<PmTaskComment>()
                .eq(PmTaskComment::getTaskId, taskId)
                .orderByAsc(PmTaskComment::getId));
        list.forEach(comment -> comment.setContent(Jsoup.clean(
                comment.getContent() == null ? "" : comment.getContent(), COMMENT_HTML_SAFELIST)));
        fillCommentAuthors(list);
        fillCommentAttachments(list);
        return list;
    }

    @Override
    public SysFile uploadCommentAttachment(MultipartFile file) {
        return fileService.upload(file, TASK_COMMENT_FILE_BIZ, null);
    }

    @Override
    public void deleteCommentAttachment(Long fileId) {
        SysFile file = fileService.get(fileId);
        if (!TASK_COMMENT_FILE_BIZ.equals(file.getBizType()) || file.getBizId() != null) {
            throw new BusinessException("无权删除该评论附件");
        }
        long loginId = StpUtil.getLoginIdAsLong();
        if (file.getCreateBy() != null && !file.getCreateBy().equals(loginId)
                && !dataScopeService.isGlobalAdmin(loginId)) {
            throw new BusinessException("只能删除自己上传的附件");
        }
        fileService.deleteFile(fileId);
    }

    @Override
    @Transactional
    public PmTaskComment addComment(Long taskId, String content, List<Long> fileIds) {
        PmTask task = getById(taskId);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        assertCanAccessTask(task);
        String safeHtml = Jsoup.clean(content == null ? "" : content.trim(), COMMENT_HTML_SAFELIST);
        String plainText = Jsoup.parse(safeHtml).text().trim();
        boolean hasAttachments = fileIds != null && !fileIds.isEmpty();
        if (!StringUtils.hasText(plainText) && !hasAttachments) {
            throw new BusinessException("评论内容不能为空");
        }
        if (plainText.length() > 2000) {
            throw new BusinessException("评论不能超过 2000 字");
        }
        PmTaskComment comment = new PmTaskComment();
        comment.setTaskId(taskId);
        comment.setContent(safeHtml);
        commentMapper.insert(comment);
        bindCommentAttachments(fileIds, comment.getId());
        lambdaUpdate().eq(PmTask::getId, taskId).set(PmTask::getLastActivityAt, LocalDateTime.now()).update();
        fillCommentAuthors(List.of(comment));
        fillCommentAttachments(List.of(comment));
        return comment;
    }

    private void bindCommentAttachments(List<Long> fileIds, Long commentId) {
        if (fileIds == null || fileIds.isEmpty()) return;
        long loginId = StpUtil.getLoginIdAsLong();
        List<Long> allowed = new ArrayList<>();
        for (Long fileId : fileIds.stream().distinct().toList()) {
            SysFile file = fileService.get(fileId);
            if (!TASK_COMMENT_FILE_BIZ.equals(file.getBizType()) || file.getBizId() != null
                    || (file.getCreateBy() != null && !file.getCreateBy().equals(loginId))) {
                throw new BusinessException("评论附件无效或无权使用");
            }
            allowed.add(fileId);
        }
        fileService.bindBiz(allowed, TASK_COMMENT_FILE_BIZ, commentId);
    }

    private void fillCommentAttachments(List<PmTaskComment> comments) {
        if (comments == null || comments.isEmpty()) return;
        Map<Long, List<SysFile>> files = fileService.mapByBiz(TASK_COMMENT_FILE_BIZ,
                comments.stream().map(PmTaskComment::getId).toList());
        comments.forEach(comment -> comment.setAttachments(files.getOrDefault(comment.getId(), List.of())));
    }

    @Override
    public List<PmTaskFlow> listFlows(Long taskId) {
        PmTask task = getById(taskId);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        assertCanAccessTask(task);
        List<PmTaskFlow> list = flowMapper.selectList(new LambdaQueryWrapper<PmTaskFlow>()
                .eq(PmTaskFlow::getTaskId, taskId)
                .orderByDesc(PmTaskFlow::getId));
        fillFlows(list);
        return list;
    }

    private void recordFlow(Long taskId, String action, Long fromUserId, Long toUserId,
                            Integer fromStatus, Integer toStatus, String remark) {
        recordFlow(taskId, action, fromUserId, toUserId, fromStatus, toStatus, remark, null);
    }

    private void recordFlow(Long taskId, String action, Long fromUserId, Long toUserId,
                            Integer fromStatus, Integer toStatus, String remark, List<Long> imageFileIds) {
        PmTaskFlow flow = new PmTaskFlow();
        flow.setTaskId(taskId);
        flow.setAction(action);
        flow.setFromUserId(fromUserId);
        flow.setToUserId(toUserId);
        flow.setFromStatus(fromStatus);
        flow.setToStatus(toStatus);
        flow.setRemark(remark);
        flowMapper.insert(flow);
        if (imageFileIds != null && !imageFileIds.isEmpty()) {
            fileService.bindBiz(imageFileIds, "task_flow", flow.getId());
        }
    }

    private void fillFlows(List<PmTaskFlow> flows) {
        if (flows == null || flows.isEmpty()) {
            return;
        }
        Set<Long> userIds = new HashSet<>();
        Set<Long> flowIds = new HashSet<>();
        for (PmTaskFlow flow : flows) {
            if (flow.getId() != null) {
                flowIds.add(flow.getId());
            }
            if (flow.getCreateBy() != null) {
                userIds.add(flow.getCreateBy());
            }
            if (flow.getFromUserId() != null) {
                userIds.add(flow.getFromUserId());
            }
            if (flow.getToUserId() != null) {
                userIds.add(flow.getToUserId());
            }
        }
        Map<Long, SysUser> userMap = loadUserMap(userIds);
        Map<Long, List<SysFile>> imageMap = flowIds.isEmpty() ? Map.of() : fileService.mapByBiz("task_flow", flowIds);
        for (PmTaskFlow flow : flows) {
            SysUser op = userMap.get(flow.getCreateBy());
            if (op != null) {
                flow.setOperatorName(op.getNickname() != null ? op.getNickname() : op.getUsername());
            }
            SysUser from = userMap.get(flow.getFromUserId());
            if (from != null) {
                flow.setFromUserName(from.getNickname() != null ? from.getNickname() : from.getUsername());
            }
            SysUser to = userMap.get(flow.getToUserId());
            if (to != null) {
                flow.setToUserName(to.getNickname() != null ? to.getNickname() : to.getUsername());
            }
            flow.setActionLabel(actionLabel(flow.getAction()));
            flow.setSummary(buildFlowSummary(flow));
            List<SysFile> images = imageMap.getOrDefault(flow.getId(), List.of());
            flow.setImages(images);
            flow.setImageFileIds(images.stream().map(SysFile::getId).toList());
        }
    }

    private String actionLabel(String action) {
        if (action == null) {
            return "操作";
        }
        return switch (action) {
            case "CREATE" -> "创建";
            case "ASSIGN" -> "指派";
            case "STATUS" -> "状态变更";
            case "TRANSFER" -> "移交";
            case "COMPLETE_SUBMIT" -> "提交完成";
            case "COMPLETE_APPROVE" -> "确认完成";
            case "COMPLETE_REJECT" -> "驳回完成";
            default -> action;
        };
    }

    private String buildFlowSummary(PmTaskFlow flow) {
        String action = flow.getAction();
        if ("CREATE".equals(action)) {
            return "创建了任务";
        }
        if ("TRANSFER".equals(action)) {
            String to = flow.getToUserName() != null ? flow.getToUserName() : "—";
            return "移交给 " + to;
        }
        if ("ASSIGN".equals(action)) {
            if (flow.getToUserName() == null) {
                return "取消了指派（历史）";
            }
            if (flow.getFromUserName() == null) {
                return "指派给 " + flow.getToUserName() + "（历史）";
            }
            return "指派由 " + flow.getFromUserName() + " 变更为 " + flow.getToUserName() + "（历史）";
        }
        if ("STATUS".equals(action) || action != null && action.startsWith("COMPLETE_")) {
            String from = STATUS_LABEL.getOrDefault(flow.getFromStatus(), "—");
            String to = STATUS_LABEL.getOrDefault(flow.getToStatus(), "—");
            return from + " → " + to;
        }
        return flow.getRemark() != null ? flow.getRemark() : actionLabel(action);
    }

    private void syncTaskImages(Long taskId, List<Long> imageFileIds) {
        List<SysFile> existing = fileService.listByBiz(TASK_IMAGE_BIZ, taskId);
        Set<Long> keepIds = imageFileIds == null ? Set.of() : new HashSet<>(imageFileIds);
        for (SysFile file : existing) {
            if (!keepIds.contains(file.getId())) {
                fileService.deleteFile(file.getId());
            }
        }
        if (imageFileIds != null && !imageFileIds.isEmpty()) {
            fileService.bindBiz(imageFileIds, TASK_IMAGE_BIZ, taskId);
        }
    }

    private void syncParticipants(Long taskId, List<Long> participantIds) {
        if (participantIds == null) {
            return;
        }
        Set<Long> uniqueIds = new LinkedHashSet<>();
        for (Long userId : participantIds) {
            if (userId != null) {
                uniqueIds.add(userId);
            }
        }
        if (uniqueIds.isEmpty()) {
            throw new BusinessException("请至少保留一名参与人");
        }
        taskMemberMapper.delete(new LambdaQueryWrapper<PmTaskMember>().eq(PmTaskMember::getTaskId, taskId));
        Set<Long> exists = userService.listByIds(uniqueIds).stream()
                .map(SysUser::getId)
                .collect(Collectors.toSet());
        for (Long userId : uniqueIds) {
            if (!exists.contains(userId)) {
                throw new BusinessException("参与人员不存在: " + userId);
            }
            PmTaskMember member = new PmTaskMember();
            member.setTaskId(taskId);
            member.setUserId(userId);
            taskMemberMapper.insert(member);
        }
    }

    /** 已是参与人则跳过，否则插入；返回是否新加入 */
    private boolean ensureParticipant(Long taskId, Long userId) {
        Long cnt = taskMemberMapper.selectCount(new LambdaQueryWrapper<PmTaskMember>()
                .eq(PmTaskMember::getTaskId, taskId)
                .eq(PmTaskMember::getUserId, userId));
        if (cnt != null && cnt > 0) {
            return false;
        }
        PmTaskMember member = new PmTaskMember();
        member.setTaskId(taskId);
        member.setUserId(userId);
        taskMemberMapper.insert(member);
        return true;
    }

    private void applyVisibleScope(LambdaQueryWrapper<PmTask> wrapper) {
        long loginId = StpUtil.getLoginIdAsLong();
        if (dataScopeService.isGlobalAdmin(loginId)
                || StpUtil.hasPermission("project:task:confirm")) {
            return;
        }
        Set<Long> memberTaskIds = taskMemberMapper.selectList(new LambdaQueryWrapper<PmTaskMember>()
                        .eq(PmTaskMember::getUserId, loginId)
                        .select(PmTaskMember::getTaskId))
                .stream()
                .map(PmTaskMember::getTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> ownedProjectIds = projectMapper.selectList(new LambdaQueryWrapper<PmProject>()
                        .eq(PmProject::getOwnerId, loginId)
                        .select(PmProject::getId))
                .stream()
                .map(PmProject::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        wrapper.and(scope -> {
            scope.eq(PmTask::getAssigneeId, loginId);
            if (!memberTaskIds.isEmpty()) {
                scope.or().in(PmTask::getId, memberTaskIds);
            }
            if (!ownedProjectIds.isEmpty()) {
                scope.or().in(PmTask::getProjectId, ownedProjectIds);
            }
        });
    }

    /** 当前用户在该公司作为项目成员的项目 ID */
    private Set<Long> listJoinedProjectIds(long userId, Long companyId) {
        Set<Long> projectIds = projectMemberMapper.selectList(new LambdaQueryWrapper<PmProjectMember>()
                        .eq(PmProjectMember::getUserId, userId)
                        .select(PmProjectMember::getProjectId))
                .stream()
                .map(PmProjectMember::getProjectId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (projectIds.isEmpty() || companyId == null) {
            return projectIds;
        }
        return projectMapper.selectList(new LambdaQueryWrapper<PmProject>()
                        .in(PmProject::getId, projectIds)
                        .eq(PmProject::getCompanyId, companyId)
                        .select(PmProject::getId))
                .stream()
                .map(PmProject::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private boolean isTaskParticipant(Long taskId, long userId) {
        if (taskId == null) {
            return false;
        }
        Long cnt = taskMemberMapper.selectCount(new LambdaQueryWrapper<PmTaskMember>()
                .eq(PmTaskMember::getTaskId, taskId)
                .eq(PmTaskMember::getUserId, userId));
        return cnt != null && cnt > 0;
    }

    /** 可读：任务管理员 / admin / 本人主责或参与 / 项目负责人。 */
    private void assertCanAccessTask(PmTask task) {
        long loginId = StpUtil.getLoginIdAsLong();
        if (dataScopeService.isGlobalAdmin(loginId)
                || StpUtil.hasPermission("project:task:confirm")) {
            return;
        }
        if (Objects.equals(task.getAssigneeId(), loginId)
                || isTaskParticipant(task.getId(), loginId)) {
            return;
        }
        if (task.getProjectId() != null) {
            PmProject project = projectMapper.selectById(task.getProjectId());
            if (project != null && Objects.equals(project.getOwnerId(), loginId)) {
                return;
            }
        }
        throw new BusinessException("无权操作该任务");
    }

    /** 可写：admin / 公司 control / 项目负责人 / 任务参与人（含创建人若在参与人中；创建人也放行） */
    private void assertCanWriteTask(PmTask task) {
        if (!canWriteTask(task, null)) {
            throw new BusinessException("仅任务参与人、项目负责人或股东可修改任务");
        }
    }

    private boolean canWriteTask(PmTask task, PmProject project) {
        if (task == null) {
            return false;
        }
        if (!StpUtil.isLogin()) {
            return false;
        }
        // 已关闭：列表上 canEdit=false；写接口另有 assertTaskNotClosed
        if (task.getStatus() != null && (task.getStatus() == 3 || task.getStatus() == 4)) {
            return false;
        }
        long loginId = StpUtil.getLoginIdAsLong();
        if (dataScopeService.isGlobalAdmin(loginId)) {
            return true;
        }
        Long companyId = task.getCompanyId();
        if (companyId != null && dataScopeService.hasRoleInCompany(loginId, "control", companyId)) {
            return true;
        }
        PmProject p = project;
        if (p == null && task.getProjectId() != null) {
            p = projectMapper.selectById(task.getProjectId());
        }
        if (p != null && Objects.equals(p.getOwnerId(), loginId)) {
            return true;
        }
        if (Objects.equals(task.getCreateBy(), loginId)) {
            return true;
        }
        return isTaskParticipant(task.getId(), loginId);
    }

    private void assertCanTransfer(PmTask task) {
        if (!StpUtil.hasPermission("project:task:add")) {
            throw new BusinessException("仅任务管理员可以分派或移交任务");
        }
        if (!canTransferTask(task, null)) {
            throw new BusinessException("仅任务参与人、项目负责人或股东可移交");
        }
    }

    private boolean canTransferTask(PmTask task, PmProject project) {
        if (!canWriteTask(task, project)) {
            return false;
        }
        long loginId = StpUtil.getLoginIdAsLong();
        if (dataScopeService.isGlobalAdmin(loginId)) {
            return true;
        }
        Long companyId = task.getCompanyId();
        if (companyId != null && dataScopeService.hasRoleInCompany(loginId, "control", companyId)) {
            return true;
        }
        PmProject p = project;
        if (p == null && task.getProjectId() != null) {
            p = projectMapper.selectById(task.getProjectId());
        }
        if (p != null && Objects.equals(p.getOwnerId(), loginId)) {
            return true;
        }
        return isTaskParticipant(task.getId(), loginId);
    }

    private void assertTaskNotClosed(PmTask task) {
        if (task.getStatus() != null && task.getStatus() == 3) {
            throw new BusinessException("已关闭的任务不可修改，仅可查看");
        }
    }

    private void assertCanAccessProject(PmProject project) {
        long loginId = StpUtil.getLoginIdAsLong();
        if (dataScopeService.isGlobalAdmin(loginId)) {
            return;
        }
        Set<Long> companies = dataScopeService.visibleCompanyIds(loginId);
        if (project.getCompanyId() == null || !companies.contains(project.getCompanyId())) {
            throw new BusinessException("无权操作该项目的任务");
        }
    }

    private Set<Long> eligibleTaskParticipantIds(Long projectId) {
        Set<Long> ids = new HashSet<>();
        if (projectId == null) {
            return ids;
        }
        PmProject project = projectMapper.selectById(projectId);
        if (project == null) {
            return ids;
        }
        if (project.getOwnerId() != null) {
            ids.add(project.getOwnerId());
        }
        projectMemberMapper.selectList(new LambdaQueryWrapper<PmProjectMember>()
                        .eq(PmProjectMember::getProjectId, projectId)
                        .select(PmProjectMember::getUserId))
                .stream()
                .map(PmProjectMember::getUserId)
                .filter(Objects::nonNull)
                .forEach(ids::add);
        return ids;
    }

    private void assertParticipantsEligible(Long projectId, List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        Set<Long> allowed = eligibleTaskParticipantIds(projectId);
        for (Long userId : userIds) {
            if (userId != null && !allowed.contains(userId)) {
                throw new BusinessException("任务参与人须为项目负责人或项目参与人");
            }
        }
    }

    private void assertUsersInCompany(Long companyId, Long assigneeId, List<Long> participantIds) {
        if (companyId == null) {
            throw new BusinessException("缺少所属公司，无法设置参与人");
        }
        if (assigneeId != null) {
            assertUserInCompany(assigneeId, companyId);
        }
        if (participantIds != null) {
            for (Long uid : participantIds) {
                if (uid != null) {
                    assertUserInCompany(uid, companyId);
                }
            }
        }
    }

    private void assertUserInCompany(Long userId, Long companyId) {
        if (dataScopeService.isGlobalAdmin(userId)) {
            return;
        }
        if (!dataScopeService.visibleCompanyIds(userId).contains(companyId)) {
            throw new BusinessException("不能跨公司设置参与人");
        }
    }

    private void fillCommentAuthors(List<PmTaskComment> comments) {
        if (comments == null || comments.isEmpty()) {
            return;
        }
        Set<Long> userIds = comments.stream()
                .map(PmTaskComment::getCreateBy)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        Map<Long, SysUser> userMap = loadUserMap(userIds);
        for (PmTaskComment comment : comments) {
            SysUser user = userMap.get(comment.getCreateBy());
            if (user != null) {
                comment.setAuthorName(user.getNickname() != null ? user.getNickname() : user.getUsername());
            }
        }
    }

    private void fillExtras(List<PmTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        Set<Long> projectIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        List<Long> taskIds = new ArrayList<>(tasks.size());
        for (PmTask task : tasks) {
            taskIds.add(task.getId());
            if (task.getAssigneeId() != null) userIds.add(task.getAssigneeId());
            if (task.getProjectId() != null) {
                projectIds.add(task.getProjectId());
            }
        }

        Map<Long, List<PmTaskMember>> membersByTask = new HashMap<>();
        if (!taskIds.isEmpty()) {
            List<PmTaskMember> members = taskMemberMapper.selectList(new LambdaQueryWrapper<PmTaskMember>()
                    .in(PmTaskMember::getTaskId, taskIds)
                    .orderByAsc(PmTaskMember::getId));
            for (PmTaskMember member : members) {
                membersByTask.computeIfAbsent(member.getTaskId(), k -> new ArrayList<>()).add(member);
                if (member.getUserId() != null) {
                    userIds.add(member.getUserId());
                }
            }
        }

        Map<Long, PmProject> projectMap = projectIds.isEmpty() ? Map.of()
                : projectMapper.selectList(new LambdaQueryWrapper<PmProject>().in(PmProject::getId, projectIds)).stream()
                .collect(Collectors.toMap(PmProject::getId, p -> p, (a, b) -> a));
        Map<Long, SysUser> userMap = loadUserMap(userIds);

        for (PmTask task : tasks) {
            PmProject project = projectMap.get(task.getProjectId());
            if (project != null) {
                task.setProjectName(project.getName());
            }
            SysUser owner = userMap.get(task.getAssigneeId());
            task.setAssigneeName(owner == null ? null : userName(owner));
            task.setCanEdit(canWriteTask(task, project));
            task.setCanTransfer(canTransferTask(task, project));
            List<PmTaskMember> members = membersByTask.getOrDefault(task.getId(), List.of());
            if (members.isEmpty()) {
                task.setParticipantIds(List.of());
                task.setParticipantNames(List.of());
            } else {
                List<Long> ids = new ArrayList<>(members.size());
                List<String> names = new ArrayList<>(members.size());
                for (PmTaskMember member : members) {
                    ids.add(member.getUserId());
                    SysUser user = userMap.get(member.getUserId());
                    if (user != null) {
                        names.add(user.getNickname() != null ? user.getNickname() : user.getUsername());
                    }
                }
                task.setParticipantIds(ids);
                task.setParticipantNames(names);
            }
            task.setOverdue(isOverdue(task));
        }
    }

    private Map<Long, SysUser> loadUserMap(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> u, (a, b) -> a));
    }

    private void validateDateRange(PmTask task) {
        if (task.getStartDate() != null && task.getDueDate() != null
                && task.getDueDate().isBefore(task.getStartDate())) {
            throw new BusinessException("截止日期不能早于开始日期");
        }
    }

    private void applyLifecycle(PmTask update, PmTask existing) {
        LocalDateTime now = LocalDateTime.now();
        Integer status = update.getStatus() != null ? update.getStatus() : existing == null ? 0 : existing.getStatus();
        update.setLastActivityAt(now);
        if (status != null && status == 1 && (existing == null || existing.getStartedAt() == null)) {
            update.setStartedAt(now);
        }
        if (status != null && status == 2) {
            update.setCompletedAt(now);
        } else if (existing != null && Integer.valueOf(2).equals(existing.getStatus())) {
            update.setCompletedAt(null);
        }
        if (!StringUtils.hasText(update.getRiskLevel())) {
            update.setRiskLevel(existing == null || !StringUtils.hasText(existing.getRiskLevel())
                    ? "NORMAL" : existing.getRiskLevel());
        }
    }

    private boolean isOverdue(PmTask task) {
        if (task.getDueDate() == null || task.getStatus() == null) {
            return false;
        }
        return task.getDueDate().isBefore(LocalDate.now()) && (task.getStatus() == 0 || task.getStatus() == 1);
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
