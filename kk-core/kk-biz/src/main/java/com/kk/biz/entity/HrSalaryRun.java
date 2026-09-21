package com.kk.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kk.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_salary_run")
public class HrSalaryRun extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long companyId;

    /**
     * 发薪周期键（库列 salary_month，避免 MySQL 保留字 year_month）：
     * 月结 yyyy-MM；周结 ISO 周 yyyy-Www
     */
    @TableField("salary_month")
    private String yearMonth;

    /** PREVIEW / PAY */
    private String phase;

    /** RUNNING / DONE / FAILED */
    private String status;

    /** CRON / MANUAL */
    private String triggerType;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private String message;

    @TableField(exist = false)
    private String companyName;
}
