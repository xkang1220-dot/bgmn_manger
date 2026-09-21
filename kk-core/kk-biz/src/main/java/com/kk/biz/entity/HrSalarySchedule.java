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

    /** 兼容字段；实际月结+周结双开，固定 BOTH */
    private String cycleType;

    /** 月结：每月第几天 1–28 */
    private Integer payDay;

    /** 周结：周一=1 … 周日=7 */
    private Integer weeklyPayDay;

    private Integer payHour;

    private Integer payMinute;

    /** 月结预告提前天 1–27 */
    private Integer previewDays;

    /** 周结确认提前天 1–6，默认 1 */
    private Integer weeklyPreviewDays;

    private Integer enabled;

    /** 1=启用定时预告；0=关闭（保留手动「发薪确认」） */
    private Integer previewEnabled;

    private Long submitterUserId;

    @TableField(exist = false)
    private String companyName;

    @TableField(exist = false)
    private String submitterName;
}
