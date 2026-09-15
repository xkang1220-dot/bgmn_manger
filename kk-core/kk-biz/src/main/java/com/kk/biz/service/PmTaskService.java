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

    Page<PmTask> pageTasks(long page, long pageSize, Long projectId, Integer status, String statuses,
                           Integer priority, Long participantId, String title, Boolean overdue);

    PmTask getDetail(Long id);

    /**
     * 任务统计。与列表共用项目/标题/优先级/参与人筛选；
     * 不含 status、overdue（这两项由统计卡片自身表达，避免点卡片后其它数变 0）。
     */
    Map<String, Object> summary(Long projectId, Integer priority, Long participantId, String title);

    /** 看板用：按项目拉取任务（不分页，排除已关闭） */
    List<PmTask> listBoardTasks(Long projectId);

    /** 与用户相关的任务：参与或自己创建 */
    List<PmTask> listRelatedTasks(Long userId);

    void createTask(PmTask task);

    void updateTask(PmTask task);

    /** 看板拖拽：仅更新状态 */
    void updateStatus(Long id, Integer status);

    void updateStatus(Long id, Integer status, List<Long> imageFileIds);

    /** @param remark 关闭(status=3)时必填原因 */
    void updateStatus(Long id, Integer status, List<Long> imageFileIds, String remark);

    /** 移交：将目标人加入参与人（已存在则不重复）；不写持有人字段 */
    void transfer(Long id, Long targetUserId, String remark);

    void transfer(Long id, Long targetUserId, String remark, List<Long> imageFileIds);

    void deleteTask(Long id);

    SysFile uploadImage(MultipartFile file);

    void deleteTaskImage(Long fileId);

    List<PmTaskComment> listComments(Long taskId);

    PmTaskComment addComment(Long taskId, String content);

    void deleteComment(Long commentId);

    List<PmTaskFlow> listFlows(Long taskId);
}
