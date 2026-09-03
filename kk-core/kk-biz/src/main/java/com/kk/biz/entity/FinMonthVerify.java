package com.kk.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kk.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fin_month_verify")
public class FinMonthVerify extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String verifyMonth;

    private Long channelId;

    private Long poolId;

    private BigDecimal systemBalance;

    private BigDecimal statementBalance;

    private BigDecimal diffAmount;

    private String status;

    private Long approvalId;

    private String remark;

    @TableField(exist = false)
    private String channelName;

    @TableField(exist = false)
    private String channelType;

    @TableField(exist = false)
    private List<SysFile> vouchers;
}
