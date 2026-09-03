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
@TableName("hr_wallet")
public class HrWallet extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private BigDecimal balance;

    private BigDecimal frozen;

    private Integer status;

    private String remark;

    @TableField(exist = false)
    private String realName;

    @TableField(exist = false)
    private String nickname;

    @TableField(exist = false)
    private String username;
}
