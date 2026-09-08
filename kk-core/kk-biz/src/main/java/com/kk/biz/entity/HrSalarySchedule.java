package com.kk.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kk.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_salary_schedule")
public class HrSalarySchedule extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long companyId;

    private Integer payDay;

    private Integer payHour;

    private Integer payMinute;

    private Integer previewDays;

    private Integer enabled;

    private Long submitterUserId;

    @TableField(exist = false)
    private String companyName;

    @TableField(exist = false)
    private String submitterName;
}
