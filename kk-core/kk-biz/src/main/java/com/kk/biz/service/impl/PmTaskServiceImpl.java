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
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PmTaskServiceImpl extends ServiceImpl<PmTaskMapper, PmTask> implements PmTaskService {

    private static final String TASK_IMAGE_BIZ = "task";

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
            3, "已关闭"
    );

    @Override
    public Page<PmTask> pageTasks(long page, long pageSize, Long projectId, Integer status, Integer priority,
                                  Long participantId, String title, Boolean overdue) {
        LambdaQueryWrapper<PmTask> wrapper = new LambdaQueryWrapper<PmTask>()
                .eq(status != null, PmTask::getStatus, status)
                .eq(priority != null, PmTask::getPriority, priority)
                .like(StringUtils.hasText(title), PmTask::getTitle, title);
        applyProjectIdFilter(wrapper, projectId);
        applyParticipantFilter(wrapper, participantId);
        if (Boolean.TRUE.equals(overdue)) {
            wrapper.lt(PmTask::getDueDate, LocalDate.now()).in(PmTask::getStatus, 0, 1);
        }
        applyVisibleScope(wrapper);
        Page<PmTask> result = page(new Page<>(page, pageSize), wrapper
                .orderByAsc(PmTask::getPriority)
                .orderByAsc(PmTask::getDueDate)
                .orderByDesc(PmTask::getId));
        fillExtras(result.getRecords());
        return result;
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
        LambdaQueryWrapper<PmTask> wrapper = new LambdaQueryWrapper<PmTask>()
                .select(PmTask::getStatus, PmTask::getDueDate)
                .eq(priority != null, PmTask::getPriority, priority)
                .like(StringUtils.hasText(title), PmTask::getTitle, title);
        applyProjectIdFilter(wrapper, projectId);
        applyParticipantFilter(wrapper, participantId);
        applyVisibleScope(wrapper);
        List<PmTask> rows = list(wrapper);
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
                    w.eq(PmTask::getCreateBy, userId);
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
        task.setCompanyId(project.getCompanyId());
        if (project.getCompanyId() == null) {
            throw new BusinessException("项目缺少所属公司，无法创建任务");
        }
        long loginId = StpUtil.getLoginIdAsLong();
        Set<Long> eligible = eligibleTaskParticipantIds(project.getId());
        List<Long> participants = task.getParticipantIds() == null
                ? new ArrayList<>()
                : new ArrayList<>(task.getParticipantIds());
        if (eligible.contains(loginId) && !participants.contains(loginId)) {
            participants.add(loginId);
        }
        task.setAssigneeId(null);
        task.setParticipantIds(participants);
        assertUsersInCompany(project.getCompanyId(), null, participants);
        assertParticipantsEligible(project.getId(), participants);
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
        // 持有人字段已停用：强制清空，忽略入参
        task.setAssigneeId(null);
        assertUsersInCompany(companyId, null, task.getParticipantIds());
        if (task.getParticipantIds() != null) {
            assertParticipantsEligible(existing.getProjectId(), task.getParticipantIds());
        }
        validateDateRange(task);
        normalizeProgress(task);
        Integer oldStatus = existing.getStatus();
        updateById(task);
        lambdaUpdate().eq(PmTask::getId, task.getId()).setSql("assignee_id = NULL").update();
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
        recordFlow(id, "STATUS", null, null, oldStatus, status, note, imageFileIds);
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
        boolean newlyAdded = ensureParticipant(id, targetUserId);
        // 持有人字段停用：始终保持 assignee_id 为空
        lambdaUpdate().eq(PmTask::getId, id).setSql("assignee_id = NULL").update();
        if (!newlyAdded) {
            // 已是参与人：幂等成功，不重复写流转/通知
            return;
        }
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
        fillCommentAuthors(list);
        return list;
    }

    @Override
    public PmTaskComment addComment(Long taskId, String content) {
        PmTask task = getById(taskId);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        assertCanAccessTask(task);
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
        PmTask task = getById(comment.getTaskId());
        if (task != null) {
            assertCanAccessTask(task);
        }
        try {
            long loginId = StpUtil.getLoginIdAsLong();
            if (comment.getCreateBy() != null && !comment.getCreateBy().equals(loginId)
                    && !dataScopeService.isGlobalAdmin(loginId)) {
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
        if (dataScopeService.isGlobalAdmin(loginId)) {
            return;
        }
        Set<Long> companies = dataScopeService.visibleCompanyIds(loginId);
        if (companies.isEmpty()) {
            wrapper.eq(PmTask::getId, -1L);
            return;
        }
        wrapper.and(outer -> {
            boolean any = false;
            for (Long companyId : companies) {
                if (dataScopeService.hasRoleInCompany(loginId, "control", companyId)) {
                    outer.or().eq(PmTask::getCompanyId, companyId);
                    any = true;
                    continue;
                }
                Set<Long> users = dataScopeService.visibleUserIdsInCompany(loginId, companyId);
                if (users.isEmpty()) {
                    continue;
                }
                Set<Long> memberTaskIds = taskMemberMapper.selectList(new LambdaQueryWrapper<PmTaskMember>()
                                .in(PmTaskMember::getUserId, users)
                                .select(PmTaskMember::getTaskId))
                        .stream()
                        .map(PmTaskMember::getTaskId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
                final Set<Long> companyMemberTaskIds;
                if (memberTaskIds.isEmpty()) {
                    companyMemberTaskIds = Set.of();
                } else {
                    companyMemberTaskIds = list(new LambdaQueryWrapper<PmTask>()
                                    .in(PmTask::getId, memberTaskIds)
                                    .eq(PmTask::getCompanyId, companyId)
                                    .select(PmTask::getId))
                            .stream()
                            .map(PmTask::getId)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toSet());
                }
                Set<Long> ownedProjectIds = projectMapper.selectList(new LambdaQueryWrapper<PmProject>()
                                .eq(PmProject::getCompanyId, companyId)
                                .in(PmProject::getOwnerId, users)
                                .select(PmProject::getId))
                        .stream()
                        .map(PmProject::getId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
                // 我作为项目参与人的项目 → 可见其下全部任务
                final Set<Long> joinedProjectIds = listJoinedProjectIds(loginId, companyId);
                outer.or(w -> {
                    w.eq(PmTask::getCompanyId, companyId).and(inner -> {
                        inner.in(PmTask::getCreateBy, users);
                        if (!companyMemberTaskIds.isEmpty()) {
                            inner.or().in(PmTask::getId, companyMemberTaskIds);
                        }
                        if (!ownedProjectIds.isEmpty()) {
                            inner.or().in(PmTask::getProjectId, ownedProjectIds);
                        }
                        if (!joinedProjectIds.isEmpty()) {
                            inner.or().in(PmTask::getProjectId, joinedProjectIds);
                        }
                    });
                });
                any = true;
            }
            if (!any) {
                outer.eq(PmTask::getId, -1L);
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

    private boolean isProjectMember(Long projectId, long userId) {
        if (projectId == null) {
            return false;
        }
        Long cnt = projectMemberMapper.selectCount(new LambdaQueryWrapper<PmProjectMember>()
                .eq(PmProjectMember::getProjectId, projectId)
                .eq(PmProjectMember::getUserId, userId));
        return cnt != null && cnt > 0;
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

    /** 可读：admin / 公司 control / data_scope 相关 / 项目成员或负责人 */
    private void assertCanAccessTask(PmTask task) {
        long loginId = StpUtil.getLoginIdAsLong();
        if (dataScopeService.isGlobalAdmin(loginId)) {
            return;
        }
        Long companyId = task.getCompanyId();
        Set<Long> companies = dataScopeService.visibleCompanyIds(loginId);
        if (companyId == null || !companies.contains(companyId)) {
            throw new BusinessException("无权操作该任务");
        }
        if (dataScopeService.hasRoleInCompany(loginId, "control", companyId)) {
            return;
        }
        Set<Long> users = dataScopeService.visibleUserIdsInCompany(loginId, companyId);
        if (task.getCreateBy() != null && users.contains(task.getCreateBy())) {
            return;
        }
        if (task.getProjectId() != null) {
            PmProject project = projectMapper.selectById(task.getProjectId());
            if (project != null && Objects.equals(project.getCompanyId(), companyId)) {
                if (Objects.equals(project.getOwnerId(), loginId)
                        || (project.getOwnerId() != null && users.contains(project.getOwnerId()))) {
                    return;
                }
                if (isProjectMember(project.getId(), loginId)) {
                    return;
                }
            }
        }
        if (task.getId() != null && !users.isEmpty()) {
            Long cnt = taskMemberMapper.selectCount(new LambdaQueryWrapper<PmTaskMember>()
                    .eq(PmTaskMember::getTaskId, task.getId())
                    .in(PmTaskMember::getUserId, users));
            if (cnt != null && cnt > 0) {
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
        if (task.getStatus() != null && task.getStatus() == 3) {
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
            // 持有人字段已停用
            task.setAssigneeName(null);
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
