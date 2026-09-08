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

    /** 1 启用 0 停用 */
    private Integer enabled;

    private String remark;

    @TableField(exist = false)
    private String userName;

    @TableField(exist = false)
    private String projectName;
}
