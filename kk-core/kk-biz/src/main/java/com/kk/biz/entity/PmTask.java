package com.kk.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kk.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
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

    private String title;

    private String content;

    /** 0待办 1进行中 2已完成 3已关闭 */
    private Integer status;

    /** 1高 2中 3低 */
    private Integer priority;

    private Long assigneeId;

    private LocalDate startDate;

    private LocalDate dueDate;

    /** 完成进度 0-100 */
    private Integer progress;

    @TableField(exist = false)
    private String projectName;

    /** 兼容旧字段：持有人已停用，VO 恒为 null */
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

    /** 提交时携带的图片文件 ID */
    @TableField(exist = false)
    private List<Long> imageFileIds;

    /** 任务关联图片 */
    @TableField(exist = false)
    private List<SysFile> images;
}
