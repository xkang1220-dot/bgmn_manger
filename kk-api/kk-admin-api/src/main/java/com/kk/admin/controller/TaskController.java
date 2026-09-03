package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.kk.biz.entity.PmTask;
import com.kk.biz.entity.PmTaskComment;
import com.kk.biz.entity.PmTaskFlow;
import com.kk.biz.entity.SysFile;
import com.kk.biz.service.PmTaskService;
import com.kk.common.result.PageResult;
import com.kk.common.result.Result;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/task")
@RequiredArgsConstructor
public class TaskController {

    private final PmTaskService taskService;

    @GetMapping("/page")
    @SaCheckPermission("project:task:list")
    public Result<PageResult<PmTask>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize,
            Long projectId, Integer status, Integer priority, Long assigneeId, String title, Boolean overdue) {
        return Result.ok(PageResult.of(
                taskService.pageTasks(page, pageSize, projectId, status, priority, assigneeId, title, overdue)));
    }

    @GetMapping("/board")
    @SaCheckPermission("project:task:list")
    public Result<List<PmTask>> board(@RequestParam Long projectId) {
        return Result.ok(taskService.listBoardTasks(projectId));
    }

    @GetMapping("/summary")
    @SaCheckPermission("project:task:list")
    public Result<Map<String, Object>> summary(Long projectId) {
        return Result.ok(taskService.summary(projectId));
    }

    @GetMapping("/{id}")
    @SaCheckPermission("project:task:list")
    public Result<PmTask> detail(@PathVariable Long id) {
        return Result.ok(taskService.getDetail(id));
    }

    @PostMapping
    @SaCheckPermission("project:task:add")
    public Result<Void> create(@RequestBody PmTask task) {
        taskService.createTask(task);
        return Result.ok();
    }

    @PutMapping
    @SaCheckPermission("project:task:edit")
    public Result<Void> update(@RequestBody PmTask task) {
        taskService.updateTask(task);
        return Result.ok();
    }

    @PutMapping("/{id}/status")
    @SaCheckPermission("project:task:edit")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestBody StatusRequest request) {
        taskService.updateStatus(id, request.getStatus(), request.getImageFileIds());
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @SaCheckPermission("project:task:remove")
    public Result<Void> delete(@PathVariable Long id) {
        taskService.deleteTask(id);
        return Result.ok();
    }

    @PostMapping("/image")
    @SaCheckPermission(value = {"project:task:add", "project:task:edit"}, mode = SaMode.OR)
    public Result<SysFile> uploadImage(@RequestParam("file") MultipartFile file) {
        return Result.ok(taskService.uploadImage(file));
    }

    @DeleteMapping("/image/{fileId}")
    @SaCheckPermission(value = {"project:task:add", "project:task:edit"}, mode = SaMode.OR)
    public Result<Void> deleteImage(@PathVariable Long fileId) {
        taskService.deleteTaskImage(fileId);
        return Result.ok();
    }

    @GetMapping("/{id}/comments")
    @SaCheckPermission("project:task:list")
    public Result<List<PmTaskComment>> comments(@PathVariable Long id) {
        return Result.ok(taskService.listComments(id));
    }

    @PostMapping("/{id}/comments")
    @SaCheckPermission(value = {"project:task:add", "project:task:edit"}, mode = SaMode.OR)
    public Result<PmTaskComment> addComment(@PathVariable Long id, @RequestBody CommentRequest request) {
        return Result.ok(taskService.addComment(id, request.getContent()));
    }

    @DeleteMapping("/comment/{commentId}")
    @SaCheckPermission(value = {"project:task:add", "project:task:edit"}, mode = SaMode.OR)
    public Result<Void> deleteComment(@PathVariable Long commentId) {
        taskService.deleteComment(commentId);
        return Result.ok();
    }

    @GetMapping("/{id}/flows")
    @SaCheckPermission("project:task:list")
    public Result<List<PmTaskFlow>> flows(@PathVariable Long id) {
        return Result.ok(taskService.listFlows(id));
    }

    @PutMapping("/{id}/transfer")
    @SaCheckPermission("project:task:edit")
    public Result<Void> transfer(@PathVariable Long id, @RequestBody TransferRequest request) {
        taskService.transfer(id, request.getAssigneeId(), request.getRemark(), request.getImageFileIds());
        return Result.ok();
    }

    @Data
    public static class StatusRequest {
        private Integer status;
        private List<Long> imageFileIds;
    }

    @Data
    public static class CommentRequest {
        private String content;
    }

    @Data
    public static class TransferRequest {
        private Long assigneeId;
        private String remark;
        private List<Long> imageFileIds;
    }
}
