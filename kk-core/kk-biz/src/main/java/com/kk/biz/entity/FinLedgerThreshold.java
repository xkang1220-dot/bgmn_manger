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
@TableName("fin_ledger_threshold")
public class FinLedgerThreshold extends BaseEntity {

    @TableId(type = IdType.INPUT)
    private Long companyId;

    /** 0 未启用（出账一律审批） 1 启用阈值分流 */
    private Integer enabled;

    private BigDecimal notifyThreshold;

    private BigDecimal approveThreshold;

    /** 是否已有库记录（占位默认值时为 false） */
    @TableField(exist = false)
    private Boolean configured;
}
