package com.kk.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kk.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fin_pool")
public class FinPool extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private BigDecimal balance;

    /** 是否默认资金池 0否 1是 */
    private Integer isDefault;

    private Integer status;

    private String remark;
}
