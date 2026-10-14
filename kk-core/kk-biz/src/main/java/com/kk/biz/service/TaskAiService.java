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
        if (request.getClarifyAnswer() != null) {
            text += "\n[结构化澄清] field=" + request.getClarifyAnswer().getField()
                    + ", optionId=" + request.getClarifyAnswer().getOptionId()
                    + ", label=" + Objects.toString(request.getClarifyAnswer().getLabel(), "");
        }
        messages.add(Map.of("role", "user", "content", text));
        AgentResult result = runAgent(loginUserId, session, messages);
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
            validateDraft(item);
            PmTask task = new PmTask();
            task.setProjectId(item.getProjectId());
            task.setTitle(item.getTitle().trim());
            task.setContent(withTimeNote(item));
            task.setAssigneeId(loginUserId);
            task.setHolderId(loginUserId);
            task.setParticipantIds(List.of(loginUserId));
            task.setStartDate(parseDate(item.getStartAt()));
            task.setDueDate(parseDate(item.getEndAt()));
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
        return done(session.getSessionId(), ids, "已成功登记 " + ids.size() + " 条任务");
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
        if (!StringUtils.hasText(properties.getApiKey())) throw new BusinessException("AI 服务未配置 apiKey");
        List<Map<String, Object>> apiMessages = new ArrayList<>();
        apiMessages.add(Map.of("role", "system", "content", systemPrompt()));
        for (Map<String, Object> msg : history) apiMessages.add(Map.of("role", msg.get("role"), "content", msg.get("content")));
        List<Map<String, Object>> trace = new ArrayList<>();
        List<Map<String, Object>> suggested = new ArrayList<>();
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
            for (JsonNode call : calls) {
                String callId = call.path("id").asText();
                String name = call.path("function").path("name").asText();
                Map<String, Object> args;
                try { args = objectMapper.readValue(call.path("function").path("arguments").asText("{}"), new TypeReference<>() {}); }
                catch (Exception e) { args = Map.of(); }
                ToolResult tr;
                try { tr = executeTool(name, userId, session, args); }
                catch (Exception e) { tr = new ToolResult(false, e.getMessage(), Map.of(), List.of()); }
                suggested.addAll(tr.blocks);
                if (properties.isToolTraceEnabled()) trace.add(Map.of("name", name, "ok", tr.ok));
                apiMessages.add(Map.of("role", "tool", "tool_call_id", callId, "content", write(Map.of(
                        "ok", tr.ok, "message", Objects.toString(tr.message, ""), "data", tr.data))));
            }
        }
        List<Map<String, Object>> blocks = sanitizeModelBlocks(reply, suggested);
        if (blocks.isEmpty()) blocks.add(Map.of("type", "markdown", "content", StringUtils.hasText(reply) ? reply : "暂时无法完成该请求，请换一种说法。"));
        return new AgentResult(reply, blocks, trace);
    }

    private JsonNode callModel(List<Map<String, Object>> messages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel()); body.put("messages", messages); body.put("tools", toolDefinitions());
        body.put("tool_choice", "auto"); body.put("temperature", 0.1);
        return RestClient.builder().baseUrl(properties.getBaseUrl()).defaultHeader(HttpHeaders.AUTHORIZATION,
                "Bearer " + properties.getApiKey()).build().post().uri("/chat/completions").body(body).retrieve().body(JsonNode.class);
    }

    private ToolResult executeTool(String name, long userId, AiTaskLogSession session, Map<String, Object> args) {
        return switch (name) {
            case "list_my_projects" -> ok(projectsFor(userId, null));
            case "search_projects", "resolve_project" -> resolveProject(userId, Objects.toString(args.get("keyword"), ""));
            case "search_users", "resolve_user" -> resolveUser(userId, Objects.toString(args.get("keyword"), ""));
            case "get_server_now" -> ok(Map.of("now", LocalDateTime.now(ZONE).format(DT), "timezone", ZONE.getId()));
            case "normalize_time_range" -> normalizeTime(Objects.toString(args.get("expression"), ""));
            case "list_project_members" -> listProjectMembers(userId, number(args.get("projectId")));
            case "get_task" -> getTask(userId, number(args.get("taskId")));
            case "list_task_flows" -> listTaskFlows(userId, number(args.get("taskId")));
            case "work_duration_summary" -> workDuration(userId, number(args.get("taskId")));
            case "list_tasks", "summarize_tasks", "tasks_timeline", "tasks_by_project_chart", "tasks_trend_chart" -> queryTasks(name, userId, args);
            case "draft_log_tasks", "update_draft" -> saveDraft(session, args);
            case "clear_draft" -> { session.setDraftJson(null); session.setStatus("chatting"); yield ok(Map.of()); }
            default -> new ToolResult(false, "未知工具", Map.of(), List.of());
        };
    }

    private ToolResult resolveProject(long userId, String keyword) {
        List<Map<String, Object>> rows = projectsFor(userId, keyword);
        if (rows.size() == 1) return ok(rows.get(0));
        if (rows.isEmpty()) return new ToolResult(false, "未找到可访问的项目", Map.of(), List.of());
        return new ToolResult(true, "存在多个项目", Map.of("candidates", rows), List.of(clarify("project", "请选择项目：", rows)));
    }

    private List<Map<String, Object>> projectsFor(long userId, String keyword) {
        Set<Long> ids = new HashSet<>(projectMapper.selectList(new LambdaQueryWrapper<PmProject>().eq(PmProject::getOwnerId, userId)
                .select(PmProject::getId)).stream().map(PmProject::getId).toList());
        ids.addAll(projectMemberMapper.selectList(new LambdaQueryWrapper<PmProjectMember>().eq(PmProjectMember::getUserId, userId)
                .select(PmProjectMember::getProjectId)).stream().map(PmProjectMember::getProjectId).toList());
        if (ids.isEmpty()) return List.of();
        LambdaQueryWrapper<PmProject> q = new LambdaQueryWrapper<PmProject>().in(PmProject::getId, ids).eq(PmProject::getApproveStatus, 1)
                .ne(PmProject::getStatus, 3).select(PmProject::getId, PmProject::getName);
        if (StringUtils.hasText(keyword)) q.like(PmProject::getName, keyword.trim());
        return projectMapper.selectList(q.orderByAsc(PmProject::getName)).stream()
                .map(p -> Map.<String,Object>of("id", p.getId(), "name", p.getName())).toList();
    }

    private ToolResult resolveUser(long loginId, String keyword) {
        Set<Long> visible = dataScopeService.visibleUserIds(loginId);
        if (visible.isEmpty()) visible = Set.of(loginId);
        LambdaQueryWrapper<SysUser> q = new LambdaQueryWrapper<SysUser>().in(SysUser::getId, visible).eq(SysUser::getStatus, 1)
                .select(SysUser::getId, SysUser::getUsername, SysUser::getNickname);
        if (StringUtils.hasText(keyword)) q.and(w -> w.like(SysUser::getNickname, keyword.trim()).or().like(SysUser::getUsername, keyword.trim()));
        List<Map<String, Object>> rows = userMapper.selectList(q).stream().map(u -> Map.<String,Object>of("id", u.getId(),
                "name", StringUtils.hasText(u.getNickname()) ? u.getNickname() : u.getUsername())).toList();
        if (rows.size() == 1) return ok(rows.get(0));
        if (rows.isEmpty()) return new ToolResult(false, "未找到用户", Map.of(), List.of());
        return new ToolResult(true, "存在多个用户", Map.of("candidates", rows), List.of(clarify("user", "请选择人员：", rows)));
    }

    private ToolResult normalizeTime(String expression) {
        LocalDateTime now = LocalDateTime.now(ZONE), start, end;
        String e = expression.trim();
        if (e.contains("过去一小时") || e.contains("最近一小时")) { start = now.minusHours(1); end = now; }
        else {
            LocalDate day = e.contains("昨天") ? now.toLocalDate().minusDays(1) : now.toLocalDate();
            if (e.contains("上午")) { start = day.atTime(9, 30); end = day.atTime(12, 0); }
            else if (e.contains("下午")) { start = day.atTime(13, 30); end = day.atTime(18, 0); }
            else if (e.contains("今天") || e.contains("今日") || e.contains("昨天")) { start = day.atStartOfDay(); end = day.atTime(23, 59, 59); }
            else return new ToolResult(false, "无法解析时间，请提供具体日期或今天/昨天/上午/下午", Map.of(), List.of());
        }
        return ok(Map.of("start", start.format(DT), "end", end.format(DT), "label", expression));
    }

    private ToolResult queryTasks(String tool, long loginId, Map<String, Object> args) {
        List<Long> userIds = longList(args.get("userIds")); if (userIds.isEmpty()) userIds = List.of(loginId);
        List<Long> projectIds = longList(args.get("projectIds"));
        projectIds = authorizedProjects(loginId, userIds, projectIds);
        final List<Long> queryUserIds = userIds;
        final List<Long> queryProjectIds = projectIds;
        LocalDateTime start = parseDateTime(Objects.toString(args.get("start"), LocalDate.now(ZONE).atStartOfDay().format(DT)));
        LocalDateTime end = parseDateTime(Objects.toString(args.get("end"), LocalDate.now(ZONE).atTime(23,59,59).format(DT)));
        Set<Long> memberTaskIds = projectMemberTaskIds(userIds);
        LambdaQueryWrapper<PmTask> q = new LambdaQueryWrapper<PmTask>().in(!queryProjectIds.isEmpty(), PmTask::getProjectId, queryProjectIds)
                .and(w -> w.in(PmTask::getAssigneeId, queryUserIds).or().in(!memberTaskIds.isEmpty(), PmTask::getId, memberTaskIds))
                .and(w -> w.between(PmTask::getStartDate, start.toLocalDate(), end.toLocalDate())
                        .or().between(PmTask::getDueDate, start.toLocalDate(), end.toLocalDate())
                        .or().between(PmTask::getCompletedAt, start, end)
                        .or().between(PmTask::getCreateTime, start, end)).orderByAsc(PmTask::getStartDate).last("LIMIT 200");
        if (args.get("status") instanceof Number n) q.eq(PmTask::getStatus, n.intValue());
        List<PmTask> tasks = taskMapper.selectList(q);
        Map<Long,String> projectNames = projectNames(tasks); Map<Long,String> userNames = userNames(tasks);
        List<Map<String,Object>> items = tasks.stream().map(t -> taskItem(t, projectNames, userNames)).toList();
        List<Map<String,Object>> blocks = new ArrayList<>();
        if ("list_tasks".equals(tool)) blocks.add(Map.of("type","task_list","title","任务列表","items",items));
        if ("tasks_timeline".equals(tool)) blocks.add(timeline(items));
        if ("tasks_by_project_chart".equals(tool)) blocks.add(projectChart(tasks, projectNames));
        if ("tasks_trend_chart".equals(tool)) blocks.add(trendChart(tasks, start.toLocalDate(), end.toLocalDate()));
        if ("summarize_tasks".equals(tool)) blocks.add(stats(tasks));
        return new ToolResult(true, "按计划日/完成日/创建日落在所选时段统计", Map.of("count",tasks.size(),"items",items), blocks);
    }

    private List<Long> authorizedProjects(long loginId, List<Long> targetUsers, List<Long> requested) {
        boolean selfOnly = targetUsers.stream().allMatch(id -> id == loginId);
        if (selfOnly) return requested;
        if (dataScopeService.isGlobalAdmin(loginId) || cn.dev33.satoken.stp.StpUtil.hasPermission("task:ai:query-others")) return requested;
        Set<Long> owned = projectMapper.selectList(new LambdaQueryWrapper<PmProject>().eq(PmProject::getOwnerId, loginId)
                .select(PmProject::getId)).stream().map(PmProject::getId).collect(Collectors.toSet());
        Set<Long> targetProjects = projectMemberMapper.selectList(new LambdaQueryWrapper<PmProjectMember>().in(PmProjectMember::getUserId, targetUsers)
                .select(PmProjectMember::getProjectId)).stream().map(PmProjectMember::getProjectId).collect(Collectors.toSet());
        targetProjects.addAll(projectMapper.selectList(new LambdaQueryWrapper<PmProject>().in(PmProject::getOwnerId, targetUsers)
                .select(PmProject::getId)).stream().map(PmProject::getId).toList());
        owned.retainAll(targetProjects);
        if (!requested.isEmpty()) owned.retainAll(requested);
        if (owned.isEmpty()) throw new BusinessException("无权查询该人员的任务，查询范围仅限您负责的共同项目");
        return new ArrayList<>(owned);
    }

    private ToolResult saveDraft(AiTaskLogSession session, Map<String, Object> args) {
        Object raw = args.get("items");
        if (!(raw instanceof List<?> list) || list.isEmpty()) return new ToolResult(false, "草稿不能为空", Map.of(), List.of());
        List<DraftItem> items = objectMapper.convertValue(list, new TypeReference<>() {});
        for (DraftItem item : items) validateDraft(item);
        session.setDraftJson(write(items)); session.setStatus("awaiting_confirm");
        List<Map<String,Object>> normalized = objectMapper.convertValue(items, MAP_LIST);
        return new ToolResult(true, "草稿已保存，等待用户确认", Map.of("count",items.size()),
                List.of(Map.of("type","confirm_card","items",normalized)));
    }

    private List<Map<String,Object>> sanitizeModelBlocks(String reply, List<Map<String,Object>> suggested) {
        List<Map<String,Object>> result = new ArrayList<>();
        if (StringUtils.hasText(reply)) {
            try {
                String json = reply.trim(); int a = json.indexOf('{'), b = json.lastIndexOf('}');
                if (a >= 0 && b > a) {
                    JsonNode root = objectMapper.readTree(json.substring(a,b+1));
                    if (root.has("blocks")) for (JsonNode n : root.get("blocks")) {
                        String type = n.path("type").asText();
                        if (Set.of("markdown","clarify").contains(type)) result.add(objectMapper.convertValue(n, new TypeReference<>() {}));
                    }
                }
            } catch (Exception ignored) {}
        }
        result.addAll(suggested);
        if (result.isEmpty() && StringUtils.hasText(reply)) result.add(Map.of("type","markdown","content",reply));
        return result;
    }

    private List<Map<String,Object>> toolDefinitions() {
        return List.of(tool("list_my_projects","列出当前用户可参与记工的项目", Map.of()),
                tool("search_projects","搜索当前用户可访问项目", props("keyword","string")),
                tool("resolve_project","解析项目名称；多命中时返回候选", props("keyword","string")),
                tool("search_users","在可见公司范围搜索用户", props("keyword","string")),
                tool("resolve_user","解析用户姓名；多命中时返回候选", props("keyword","string")),
                tool("get_server_now","获取服务器当前时间", Map.of()),
                tool("normalize_time_range","将中文时间表达归一化", props("expression","string")),
                tool("list_project_members","列出项目负责人和成员", Map.of("projectId",Map.of("type","integer"))),
                tool("get_task","读取单个任务详情", Map.of("taskId",Map.of("type","integer"))),
                tool("list_task_flows","读取任务流转时序", Map.of("taskId",Map.of("type","integer"))),
                taskQueryTool("list_tasks","查询真实任务并生成任务列表"), taskQueryTool("summarize_tasks","汇总真实任务"),
                taskQueryTool("tasks_timeline","生成任务时间线"), taskQueryTool("tasks_by_project_chart","生成项目分布图"),
                taskQueryTool("tasks_trend_chart","生成任务趋势图"),
                tool("work_duration_summary","计算单任务有效工时", Map.of("taskId",Map.of("type","integer"))),
                tool("draft_log_tasks","保存记工草稿，绝不直接入库", Map.of("items", Map.of("type","array","items", Map.of("type","object")))),
                tool("update_draft","更新记工草稿", Map.of("items", Map.of("type","array","items", Map.of("type","object")))),
                tool("clear_draft","清空记工草稿", Map.of()));
    }
    private Map<String,Object> taskQueryTool(String name,String description) { return tool(name,description,Map.of(
            "userIds",Map.of("type","array","items",Map.of("type","integer")), "projectIds",Map.of("type","array","items",Map.of("type","integer")),
            "start",Map.of("type","string"),"end",Map.of("type","string"),"status",Map.of("type","integer"))); }
    private Map<String,Object> tool(String name,String desc,Map<String,Object> properties) { return Map.of("type","function","function",Map.of(
            "name",name,"description",desc,"parameters",Map.of("type","object","properties",properties))); }
    private Map<String,Object> props(String key,String type) { return Map.of(key,Map.of("type",type)); }
    private String systemPrompt() { return """
            你是 BGMN 任务助手。所有项目、用户、任务数字必须通过工具获取，禁止编造。记工必须先解析时间和项目，再调用 draft_log_tasks，绝不能直接写库。
            查询他人必须 resolve_user 后再查询。工具返回的图表/时间线/统计块是唯一可信数据，不要自行生成图表数据。
            最终用简洁中文回答；可输出 {\"blocks\":[{\"type\":\"markdown\",\"content\":\"...\"}]}，但确认卡、图表和统计由工具生成。
            状态：0待办、1进行中、2已完成、3已关闭。时间格式 yyyy-MM-dd HH:mm:ss，时区 Asia/Shanghai。
            """; }

    private ToolResult listProjectMembers(long loginId, Long projectId) {
        PmProject project = accessibleProject(loginId, projectId);
        Set<Long> ids = projectMemberMapper.selectList(new LambdaQueryWrapper<PmProjectMember>().eq(PmProjectMember::getProjectId, projectId))
                .stream().map(PmProjectMember::getUserId).collect(Collectors.toCollection(LinkedHashSet::new));
        if (project.getOwnerId() != null) ids.add(project.getOwnerId());
        List<Map<String,Object>> rows = userMapper.selectBatchIds(ids).stream().map(u -> Map.<String,Object>of("id",u.getId(),
                "name",StringUtils.hasText(u.getNickname())?u.getNickname():u.getUsername(),"owner",Objects.equals(u.getId(),project.getOwnerId()))).toList();
        return ok(rows);
    }
    private ToolResult getTask(long loginId, Long taskId) {
        PmTask t = accessibleTask(loginId, taskId); return ok(taskItem(t, projectNames(List.of(t)), userNames(List.of(t))));
    }
    private ToolResult listTaskFlows(long loginId, Long taskId) {
        accessibleTask(loginId, taskId);
        List<Map<String,Object>> rows = taskFlowMapper.selectList(new LambdaQueryWrapper<PmTaskFlow>().eq(PmTaskFlow::getTaskId,taskId).orderByAsc(PmTaskFlow::getId))
                .stream().map(f -> Map.<String,Object>of("id",f.getId(),"action",Objects.toString(f.getAction(),""),"fromStatus",Objects.toString(f.getFromStatus(),""),
                        "toStatus",Objects.toString(f.getToStatus(),""),"remark",Objects.toString(f.getRemark(),""),"time",Objects.toString(f.getCreateTime(),""))).toList();
        return ok(rows);
    }
    private ToolResult workDuration(long loginId, Long taskId) {
        PmTask t=accessibleTask(loginId,taskId); LocalDateTime end=t.getCompletedAt()!=null?t.getCompletedAt():LocalDateTime.now(ZONE);
        if(t.getStartedAt()==null)return new ToolResult(false,"任务尚未开始",Map.of(),List.of());
        long seconds=taskWorkDurationService.calculate(t.getId(),t.getAssigneeId(),t.getStartedAt(),end).toSeconds(); return ok(Map.of("taskId",taskId,"seconds",seconds));
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
    private Map<String,Object> taskItem(PmTask t,Map<Long,String> pn,Map<Long,String> un){return Map.of("id",t.getId(),"title",t.getTitle(),"projectName",Objects.toString(pn.get(t.getProjectId()),""),"statusLabel",status(t.getStatus()),"timeLabel",t.getStartDate()==null?"":t.getStartDate().toString(),"userName",Objects.toString(un.get(t.getAssigneeId()),""));}
    private String status(Integer s){return switch(s==null?0:s){case 1->"进行中";case 2->"已完成";case 3->"已关闭";case 4->"待确认完成";default->"待办";};}
    private Map<String,Object> timeline(List<Map<String,Object>> items){return Map.of("type","timeline","title","任务时序","events",items.stream().map(i->Map.of("id",String.valueOf(i.get("id")),"time",i.get("timeLabel"),"title",i.get("title"),"projectName",i.get("projectName"),"statusLabel",i.get("statusLabel"),"userName",i.get("userName"))).toList());}
    private Map<String,Object> stats(List<PmTask> ts){long done=ts.stream().filter(t->Integer.valueOf(2).equals(t.getStatus())).count(),doing=ts.stream().filter(t->Integer.valueOf(1).equals(t.getStatus())).count();return Map.of("type","stat_row","items",List.of(Map.of("label","总数","value",ts.size()),Map.of("label","已完成","value",done),Map.of("label","进行中","value",doing)));}
    private Map<String,Object> projectChart(List<PmTask> ts,Map<Long,String> names){Map<String,Long> c=ts.stream().collect(Collectors.groupingBy(t->Objects.toString(names.get(t.getProjectId()),"未命名项目"),LinkedHashMap::new,Collectors.counting()));return Map.of("type","bar_chart","title","按项目分布","categories",new ArrayList<>(c.keySet()),"series",List.of(Map.of("name","任务数","values",new ArrayList<>(c.values()))));}
    private Map<String,Object> trendChart(List<PmTask> ts,LocalDate start,LocalDate end){Map<LocalDate,Long> c=ts.stream().filter(t->t.getCompletedAt()!=null).collect(Collectors.groupingBy(t->t.getCompletedAt().toLocalDate(),Collectors.counting()));List<Map<String,Object>> pts=start.datesUntil(end.plusDays(1)).limit(31).map(d->Map.<String,Object>of("x",d.format(DateTimeFormatter.ofPattern("MM-dd")),"y",c.getOrDefault(d,0L))).toList();return Map.of("type","line_chart","title","完成趋势","xLabel","日期","yLabel","数量","series",List.of(Map.of("name","完成数","points",pts)));}
    private record ToolResult(boolean ok,String message,Object data,List<Map<String,Object>> blocks){}
    private record AgentResult(String replyText,List<Map<String,Object>> blocks,List<Map<String,Object>> trace){}
}
