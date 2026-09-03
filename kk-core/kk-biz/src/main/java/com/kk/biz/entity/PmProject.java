package com.kk.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kk.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("pm_project")
public class PmProject extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String code;

    private Long ownerId;

    private Long poolId;

    private BigDecimal budget;

    private BigDecimal settledAmount;

    /** 预留金额 */
    private BigDecimal reserveAmount;

    /** 支出用途比例 % */
    private BigDecimal expensePercent;

    /** 预留用途比例 % */
    private BigDecimal reservePercent;

    /** 分成用途比例 % */
    private BigDecimal settlePercent;

    /** 0筹备 1进行中 2已完成 3已关闭 */
    private Integer status;

    /** 0待审 1已生效 2已拒绝 */
    private Integer approveStatus;

    private LocalDate startDate;

    private LocalDate endDate;

    private String description;

    @TableField(exist = false)
    private String ownerName;

    @TableField(exist = false)
    private String poolName;

    @TableField(exist = false)
    private List<PmProjectMember> members;
}
