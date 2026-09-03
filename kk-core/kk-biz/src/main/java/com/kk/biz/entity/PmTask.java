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

    private Long projectId;

    private String title;

    private String content;

    /** 0待办 1进行中 2已完成 3已取消 */
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

    @TableField(exist = false)
    private String assigneeName;

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
