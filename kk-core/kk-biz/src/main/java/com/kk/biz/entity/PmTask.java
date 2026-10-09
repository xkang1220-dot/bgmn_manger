package com.kk.biz.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kk.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("pm_task")
public class PmTask extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属公司（顶层部门 id） */
    private Long companyId;

    private Long projectId;

    /** 父任务 ID；为空表示项目内的顶层任务。 */
    private Long parentTaskId;

    private String title;

    private String content;

    /** 0待办 1进行中 2已完成 3已关闭 4待确认完成 */
    private Integer status;

    /** 1高 2中 3低 */
    private Integer priority;

    private Long assigneeId;

    /** 任务报酬；仅任务管理员可设置，返回时由服务层按权限及人员开关脱敏 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private BigDecimal taskReward;

    private LocalDate startDate;

    private LocalDate dueDate;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private LocalDateTime lastActivityAt;

    /** NORMAL / WARNING / DANGER */
    private String riskLevel;

    @TableField(exist = false)
    private String parentTaskTitle;

    @TableField(exist = false)
    private List<PmTask> children;

    @TableField(exist = false)
    private String projectName;

    /** 唯一主责人展示名 */
    @TableField(exist = false)
    private String assigneeName;

    /** 当前登录人是否可编辑（任务参与人 / 项目负责人 / 股东 control / 全局管理员） */
    @TableField(exist = false)
    private Boolean canEdit;

    /** 当前登录人是否可移交（参与人 / 项目负责人 / 股东 control / 全局管理员） */
    @TableField(exist = false)
    private Boolean canTransfer;

    /** 参与人员用户 ID */
    @TableField(exist = false)
    private List<Long> participantIds;

    /** 参与人员姓名（展示用） */
    @TableField(exist = false)
    private List<String> participantNames;

    @TableField(exist = false)
    private Boolean overdue;

    /** 个人中心优先事项：OVERDUE / DUE_SOON / STALE / NO_DUE_DATE / PENDING */
    @TableField(exist = false)
    private String riskType;

    /** 提交时携带的图片文件 ID */
    @TableField(exist = false)
    private List<Long> imageFileIds;

    /** 任务关联图片 */
    @TableField(exist = false)
    private List<SysFile> images;
}
