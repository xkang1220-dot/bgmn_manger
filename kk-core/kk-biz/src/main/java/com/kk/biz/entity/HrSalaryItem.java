package com.kk.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kk.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_salary_item")
public class HrSalaryItem extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long companyId;

    private Long userId;

    private Long projectId;

    private BigDecimal amount;

    /** 员工独立发薪日，同时作为考勤核算周期边界（1-28） */
    private Integer payDay;

    /** MONTHLY 固定月薪 / DAILY 按天计薪 */
    private String payMode;

    /** 是否按考勤核算 */
    private Integer attendanceEnabled;

    /** 固定月薪：平常上班（含值班）占比 */
    private BigDecimal normalRatio;

    /** 固定月薪：周末及节假日占比 */
    private BigDecimal restRatio;

    /** 固定月薪：平常上班（含值班）日工资系数 */
    private BigDecimal normalCoefficient;

    /** 固定月薪：周末及节假日日工资系数 */
    private BigDecimal restCoefficient;

    /** 按天计薪：平常上班（含值班）日薪 */
    private BigDecimal normalDayRate;

    /** 按天计薪：周末及节假日日薪 */
    private BigDecimal restDayRate;

    /** MONTHLY 月薪 / WEEKLY 周薪 */
    private String cycleType;

    /** 1 启用 0 停用 */
    private Integer enabled;

    private String remark;

    @TableField(exist = false)
    private String userName;

    @TableField(exist = false)
    private String projectName;
}
