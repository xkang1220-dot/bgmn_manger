package com.kk.biz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kk.biz.dto.CompanyTaskShareOption;
import com.kk.biz.dto.CompanyTaskShareState;
import com.kk.biz.dto.PublicCompanyTaskBoard;
import com.kk.biz.dto.PublicCompanyTaskComment;
import com.kk.biz.dto.PublicCompanyTaskItem;
import com.kk.biz.dto.PublicCompanyTaskPerson;
import com.kk.biz.entity.PmCompanyTaskShare;
import com.kk.biz.entity.PmProject;
import com.kk.biz.entity.PmTask;
import com.kk.biz.entity.PmTaskComment;
import com.kk.biz.entity.PmTaskMember;
import com.kk.biz.mapper.PmCompanyTaskShareMapper;
import com.kk.biz.mapper.PmProjectMapper;
import com.kk.biz.mapper.PmTaskCommentMapper;
import com.kk.biz.mapper.PmTaskMapper;
import com.kk.biz.mapper.PmTaskMemberMapper;
import com.kk.common.exception.BusinessException;
import com.kk.system.entity.SysDept;
import com.kk.system.entity.SysUser;
import com.kk.system.service.DataScopeService;
import com.kk.system.service.SysDeptService;
import com.kk.system.service.SysUserService;
import com.kk.system.support.TaskQueryRateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CompanyTaskShareService {

    private static final int ACTIVE = 1;
    private static final int REVOKED = 0;
    private static final int COMMENT_LIMIT = 5;
    private static final int MAX_RANGE_DAYS = 62;
    private static final String INVALID = "链接无效或已作废";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter COMMENT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<Integer, String> STATUS_LABEL = Map.of(
            0, "待办",
            1, "进行中",
            2, "已完成",
            3, "已关闭"
    );

    private final PmCompanyTaskShareMapper shareMapper;
    private final PmTaskMapper taskMapper;
    private final PmTaskMemberMapper taskMemberMapper;
    private final PmTaskCommentMapper commentMapper;
    private final PmProjectMapper projectMapper;
    private final SysDeptService deptService;
    private final SysUserService userService;
    private final DataScopeService dataScopeService;
    private final TaskQueryRateLimiter rateLimiter;

    public List<CompanyTaskShareOption> listOptions(long loginId) {
        return shareableCompanies(loginId).stream().map(dept -> {
            CompanyTaskShareOption option = new CompanyTaskShareOption();
            option.setId(dept.getId());
            option.setName(dept.getName());
            return option;
        }).toList();
    }

    public List<CompanyTaskShareOption> listMembers(long loginId, Long companyId) {
        SysDept company = requireShareable(loginId, companyId);
        return enabledMembers(company.getId()).stream()
                .sorted(Comparator.comparing(CompanyTaskShareService::displayName, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(user -> {
                    CompanyTaskShareOption option = new CompanyTaskShareOption();
                    option.setId(user.getId());
                    option.setName(displayName(user));
                    return option;
                }).toList();
    }

    public CompanyTaskShareState state(long loginId, Long companyId) {
        SysDept company = requireShareable(loginId, companyId);
        return toState(company, findByCompany(company.getId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public CompanyTaskShareState generate(long loginId, Long companyId, List<Long> userIds, String fromText, String toText) {
        SysDept company = requireShareable(loginId, companyId);
        LocalDate[] range = parseRange(fromText, toText);
        String storedUsers = joinUserIds(requireSelectedUsers(company.getId(), userIds));
        String token = newToken();
        PmCompanyTaskShare existing = findByCompany(company.getId());
        if (existing == null) {
            PmCompanyTaskShare row = new PmCompanyTaskShare();
            row.setCompanyId(company.getId());
            row.setToken(token);
            row.setStatus(ACTIVE);
            row.setUserIds(storedUsers);
            row.setDateFrom(range[0]);
            row.setDateTo(range[1]);
            shareMapper.insert(row);
            return toState(company, row);
        }
        shareMapper.update(null, new LambdaUpdateWrapper<PmCompanyTaskShare>()
                .eq(PmCompanyTaskShare::getId, existing.getId())
                .set(PmCompanyTaskShare::getToken, token)
                .set(PmCompanyTaskShare::getStatus, ACTIVE)
                .set(PmCompanyTaskShare::getUserIds, storedUsers)
                .set(PmCompanyTaskShare::getDateFrom, range[0])
                .set(PmCompanyTaskShare::getDateTo, range[1]));
        existing.setToken(token);
        existing.setStatus(ACTIVE);
        existing.setUserIds(storedUsers);
        existing.setDateFrom(range[0]);
        existing.setDateTo(range[1]);
        return toState(company, existing);
    }

    @Transactional(rollbackFor = Exception.class)
    public CompanyTaskShareState updateUsers(long loginId, Long companyId, List<Long> userIds, String fromText, String toText) {
        SysDept company = requireShareable(loginId, companyId);
        LocalDate[] range = parseRange(fromText, toText);
        String storedUsers = joinUserIds(requireSelectedUsers(company.getId(), userIds));
        PmCompanyTaskShare existing = findByCompany(company.getId());
        if (existing == null || !Integer.valueOf(ACTIVE).equals(existing.getStatus()) || !StringUtils.hasText(existing.getToken())) {
            throw new BusinessException("请先生成链接");
        }
        shareMapper.update(null, new LambdaUpdateWrapper<PmCompanyTaskShare>()
                .eq(PmCompanyTaskShare::getId, existing.getId())
                .set(PmCompanyTaskShare::getUserIds, storedUsers)
                .set(PmCompanyTaskShare::getDateFrom, range[0])
                .set(PmCompanyTaskShare::getDateTo, range[1]));
        existing.setUserIds(storedUsers);
        existing.setDateFrom(range[0]);
        existing.setDateTo(range[1]);
        return toState(company, existing);
    }

    @Transactional(rollbackFor = Exception.class)
    public void revoke(long loginId, Long companyId) {
        SysDept company = requireShareable(loginId, companyId);
        PmCompanyTaskShare existing = findByCompany(company.getId());
        if (existing == null) {
            return;
        }
        shareMapper.update(null, new LambdaUpdateWrapper<PmCompanyTaskShare>()
                .eq(PmCompanyTaskShare::getId, existing.getId())
                .set(PmCompanyTaskShare::getToken, null)
                .set(PmCompanyTaskShare::getStatus, REVOKED));
    }

    public PublicCompanyTaskBoard query(String token, String clientIp) {
        rateLimiter.assertAllowed(clientIp);
        if (!isValidToken(token)) {
            rateLimiter.recordFail(clientIp);
            throw new BusinessException(INVALID);
        }
        PmCompanyTaskShare share = shareMapper.selectOne(new LambdaQueryWrapper<PmCompanyTaskShare>()
                .eq(PmCompanyTaskShare::getToken, token.trim())
                .eq(PmCompanyTaskShare::getStatus, ACTIVE)
                .last("LIMIT 1"));
        if (share == null || share.getCompanyId() == null) {
            rateLimiter.recordFail(clientIp);
            throw new BusinessException(INVALID);
        }
        SysDept company = deptService.getById(share.getCompanyId());
        if (!isActiveCompany(company)) {
            rateLimiter.recordFail(clientIp);
            throw new BusinessException(INVALID);
        }
        LocalDate[] range = storedRange(share);
        rateLimiter.recordSuccess(clientIp);
        return buildBoard(company, range[0], range[1], parseUserIds(share.getUserIds()));
    }

    private PublicCompanyTaskBoard buildBoard(SysDept company, LocalDate from, LocalDate to, List<Long> selectedIds) {
        Map<Long, SysUser> enabledById = new LinkedHashMap<>();
        for (SysUser user : enabledMembers(company.getId())) {
            enabledById.put(user.getId(), user);
        }
        List<SysUser> people = new ArrayList<>();
        for (Long userId : selectedIds) {
            SysUser user = enabledById.get(userId);
            if (user != null) {
                people.add(user);
            }
        }
        Set<Long> selected = people.stream().map(SysUser::getId).collect(Collectors.toSet());
        if (selected.isEmpty()) {
            PublicCompanyTaskBoard empty = new PublicCompanyTaskBoard();
            empty.setCompanyName(company.getName());
            empty.setFrom(from);
            empty.setTo(to);
            return empty;
        }
        List<PmTask> tasks = listTasks(company.getId(), from, to);
        Map<Long, List<Long>> participants = loadParticipants(tasks);
        List<PmTask> visible = new ArrayList<>();
        Map<Long, List<Long>> relatedByTask = new HashMap<>();
        for (PmTask task : tasks) {
            List<Long> related = relatedUsers(task, participants.getOrDefault(task.getId(), List.of()), enabledById.keySet())
                    .stream().filter(selected::contains).toList();
            if (related.isEmpty()) {
                continue;
            }
            visible.add(task);
            relatedByTask.put(task.getId(), related);
        }
        Map<Long, SysUser> nameUsers = new HashMap<>(enabledById);
        Set<Long> extraIds = new LinkedHashSet<>();
        for (PmTask task : visible) {
            for (Long userId : participants.getOrDefault(task.getId(), List.of())) {
                if (!nameUsers.containsKey(userId)) {
                    extraIds.add(userId);
                }
            }
        }
        if (!extraIds.isEmpty()) {
            userService.listByIds(extraIds).forEach(user -> nameUsers.put(user.getId(), user));
        }
        Map<Long, String> projectNames = loadProjectNames(visible);
        Map<Long, List<PublicCompanyTaskComment>> comments = loadComments(visible);

        Map<Long, Integer> taskCounts = new HashMap<>();
        Map<Long, Integer> overdueCounts = new HashMap<>();
        List<PublicCompanyTaskItem> items = new ArrayList<>();
        for (PmTask task : visible) {
            List<Long> related = relatedByTask.getOrDefault(task.getId(), List.of());
            boolean overdue = isOverdue(task);
            for (Long userId : related) {
                taskCounts.merge(userId, 1, Integer::sum);
                if (overdue) {
                    overdueCounts.merge(userId, 1, Integer::sum);
                }
            }
            items.add(toItem(task, projectNames.get(task.getProjectId()), related, nameUsers, participants, overdue, comments));
        }

        PublicCompanyTaskBoard board = new PublicCompanyTaskBoard();
        board.setCompanyName(company.getName());
        board.setFrom(from);
        board.setTo(to);
        board.setPeople(people.stream().map(user -> {
            PublicCompanyTaskPerson person = new PublicCompanyTaskPerson();
            person.setUserId(user.getId());
            person.setName(displayName(user));
            person.setTaskCount(taskCounts.getOrDefault(user.getId(), 0));
            person.setOverdueCount(overdueCounts.getOrDefault(user.getId(), 0));
            return person;
        }).toList());
        board.setTasks(items);
        return board;
    }

    private PublicCompanyTaskItem toItem(PmTask task, String projectName, List<Long> related,
                                         Map<Long, SysUser> peopleById, Map<Long, List<Long>> participants,
                                         boolean overdue, Map<Long, List<PublicCompanyTaskComment>> comments) {
        PublicCompanyTaskItem item = new PublicCompanyTaskItem();
        item.setId(task.getId());
        item.setTitle(task.getTitle());
        item.setContent(task.getContent());
        item.setStatus(task.getStatus());
        item.setStatusLabel(STATUS_LABEL.getOrDefault(task.getStatus() == null ? 0 : task.getStatus(), "—"));
        item.setPriority(task.getPriority());
        item.setStartDate(task.getStartDate());
        item.setDueDate(task.getDueDate());
        item.setProgress(task.getProgress());
        item.setProjectName(projectName);
        item.setOverdue(overdue);
        item.setRelatedUserIds(related);
        List<String> names = new ArrayList<>();
        for (Long userId : participants.getOrDefault(task.getId(), List.of())) {
            SysUser user = peopleById.get(userId);
            if (user != null) {
                names.add(displayName(user));
            }
        }
        item.setParticipantNames(names);
        item.setComments(comments.getOrDefault(task.getId(), List.of()));
        return item;
    }

    private List<PmTask> listTasks(Long companyId, LocalDate from, LocalDate to) {
        List<PmTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<PmTask>()
                .eq(PmTask::getCompanyId, companyId)
                .and(w -> w.isNotNull(PmTask::getStartDate).or().isNotNull(PmTask::getDueDate))
                .apply("COALESCE(start_date, due_date) <= {0}", to)
                .apply("COALESCE(due_date, start_date) >= {0}", from));
        tasks.sort(Comparator
                .comparing((PmTask task) -> task.getPriority() == null ? 9 : task.getPriority())
                .thenComparing(PmTask::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(PmTask::getId, Comparator.nullsLast(Comparator.reverseOrder())));
        return tasks;
    }

    private Map<Long, List<Long>> loadParticipants(List<PmTask> tasks) {
        List<Long> taskIds = tasks.stream().map(PmTask::getId).filter(id -> id != null).toList();
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<Long>> grouped = new HashMap<>();
        taskMemberMapper.selectList(new LambdaQueryWrapper<PmTaskMember>()
                        .in(PmTaskMember::getTaskId, taskIds)
                        .orderByAsc(PmTaskMember::getId))
                .forEach(member -> {
                    if (member.getTaskId() != null && member.getUserId() != null) {
                        grouped.computeIfAbsent(member.getTaskId(), key -> new ArrayList<>()).add(member.getUserId());
                    }
                });
        return grouped;
    }

    private Map<Long, String> loadProjectNames(List<PmTask> tasks) {
        Set<Long> projectIds = tasks.stream().map(PmTask::getProjectId).filter(id -> id != null).collect(Collectors.toSet());
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        return projectMapper.selectList(new LambdaQueryWrapper<PmProject>().in(PmProject::getId, projectIds)).stream()
                .collect(Collectors.toMap(PmProject::getId, PmProject::getName, (a, b) -> a));
    }

    private Map<Long, List<PublicCompanyTaskComment>> loadComments(List<PmTask> tasks) {
        List<Long> taskIds = tasks.stream().map(PmTask::getId).filter(id -> id != null).toList();
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        List<PmTaskComment> comments = commentMapper.selectList(new LambdaQueryWrapper<PmTaskComment>()
                .in(PmTaskComment::getTaskId, taskIds)
                .orderByDesc(PmTaskComment::getId));
        Set<Long> authorIds = comments.stream().map(PmTaskComment::getCreateBy).filter(id -> id != null).collect(Collectors.toSet());
        Map<Long, SysUser> authors = authorIds.isEmpty() ? Map.of()
                : userService.listByIds(authorIds).stream().collect(Collectors.toMap(SysUser::getId, user -> user, (a, b) -> a));
        Map<Long, List<PublicCompanyTaskComment>> grouped = new HashMap<>();
        for (PmTaskComment comment : comments) {
            List<PublicCompanyTaskComment> bucket = grouped.computeIfAbsent(comment.getTaskId(), key -> new ArrayList<>());
            if (bucket.size() >= COMMENT_LIMIT) {
                continue;
            }
            PublicCompanyTaskComment item = new PublicCompanyTaskComment();
            SysUser author = authors.get(comment.getCreateBy());
            item.setAuthorName(author == null ? "—" : displayName(author));
            item.setContent(comment.getContent());
            item.setCreateTime(comment.getCreateTime() == null ? null : comment.getCreateTime().format(COMMENT_TIME));
            bucket.add(item);
        }
        return grouped;
    }

    private List<Long> relatedUsers(PmTask task, List<Long> participantIds, Set<Long> memberIds) {
        LinkedHashSet<Long> related = new LinkedHashSet<>();
        if (task.getCreateBy() != null && memberIds.contains(task.getCreateBy())) {
            related.add(task.getCreateBy());
        }
        for (Long userId : participantIds) {
            if (memberIds.contains(userId)) {
                related.add(userId);
            }
        }
        return new ArrayList<>(related);
    }

    private List<SysUser> enabledMembers(Long companyId) {
        Set<Long> memberIds = dataScopeService.memberUserIdsInCompany(companyId);
        if (memberIds.isEmpty()) {
            return List.of();
        }
        return userService.listByIds(memberIds).stream()
                .filter(user -> user.getId() != null && Integer.valueOf(1).equals(user.getStatus()))
                .toList();
    }

    private List<SysDept> shareableCompanies(long loginId) {
        List<SysDept> companies = deptService.listCompanies();
        if (dataScopeService.isGlobalAdmin(loginId)) {
            return companies;
        }
        return companies.stream()
                .filter(dept -> dataScopeService.hasRoleInCompany(loginId, "control", dept.getId()))
                .toList();
    }

    private SysDept requireShareable(long loginId, Long companyId) {
        if (companyId == null) {
            throw new BusinessException(400, "请选择公司");
        }
        return shareableCompanies(loginId).stream()
                .filter(dept -> companyId.equals(dept.getId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(403, "无权生成该公司的工作外链"));
    }

    private PmCompanyTaskShare findByCompany(Long companyId) {
        return shareMapper.selectOne(new LambdaQueryWrapper<PmCompanyTaskShare>()
                .eq(PmCompanyTaskShare::getCompanyId, companyId)
                .last("LIMIT 1"));
    }

    private CompanyTaskShareState toState(SysDept company, PmCompanyTaskShare share) {
        CompanyTaskShareState state = new CompanyTaskShareState();
        state.setCompanyId(company.getId());
        state.setCompanyName(company.getName());
        boolean active = share != null && Integer.valueOf(ACTIVE).equals(share.getStatus()) && StringUtils.hasText(share.getToken());
        state.setActive(active);
        state.setPath(active ? "/public/company-tasks/" + share.getToken() : null);
        state.setUserIds(parseUserIds(share == null ? null : share.getUserIds()));
        state.setDateFrom(share == null ? null : share.getDateFrom());
        state.setDateTo(share == null ? null : share.getDateTo());
        return state;
    }

    private List<Long> requireSelectedUsers(Long companyId, List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            throw new BusinessException(400, "请选择人员");
        }
        Set<Long> allowed = enabledMembers(companyId).stream().map(SysUser::getId).collect(Collectors.toSet());
        LinkedHashSet<Long> picked = new LinkedHashSet<>();
        for (Long userId : userIds) {
            if (userId == null || !allowed.contains(userId)) {
                throw new BusinessException(400, "只能选择本公司的在职人员");
            }
            picked.add(userId);
        }
        return new ArrayList<>(picked);
    }

    private static String joinUserIds(List<Long> userIds) {
        return userIds.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static List<Long> parseUserIds(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            try {
                ids.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {
                // 脏数据跳过，避免整条链接打不开
            }
        }
        return ids;
    }

    private String newToken() {
        for (int i = 0; i < 5; i++) {
            byte[] bytes = new byte[24];
            RANDOM.nextBytes(bytes);
            String token = HexFormat.of().formatHex(bytes);
            Long count = shareMapper.selectCount(new LambdaQueryWrapper<PmCompanyTaskShare>()
                    .eq(PmCompanyTaskShare::getToken, token));
            if (count == null || count == 0) {
                return token;
            }
        }
        throw new BusinessException("生成链接失败，请重试");
    }

    private static boolean isValidToken(String token) {
        return StringUtils.hasText(token) && token.trim().matches("[0-9a-f]{48}");
    }

    private static boolean isActiveCompany(SysDept company) {
        if (company == null || !Integer.valueOf(1).equals(company.getStatus())) {
            return false;
        }
        Long parentId = company.getParentId();
        return parentId == null || parentId == 0L;
    }

    private static LocalDate[] storedRange(PmCompanyTaskShare share) {
        LocalDate today = LocalDate.now();
        LocalDate from = share.getDateFrom() != null ? share.getDateFrom() : today;
        LocalDate to = share.getDateTo() != null ? share.getDateTo() : from;
        if (from.isAfter(to)) {
            LocalDate swap = from;
            from = to;
            to = swap;
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS - 1L) {
            to = from.plusDays(MAX_RANGE_DAYS - 1L);
        }
        return new LocalDate[]{from, to};
    }

    private static LocalDate[] parseRange(String fromText, String toText) {
        LocalDate today = LocalDate.now();
        LocalDate from = parseDay(fromText, today);
        LocalDate to = parseDay(toText, today);
        if (from.isAfter(to)) {
            throw new BusinessException(400, "开始日期不能晚于结束日期");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS - 1L) {
            throw new BusinessException(400, "日期范围不能超过 62 天");
        }
        return new LocalDate[]{from, to};
    }

    private static LocalDate parseDay(String text, LocalDate fallback) {
        if (!StringUtils.hasText(text)) {
            return fallback;
        }
        try {
            return LocalDate.parse(text.trim(), DAY);
        } catch (DateTimeParseException e) {
            throw new BusinessException(400, "日期格式应为 yyyy-MM-dd");
        }
    }

    private static boolean isOverdue(PmTask task) {
        if (task.getDueDate() == null || task.getStatus() == null) {
            return false;
        }
        return task.getDueDate().isBefore(LocalDate.now()) && (task.getStatus() == 0 || task.getStatus() == 1);
    }

    private static String displayName(SysUser user) {
        if (StringUtils.hasText(user.getNickname())) {
            return user.getNickname();
        }
        return StringUtils.hasText(user.getUsername()) ? user.getUsername() : "—";
    }
}
