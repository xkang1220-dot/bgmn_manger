package com.kk.biz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kk.biz.config.TaskAiProperties;
import com.kk.biz.dto.TaskAiModels.*;
import com.kk.biz.entity.*;
import com.kk.biz.mapper.*;
import com.kk.common.exception.BusinessException;
import com.kk.system.entity.SysUser;
import com.kk.system.mapper.SysUserMapper;
import com.kk.system.service.DataScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TaskAiService {
    private static final int PROTOCOL_VERSION = 1;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final TypeReference<List<Map<String, Object>>> MAP_LIST = new TypeReference<>() {};
    /** 普通员工查询他人时的固定拒绝文案 */
    private static final String MSG_DENY_QUERY_OTHERS =
            "权限不足：普通员工仅可查看自己的任务信息，无法查询其他人员。";
    /** 项目负责人查询对象不在负责项目内时的固定拒绝文案 */
    private static final String MSG_DENY_OUTSIDE_OWNED_PROJECTS =
            "权限不足：您仅可查询自己负责项目内成员的任务；当前查询对象不在您负责的项目中。";
    private final AiTaskLogSessionMapper sessionMapper;
    private final PmProjectMapper projectMapper;
    private final PmProjectMemberMapper projectMemberMapper;
    private final PmTaskMapper taskMapper;
    private final PmTaskMemberMapper taskMemberMapper;
    private final PmTaskFlowMapper taskFlowMapper;
    private final SysUserMapper userMapper;
    private final PmTaskService taskService;
    private final TaskWorkDurationService taskWorkDurationService;
    private final DataScopeService dataScopeService;
    private final ObjectMapper objectMapper;
    private final TaskAiProperties properties;
    private final TaskAiTimeRangeNormalizer timeRangeNormalizer;

    @Transactional(rollbackFor = Exception.class)
    public ChatResponse chat(long loginUserId, ChatRequest request) {
        if (request == null || (!StringUtils.hasText(request.getMessage()) && request.getClarifyAnswer() == null)) {
            throw new BusinessException("消息或澄清选项不能为空");
        }
        AiTaskLogSession session = loadOrCreate(loginUserId, request.getSessionId());
        if (Set.of("committed", "cancelled").contains(session.getStatus())) {
            throw new BusinessException("会话已结束，请发起新会话");
        }
        List<Map<String, Object>> messages = readList(session.getMessagesJson());
        String text = StringUtils.hasText(request.getMessage()) ? request.getMessage().trim() : "";
        Long clarifiedUserId = null;
        if (request.getClarifyAnswer() != null) {
            text += "\n[结构化澄清] field=" + request.getClarifyAnswer().getField()
                    + ", optionId=" + request.getClarifyAnswer().getOptionId()
                    + ", label=" + Objects.toString(request.getClarifyAnswer().getLabel(), "");
            if ("user".equals(request.getClarifyAnswer().getField())) {
                clarifiedUserId = number(request.getClarifyAnswer().getOptionId());
            }
        }
        messages.add(Map.of("role", "user", "content", text));
        AgentResult result = runAgent(loginUserId, session, messages, clarifiedUserId);
        messages.add(Map.of("role", "assistant", "content", result.replyText,
                "blocks", result.blocks));
        session.setMessagesJson(write(messages));
        session.setUpdateTime(LocalDateTime.now(ZONE));
        sessionMapper.updateById(session);
        return new ChatResponse(session.getSessionId(), PROTOCOL_VERSION, result.replyText,
                result.blocks, "awaiting_confirm".equals(session.getStatus()), result.trace);
    }

    @Transactional(rollbackFor = Exception.class)
    public ConfirmResponse confirm(long loginUserId, ConfirmRequest request) {
        if (request == null || !StringUtils.hasText(request.getSessionId())) throw new BusinessException("sessionId 不能为空");
        AiTaskLogSession session = ownedSession(loginUserId, request.getSessionId());
        if ("committed".equals(session.getStatus()) && StringUtils.hasText(session.getCommittedTaskIds())) {
            List<Long> ids = Arrays.stream(session.getCommittedTaskIds().split(",")).map(Long::valueOf).toList();
            return done(session.getSessionId(), ids, "任务已登记，请勿重复提交");
        }
        if (!"awaiting_confirm".equals(session.getStatus())) throw new BusinessException("当前没有待确认的记工");
        List<DraftItem> items = request.getItems();
        if (items == null || items.isEmpty()) items = objectMapper.convertValue(readList(session.getDraftJson()), new TypeReference<>() {});
        if (items == null || items.isEmpty()) throw new BusinessException("待确认记工为空");
        if (items.size() > 20) throw new BusinessException("单次最多登记 20 条任务");
        List<Long> ids = new ArrayList<>();
        for (DraftItem item : items) {
            normalizeDraft(loginUserId, item);
            Long assigneeId = item.getAssigneeId() != null ? item.getAssigneeId() : loginUserId;
            PmTask task = new PmTask();
            task.setProjectId(item.getProjectId());
            task.setTitle(item.getTitle().trim());
            task.setContent(withTimeNote(item));
            task.setAssigneeId(assigneeId);
            task.setHolderId(assigneeId);
            LinkedHashSet<Long> participants = new LinkedHashSet<>();
            participants.add(assigneeId);
            participants.add(loginUserId);
            task.setParticipantIds(new ArrayList<>(participants));
            LocalDateTime startAt = parseDateTime(item.getStartAt());
            LocalDateTime endAt = parseDateTime(item.getEndAt());
            task.setStartDate(startAt.toLocalDate());
            task.setDueDate(endAt.toLocalDate());
            // 有明确起止时刻时写入预估时长（小时），供查询与报表使用
            long seconds = Math.max(0L, Duration.between(startAt, endAt).getSeconds());
            task.setEstimatedHours(java.math.BigDecimal.valueOf(seconds)
                    .divide(java.math.BigDecimal.valueOf(3600), 2, java.math.RoundingMode.HALF_UP));
            taskService.createTask(task);
            int status = item.getStatus() == null ? 0 : item.getStatus();
            if (status == 4) status = 2;
            taskService.applyAiConfirmedStatus(task.getId(), status);
            ids.add(task.getId());
        }
        session.setStatus("committed");
        session.setDraftJson(null);
        session.setCommittedTaskIds(ids.stream().map(String::valueOf).collect(Collectors.joining(",")));
        session.setUpdateTime(LocalDateTime.now(ZONE));
        sessionMapper.updateById(session);
        return done(session.getSessionId(), ids, "已登记 " + ids.size() + " 条任务");
    }

    public void cancel(long loginUserId, String sessionId) {
        AiTaskLogSession session = ownedSession(loginUserId, sessionId);
        if ("committed".equals(session.getStatus())) throw new BusinessException("已提交会话不能取消");
        session.setStatus("cancelled"); session.setDraftJson(null); session.setUpdateTime(LocalDateTime.now(ZONE));
        sessionMapper.updateById(session);
    }

    public List<Map<String, String>> tools() {
        return toolDefinitions().stream().map(t -> Map.of("name", (String)t.get("name"),
                "description", (String)t.get("description"))).toList();
    }

    private AgentResult runAgent(long userId, AiTaskLogSession session, List<Map<String, Object>> history) {
        return runAgent(userId, session, history, null);
    }

    private AgentResult runAgent(long userId, AiTaskLogSession session, List<Map<String, Object>> history,
                                 Long preResolvedUserId) {
        if (!StringUtils.hasText(properties.getApiKey())) throw new BusinessException("AI 服务未配置 apiKey");
        List<Map<String, Object>> apiMessages = new ArrayList<>();
        apiMessages.add(Map.of("role", "system", "content", systemPrompt(session)));
        for (Map<String, Object> msg : history) apiMessages.add(Map.of("role", msg.get("role"), "content", msg.get("content")));
        List<Map<String, Object>> trace = new ArrayList<>();
        List<Map<String, Object>> suggested = new ArrayList<>();
        Set<Long> resolvedUserIds = new LinkedHashSet<>();
        if (preResolvedUserId != null) resolvedUserIds.add(preResolvedUserId);
        Long inferredFromText = inferAssigneeFromHistory(history, userId);
        if (inferredFromText != null) resolvedUserIds.add(inferredFromText);
        Map<String, String> requestedTimeRange = latestUserTimeRange(history);
        String reply = "";
        for (int i = 0; i <= properties.getMaxToolCalls(); i++) {
            JsonNode answer = callModel(apiMessages);
            JsonNode message = answer.path("choices").path(0).path("message");
            JsonNode calls = message.path("tool_calls");
            if (!calls.isArray() || calls.isEmpty()) {
                reply = message.path("content").asText("");
                break;
            }
            Map<String, Object> assistantCall = objectMapper.convertValue(message, new TypeReference<>() {});
            apiMessages.add(assistantCall);
            List<JsonNode> orderedCalls = new ArrayList<>();
            calls.forEach(orderedCalls::add);
            // 同一批里先解析人员，再筛项目，避免给他人建任务时仍按「我参与的项目」消歧
            orderedCalls.sort(Comparator.comparingInt(c -> isUserResolveTool(c.path("function").path("name").asText()) ? 0 : 1));
            for (JsonNode call : orderedCalls) {
                String callId = call.path("id").asText();
                String name = call.path("function").path("name").asText();
                Map<String, Object> args;
                try { args = objectMapper.readValue(call.path("function").path("arguments").asText("{}"), new TypeReference<>() {}); }
                catch (Exception e) { args = Map.of(); }
                args = new LinkedHashMap<>(args);
                if (isTaskQuery(name)) {
                    if (!resolvedUserIds.isEmpty()) args.put("userIds", new ArrayList<>(resolvedUserIds));
                    if (!requestedTimeRange.isEmpty()) {
                        args.put("start", requestedTimeRange.get("start"));
                        args.put("end", requestedTimeRange.get("end"));
                    }
                }
                if (isDraftTool(name)) {
                    injectDraftDefaults(args, requestedTimeRange, resolvedUserIds, userId);
                    String userMessage = latestUserText(history);
                    if (StringUtils.hasText(userMessage)) args.put("userMessage", userMessage);
                    if (!requestedTimeRange.isEmpty()) {
                        args.put("resolvedTimeRange", requestedTimeRange);
                    }
                }
                if (isProjectTool(name)) {
                    injectProjectAssignee(args, resolvedUserIds, userId);
                }
                ToolResult tr;
                try { tr = executeTool(name, userId, session, args); }
                catch (Exception e) { tr = new ToolResult(false, e.getMessage(), Map.of(), List.of()); }
                if (isUserResolveTool(name) && tr.ok && tr.data instanceof Map<?, ?> data) {
                    Long resolvedId = number(data.get("id"));
                    if (resolvedId != null) resolvedUserIds.add(resolvedId);
                }
                mergeSuggestedBlocks(suggested, tr.blocks);
                if (properties.isToolTraceEnabled()) trace.add(Map.of("name", name, "ok", tr.ok));
                // 权限拒绝：直接返回固定文案，不再让模型改写或继续试探
                if (tr.data instanceof Map<?, ?> data && Boolean.TRUE.equals(data.get("permissionDenied"))) {
                    String deny = StringUtils.hasText(tr.message) ? tr.message : MSG_DENY_QUERY_OTHERS;
                    List<Map<String, Object>> denyBlocks = tr.blocks.isEmpty()
                            ? List.of(Map.of("type", "markdown", "content", deny))
                            : tr.blocks;
                    return new AgentResult(deny, denyBlocks, trace);
                }
                // 人员消歧：直接出点选气泡，避免模型改成「未找到」纯文本
                if (isUserResolveTool(name) && tr.blocks.stream().anyMatch(b -> "clarify".equals(b.get("type")))) {
                    return new AgentResult("", tr.blocks, trace);
                }
                apiMessages.add(Map.of("role", "tool", "tool_call_id", callId, "content", write(Map.of(
                        "ok", tr.ok, "message", Objects.toString(tr.message, ""), "data", tr.data))));
            }
        }
        normalizeConfirmationBlocks(session, suggested);
        List<Map<String, Object>> blocks = sanitizeModelBlocks(reply, suggested);
        if (blocks.isEmpty()) blocks.add(Map.of("type", "markdown", "content", StringUtils.hasText(reply) ? reply : "暂时无法完成该请求，请换一种说法。"));
        boolean hasConfirmCard = blocks.stream().anyMatch(block -> "confirm_card".equals(block.get("type")));
        boolean hasStructuredQuery = blocks.stream().anyMatch(block -> {
            String type = Objects.toString(block.get("type"), "");
            return Set.of("task_list", "timeline", "stat_row", "bar_chart", "line_chart", "pie_chart").contains(type);
        });
        // 有结构化查询块时丢弃模型 Markdown 表，避免窄抽屉里表格炸版
        String visibleReply = hasConfirmCard ? "" : (hasStructuredQuery ? briefQueryReply(reply) : safeUserText(reply));
        return new AgentResult(visibleReply, blocks, trace);
    }

    /** 结构化查询场景只保留一句短旁白，去掉 Markdown 表格与条目罗列。 */
    private String briefQueryReply(String reply) {
        String text = safeUserText(reply);
        if (!StringUtils.hasText(text)) return "";
        String noTable = text.replaceAll("(?m)^\\s*\\|.*\\|\\s*$", "")
                .replaceAll("(?m)^\\s*:?[-|]{2,}:?\\s*$", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        if (!StringUtils.hasText(noTable)) return "";
        for (String line : noTable.split("\\n")) {
            String t = line.trim();
            if (!StringUtils.hasText(t) || t.startsWith("|") || t.startsWith("-") || t.startsWith("*")) continue;
            if (t.contains("|") && t.split("\\|").length >= 3) continue;
            // 只取第一句概述
            int cut = indexOfSentenceEnd(t);
            return cut > 0 ? t.substring(0, cut + 1).trim() : (t.length() > 80 ? t.substring(0, 80) + "…" : t);
        }
        return "";
    }

    private static int indexOfSentenceEnd(String text) {
        int best = -1;
        for (char c : new char[]{'。', '！', '？', '.', '!', '?'}) {
            int i = text.indexOf(c);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    private boolean isTaskQuery(String name) {
        return Set.of("list_tasks", "summarize_tasks", "tasks_timeline", "tasks_by_project_chart", "tasks_trend_chart").contains(name);
    }

    private boolean isDraftTool(String name) {
        return "draft_log_tasks".equals(name) || "update_draft".equals(name);
    }

    private boolean isProjectTool(String name) {
        return "list_my_projects".equals(name) || "search_projects".equals(name) || "resolve_project".equals(name);
    }

    private boolean isUserResolveTool(String name) {
        return "resolve_user".equals(name) || "search_users".equals(name);
    }

    /** 已解析到他人时，项目工具强制按「可为其建任务」的范围过滤。 */
    private void injectProjectAssignee(Map<String, Object> args, Set<Long> resolvedUserIds, long loginUserId) {
        if (args.get("assigneeId") != null) return;
        Long inferred = inferredOtherAssignee(resolvedUserIds, loginUserId);
        if (inferred != null) args.put("assigneeId", inferred);
    }

    private Long inferredOtherAssignee(Set<Long> resolvedUserIds, long loginUserId) {
        if (resolvedUserIds == null || resolvedUserIds.size() != 1) return null;
        Long only = resolvedUserIds.iterator().next();
        return only != null && only != loginUserId ? only : null;
    }

    /** 从「给杨冲创建任务」类话术推断被指派人，供项目范围过滤使用。 */
    private Long inferAssigneeFromHistory(List<Map<String, Object>> history, long loginId) {
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, Object> message = history.get(i);
            if (!"user".equals(message.get("role"))) continue;
            String text = Objects.toString(message.get("content"), "");
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?:给|帮|为)\\s*([\\u4e00-\\u9fa5A-Za-z0-9_]{1,20})\\s*(?:创建|建|登记|安排)")
                    .matcher(text);
            if (!m.find()) return null;
            String keyword = m.group(1).trim();
            if (keyword.isEmpty() || keyword.matches("我|自己|本人")) return null;
            ToolResult resolved = resolveUser(loginId, keyword);
            if (!resolved.ok || !(resolved.data instanceof Map<?, ?> data)) return null;
            if (Boolean.TRUE.equals(data.get("permissionDenied"))) return null;
            Long id = number(data.get("id"));
            return id != null && !Objects.equals(id, loginId) ? id : null;
        }
        return null;
    }

    /** 草稿缺时间/负责人时，用会话解析结果补齐；多条草稿不套同一全局时段。 */
    @SuppressWarnings("unchecked")
    private void injectDraftDefaults(Map<String, Object> args, Map<String, String> timeRange,
                                     Set<Long> resolvedUserIds, long loginUserId) {
        Object raw = args.get("items");
        if (!(raw instanceof List<?> list) || list.isEmpty()) return;
        Long inferredAssignee = inferredOtherAssignee(resolvedUserIds, loginUserId);
        boolean multiItem = list.size() > 1;
        for (Object row : list) {
            if (!(row instanceof Map<?, ?> map)) continue;
            Map<String, Object> item = (Map<String, Object>) map;
            // 多条记工时禁止把「晚上六点到九点半」整段盖到上午/下午事项上
            if (!multiItem
                    && !StringUtils.hasText(Objects.toString(item.get("startAt"), null))
                    && !StringUtils.hasText(Objects.toString(item.get("endAt"), null))
                    && !timeRange.isEmpty()) {
                item.put("startAt", timeRange.get("start"));
                item.put("endAt", timeRange.get("end"));
            }
            if (item.get("assigneeId") == null && inferredAssignee != null) {
                item.put("assigneeId", inferredAssignee);
            }
        }
    }

    private String latestUserText(List<Map<String, Object>> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, Object> message = history.get(i);
            if (!"user".equals(message.get("role"))) continue;
            return Objects.toString(message.get("content"), "").trim();
        }
        return "";
    }

    private Map<String, String> latestUserTimeRange(List<Map<String, Object>> history) {
        String text = latestUserText(history);
        if (!StringUtils.hasText(text)) return Map.of();
        return timeRangeNormalizer.normalize(text, LocalDateTime.now(ZONE)).orElse(Map.of());
    }

    private void normalizeConfirmationBlocks(AiTaskLogSession session, List<Map<String, Object>> blocks) {
        Map<String, Object> lastConfirmCard = null;
        for (Map<String, Object> block : blocks) if ("confirm_card".equals(block.get("type"))) lastConfirmCard = block;
        blocks.removeIf(block -> "confirm_card".equals(block.get("type")));
        if ("awaiting_confirm".equals(session.getStatus()) && lastConfirmCard != null) blocks.add(lastConfirmCard);
    }

    private JsonNode callModel(List<Map<String, Object>> messages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel()); body.put("messages", messages); body.put("tools", toolDefinitions());
        body.put("tool_choice", "auto"); body.put("temperature", 0.1);
        return RestClient.builder().baseUrl(properties.getBaseUrl()).defaultHeader(HttpHeaders.AUTHORIZATION,
                "Bearer " + properties.getApiKey()).build().post().uri("/chat/completions").body(body).retrieve().body(JsonNode.class);
    }

    private ToolResult executeTool(String name, long userId, AiTaskLogSession session, Map<String, Object> args) {
        Long assigneeId = number(args.get("assigneeId"));
        return switch (name) {
            case "list_my_projects" -> listProjectsForAssign(userId, null, assigneeId);
            case "search_projects", "resolve_project" -> resolveProject(userId, Objects.toString(args.get("keyword"), ""), assigneeId);
            case "search_users", "resolve_user" -> resolveUser(userId, Objects.toString(args.get("keyword"), ""));
            case "get_server_now" -> ok(Map.of("now", LocalDateTime.now(ZONE).format(DT), "timezone", ZONE.getId()));
            case "normalize_time_range" -> normalizeTime(Objects.toString(args.get("expression"), ""));
            case "list_project_members" -> listProjectMembers(userId, number(args.get("projectId")));
            case "get_task" -> getTask(userId, number(args.get("taskId")));
            case "list_task_flows" -> listTaskFlows(userId, number(args.get("taskId")));
            case "work_duration_summary" -> workDuration(userId, number(args.get("taskId")));
            case "list_tasks", "summarize_tasks", "tasks_timeline", "tasks_by_project_chart", "tasks_trend_chart" -> queryTasks(name, userId, args);
            case "draft_log_tasks", "update_draft" -> saveDraft(userId, session, args);
            case "clear_draft" -> { session.setDraftJson(null); session.setStatus("chatting"); yield ok(Map.of()); }
            default -> new ToolResult(false, "未知工具", Map.of(), List.of());
        };
    }

    private ToolResult listProjectsForAssign(long userId, String keyword, Long assigneeId) {
        ToolResult denied = denyIfCannotListAssignProjects(userId, assigneeId);
        if (denied != null) return denied;
        List<Map<String, Object>> rows = projectsFor(userId, keyword, assigneeId);
        // 给他人建任务时，事项关键词常误命中无关项目名；无命中则回退为全部可指派项目
        if (rows.isEmpty() && StringUtils.hasText(keyword) && isAssignToOther(userId, assigneeId)) {
            rows = projectsFor(userId, null, assigneeId);
        }
        if (rows.isEmpty() && isAssignToOther(userId, assigneeId)) {
            return permissionDenied(MSG_DENY_OUTSIDE_OWNED_PROJECTS);
        }
        return ok(rows);
    }

    private ToolResult resolveProject(long userId, String keyword, Long assigneeId) {
        ToolResult listed = listProjectsForAssign(userId, keyword, assigneeId);
        if (!listed.ok || (listed.data instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("permissionDenied")))) {
            return listed;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = listed.data instanceof List<?> list
                ? (List<Map<String, Object>>) list
                : List.of();
        if (rows.size() == 1) return ok(rows.get(0));
        if (rows.isEmpty()) return new ToolResult(false, "未找到可访问的项目", Map.of(), List.of());
        return new ToolResult(true, "存在多个项目", Map.of("candidates", rows), List.of(clarify("project", "请选择项目：", rows)));
    }

    private ToolResult denyIfCannotListAssignProjects(long loginId, Long assigneeId) {
        if (!isAssignToOther(loginId, assigneeId)) return null;
        if (isUnrestricted(loginId)) return null;
        Set<Long> owned = ownedProjectIds(loginId);
        if (owned.isEmpty()) return permissionDenied(MSG_DENY_QUERY_OTHERS);
        if (!sharesOwnedProject(owned, assigneeId)) {
            return permissionDenied(MSG_DENY_OUTSIDE_OWNED_PROJECTS);
        }
        return null;
    }

    private boolean isAssignToOther(long loginId, Long assigneeId) {
        return assigneeId != null && !Objects.equals(loginId, assigneeId);
    }

    /**
     * 本人记工：负责或参与的项目。
     * 给他人建任务：仅「我负责 ∩ 对方参与」；管理员则为对方参与的全部项目。
     */
    private List<Map<String, Object>> projectsFor(long userId, String keyword, Long assigneeId) {
        Set<Long> ids;
        if (isAssignToOther(userId, assigneeId)) {
            ids = new LinkedHashSet<>(projectIdsOfUser(assigneeId));
            if (!isUnrestricted(userId)) {
                ids.retainAll(ownedProjectIds(userId));
            }
        } else {
            ids = new LinkedHashSet<>(ownedProjectIds(userId));
            ids.addAll(projectMemberMapper.selectList(new LambdaQueryWrapper<PmProjectMember>().eq(PmProjectMember::getUserId, userId)
                    .select(PmProjectMember::getProjectId)).stream().map(PmProjectMember::getProjectId).toList());
        }
        if (ids.isEmpty()) return List.of();
        LambdaQueryWrapper<PmProject> q = new LambdaQueryWrapper<PmProject>().in(PmProject::getId, ids).eq(PmProject::getApproveStatus, 1)
                .ne(PmProject::getStatus, 3).select(PmProject::getId, PmProject::getName);
        if (StringUtils.hasText(keyword)) q.like(PmProject::getName, keyword.trim());
        return projectMapper.selectList(q.orderByAsc(PmProject::getName)).stream()
                .map(p -> Map.<String,Object>of("id", p.getId(), "name", p.getName())).toList();
    }

    private Set<Long> projectIdsOfUser(long targetUserId) {
        Set<Long> ids = projectMemberMapper.selectList(new LambdaQueryWrapper<PmProjectMember>()
                        .eq(PmProjectMember::getUserId, targetUserId)
                        .select(PmProjectMember::getProjectId))
                .stream().map(PmProjectMember::getProjectId).collect(Collectors.toCollection(LinkedHashSet::new));
        ids.addAll(projectMapper.selectList(new LambdaQueryWrapper<PmProject>()
                        .eq(PmProject::getOwnerId, targetUserId)
                        .select(PmProject::getId))
                .stream().map(PmProject::getId).toList());
        return ids;
    }

    private ToolResult resolveUser(long loginId, String keyword) {
        boolean unrestricted = isUnrestricted(loginId);
        Set<Long> owned = ownedProjectIds(loginId);
        String rawKw = keyword == null ? "" : keyword.trim();

        List<SysUser> found = List.of();
        if (StringUtils.hasText(rawKw)) {
            found = userMapper.selectList(new LambdaQueryWrapper<SysUser>().eq(SysUser::getStatus, 1)
                    .select(SysUser::getId, SysUser::getUsername, SysUser::getNickname)
                    .and(w -> w.like(SysUser::getNickname, rawKw).or().like(SysUser::getUsername, rawKw))
                    .last("LIMIT 50"));
        } else if (!unrestricted) {
            found = userMapper.selectList(new LambdaQueryWrapper<SysUser>().eq(SysUser::getStatus, 1)
                    .eq(SysUser::getId, loginId)
                    .select(SysUser::getId, SysUser::getUsername, SysUser::getNickname));
        } else {
            found = userMapper.selectList(new LambdaQueryWrapper<SysUser>().eq(SysUser::getStatus, 1)
                    .select(SysUser::getId, SysUser::getUsername, SysUser::getNickname).last("LIMIT 20"));
        }

        List<SysUser> allowed = filterResolvableUsers(loginId, found, unrestricted, owned);
        boolean sawDeniedOther = !found.isEmpty() && allowed.isEmpty()
                && found.stream().anyMatch(u -> !Objects.equals(u.getId(), loginId));
        if (allowed.isEmpty() && sawDeniedOther && StringUtils.hasText(rawKw)
                && found.stream().anyMatch(u -> exactNameHit(u, rawKw))) {
            return permissionDenied(owned.isEmpty() ? MSG_DENY_QUERY_OTHERS : MSG_DENY_OUTSIDE_OWNED_PROJECTS);
        }

        // 精确/模糊都无命中：在可见范围内找相近姓名，铺成点选气泡
        if (allowed.isEmpty() && StringUtils.hasText(rawKw)) {
            List<SysUser> similar = fuzzyMatchUsers(rawKw, visibleUsersForResolve(loginId, unrestricted, owned));
            if (similar.isEmpty()) {
                String tip = "没有找到与「" + rawKw + "」相近的人员，请直接输入全名或账号。";
                return new ToolResult(false, tip, Map.of("needsClarify", true),
                        List.of(Map.of("type", "markdown", "content", tip)));
            }
            List<Map<String, Object>> rows = toUserRows(similar);
            String prompt = "未精确匹配到「" + rawKw + "」，相近人员如下，请点选；都不是可在下方输入全名或账号：";
            return new ToolResult(true, "相近用户候选", Map.of("candidates", rows, "needsClarify", true),
                    List.of(clarify("user", prompt, rows)));
        }
        if (allowed.isEmpty()) return new ToolResult(false, "未找到用户", Map.of(), List.of());

        List<Map<String, Object>> rows = toUserRows(allowed);
        if (rows.size() == 1) return ok(rows.get(0));
        return new ToolResult(true, "存在多个用户", Map.of("candidates", rows, "needsClarify", true),
                List.of(clarify("user", "请选择人员（都不是可在下方输入全名或账号）：", rows)));
    }

    private List<Map<String, Object>> toUserRows(List<SysUser> users) {
        return users.stream().map(u -> Map.<String, Object>of(
                "id", u.getId(),
                "name", StringUtils.hasText(u.getNickname()) ? u.getNickname() : u.getUsername())).toList();
    }

    private List<SysUser> filterResolvableUsers(long loginId, List<SysUser> found,
                                               boolean unrestricted, Set<Long> owned) {
        List<SysUser> allowed = new ArrayList<>();
        for (SysUser user : found) {
            if (Objects.equals(user.getId(), loginId) || unrestricted) {
                allowed.add(user);
            } else if (!owned.isEmpty() && sharesOwnedProject(owned, user.getId())) {
                allowed.add(user);
            }
        }
        return allowed;
    }

    private boolean exactNameHit(SysUser user, String keyword) {
        String kw = keyword.trim();
        String nick = Objects.toString(user.getNickname(), "");
        String name = Objects.toString(user.getUsername(), "");
        return nick.equalsIgnoreCase(kw) || name.equalsIgnoreCase(kw)
                || nick.contains(kw) || name.contains(kw);
    }

    /** 当前登录者可解析到的人员池：本人 + 负责项目内成员；管理员不限。 */
    private List<SysUser> visibleUsersForResolve(long loginId, boolean unrestricted, Set<Long> owned) {
        if (unrestricted) {
            return userMapper.selectList(new LambdaQueryWrapper<SysUser>().eq(SysUser::getStatus, 1)
                    .select(SysUser::getId, SysUser::getUsername, SysUser::getNickname)
                    .last("LIMIT 200"));
        }
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(loginId);
        if (!owned.isEmpty()) {
            ids.addAll(projectMemberMapper.selectList(new LambdaQueryWrapper<PmProjectMember>()
                            .in(PmProjectMember::getProjectId, owned)
                            .select(PmProjectMember::getUserId))
                    .stream().map(PmProjectMember::getUserId).filter(Objects::nonNull).toList());
            ids.addAll(projectMapper.selectList(new LambdaQueryWrapper<PmProject>()
                            .in(PmProject::getId, owned)
                            .select(PmProject::getOwnerId))
                    .stream().map(PmProject::getOwnerId).filter(Objects::nonNull).toList());
        }
        if (ids.isEmpty()) return List.of();
        return userMapper.selectBatchIds(ids).stream()
                .filter(u -> u != null && (u.getStatus() == null || u.getStatus() == 1))
                .toList();
    }

    /** 去掉「姐/哥/总」等称呼后再比，例如「徐姐」→「徐」命中「徐康」。 */
    private List<SysUser> fuzzyMatchUsers(String keyword, List<SysUser> pool) {
        if (!StringUtils.hasText(keyword) || pool == null || pool.isEmpty()) return List.of();
        String raw = keyword.trim();
        String core = normalizePersonKeyword(raw);
        if (!StringUtils.hasText(core)) core = raw;
        String finalCore = core;
        record Scored(SysUser user, int score) {}
        List<Scored> scored = new ArrayList<>();
        for (SysUser user : pool) {
            String nick = Objects.toString(user.getNickname(), "").trim();
            String uname = Objects.toString(user.getUsername(), "").trim();
            int score = 0;
            if (nick.equals(raw) || uname.equalsIgnoreCase(raw)) score = 100;
            else if (nick.equals(finalCore)) score = 95;
            else if (nick.startsWith(finalCore) || finalCore.startsWith(nick)) score = 90;
            else if (nick.contains(finalCore) || uname.toLowerCase(Locale.ROOT).contains(finalCore.toLowerCase(Locale.ROOT))) score = 80;
            else if (!finalCore.isEmpty() && shareCharRatio(nick, finalCore) >= 0.5) score = 60;
            else if (nick.length() >= 2 && raw.length() >= 2 && nick.charAt(0) == raw.charAt(0)) score = 40;
            if (score > 0) scored.add(new Scored(user, score));
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparing(s -> StringUtils.hasText(s.user().getNickname()) ? s.user().getNickname() : s.user().getUsername()));
        LinkedHashMap<Long, SysUser> unique = new LinkedHashMap<>();
        for (Scored s : scored) {
            if (s.user().getId() != null) unique.putIfAbsent(s.user().getId(), s.user());
            if (unique.size() >= 8) break;
        }
        return new ArrayList<>(unique.values());
    }

    private String normalizePersonKeyword(String keyword) {
        String s = keyword == null ? "" : keyword.trim();
        if (s.isEmpty()) return s;
        s = s.replaceAll("(?i)(小姐|女士|先生|老师|同学|师傅|总|哥|姐|兄|弟|妹|叔|伯|阿姨|大叔|大姐|大哥|小妹|小弟)$", "");
        if (s.length() >= 2 && s.startsWith("小")) s = s.substring(1);
        return s.trim();
    }

    private double shareCharRatio(String name, String core) {
        if (!StringUtils.hasText(name) || !StringUtils.hasText(core)) return 0;
        int hit = 0;
        for (int i = 0; i < core.length(); i++) {
            if (name.indexOf(core.charAt(i)) >= 0) hit++;
        }
        return (double) hit / core.length();
    }

    private ToolResult normalizeTime(String expression) {
        return timeRangeNormalizer.normalize(expression, LocalDateTime.now(ZONE))
                .<ToolResult>map(this::ok)
                .orElseGet(() -> new ToolResult(false, "时间是早上、上午还是具体几点到几点？例如：09:00 到 11:00。", Map.of(), List.of()));
    }

    private ToolResult queryTasks(String tool, long loginId, Map<String, Object> args) {
        List<Long> userIds = longList(args.get("userIds"));
        if (userIds.isEmpty()) userIds = List.of(loginId);
        List<Long> requestedProjectIds = longList(args.get("projectIds"));
        boolean unrestricted = isUnrestricted(loginId);
        Set<Long> owned = ownedProjectIds(loginId);
        boolean includesSelf = userIds.contains(loginId);
        List<Long> otherUserIds = userIds.stream().filter(id -> id != loginId).toList();

        // 1) 普通员工：禁止查他人，直接固定文案（不是“查不到”）
        // 2) 项目负责人：仅限负责项目 ∩ 对方参与项目，并按项目分别汇报
        // 3) 管理员/超管（或 task:ai:query-others）：不限制
        List<Long> otherProjectIds = requestedProjectIds;
        boolean ownerScopedOthers = false;
        if (!otherUserIds.isEmpty() && !unrestricted) {
            if (owned.isEmpty()) {
                return permissionDenied(MSG_DENY_QUERY_OTHERS);
            }
            otherProjectIds = authorizedProjects(loginId, otherUserIds, requestedProjectIds, owned);
            if (otherProjectIds.isEmpty()) {
                return permissionDenied(MSG_DENY_OUTSIDE_OWNED_PROJECTS);
            }
            ownerScopedOthers = true;
        }

        final List<Long> queryUserIds = userIds;
        final List<Long> queryProjectIds = requestedProjectIds;
        LocalDateTime start = parseDateTime(Objects.toString(args.get("start"), LocalDate.now(ZONE).atStartOfDay().format(DT)));
        LocalDateTime end = parseDateTime(Objects.toString(args.get("end"), LocalDate.now(ZONE).atTime(23, 59, 59).format(DT)));
        LambdaQueryWrapper<PmTask> q = new LambdaQueryWrapper<>();
        if (otherUserIds.isEmpty() || unrestricted) {
            q.in(!queryProjectIds.isEmpty(), PmTask::getProjectId, queryProjectIds)
                    .and(w -> w.in(PmTask::getAssigneeId, queryUserIds).or().in(PmTask::getHolderId, queryUserIds));
        } else if (includesSelf) {
            // 混合查询“本人 + 他人”时，本人任务不能被他人的共同项目权限范围误过滤。
            List<Long> finalOtherProjectIds = otherProjectIds;
            q.and(scope -> scope
                    .and(self -> self.in(!queryProjectIds.isEmpty(), PmTask::getProjectId, queryProjectIds)
                            .and(person -> person.eq(PmTask::getAssigneeId, loginId)
                                    .or().eq(PmTask::getHolderId, loginId)))
                    .or(other -> other.in(PmTask::getProjectId, finalOtherProjectIds)
                            .and(person -> person.in(PmTask::getAssigneeId, otherUserIds)
                                    .or().in(PmTask::getHolderId, otherUserIds))));
        } else {
            q.in(PmTask::getProjectId, otherProjectIds)
                    .and(w -> w.in(PmTask::getAssigneeId, otherUserIds).or().in(PmTask::getHolderId, otherUserIds));
        }
        q.and(w -> w.between(PmTask::getStartDate, start.toLocalDate(), end.toLocalDate())
                        .or().between(PmTask::getDueDate, start.toLocalDate(), end.toLocalDate())
                        .or().between(PmTask::getCompletedAt, start, end)
                        .or().between(PmTask::getCreateTime, start, end))
                .orderByAsc(PmTask::getStartDate).last("LIMIT 200");
        if (args.get("status") instanceof Number n) q.eq(PmTask::getStatus, n.intValue());
        List<PmTask> tasks = taskMapper.selectList(q);
        Map<Long, String> projectNames = projectNames(tasks);
        Map<Long, String> userNames = enrichUserNames(queryUserIds, userNames(tasks));
        List<Map<String, Object>> items = tasks.stream().map(t -> taskItem(t, projectNames, userNames)).toList();
        List<Map<String, Object>> blocks = new ArrayList<>();
        String message;
        boolean multiPerson = queryUserIds.size() >= 2;
        boolean compareMode = multiPerson && Set.of("list_tasks", "summarize_tasks", "tasks_timeline").contains(tool);
        if (compareMode) {
            // 多人：侧重按人对比汇总，不堆完整明细
            message = buildMultiPersonCompareBlocks(tool, tasks, queryUserIds, userNames, projectNames, blocks);
        } else if (ownerScopedOthers) {
            message = buildOwnerScopedBlocks(tool, tasks, items, otherProjectIds, projectNames, blocks, includesSelf);
            if (("list_tasks".equals(tool) || "summarize_tasks".equals(tool)) && !tasks.isEmpty()) {
                appendQueryVisualBlocks(blocks, tasks, projectNames, items);
            }
        } else {
            if ("list_tasks".equals(tool)) {
                blocks.add(stats(tasks));
                blocks.add(Map.of("type", "task_list", "title", "任务列表", "items", items));
                if (!items.isEmpty()) blocks.add(timeline(items));
                appendQueryVisualBlocks(blocks, tasks, projectNames, items);
            } else if ("summarize_tasks".equals(tool)) {
                blocks.add(stats(tasks));
                if (!items.isEmpty()) blocks.add(Map.of("type", "task_list", "title", "任务明细", "items", items));
                appendQueryVisualBlocks(blocks, tasks, projectNames, items);
            } else if ("tasks_timeline".equals(tool)) {
                blocks.add(timeline(items));
                appendQueryVisualBlocks(blocks, tasks, projectNames, items);
            } else if ("tasks_by_project_chart".equals(tool)) {
                appendQueryVisualBlocks(blocks, tasks, projectNames, items);
            } else if ("tasks_trend_chart".equals(tool)) {
                blocks.add(trendChart(tasks, start.toLocalDate(), end.toLocalDate()));
            }
            message = "按计划日/完成日/创建日落在所选时段统计；详情用结构化列表与图表展示，勿用 Markdown 表格。"
                    + " list_tasks/summarize_tasks 已含柱图与饼图，无需再调 tasks_by_project_chart。";
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", tasks.size());
        data.put("items", items);
        if (ownerScopedOthers) {
            data.put("scope", "owned_projects");
            data.put("projectIds", otherProjectIds);
            data.put("projectNames", otherProjectIds.stream()
                    .map(id -> projectNames.getOrDefault(id, loadProjectName(id)))
                    .toList());
        }
        return new ToolResult(true, message, data, blocks);
    }

    /**
     * 多人查询：按人拆分汇总 + 对比柱图，默认不展开完整任务明细。
     */
    private String buildMultiPersonCompareBlocks(String tool, List<PmTask> tasks, List<Long> userIds,
                                                 Map<Long, String> userNames, Map<Long, String> projectNames,
                                                 List<Map<String, Object>> blocks) {
        String names = userIds.stream()
                .map(id -> "「" + userNames.getOrDefault(id, "用户#" + id) + "」")
                .collect(Collectors.joining("、"));
        blocks.add(Map.of("type", "markdown", "content",
                "以下为 " + userIds.size() + " 人对比汇总（" + names + "），侧重对比而非明细。"));
        blocks.add(personTaskCountChart(tasks, userIds, userNames));
        Map<String, Object> hoursChart = personHoursChart(tasks, userIds, userNames);
        if (hoursChart != null) blocks.add(hoursChart);

        for (Long uid : userIds) {
            String name = userNames.getOrDefault(uid, "用户#" + uid);
            List<PmTask> personTasks = tasks.stream()
                    .filter(t -> Objects.equals(t.getAssigneeId(), uid) || Objects.equals(t.getHolderId(), uid))
                    .toList();
            blocks.add(Map.of("type", "markdown", "content", "**" + name + "**"));
            blocks.add(stats(personTasks, name));
            if (personTasks.isEmpty()) {
                blocks.add(Map.of("type", "markdown", "content", "该时段暂无任务。"));
                continue;
            }
            // 每人最多 3 条要点标题，避免变成详情墙
            String highlights = personTasks.stream()
                    .limit(3)
                    .map(t -> {
                        String title = Objects.toString(t.getTitle(), "未命名任务");
                        String project = Objects.toString(projectNames.get(t.getProjectId()), "");
                        return StringUtils.hasText(project) ? "- " + title + "（" + project + "）" : "- " + title;
                    })
                    .collect(Collectors.joining("\n"));
            if (personTasks.size() > 3) {
                highlights += "\n- …另有 " + (personTasks.size() - 3) + " 条未展开";
            }
            blocks.add(Map.of("type", "markdown", "content", "要点：\n" + highlights));
        }

        if (!tasks.isEmpty()) blocks.add(statusPieChart(tasks));
        // timeline 工具仍可给极简对比旁白，但不附全量时序
        if ("tasks_timeline".equals(tool) && !tasks.isEmpty()) {
            blocks.add(Map.of("type", "markdown", "content", "多人对比已按人汇总；如需某人完整时序，请单独查询该人员。"));
        }
        return "多人对比汇总（按人拆分统计与要点，已附对比图）";
    }

    private Map<Long, String> enrichUserNames(List<Long> userIds, Map<Long, String> existing) {
        Map<Long, String> names = new LinkedHashMap<>(existing == null ? Map.of() : existing);
        List<Long> missing = userIds.stream().filter(id -> id != null && !names.containsKey(id)).toList();
        if (missing.isEmpty()) return names;
        for (SysUser u : userMapper.selectBatchIds(missing)) {
            if (u == null || u.getId() == null) continue;
            names.put(u.getId(), StringUtils.hasText(u.getNickname()) ? u.getNickname() : u.getUsername());
        }
        return names;
    }

    private Map<String, Object> personTaskCountChart(List<PmTask> tasks, List<Long> userIds, Map<Long, String> userNames) {
        List<String> categories = new ArrayList<>();
        List<Number> values = new ArrayList<>();
        for (Long uid : userIds) {
            categories.add(userNames.getOrDefault(uid, "用户#" + uid));
            long count = tasks.stream()
                    .filter(t -> Objects.equals(t.getAssigneeId(), uid) || Objects.equals(t.getHolderId(), uid))
                    .count();
            values.add(count);
        }
        return Map.of("type", "bar_chart", "title", "按人员任务数对比",
                "categories", categories,
                "series", List.of(Map.of("name", "任务数", "values", values)));
    }

    private Map<String, Object> personHoursChart(List<PmTask> tasks, List<Long> userIds, Map<Long, String> userNames) {
        List<String> categories = new ArrayList<>();
        List<Number> values = new ArrayList<>();
        boolean any = false;
        for (Long uid : userIds) {
            categories.add(userNames.getOrDefault(uid, "用户#" + uid));
            double hours = tasks.stream()
                    .filter(t -> Objects.equals(t.getAssigneeId(), uid) || Objects.equals(t.getHolderId(), uid))
                    .mapToDouble(this::taskHours)
                    .sum();
            if (hours > 0) any = true;
            values.add(Math.round(hours * 10) / 10.0);
        }
        if (!any) return null;
        return Map.of("type", "bar_chart", "title", "按人员工时对比(小时)",
                "categories", categories,
                "series", List.of(Map.of("name", "工时", "values", values)));
    }

    /**
     * 项目负责人查他人：按负责项目分别组装展示块。
     * 若同时包含本人任务，本人任务也会按项目单独列出，避免被负责项目范围裁掉。
     */
    private String buildOwnerScopedBlocks(String tool, List<PmTask> tasks, List<Map<String, Object>> items,
                                          List<Long> ownedScopeProjectIds, Map<Long, String> projectNames,
                                          List<Map<String, Object>> blocks, boolean includesSelf) {
        Map<Long, List<PmTask>> byProject = tasks.stream()
                .filter(t -> t.getProjectId() != null)
                .collect(Collectors.groupingBy(PmTask::getProjectId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, List<Map<String, Object>>> itemsByProject = items.stream()
                .filter(i -> number(i.get("projectId")) != null)
                .collect(Collectors.groupingBy(i -> number(i.get("projectId")), LinkedHashMap::new, Collectors.toList()));
        Set<Long> ownedScope = new LinkedHashSet<>(ownedScopeProjectIds);
        // 展示顺序：先负责项目范围内有数据的，再补上本人任务所在的其他项目
        LinkedHashSet<Long> projectsWithData = new LinkedHashSet<>();
        for (Long id : ownedScopeProjectIds) {
            if (!byProject.getOrDefault(id, List.of()).isEmpty()) projectsWithData.add(id);
        }
        if (includesSelf) {
            for (Long id : byProject.keySet()) {
                if (!byProject.getOrDefault(id, List.of()).isEmpty()) projectsWithData.add(id);
            }
        }
        if (projectsWithData.isEmpty()) {
            String empty = "在您负责且对方参与的项目范围内，未找到符合条件的任务。";
            blocks.add(Map.of("type", "markdown", "content", empty));
            return empty;
        }
        List<String> ownedNames = projectsWithData.stream().filter(ownedScope::contains)
                .map(id -> projectNames.getOrDefault(id, loadProjectName(id))).toList();
        String scopeText;
        if (ownedNames.isEmpty()) {
            scopeText = "以下为您本人的任务（未包含可查看的他人项目数据）。";
        } else if (ownedNames.size() == 1) {
            scopeText = "以下他人相关数据均来自您负责的项目「" + ownedNames.get(0) + "」。"
                    + (includesSelf ? "本人任务按项目另行列出。" : "");
        } else {
            scopeText = "以下他人相关数据按您负责的项目分别汇报："
                    + String.join("、", ownedNames.stream().map(n -> "「" + n + "」").toList()) + "。"
                    + (includesSelf ? "本人任务按项目另行列出。" : "");
        }
        blocks.add(Map.of("type", "markdown", "content", scopeText));
        for (Long projectId : projectsWithData) {
            String projectName = projectNames.getOrDefault(projectId, loadProjectName(projectId));
            List<PmTask> projectTasks = byProject.getOrDefault(projectId, List.of());
            List<Map<String, Object>> projectItems = itemsByProject.getOrDefault(projectId, List.of());
            boolean owned = ownedScope.contains(projectId);
            String title = owned
                    ? "项目「" + projectName + "」内的数据"
                    : "我的任务（项目「" + projectName + "」）";
            if ("list_tasks".equals(tool)) {
                blocks.add(Map.of("type", "task_list", "title", title, "items", projectItems));
            } else if ("tasks_timeline".equals(tool)) {
                Map<String, Object> tl = new LinkedHashMap<>(timeline(projectItems));
                tl.put("title", title);
                blocks.add(tl);
            } else if ("summarize_tasks".equals(tool)) {
                blocks.add(Map.of("type", "markdown", "content", "**" + title + "**"));
                blocks.add(stats(projectTasks));
            } else if ("tasks_by_project_chart".equals(tool)) {
                blocks.add(Map.of("type", "markdown", "content", "**" + title + "**（共 " + projectTasks.size() + " 条）"));
                blocks.add(projectChart(projectTasks, Map.of(projectId, projectName)));
            } else if ("tasks_trend_chart".equals(tool)) {
                blocks.add(Map.of("type", "markdown", "content", "**" + title + "**（共 " + projectTasks.size() + " 条）"));
            } else {
                blocks.add(Map.of("type", "task_list", "title", title, "items", projectItems));
            }
        }
        if ("tasks_trend_chart".equals(tool) && !tasks.isEmpty()) {
            LocalDate start = tasks.stream().map(PmTask::getStartDate).filter(Objects::nonNull).min(LocalDate::compareTo).orElse(LocalDate.now(ZONE));
            LocalDate end = tasks.stream().map(PmTask::getDueDate).filter(Objects::nonNull).max(LocalDate::compareTo).orElse(LocalDate.now(ZONE));
            blocks.add(trendChart(tasks, start, end));
        }
        return scopeText + "（按计划日/完成日/创建日落在所选时段统计）";
    }

    private boolean isUnrestricted(long loginId) {
        return dataScopeService.isGlobalAdmin(loginId)
                || cn.dev33.satoken.stp.StpUtil.hasPermission("task:ai:query-others");
    }

    private Set<Long> ownedProjectIds(long loginId) {
        return projectMapper.selectList(new LambdaQueryWrapper<PmProject>()
                        .eq(PmProject::getOwnerId, loginId)
                        .select(PmProject::getId))
                .stream().map(PmProject::getId).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean sharesOwnedProject(Set<Long> owned, long targetUserId) {
        if (owned.isEmpty()) return false;
        long asMember = projectMemberMapper.selectCount(new LambdaQueryWrapper<PmProjectMember>()
                .eq(PmProjectMember::getUserId, targetUserId)
                .in(PmProjectMember::getProjectId, owned));
        if (asMember > 0) return true;
        return projectMapper.selectCount(new LambdaQueryWrapper<PmProject>()
                .eq(PmProject::getOwnerId, targetUserId)
                .in(PmProject::getId, owned)) > 0;
    }

    private ToolResult permissionDenied(String message) {
        return new ToolResult(false, message, Map.of("permissionDenied", true),
                List.of(Map.of("type", "markdown", "content", message)));
    }

    private List<Long> authorizedProjects(long loginId, List<Long> targetUsers, List<Long> requested, Set<Long> owned) {
        Set<Long> scope = new LinkedHashSet<>(owned);
        Set<Long> targetProjects = projectMemberMapper.selectList(new LambdaQueryWrapper<PmProjectMember>()
                        .in(PmProjectMember::getUserId, targetUsers)
                        .select(PmProjectMember::getProjectId))
                .stream().map(PmProjectMember::getProjectId).collect(Collectors.toSet());
        targetProjects.addAll(projectMapper.selectList(new LambdaQueryWrapper<PmProject>()
                        .in(PmProject::getOwnerId, targetUsers)
                        .select(PmProject::getId))
                .stream().map(PmProject::getId).toList());
        scope.retainAll(targetProjects);
        if (!requested.isEmpty()) scope.retainAll(requested);
        return new ArrayList<>(scope);
    }

    private String loadProjectName(Long projectId) {
        if (projectId == null) return "未命名项目";
        PmProject p = projectMapper.selectById(projectId);
        return p != null && StringUtils.hasText(p.getName()) ? p.getName() : ("项目#" + projectId);
    }

    /**
     * 多时段一天记工：按「上午/下午/晚上」分段匹配事项，禁止把唯一钟点（如晚上 18:00~21:30）盖到所有条目。
     * 单条记工仍可用用户话里的唯一时段强制纠偏。
     */
    private void applyDraftItemTimes(List<DraftItem> items, Map<String, Object> args) {
        LocalDateTime now = LocalDateTime.now(ZONE);
        String userMessage = Objects.toString(args.get("userMessage"), "");
        for (DraftItem item : items) {
            if (!StringUtils.hasText(item.getProjectName()) && item.getProjectId() != null) {
                item.setProjectName(loadProjectName(item.getProjectId()));
            }
        }
        List<TaskAiTimeRangeNormalizer.DaySegment> segments = timeRangeNormalizer.parseDaySegments(userMessage, now);
        if (segments.size() >= 2 && items.size() >= 2) {
            assignTimesByDaySegments(items, segments);
            for (DraftItem item : items) {
                if (!StringUtils.hasText(item.getStartAt()) || !StringUtils.hasText(item.getEndAt())) {
                    Map<String, String> fallback = timeRangeNormalizer.defaultWorkToNow(now);
                    item.setStartAt(fallback.get("start"));
                    item.setEndAt(fallback.get("end"));
                }
            }
            return;
        }
        Map<String, String> forcedTime = Map.of();
        if (args.get("resolvedTimeRange") instanceof Map<?, ?> timeMap) {
            Object start = timeMap.get("start");
            Object end = timeMap.get("end");
            if (start != null && end != null) {
                forcedTime = Map.of("start", start.toString(), "end", end.toString());
            }
        }
        // 仅单条草稿时，用用户话中的唯一时段覆盖模型乱填
        if (!forcedTime.isEmpty() && items.size() == 1) {
            items.get(0).setStartAt(forcedTime.get("start"));
            items.get(0).setEndAt(forcedTime.get("end"));
            return;
        }
        if (segments.size() == 1) {
            TaskAiTimeRangeNormalizer.DaySegment only = segments.get(0);
            for (DraftItem item : items) {
                if (!StringUtils.hasText(item.getStartAt()) || !StringUtils.hasText(item.getEndAt()) || items.size() == 1) {
                    item.setStartAt(only.start());
                    item.setEndAt(only.end());
                }
            }
            return;
        }
        for (DraftItem item : items) {
            if (!StringUtils.hasText(item.getStartAt()) || !StringUtils.hasText(item.getEndAt())) {
                Map<String, String> fallback = timeRangeNormalizer.defaultWorkToNow(now);
                item.setStartAt(fallback.get("start"));
                item.setEndAt(fallback.get("end"));
            }
        }
    }

    private void assignTimesByDaySegments(List<DraftItem> items, List<TaskAiTimeRangeNormalizer.DaySegment> segments) {
        int fallbackCursor = 0;
        for (DraftItem item : items) {
            int bestIdx = -1;
            int bestScore = 0;
            String needle = (Objects.toString(item.getProjectName(), "") + " "
                    + Objects.toString(item.getTitle(), "") + " "
                    + Objects.toString(item.getContent(), "")).toLowerCase(Locale.ROOT);
            for (int i = 0; i < segments.size(); i++) {
                int score = segmentMatchScore(segments.get(i).body(), needle);
                if (score > bestScore) {
                    bestScore = score;
                    bestIdx = i;
                }
            }
            // 同一上午可对应多条事项，允许共享同一时段；无关键词命中时再按顺序回退
            if (bestIdx < 0 || bestScore <= 0) {
                bestIdx = Math.min(fallbackCursor, segments.size() - 1);
                fallbackCursor++;
            }
            TaskAiTimeRangeNormalizer.DaySegment seg = segments.get(bestIdx);
            item.setStartAt(seg.start());
            item.setEndAt(seg.end());
        }
    }

    private int segmentMatchScore(String body, String needle) {
        if (!StringUtils.hasText(body) || !StringUtils.hasText(needle)) return 0;
        String hay = body.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String token : needle.split("[\\s,，、；;]+")) {
            String t = token.trim();
            if (t.length() < 2) continue;
            if (hay.contains(t)) score += Math.min(t.length(), 8);
            // 项目名较长时，用前缀/关键词互含
            if (t.length() >= 2) {
                for (int len = Math.min(6, t.length()); len >= 2; len--) {
                    String part = t.substring(0, len);
                    if (hay.contains(part)) { score += len; break; }
                }
            }
        }
        return score;
    }

    private ToolResult saveDraft(long userId, AiTaskLogSession session, Map<String, Object> args) {
        Object raw = args.get("items");
        if (!(raw instanceof List<?> list) || list.isEmpty()) return new ToolResult(false, "草稿不能为空", Map.of(), List.of());
        List<DraftItem> items = objectMapper.convertValue(list, new TypeReference<>() {});
        items = mergeDraftItems(session, items);
        if (items.size() > 20) return new ToolResult(false, "单次最多登记 20 条任务", Map.of(), List.of());
        applyDraftItemTimes(items, args);
        for (DraftItem item : items) {
            if (item.getStatus() == null) item.setStatus(inferStatus(item));
            try {
                normalizeDraft(userId, item);
            } catch (BusinessException e) {
                String msg = Objects.toString(e.getMessage(), "草稿校验失败");
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("permissionDenied", msg.contains("权限"));
                return new ToolResult(false, msg, data, List.of(Map.of("type", "markdown", "content", msg)));
            }
        }
        session.setDraftJson(write(items)); session.setStatus("awaiting_confirm");
        List<Map<String,Object>> normalized = objectMapper.convertValue(items, MAP_LIST);
        return new ToolResult(true, "草稿已保存，等待用户确认", Map.of("count",items.size()),
                List.of(Map.of("type","confirm_card","items",normalized)));
    }

    private List<Map<String,Object>> sanitizeModelBlocks(String reply, List<Map<String,Object>> suggested) {
        boolean hasConfirmCard = suggested.stream().anyMatch(b -> "confirm_card".equals(b.get("type")));
        if (hasConfirmCard) {
            List<Map<String,Object>> result = new ArrayList<>();
            result.add(Map.of("type", "markdown", "content", "请确认以下登记信息："));
            result.addAll(suggested);
            return result;
        }
        boolean hasStructuredQuery = suggested.stream().anyMatch(b -> {
            String type = Objects.toString(b.get("type"), "");
            return Set.of("task_list", "timeline", "stat_row", "bar_chart", "line_chart", "pie_chart").contains(type);
        });
        List<Map<String,Object>> result = new ArrayList<>();
        if (StringUtils.hasText(reply) && !hasStructuredQuery) {
            try {
                String json = reply.trim(); int a = json.indexOf('{'), b = json.lastIndexOf('}');
                if (a >= 0 && b > a) {
                    JsonNode root = objectMapper.readTree(json.substring(a,b+1));
                    if (root.has("blocks")) for (JsonNode n : root.get("blocks")) {
                        String type = n.path("type").asText();
                        if ("markdown".equals(type)) {
                            String content = safeUserText(n.path("content").asText(""));
                            if (StringUtils.hasText(content) && !looksLikeMarkdownTable(content)) {
                                result.add(Map.of("type", "markdown", "content", content));
                            }
                        } else if ("clarify".equals(type)) result.add(objectMapper.convertValue(n, new TypeReference<>() {}));
                    }
                }
            } catch (Exception ignored) {}
        }
        result.addAll(suggested);
        if (result.isEmpty() && StringUtils.hasText(reply)) {
            String text = briefQueryReply(reply);
            if (!StringUtils.hasText(text)) text = safeUserText(reply);
            if (StringUtils.hasText(text) && !looksLikeMarkdownTable(text)) {
                result.add(Map.of("type", "markdown", "content", text));
            }
        }
        return result;
    }

    private boolean looksLikeMarkdownTable(String text) {
        if (!StringUtils.hasText(text)) return false;
        long pipes = text.chars().filter(c -> c == '|').count();
        return pipes >= 4 || text.contains("|---") || text.contains("| ---");
    }

    private String safeUserText(String text) {
        if (!StringUtils.hasText(text)) return "";
        String value = text.trim();
        if (value.contains("工具报错") || value.contains("工具失败") || value.contains("错误详情")
                || value.contains("不会直接入库") || value.contains("草稿工具") || value.contains("当前时间")) {
            return "暂时无法生成确认信息，请补充信息或稍后重试。";
        }
        return value;
    }

    private List<Map<String,Object>> toolDefinitions() {
        return List.of(tool("list_my_projects","列出可记工/建任务的项目；给他人建任务时传 assigneeId，仅返回我负责且对方参与的项目",
                        Map.of("assigneeId", Map.of("type", "integer"))),
                tool("search_projects","搜索项目；给他人建任务时必须传 assigneeId，范围=我负责∩对方参与",
                        Map.of("keyword", Map.of("type", "string"), "assigneeId", Map.of("type", "integer"))),
                tool("resolve_project","解析项目名称；多命中时返回候选；给他人建任务时传 assigneeId，勿用事项关键词乱搜",
                        Map.of("keyword", Map.of("type", "string"), "assigneeId", Map.of("type", "integer"))),
                tool("search_users","按姓名/账号搜索用户；无精确命中时返回相近候选供点选；普通员工查他人会权限拒绝", props("keyword","string")),
                tool("resolve_user","解析用户姓名；支持「徐姐」等称呼模糊匹配，多命中/相近时返回候选点选；普通员工查他人直接权限拒绝", props("keyword","string")),
                tool("get_server_now","获取服务器当前时间", Map.of()),
                tool("normalize_time_range","将中文时间表达归一化", props("expression","string")),
                tool("list_project_members","列出项目负责人和成员", Map.of("projectId",Map.of("type","integer"))),
                tool("get_task","读取单个任务详情", Map.of("taskId",Map.of("type","integer"))),
                tool("list_task_flows","读取任务流转时序", Map.of("taskId",Map.of("type","integer"))),
                taskQueryTool("list_tasks","查询真实任务。单人：统计+列表+图表；多人 userIds：按人对比汇总（不堆详情）"),
                taskQueryTool("summarize_tasks","汇总真实任务。单人：统计+要点+图表；多人 userIds：按人对比汇总与对比柱图（推荐）"),
                taskQueryTool("tasks_timeline","生成任务时间线；多人时改为对比汇总提示"),
                taskQueryTool("tasks_by_project_chart","仅要图表时使用；若已调 list/summarize 则不必再调"),
                taskQueryTool("tasks_trend_chart","生成任务趋势折线图"),
                tool("work_duration_summary","计算单任务有效工时", Map.of("taskId",Map.of("type","integer"))),
                tool("draft_log_tasks","保存记工/建任务草稿（items 可含 assigneeId 为他人创建；绝不直接入库）",
                        Map.of("items", Map.of(
                                "type", "array",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "projectId", Map.of("type", "integer"),
                                                "title", Map.of("type", "string"),
                                                "content", Map.of("type", "string"),
                                                "startAt", Map.of("type", "string"),
                                                "endAt", Map.of("type", "string"),
                                                "status", Map.of("type", "integer"),
                                                "assigneeId", Map.of("type", "integer")))))),
                tool("update_draft","更新记工草稿", Map.of("items", Map.of("type","array","items", Map.of("type","object")))),
                tool("clear_draft","清空记工草稿", Map.of()));
    }
    private Map<String,Object> taskQueryTool(String name,String description) { return tool(name,description,Map.of(
            "userIds",Map.of("type","array","items",Map.of("type","integer")), "projectIds",Map.of("type","array","items",Map.of("type","integer")),
            "start",Map.of("type","string"),"end",Map.of("type","string"),"status",Map.of("type","integer"))); }
    private Map<String,Object> tool(String name,String desc,Map<String,Object> properties) { return Map.of("type","function","function",Map.of(
            "name",name,"description",desc,"parameters",Map.of("type","object","properties",properties))); }
    private Map<String,Object> props(String key,String type) { return Map.of(key,Map.of("type",type)); }
    private String systemPrompt(AiTaskLogSession session) { return """
            你是 BGMN 任务助手。所有项目、用户、任务数字必须通过工具获取，禁止编造。记工/建任务必须先解析时间和项目，再调用 draft_log_tasks，绝不能直接写库。
            查询他人或为他人建任务，必须先 resolve_user。工具返回的 task_list / timeline / stat_row / bar_chart / pie_chart / line_chart 是唯一可信展示；禁止用 Markdown 表格罗列任务（窄屏会炸版）。旁白最多一两句，图表与列表交给工具块。
            查询汇总时优先只调 summarize_tasks 或 list_tasks（它们已附带柱图/饼图），不要再额外调用 tasks_by_project_chart，以免图表重复。
            多人查询（含「分别」「对比」「和/与/跟」多人）：先 resolve 每位人员，再一次 summarize_tasks 传入全部 userIds。工具会按人拆分汇总并给对比图；你只做一句对比旁白，禁止再罗列每人完整任务明细，禁止把多人结果混成一张大表。用户明确要某人详情时，再单独 list_tasks 且只传该人 userId。
            权限（必须遵守，禁止绕过或改写拒绝文案）：
            1) 普通员工只能查自己；一旦查询他人姓名/任务，工具会返回固定拒绝文案，你必须原样转述给用户，不要改成“未查到”。
            2) 普通员工若是某项目负责人，可查该负责项目内成员；多人对比时保留各人姓名与负责项目范围，不要合并成全公司视角。
            3) 管理员/超管可查全部人员，不做限制。
            4) 为他人创建任务：仅项目负责人可在自己负责的项目内，为项目成员创建；须先 resolve_user，再 list_my_projects/resolve_project（传 assigneeId），候选仅为「我负责且对方参与」的项目；禁止用事项里的词（如 AI）去搜无关项目；draft items 必须写 assigneeId。普通员工不能给别人建任务。
            对用户只给结论，不展示思考、试错、内部规则、草稿机制、工具名、工具错误或异常原文。工具失败时只简短追问缺失信息，或提示稍后重试。
            参数齐全后必须调用 draft_log_tasks；最终确认只能使用工具生成的 confirm_card，禁止用 Markdown 表格或列表代替。缺事项标题时只简短追问，不生成半空确认卡。
            必须结合全部会话历史和下方当前草稿按槽位累加。短回复（项目名、时段、事项、状态、负责人）只更新对应槽，其他已有槽不得丢失；澄清 optionId 选中后也继续合并。
            工作日时间约定（必须遵守）：上班一般 09:30；午休 12:00~13:30；下班一般 18:00。
            时段默认：早上/早晨/一早/上午 = 09:30~12:00；中午 = 12:00~13:30；下午 = 13:30~18:00；晚上/傍晚 = 18:00~21:00（段内有钟点则用钟点）。
            具体钟点优先：「下午两点到六点」= 14:00~18:00；「晚上六点到九点半」= 18:00~21:30。不要把具体钟点扩成整段上午/下午。
            一天多事项（强制）：用户按上午/下午/晚上分别描述时，必须拆成多条 draft item，且每条使用对应时段，禁止把晚上的钟点套到上午/下午事项。
            示例：「上午做了云控联调和爱康官网 bug；下午开发 ERP；晚上六点到九点半优化工单系统」→
            云控与爱康官网均 09:30~12:00；ERP 13:30~18:00；工单系统 18:00~21:30。同一上午多项目可共用 09:30~12:00。
            draft items 必须各自填写正确的 startAt/endAt（yyyy-MM-dd HH:mm:ss）。给他人建任务必须填写 assigneeId。
            状态未给时：做了/处理了/完成了/弄完了为 2，正在/排查中/还没做完为 1，否则为 0。一次最多只追问一个完全缺失且无法默认的槽。
            最终用简洁中文回答；可输出 {\"blocks\":[{\"type\":\"markdown\",\"content\":\"...\"}]}，但确认卡、图表和统计由工具生成。
            状态：0待办、1进行中、2已完成、3已关闭、4待确认完成。时间格式 yyyy-MM-dd HH:mm:ss，时区 Asia/Shanghai。
            当前会话草稿（可能为空）：
            """ + Objects.toString(session.getDraftJson(), "[]"); }

    private ToolResult listProjectMembers(long loginId, Long projectId) {
        PmProject project = accessibleProject(loginId, projectId);
        Set<Long> ids = projectMemberMapper.selectList(new LambdaQueryWrapper<PmProjectMember>().eq(PmProjectMember::getProjectId, projectId))
                .stream().map(PmProjectMember::getUserId).collect(Collectors.toCollection(LinkedHashSet::new));
        if (project.getOwnerId() != null) ids.add(project.getOwnerId());
        // 普通成员不能借此枚举他人；仅负责人/管理员可看完整名单
        if (!isUnrestricted(loginId) && !Objects.equals(project.getOwnerId(), loginId)) {
            if (!ids.contains(loginId)) return permissionDenied(MSG_DENY_QUERY_OTHERS);
            ids = Set.of(loginId);
        }
        List<Map<String,Object>> rows = userMapper.selectBatchIds(ids).stream().map(u -> Map.<String,Object>of("id",u.getId(),
                "name",StringUtils.hasText(u.getNickname())?u.getNickname():u.getUsername(),"owner",Objects.equals(u.getId(),project.getOwnerId()))).toList();
        return ok(rows);
    }
    private ToolResult getTask(long loginId, Long taskId) {
        PmTask t = accessibleTask(loginId, taskId);
        ToolResult denied = denyIfCannotReadOthersTask(loginId, t);
        if (denied != null) return denied;
        return ok(taskItem(t, projectNames(List.of(t)), userNames(List.of(t))));
    }
    private ToolResult listTaskFlows(long loginId, Long taskId) {
        PmTask t = accessibleTask(loginId, taskId);
        ToolResult denied = denyIfCannotReadOthersTask(loginId, t);
        if (denied != null) return denied;
        List<Map<String,Object>> rows = taskFlowMapper.selectList(new LambdaQueryWrapper<PmTaskFlow>().eq(PmTaskFlow::getTaskId,taskId).orderByAsc(PmTaskFlow::getId))
                .stream().map(f -> Map.<String,Object>of("id",f.getId(),"action",Objects.toString(f.getAction(),""),"fromStatus",Objects.toString(f.getFromStatus(),""),
                        "toStatus",Objects.toString(f.getToStatus(),""),"remark",Objects.toString(f.getRemark(),""),"time",Objects.toString(f.getCreateTime(),""))).toList();
        return ok(rows);
    }
    private ToolResult workDuration(long loginId, Long taskId) {
        PmTask t=accessibleTask(loginId,taskId);
        ToolResult denied = denyIfCannotReadOthersTask(loginId, t);
        if (denied != null) return denied;
        LocalDateTime end=t.getCompletedAt()!=null?t.getCompletedAt():LocalDateTime.now(ZONE);
        if(t.getStartedAt()==null)return new ToolResult(false,"任务尚未开始",Map.of(),List.of());
        long seconds=taskWorkDurationService.calculate(t.getId(),t.getAssigneeId(),t.getStartedAt(),end).toSeconds(); return ok(Map.of("taskId",taskId,"seconds",seconds));
    }

    /** 读单任务/流转/工时：本人、负责项目内、管理员可；普通员工读他人任务直接拒绝。 */
    private ToolResult denyIfCannotReadOthersTask(long loginId, PmTask task) {
        if (task == null) return permissionDenied(MSG_DENY_QUERY_OTHERS);
        if (isUnrestricted(loginId)) return null;
        if (Objects.equals(task.getAssigneeId(), loginId) || Objects.equals(task.getHolderId(), loginId)) return null;
        Set<Long> owned = ownedProjectIds(loginId);
        if (task.getProjectId() != null && owned.contains(task.getProjectId())) return null;
        return permissionDenied(owned.isEmpty() ? MSG_DENY_QUERY_OTHERS : MSG_DENY_OUTSIDE_OWNED_PROJECTS);
    }

    private PmProject accessibleProject(long loginId,Long projectId){if(projectId==null)throw new BusinessException("projectId 不能为空"); PmProject p=projectMapper.selectById(projectId);
        if(p==null)throw new BusinessException("项目不存在"); boolean member=Objects.equals(p.getOwnerId(),loginId)||projectMemberMapper.selectCount(new LambdaQueryWrapper<PmProjectMember>().eq(PmProjectMember::getProjectId,projectId).eq(PmProjectMember::getUserId,loginId))>0;
        if(!member&&!dataScopeService.isGlobalAdmin(loginId))throw new BusinessException("无权访问该项目");return p;}
    private PmTask accessibleTask(long loginId,Long taskId){if(taskId==null)throw new BusinessException("taskId 不能为空");PmTask t=taskMapper.selectById(taskId);if(t==null)throw new BusinessException("任务不存在");
        if(!Objects.equals(t.getAssigneeId(),loginId)&&!Objects.equals(t.getHolderId(),loginId)&&!projectMemberTaskIds(List.of(loginId)).contains(taskId))accessibleProject(loginId,t.getProjectId());return t;}
    private Long number(Object value){return value instanceof Number n?n.longValue():value==null?null:Long.valueOf(value.toString());}

    private AiTaskLogSession loadOrCreate(long userId, String sessionId) {
        if (StringUtils.hasText(sessionId)) return ownedSession(userId, sessionId);
        AiTaskLogSession s = new AiTaskLogSession(); s.setSessionId(UUID.randomUUID().toString()); s.setUserId(userId);
        s.setStatus("chatting"); s.setMessagesJson("[]"); s.setCreateTime(LocalDateTime.now(ZONE)); s.setUpdateTime(s.getCreateTime());
        sessionMapper.insert(s); return s;
    }
    private AiTaskLogSession ownedSession(long userId,String id) { if (!StringUtils.hasText(id)) throw new BusinessException("sessionId 不能为空");
        AiTaskLogSession s=sessionMapper.selectById(id); if(s==null||!Objects.equals(s.getUserId(),userId)) throw new BusinessException("会话不存在或无权访问"); return s; }
    private ConfirmResponse done(String sid,List<Long> ids,String msg) { Map<String,Object> d=Map.of("taskIds",ids,"message",msg);
        return new ConfirmResponse(sid,PROTOCOL_VERSION,List.of(Map.of("type","done","taskIds",ids,"message",msg)),d); }
    private List<DraftItem> mergeDraftItems(AiTaskLogSession session, List<DraftItem> updates) {
        if (!StringUtils.hasText(session.getDraftJson())) return updates;
        List<DraftItem> existing;
        try { existing = objectMapper.readValue(session.getDraftJson(), new TypeReference<>() {}); }
        catch (Exception ignored) { return updates; }
        List<DraftItem> merged = new ArrayList<>(existing);
        for (int index = 0; index < updates.size(); index++) {
            DraftItem update = updates.get(index);
            int target = -1;
            if (StringUtils.hasText(update.getTempId())) {
                for (int i = 0; i < existing.size(); i++) if (update.getTempId().equals(existing.get(i).getTempId())) { target = i; break; }
            } else if (index < existing.size()) target = index;
            if (target >= 0) merged.set(target, mergeDraftItem(merged.get(target), update));
            else merged.add(update);
        }
        return merged;
    }
    private DraftItem mergeDraftItem(DraftItem base, DraftItem update) {
        if (update.getProjectId() != null) base.setProjectId(update.getProjectId());
        if (StringUtils.hasText(update.getProjectName())) base.setProjectName(update.getProjectName());
        if (StringUtils.hasText(update.getTitle())) base.setTitle(update.getTitle());
        if (update.getContent() != null) base.setContent(update.getContent());
        if (StringUtils.hasText(update.getStartAt())) base.setStartAt(update.getStartAt());
        if (StringUtils.hasText(update.getEndAt())) base.setEndAt(update.getEndAt());
        if (update.getStatus() != null) base.setStatus(update.getStatus());
        if (update.getAssigneeId() != null) base.setAssigneeId(update.getAssigneeId());
        if (StringUtils.hasText(update.getAssigneeName())) base.setAssigneeName(update.getAssigneeName());
        return base;
    }
    private int inferStatus(DraftItem item) {
        String text = Objects.toString(item.getTitle(), "") + Objects.toString(item.getContent(), "");
        if (text.matches(".*(正在|排查中|还没做完).*")) return 1;
        if (text.matches(".*(做了|处理了|完成了|弄完了|已完成).*")) return 2;
        return 0;
    }
    private void normalizeDraft(long userId, DraftItem item) {
        validateDraft(item);
        PmProject project = accessibleProject(userId, item.getProjectId());
        Long assigneeId = item.getAssigneeId() != null ? item.getAssigneeId() : userId;
        assertCanAssign(userId, project, assigneeId);
        SysUser assignee = userMapper.selectById(assigneeId);
        if (assignee == null || (assignee.getStatus() != null && assignee.getStatus() != 1)) {
            throw new BusinessException("任务负责人不存在或已停用");
        }
        LocalDateTime start = parseDateTime(item.getStartAt()), end = parseDateTime(item.getEndAt());
        int status = item.getStatus() == null ? 0 : item.getStatus();
        item.setTempId(StringUtils.hasText(item.getTempId()) ? item.getTempId() : "draft-" + UUID.randomUUID());
        item.setProjectName(project.getName());
        item.setTitle(item.getTitle().trim());
        item.setContent(Objects.toString(item.getContent(), "").trim());
        item.setAssigneeId(assigneeId);
        item.setAssigneeName(StringUtils.hasText(assignee.getNickname()) ? assignee.getNickname() : assignee.getUsername());
        item.setStartAt(start.format(DT)); item.setEndAt(end.format(DT));
        item.setTimeLabel(start.toLocalDate().equals(end.toLocalDate())
                ? start.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " ~ " + end.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                : start.format(DT) + " ~ " + end.format(DT));
        item.setDurationLabel(durationLabel(Duration.between(start, end)));
        item.setStatus(status); item.setStatusLabel(status(status));
    }

    /** 本人可给自己建；项目负责人/管理员可给项目内成员建。 */
    private void assertCanAssign(long loginId, PmProject project, Long assigneeId) {
        if (assigneeId == null) throw new BusinessException("请指定任务负责人");
        if (Objects.equals(loginId, assigneeId)) return;
        boolean owner = project != null && Objects.equals(project.getOwnerId(), loginId);
        if (!isUnrestricted(loginId) && !owner) {
            throw new BusinessException("权限不足：仅项目负责人可为项目内成员创建任务。");
        }
        if (!isUserInProject(project.getId(), assigneeId)) {
            throw new BusinessException(owner
                    ? "只能为自己负责项目内的成员创建任务。"
                    : "任务负责人必须属于该项目。");
        }
    }

    private boolean isUserInProject(Long projectId, Long userId) {
        if (projectId == null || userId == null) return false;
        PmProject project = projectMapper.selectById(projectId);
        if (project != null && Objects.equals(project.getOwnerId(), userId)) return true;
        return projectMemberMapper.selectCount(new LambdaQueryWrapper<PmProjectMember>()
                .eq(PmProjectMember::getProjectId, projectId)
                .eq(PmProjectMember::getUserId, userId)) > 0;
    }
    private String durationLabel(Duration duration) {
        long seconds = duration.getSeconds();
        long hours = seconds / 3600, minutes = seconds % 3600 / 60, remainingSeconds = seconds % 60;
        StringBuilder label = new StringBuilder();
        if (hours > 0) label.append(hours).append("小时");
        if (minutes > 0) label.append(minutes).append("分钟");
        if (remainingSeconds > 0 || label.isEmpty()) label.append(remainingSeconds).append("秒");
        return label.toString();
    }
    private void validateDraft(DraftItem i) { if(i==null||i.getProjectId()==null) throw new BusinessException("项目不能为空");
        if(!StringUtils.hasText(i.getTitle())) throw new BusinessException("任务标题不能为空"); if(i.getTitle().trim().length()>200) throw new BusinessException("任务标题过长");
        int s=i.getStatus()==null?0:i.getStatus(); if(s<0||s>4) throw new BusinessException("任务状态不正确");
        LocalDateTime start=parseDateTime(i.getStartAt()),end=parseDateTime(i.getEndAt()); if(end.isBefore(start)) throw new BusinessException("结束时间不能早于开始时间"); }
    private String withTimeNote(DraftItem i) { String note="AI 记工时段："+i.getStartAt()+" 至 "+i.getEndAt(); return StringUtils.hasText(i.getContent())?i.getContent().trim()+"\n"+note:note; }
    private LocalDate parseDate(String s){return parseDateTime(s).toLocalDate();} private LocalDateTime parseDateTime(String s){try{return LocalDateTime.parse(s,DT);}catch(Exception e){throw new BusinessException("时间格式应为 yyyy-MM-dd HH:mm:ss");}}
    private List<Map<String,Object>> readList(String json){if(!StringUtils.hasText(json))return new ArrayList<>();try{return new ArrayList<>(objectMapper.readValue(json,MAP_LIST));}catch(Exception e){return new ArrayList<>();}}
    private String write(Object o){try{return objectMapper.writeValueAsString(o);}catch(Exception e){throw new BusinessException("JSON 序列化失败");}}
    private ToolResult ok(Object data){return new ToolResult(true,null,data,List.of());}
    private Map<String,Object> clarify(String field,String prompt,List<Map<String,Object>> rows){return Map.of("type","clarify","field",field,"prompt",prompt,"options",rows.stream().map(r->Map.of("id",String.valueOf(r.get("id")),"label",r.get("name"))).toList());}
    private List<Long> longList(Object v){if(!(v instanceof List<?> l))return new ArrayList<>();return l.stream().filter(Objects::nonNull).map(x->x instanceof Number n?n.longValue():Long.parseLong(x.toString())).distinct().toList();}
    private Set<Long> projectMemberTaskIds(List<Long> users){return taskMemberMapper.selectList(new LambdaQueryWrapper<PmTaskMember>().in(PmTaskMember::getUserId,users).select(PmTaskMember::getTaskId)).stream().map(PmTaskMember::getTaskId).collect(Collectors.toSet());}
    private Map<Long,String> projectNames(List<PmTask> tasks){Set<Long> ids=tasks.stream().map(PmTask::getProjectId).filter(Objects::nonNull).collect(Collectors.toSet());if(ids.isEmpty())return Map.of();return projectMapper.selectBatchIds(ids).stream().collect(Collectors.toMap(PmProject::getId,PmProject::getName));}
    private Map<Long,String> userNames(List<PmTask> tasks){Set<Long> ids=tasks.stream().map(PmTask::getAssigneeId).filter(Objects::nonNull).collect(Collectors.toSet());if(ids.isEmpty())return Map.of();return userMapper.selectBatchIds(ids).stream().collect(Collectors.toMap(SysUser::getId,u->StringUtils.hasText(u.getNickname())?u.getNickname():u.getUsername()));}
    private static final java.util.regex.Pattern AI_TIME_RANGE = java.util.regex.Pattern.compile(
            "AI 记工时段：(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\s*至\\s*(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})");

    private Map<String,Object> taskItem(PmTask t,Map<Long,String> pn,Map<Long,String> un){
        Map<String,Object> item = new LinkedHashMap<>();
        item.put("id", t.getId());
        item.put("title", t.getTitle());
        item.put("projectId", t.getProjectId());
        item.put("projectName", Objects.toString(pn.get(t.getProjectId()), ""));
        item.put("statusLabel", status(t.getStatus()));
        item.put("userName", Objects.toString(un.get(t.getAssigneeId()), ""));
        fillTaskTimeFields(t, item);
        return item;
    }

    /** 优先还原 AI 记工起止时刻与时长；否则回退 startedAt/completedAt 或日期 + estimatedHours。 */
    private void fillTaskTimeFields(PmTask t, Map<String, Object> item) {
        LocalDateTime start = null;
        LocalDateTime end = null;
        if (StringUtils.hasText(t.getContent())) {
            java.util.regex.Matcher m = AI_TIME_RANGE.matcher(t.getContent());
            if (m.find()) {
                try {
                    start = LocalDateTime.parse(m.group(1), DT);
                    end = LocalDateTime.parse(m.group(2), DT);
                } catch (Exception ignored) {
                    start = null;
                    end = null;
                }
            }
        }
        if (start == null && t.getStartedAt() != null) {
            start = t.getStartedAt();
            end = t.getCompletedAt();
        }
        if (start != null && end != null) {
            item.put("startAt", start.format(DT));
            item.put("endAt", end.format(DT));
            item.put("timeLabel", rangeTimeLabel(start, end));
            item.put("durationLabel", durationLabel(Duration.between(start, end)));
            return;
        }
        String dateLabel = "";
        if (t.getStartDate() != null && t.getDueDate() != null && !t.getStartDate().equals(t.getDueDate())) {
            dateLabel = t.getStartDate() + " ~ " + t.getDueDate();
        } else if (t.getStartDate() != null) {
            dateLabel = t.getStartDate().toString();
        } else if (t.getDueDate() != null) {
            dateLabel = t.getDueDate().toString();
        }
        item.put("timeLabel", dateLabel);
        if (t.getEstimatedHours() != null && t.getEstimatedHours().signum() > 0) {
            long seconds = t.getEstimatedHours()
                    .multiply(java.math.BigDecimal.valueOf(3600))
                    .setScale(0, java.math.RoundingMode.HALF_UP)
                    .longValue();
            item.put("durationLabel", durationLabel(Duration.ofSeconds(Math.max(0L, seconds))));
        } else {
            item.put("durationLabel", "");
        }
    }

    private String rangeTimeLabel(LocalDateTime start, LocalDateTime end) {
        DateTimeFormatter clock = DateTimeFormatter.ofPattern("HH:mm:ss");
        return start.toLocalDate().equals(end.toLocalDate())
                ? start.toLocalTime().format(clock) + " ~ " + end.toLocalTime().format(clock)
                : start.format(DT) + " ~ " + end.format(DT);
    }

    private String status(Integer s){return switch(s==null?0:s){case 1->"进行中";case 2->"已完成";case 3->"已关闭";case 4->"待确认完成";default->"待办";};}
    private Map<String,Object> timeline(List<Map<String,Object>> items){
        List<Map<String,Object>> events = new ArrayList<>();
        for (Map<String,Object> i : items) {
            Map<String,Object> ev = new LinkedHashMap<>();
            ev.put("id", String.valueOf(i.get("id")));
            String time = Objects.toString(i.get("timeLabel"), "");
            String duration = Objects.toString(i.get("durationLabel"), "");
            ev.put("time", StringUtils.hasText(duration) && StringUtils.hasText(time) ? time + " · " + duration : time);
            ev.put("durationLabel", duration);
            ev.put("title", i.get("title"));
            ev.put("projectName", i.get("projectName"));
            ev.put("statusLabel", i.get("statusLabel"));
            ev.put("userName", i.get("userName"));
            events.add(ev);
        }
        return Map.of("type", "timeline", "title", "任务时序", "events", events);
    }
    private Map<String,Object> stats(List<PmTask> ts){
        return stats(ts, null);
    }

    private Map<String,Object> stats(List<PmTask> ts, String title){
        long done = ts.stream().filter(t -> Integer.valueOf(2).equals(t.getStatus())).count();
        long doing = ts.stream().filter(t -> Integer.valueOf(1).equals(t.getStatus())).count();
        double hours = ts.stream().mapToDouble(this::taskHours).sum();
        List<Map<String, Object>> row = new ArrayList<>();
        row.add(Map.of("label", "总数", "value", ts.size()));
        row.add(Map.of("label", "已完成", "value", done));
        row.add(Map.of("label", "进行中", "value", doing));
        if (hours > 0) {
            String hoursLabel = hours == Math.rint(hours)
                    ? String.valueOf((long) hours)
                    : String.format(Locale.ROOT, "%.1f", hours);
            row.add(Map.of("label", "合计工时", "value", hoursLabel + "h"));
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "stat_row");
        if (StringUtils.hasText(title)) block.put("title", title);
        block.put("items", row);
        return block;
    }

    /** 多工具结果合并时按 type+title 去重，避免 summarize + chart 各出一套图。 */
    private void mergeSuggestedBlocks(List<Map<String, Object>> suggested, List<Map<String, Object>> incoming) {
        if (incoming == null || incoming.isEmpty()) return;
        Set<String> seen = suggested.stream()
                .map(this::blockDedupeKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (Map<String, Object> block : incoming) {
            if (block == null || block.isEmpty()) continue;
            String key = blockDedupeKey(block);
            if (key != null && !seen.add(key)) continue;
            suggested.add(block);
        }
    }

    private String blockDedupeKey(Map<String, Object> block) {
        String type = Objects.toString(block.get("type"), "");
        // stat_row 多人场景会每人一块，不能按空 title 去重
        if (!Set.of("bar_chart", "pie_chart", "line_chart", "task_list", "timeline").contains(type)) {
            return null;
        }
        return type + "|" + Objects.toString(block.get("title"), "");
    }

    /** 查询结果结尾附带柱图/饼图；已有同 title 块则不重复。 */
    private void appendQueryVisualBlocks(List<Map<String, Object>> blocks, List<PmTask> tasks,
                                         Map<Long, String> projectNames, List<Map<String, Object>> items) {
        if (tasks == null || tasks.isEmpty()) return;
        List<Map<String, Object>> visuals = new ArrayList<>();
        visuals.add(projectChart(tasks, projectNames));
        Map<String, Object> durationChart = durationByProjectChart(tasks, projectNames);
        if (durationChart != null) visuals.add(durationChart);
        visuals.add(statusPieChart(tasks));
        mergeSuggestedBlocks(blocks, visuals);
    }

    private Map<String,Object> projectChart(List<PmTask> ts,Map<Long,String> names){
        Map<String,Long> c = ts.stream().collect(Collectors.groupingBy(
                t -> Objects.toString(names.get(t.getProjectId()), "未命名项目"),
                LinkedHashMap::new, Collectors.counting()));
        return Map.of("type", "bar_chart", "title", "按项目任务数",
                "categories", new ArrayList<>(c.keySet()),
                "series", List.of(Map.of("name", "任务数", "values", new ArrayList<>(c.values()))));
    }

    private Map<String, Object> durationByProjectChart(List<PmTask> ts, Map<Long, String> names) {
        Map<String, Double> hours = new LinkedHashMap<>();
        for (PmTask t : ts) {
            double h = taskHours(t);
            if (h <= 0) continue;
            String name = Objects.toString(names.get(t.getProjectId()), "未命名项目");
            hours.merge(name, h, Double::sum);
        }
        if (hours.isEmpty()) return null;
        List<String> categories = new ArrayList<>(hours.keySet());
        List<Number> values = categories.stream()
                .map(k -> Math.round(hours.get(k) * 10) / 10.0)
                .map(Number.class::cast)
                .toList();
        return Map.of("type", "bar_chart", "title", "按项目工时(小时)",
                "categories", categories,
                "series", List.of(Map.of("name", "工时", "values", values)));
    }

    private Map<String, Object> statusPieChart(List<PmTask> ts) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (PmTask t : ts) {
            String label = status(t.getStatus());
            counts.merge(label, 1L, Long::sum);
        }
        List<Map<String, Object>> slices = counts.entrySet().stream()
                .map(e -> Map.<String, Object>of("name", e.getKey(), "value", e.getValue()))
                .toList();
        return Map.of("type", "pie_chart", "title", "状态分布", "slices", slices);
    }

    private double taskHours(PmTask t) {
        if (t.getEstimatedHours() != null && t.getEstimatedHours().signum() > 0) {
            return t.getEstimatedHours().doubleValue();
        }
        if (!StringUtils.hasText(t.getContent())) return 0;
        java.util.regex.Matcher m = AI_TIME_RANGE.matcher(t.getContent());
        if (!m.find()) return 0;
        try {
            LocalDateTime start = LocalDateTime.parse(m.group(1), DT);
            LocalDateTime end = LocalDateTime.parse(m.group(2), DT);
            long seconds = Math.max(0L, Duration.between(start, end).getSeconds());
            return seconds / 3600.0;
        } catch (Exception e) {
            return 0;
        }
    }

    private Map<String,Object> trendChart(List<PmTask> ts,LocalDate start,LocalDate end){Map<LocalDate,Long> c=ts.stream().filter(t->t.getCompletedAt()!=null).collect(Collectors.groupingBy(t->t.getCompletedAt().toLocalDate(),Collectors.counting()));List<Map<String,Object>> pts=start.datesUntil(end.plusDays(1)).limit(31).map(d->Map.<String,Object>of("x",d.format(DateTimeFormatter.ofPattern("MM-dd")),"y",c.getOrDefault(d,0L))).toList();return Map.of("type","line_chart","title","完成趋势","xLabel","日期","yLabel","数量","series",List.of(Map.of("name","完成数","points",pts)));}
    private record ToolResult(boolean ok,String message,Object data,List<Map<String,Object>> blocks){}
    private record AgentResult(String replyText,List<Map<String,Object>> blocks,List<Map<String,Object>> trace){}
}
