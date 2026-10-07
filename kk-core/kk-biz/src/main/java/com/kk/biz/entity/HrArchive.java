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
@TableName("hr_archive")
public class HrArchive extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String realName;

    private String employeeNo;

    private String idCard;

    private LocalDate birthday;

    private LocalDate entryDate;

    private String position;

    private String education;

    private String address;

    private String emergencyContact;

    private String emergencyPhone;

    private String remark;

    /** 是否纳入考勤（0否 1是） */
    private Integer attendanceEnabled;

    /** 考勤周期日（1-31，表示每月几号） */
    private Integer attendanceCycleDay;

    /** 是否启用任务报酬（0否 1是） */
    private Integer taskRewardEnabled;

    @TableField(exist = false)
    private String username;

    @TableField(exist = false)
    private String nickname;

    @TableField(exist = false)
    private String deptName;

    @TableField(exist = false)
    private String phone;

    /** 个人收款方式（详情/保存） */
    @TableField(exist = false)
    private List<HrPayMethod> payMethods;
}
