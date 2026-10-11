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

    /** 任务管理页：mine 按负责人/持有人；all 按参与项目，且项目负责人可看所负责项目全部任务。 */
    Page<PmTask> pageManagementTasks(long page, long pageSize, Long projectId, Integer status, String statuses,
                                     Integer priority, Long participantId, String title, Boolean overdue,
                                     String dashboardCategory, Long dashboardOwnerId,
                                     String dashboardFrom, String dashboardTo,
                                     String periodFrom, String periodTo, boolean taskTree, String scope);

    PmTask getDetail(Long id, String scope);

    /**
     * 任务统计。与列表共用项目/标题/优先级/参与人筛选；
     * 不含 status、overdue（这两项由统计卡片自身表达，避免点卡片后其它数变 0）。
     */
    Map<String, Object> summary(Long projectId, Integer priority, Long participantId, String title);

    /** 任务管理页统计，范围规则与 {@link #pageManagementTasks} 一致。 */
    Map<String, Object> managementSummary(Long projectId, Integer priority, Long participantId, String title,
                                          String periodFrom, String periodTo);

    Map<String, Object> managementDashboard(Long projectId, Integer priority, Long participantId, String title,
                                            String periodFrom, String periodTo);

    /** 看板用：按项目拉取任务（不分页，排除已关闭） */
    List<PmTask> listBoardTasks(Long projectId);

    /** 与用户相关的任务：我负责或我参与（与任务工作台个人列表口径一致） */
    List<PmTask> listRelatedTasks(Long userId);

    /** 与我相关任务分页，口径与 {@link #listRelatedTasks} 一致 */
    Page<PmTask> pageRelatedTasks(long page, long pageSize, Long projectId, Long userId);

    /** 个人中心优先事项分页：逾期 / 临期 / 长期未更新 / 未设截止日 / 待确认 */
    Page<PmTask> pagePriorityTasks(long page, long pageSize, Long projectId);

    void createTask(PmTask task);

    void updateTask(PmTask task);

    /** 看板拖拽：仅更新状态 */
    void updateStatus(Long id, Integer status);

    void updateStatus(Long id, Integer status, List<Long> imageFileIds);

    /** @param remark 关闭(status=3)时必填原因 */
    void updateStatus(Long id, Integer status, List<Long> imageFileIds, String remark);

    /** 任务管理员确认或驳回用户提交的完成申请。 */
    void reviewCompletion(Long id, boolean approved, String remark);

    /** 移交：将目标人设为任务持有人，并确保其在任务参与人中；负责人保持不变。 */
    void transfer(Long id, Long targetUserId, String remark);

    void transfer(Long id, Long targetUserId, String remark, List<Long> imageFileIds);

    void deleteTask(Long id);

    SysFile uploadImage(MultipartFile file);

    void deleteTaskImage(Long fileId);

    SysFile uploadCommentAttachment(MultipartFile file);

    void deleteCommentAttachment(Long fileId);

    List<PmTaskComment> listComments(Long taskId);

    PmTaskComment addComment(Long taskId, String content, List<Long> fileIds);

    List<PmTaskFlow> listFlows(Long taskId);
}
