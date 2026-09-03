package com.kk.biz.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kk.biz.entity.PmProject;
import com.kk.biz.entity.PmTask;
import com.kk.biz.entity.PmTaskComment;
import com.kk.biz.entity.PmTaskFlow;
import com.kk.biz.entity.PmTaskMember;
import com.kk.biz.entity.SysFile;
import com.kk.biz.mapper.PmProjectMapper;
import com.kk.biz.mapper.PmTaskCommentMapper;
import com.kk.biz.mapper.PmTaskFlowMapper;
import com.kk.biz.mapper.PmTaskMapper;
import com.kk.biz.mapper.PmTaskMemberMapper;
import com.kk.biz.service.PmTaskService;
import com.kk.biz.service.SysFileService;
import com.kk.common.exception.BusinessException;
import com.kk.system.entity.SysUser;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PmTaskServiceImpl extends ServiceImpl<PmTaskMapper, PmTask> implements PmTaskService {

    private static final String TASK_IMAGE_BIZ = "task";

    private final PmProjectMapper projectMapper;
    private final PmTaskMemberMapper taskMemberMapper;
    private final PmTaskCommentMapper commentMapper;
    private final PmTaskFlowMapper flowMapper;
    private final SysUserService userService;
    private final SysFileService fileService;

    private static final Map<Integer, String> STATUS_LABEL = Map.of(
            0, "待办",
            1, "进行中",
            2, "已完成",
            3, "已取消"
    );

    @Override
    public Page<PmTask> pageTasks(long page, long pageSize, Long projectId, Integer status, Integer priority,
                                  Long assigneeId, String title, Boolean overdue) {
        LambdaQueryWrapper<PmTask> wrapper = new LambdaQueryWrapper<PmTask>()
                .eq(projectId != null, PmTask::getProjectId, projectId)
                .eq(status != null, PmTask::getStatus, status)
                .eq(priority != null, PmTask::getPriority, priority)
                .eq(assigneeId != null, PmTask::getAssigneeId, assigneeId)
                .like(StringUtils.hasText(title), PmTask::getTitle, title);
        if (Boolean.TRUE.equals(overdue)) {
            wrapper.lt(PmTask::getDueDate, LocalDate.now()).in(PmTask::getStatus, 0, 1);
        }
        Page<PmTask> result = page(new Page<>(page, pageSize), wrapper
                .orderByAsc(PmTask::getPriority)
                .orderByAsc(PmTask::getDueDate)
                .orderByDesc(PmTask::getId));
        fillExtras(result.getRecords());
        return result;
    }

    @Override
    public PmTask getDetail(Long id) {
        PmTask task = getById(id);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        fillExtras(List.of(task));
        task.setImages(fileService.listByBiz(TASK_IMAGE_BIZ, id));
        return task;
    }

    @Override
    public Map<String, Object> summary(Long projectId) {
        List<PmTask> rows = list(new LambdaQueryWrapper<PmTask>()
                .select(PmTask::getStatus, PmTask::getDueDate)
                .eq(projectId != null, PmTask::getProjectId, projectId));
        LocalDate today = LocalDate.now();
        long todo = 0, doing = 0, done = 0, cancelled = 0, overdue = 0;
        for (PmTask row : rows) {
            Integer status = row.getStatus() == null ? 0 : row.getStatus();
            switch (status) {
                case 1 -> doing++;
                case 2 -> done++;
                case 3 -> cancelled++;
                default -> todo++;
            }
            if (row.getDueDate() != null && row.getDueDate().isBefore(today) && (status == 0 || status == 1)) {
                overdue++;
            }
        }
        Map<String, Object> map = new HashMap<>();
        map.put("total", rows.size());
        map.put("todo", todo);
        map.put("doing", doing);
        map.put("done", done);
        map.put("cancelled", cancelled);
        map.put("overdue", overdue);
        return map;
    }

    @Override
    public List<PmTask> listBoardTasks(Long projectId) {
        if (projectId == null) {
            throw new BusinessException("项目 ID 不能为空");
        }
        List<PmTask> list = list(new LambdaQueryWrapper<PmTask>()
                .eq(PmTask::getProjectId, projectId)
                .ne(PmTask::getStatus, 3)
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
        if (projectMapper.selectById(task.getProjectId()) == null) {
            throw new BusinessException("项目不存在");
        }
        if (task.getStatus() == null) {
            task.setStatus(0);
        }
        if (task.getPriority() == null) {
            task.setPriority(2);
        }
        validateDateRange(task);
        normalizeProgress(task);
        save(task);
        syncParticipants(task.getId(), task.getParticipantIds());
        syncTaskImages(task.getId(), task.getImageFileIds());
        recordFlow(task.getId(), "CREATE", null, task.getAssigneeId(), null, task.getStatus(), "创建任务");
        if (task.getAssigneeId() != null) {
            recordFlow(task.getId(), "ASSIGN", null, task.getAssigneeId(), null, null, "指派负责人");
        }
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
        validateDateRange(task);
        normalizeProgress(task);
        Long oldAssignee = existing.getAssigneeId();
        Integer oldStatus = existing.getStatus();
        updateById(task);
        if (task.getParticipantIds() != null) {
            syncParticipants(task.getId(), task.getParticipantIds());
        }
        if (task.getImageFileIds() != null) {
            syncTaskImages(task.getId(), task.getImageFileIds());
        }
        if (task.getAssigneeId() != null && !task.getAssigneeId().equals(oldAssignee)) {
            recordFlow(task.getId(), "ASSIGN", oldAssignee, task.getAssigneeId(), null, null, "修改负责人");
        } else if (task.getAssigneeId() == null && oldAssignee != null) {
            recordFlow(task.getId(), "ASSIGN", oldAssignee, null, null, null, "清空负责人");
        }
        if (task.getStatus() != null && !task.getStatus().equals(oldStatus)) {
            recordFlow(task.getId(), "STATUS", null, null, oldStatus, task.getStatus(), "编辑时变更状态");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, Integer status) {
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
        Integer oldStatus = existing.getStatus();
        if (status.equals(oldStatus)) {
            return;
        }
        PmTask update = new PmTask();
        update.setId(id);
        update.setStatus(status);
        if (status == 2) {
            update.setProgress(100);
        } else if (status == 0) {
            update.setProgress(0);
        } else if (existing.getProgress() == null || existing.getProgress() == 0 || existing.getProgress() == 100) {
            update.setProgress(status == 1 ? 10 : existing.getProgress());
        }
        updateById(update);
        recordFlow(id, "STATUS", null, null, oldStatus, status, null, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, Integer status, List<Long> imageFileIds) {
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
        Integer oldStatus = existing.getStatus();
        if (status.equals(oldStatus)) {
            return;
        }
        PmTask update = new PmTask();
        update.setId(id);
        update.setStatus(status);
        if (status == 2) {
            update.setProgress(100);
        } else if (status == 0) {
            update.setProgress(0);
        } else if (existing.getProgress() == null || existing.getProgress() == 0 || existing.getProgress() == 100) {
            update.setProgress(status == 1 ? 10 : existing.getProgress());
        }
        updateById(update);
        recordFlow(id, "STATUS", null, null, oldStatus, status, null, imageFileIds);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transfer(Long id, Long assigneeId, String remark) {
        if (id == null) {
            throw new BusinessException("任务 ID 不能为空");
        }
        if (assigneeId == null) {
            throw new BusinessException("请选择转交对象");
        }
        PmTask existing = getById(id);
        if (existing == null) {
            throw new BusinessException("任务不存在");
        }
        if (assigneeId.equals(existing.getAssigneeId())) {
            throw new BusinessException("转交对象与当前负责人相同");
        }
        SysUser target = userService.getById(assigneeId);
        if (target == null) {
            throw new BusinessException("转交对象不存在");
        }
        Long fromUserId = existing.getAssigneeId();
        PmTask update = new PmTask();
        update.setId(id);
        update.setAssigneeId(assigneeId);
        updateById(update);
        String note = StringUtils.hasText(remark) ? remark.trim() : "任务转交";
        if (note.length() > 500) {
            throw new BusinessException("转交说明不能超过 500 字");
        }
        recordFlow(id, "TRANSFER", fromUserId, assigneeId, null, null, note, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transfer(Long id, Long assigneeId, String remark, List<Long> imageFileIds) {
        if (id == null) {
            throw new BusinessException("任务 ID 不能为空");
        }
        if (assigneeId == null) {
            throw new BusinessException("请选择转交对象");
        }
        PmTask existing = getById(id);
        if (existing == null) {
            throw new BusinessException("任务不存在");
        }
        if (assigneeId.equals(existing.getAssigneeId())) {
            throw new BusinessException("转交对象与当前负责人相同");
        }
        SysUser target = userService.getById(assigneeId);
        if (target == null) {
            throw new BusinessException("转交对象不存在");
        }
        Long fromUserId = existing.getAssigneeId();
        PmTask update = new PmTask();
        update.setId(id);
        update.setAssigneeId(assigneeId);
        updateById(update);
        String note = StringUtils.hasText(remark) ? remark.trim() : "任务转交";
        if (note.length() > 500) {
            throw new BusinessException("转交说明不能超过 500 字");
        }
        recordFlow(id, "TRANSFER", fromUserId, assigneeId, null, null, note, imageFileIds);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTask(Long id) {
        commentMapper.delete(new LambdaQueryWrapper<PmTaskComment>().eq(PmTaskComment::getTaskId, id));
        flowMapper.delete(new LambdaQueryWrapper<PmTaskFlow>().eq(PmTaskFlow::getTaskId, id));
        taskMemberMapper.delete(new LambdaQueryWrapper<PmTaskMember>().eq(PmTaskMember::getTaskId, id));
        fileService.listByBiz(TASK_IMAGE_BIZ, id).forEach(f -> fileService.deleteFile(f.getId()));
        removeById(id);
    }

    @Override
    public SysFile uploadImage(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BusinessException("仅支持上传图片文件");
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
        if (getById(taskId) == null) {
            throw new BusinessException("任务不存在");
        }
        List<PmTaskComment> list = commentMapper.selectList(new LambdaQueryWrapper<PmTaskComment>()
                .eq(PmTaskComment::getTaskId, taskId)
                .orderByAsc(PmTaskComment::getId));
        fillCommentAuthors(list);
        return list;
    }

    @Override
    public PmTaskComment addComment(Long taskId, String content) {
        if (getById(taskId) == null) {
            throw new BusinessException("任务不存在");
        }
        if (!StringUtils.hasText(content)) {
            throw new BusinessException("评论内容不能为空");
        }
        String text = content.trim();
        if (text.length() > 2000) {
            throw new BusinessException("评论不能超过 2000 字");
        }
        PmTaskComment comment = new PmTaskComment();
        comment.setTaskId(taskId);
        comment.setContent(text);
        commentMapper.insert(comment);
        fillCommentAuthors(List.of(comment));
        return comment;
    }

    @Override
    public void deleteComment(Long commentId) {
        PmTaskComment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new BusinessException("评论不存在");
        }
        try {
            long loginId = StpUtil.getLoginIdAsLong();
            if (comment.getCreateBy() != null && !comment.getCreateBy().equals(loginId)
                    && !StpUtil.hasRole("admin")) {
                throw new BusinessException("只能删除自己的评论");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception ignored) {
            // 未登录由网关/拦截器处理
        }
        commentMapper.deleteById(commentId);
    }

    @Override
    public List<PmTaskFlow> listFlows(Long taskId) {
        if (getById(taskId) == null) {
            throw new BusinessException("任务不存在");
        }
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
            case "TRANSFER" -> "转交";
            default -> action;
        };
    }

    private String buildFlowSummary(PmTaskFlow flow) {
        String action = flow.getAction();
        if ("CREATE".equals(action)) {
            return "创建了任务";
        }
        if ("TRANSFER".equals(action)) {
            String from = flow.getFromUserName() != null ? flow.getFromUserName() : "未分配";
            String to = flow.getToUserName() != null ? flow.getToUserName() : "—";
            return "从 " + from + " 转交给 " + to;
        }
        if ("ASSIGN".equals(action)) {
            if (flow.getToUserName() == null) {
                return "清空了负责人";
            }
            if (flow.getFromUserName() == null) {
                return "指派给 " + flow.getToUserName();
            }
            return "负责人由 " + flow.getFromUserName() + " 变更为 " + flow.getToUserName();
        }
        if ("STATUS".equals(action)) {
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
        taskMemberMapper.delete(new LambdaQueryWrapper<PmTaskMember>().eq(PmTaskMember::getTaskId, taskId));
        if (participantIds == null || participantIds.isEmpty()) {
            return;
        }
        Set<Long> uniqueIds = new LinkedHashSet<>();
        for (Long userId : participantIds) {
            if (userId != null) {
                uniqueIds.add(userId);
            }
        }
        if (uniqueIds.isEmpty()) {
            return;
        }
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
            if (task.getProjectId() != null) {
                projectIds.add(task.getProjectId());
            }
            if (task.getAssigneeId() != null) {
                userIds.add(task.getAssigneeId());
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
            SysUser assignee = userMap.get(task.getAssigneeId());
            if (assignee != null) {
                task.setAssigneeName(assignee.getNickname() != null ? assignee.getNickname() : assignee.getUsername());
            }
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

    private void normalizeProgress(PmTask task) {
        if (Integer.valueOf(2).equals(task.getStatus())) {
            task.setProgress(100);
            return;
        }
        if (task.getProgress() == null) {
            task.setProgress(0);
        }
        task.setProgress(Math.max(0, Math.min(100, task.getProgress())));
    }

    private boolean isOverdue(PmTask task) {
        if (task.getDueDate() == null || task.getStatus() == null) {
            return false;
        }
        return task.getDueDate().isBefore(LocalDate.now()) && (task.getStatus() == 0 || task.getStatus() == 1);
    }
}
