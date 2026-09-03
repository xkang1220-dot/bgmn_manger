package com.kk.biz.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kk.biz.entity.PmTask;
import com.kk.biz.entity.PmTaskComment;
import com.kk.biz.entity.PmTaskFlow;
import com.kk.biz.entity.SysFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

public interface PmTaskService extends IService<PmTask> {

    Page<PmTask> pageTasks(long page, long pageSize, Long projectId, Integer status, Integer priority,
                           Long assigneeId, String title, Boolean overdue);

    PmTask getDetail(Long id);

    Map<String, Object> summary(Long projectId);

    /** 看板用：按项目拉取任务（不分页，排除已取消） */
    List<PmTask> listBoardTasks(Long projectId);

    void createTask(PmTask task);

    void updateTask(PmTask task);

    /** 看板拖拽：仅更新状态 */
    void updateStatus(Long id, Integer status);

    void updateStatus(Long id, Integer status, List<Long> imageFileIds);

    /** 转交负责人 */
    void transfer(Long id, Long assigneeId, String remark);

    void transfer(Long id, Long assigneeId, String remark, List<Long> imageFileIds);

    void deleteTask(Long id);

    SysFile uploadImage(MultipartFile file);

    void deleteTaskImage(Long fileId);

    List<PmTaskComment> listComments(Long taskId);

    PmTaskComment addComment(Long taskId, String content);

    void deleteComment(Long commentId);

    List<PmTaskFlow> listFlows(Long taskId);
}
