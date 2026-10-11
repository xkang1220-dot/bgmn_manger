package com.kk.biz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kk.biz.entity.PmProject;
import com.kk.biz.entity.PmProjectLink;
import com.kk.biz.entity.PmTask;
import com.kk.biz.entity.PmTaskComment;
import com.kk.biz.entity.SysFile;
import com.kk.biz.mapper.PmProjectLinkMapper;
import com.kk.biz.mapper.PmTaskCommentMapper;
import com.kk.biz.mapper.PmTaskMapper;
import com.kk.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ProjectResourceService {
    public static final String PROJECT_RESOURCE = "PROJECT_RESOURCE";
    public static final String REPOSITORY = "REPOSITORY";
    public static final String WEBSITE = "WEBSITE";

    private final PmProjectLinkMapper linkMapper;
    private final PmTaskMapper taskMapper;
    private final PmTaskCommentMapper taskCommentMapper;
    private final PmProjectService projectService;
    private final SysFileService fileService;

    public Map<String, Object> getResources(Long projectId) {
        requireProject(projectId);
        List<PmProjectLink> links = linkMapper.selectList(new LambdaQueryWrapper<PmProjectLink>()
                .eq(PmProjectLink::getProjectId, projectId).orderByAsc(PmProjectLink::getId));
        return Map.of(
                "repositories", links.stream().filter(link -> REPOSITORY.equals(link.getType())).toList(),
                "websites", links.stream().filter(link -> WEBSITE.equals(link.getType())).toList(),
                "files", fileService.listByBiz(PROJECT_RESOURCE, projectId),
                "taskFiles", listTaskFiles(projectId));
    }

    /**
     * 汇总项目下所有任务正文附件及任务评论附件，供项目资料的文件库统一检索。
     */
    private List<Map<String, Object>> listTaskFiles(Long projectId) {
        List<PmTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<PmTask>()
                .eq(PmTask::getProjectId, projectId)
                .select(PmTask::getId, PmTask::getTitle));
        if (tasks.isEmpty()) {
            return List.of();
        }

        Map<Long, String> taskTitles = new LinkedHashMap<>();
        tasks.forEach(task -> taskTitles.put(task.getId(), task.getTitle()));
        List<Long> taskIds = tasks.stream().map(PmTask::getId).toList();
        Map<Long, List<SysFile>> taskFileMap = fileService.mapByBiz("task", taskIds);

        List<Map<String, Object>> result = new ArrayList<>();
        tasks.forEach(task -> taskFileMap.getOrDefault(task.getId(), List.of())
                .forEach(file -> result.add(toProjectFile(file, "任务附件", task.getTitle(), task.getId()))));

        List<PmTaskComment> comments = taskCommentMapper.selectList(new LambdaQueryWrapper<PmTaskComment>()
                .in(PmTaskComment::getTaskId, taskIds)
                .select(PmTaskComment::getId, PmTaskComment::getTaskId));
        if (!comments.isEmpty()) {
            Map<Long, List<SysFile>> commentFileMap = fileService.mapByBiz("task_comment",
                    comments.stream().map(PmTaskComment::getId).toList());
            comments.forEach(comment -> commentFileMap.getOrDefault(comment.getId(), List.of())
                    .forEach(file -> result.add(toProjectFile(file, "任务评论附件",
                            taskTitles.get(comment.getTaskId()), comment.getTaskId()))));
        }
        return result;
    }

    private Map<String, Object> toProjectFile(SysFile file, String sourceLabel,
                                               String taskTitle, Long taskId) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", file.getId());
        item.put("originalName", file.getOriginalName());
        item.put("contentType", file.getContentType());
        item.put("size", file.getSize());
        item.put("createTime", file.getCreateTime());
        item.put("createBy", file.getCreateBy());
        item.put("sourceLabel", sourceLabel);
        item.put("taskId", taskId);
        item.put("taskTitle", taskTitle);
        return item;
    }

    @Transactional(rollbackFor = Exception.class)
    public PmProjectLink saveLink(Long projectId, PmProjectLink link) {
        requireProject(projectId);
        validateLink(link);
        link.setProjectId(projectId);
        if (link.getId() == null) {
            linkMapper.insert(link);
            projectService.recordFlow(projectId, "RESOURCE_CREATE", null, null, null,
                    "新增项目资料链接「" + link.getTitle() + "」");
        } else {
            PmProjectLink existing = linkMapper.selectById(link.getId());
            if (existing == null || !projectId.equals(existing.getProjectId())) {
                throw new BusinessException("项目链接不存在");
            }
            linkMapper.updateById(link);
            projectService.recordFlow(projectId, "RESOURCE_UPDATE", null, null, null,
                    "编辑项目资料链接「" + link.getTitle() + "」");
        }
        return linkMapper.selectById(link.getId());
    }

    public void deleteLink(Long projectId, Long linkId) {
        requireProject(projectId);
        PmProjectLink existing = linkMapper.selectById(linkId);
        if (existing == null || !projectId.equals(existing.getProjectId())) {
            throw new BusinessException("项目链接不存在");
        }
        linkMapper.deleteById(linkId);
        projectService.recordFlow(projectId, "RESOURCE_DELETE", null, null, null,
                "删除项目资料链接「" + existing.getTitle() + "」");
    }

    public SysFile uploadFile(Long projectId, MultipartFile file) {
        requireProject(projectId);
        SysFile saved = fileService.upload(file, PROJECT_RESOURCE, projectId);
        projectService.recordFlow(projectId, "RESOURCE_CREATE", null, null, null,
                "新增项目资料文件「" + saved.getOriginalName() + "」");
        return saved;
    }

    public void deleteFile(Long projectId, Long fileId) {
        requireProject(projectId);
        SysFile file = fileService.get(fileId);
        if (!PROJECT_RESOURCE.equals(file.getBizType()) || !projectId.equals(file.getBizId())) {
            throw new BusinessException("项目文件不存在");
        }
        fileService.deleteFile(fileId);
        projectService.recordFlow(projectId, "RESOURCE_DELETE", null, null, null,
                "删除项目资料文件「" + file.getOriginalName() + "」");
    }

    private PmProject requireProject(Long projectId) {
        PmProject project = projectService.getDetail(projectId);
        if (project == null) throw new BusinessException("项目不存在");
        return project;
    }

    private void validateLink(PmProjectLink link) {
        if (link == null || !StringUtils.hasText(link.getTitle())) throw new BusinessException("请填写链接名称");
        if (!REPOSITORY.equals(link.getType()) && !WEBSITE.equals(link.getType())) {
            throw new BusinessException("链接类型不正确");
        }
        try {
            URI uri = URI.create(link.getUrl());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException();
            }
        } catch (Exception e) {
            throw new BusinessException("请填写有效的 http(s) 地址");
        }
    }
}
