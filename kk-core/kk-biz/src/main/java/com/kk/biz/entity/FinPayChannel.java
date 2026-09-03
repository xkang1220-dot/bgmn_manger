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
@TableName("fin_pay_channel")
public class FinPayChannel extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long poolId;

    /** ALIPAY / WECHAT / BANK / CASH / OTHER */
    private String channelType;

    private String name;

    private String accountNo;

    private String accountName;

    private String bankName;

    private BigDecimal balance;

    private Integer sort;

    private Integer status;

    private String remark;

    @TableField(exist = false)
    private String poolName;

    @TableField(exist = false)
    private String channelTypeLabel;
}
