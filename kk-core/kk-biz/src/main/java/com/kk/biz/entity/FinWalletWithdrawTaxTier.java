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
@TableName("fin_wallet_withdraw_tax_tier")
public class FinWalletWithdrawTaxTier extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long companyId;

    /** inclusive */
    private BigDecimal minAmount;

    /** exclusive; null = no upper bound */
    private BigDecimal maxAmount;

    private BigDecimal taxRate;

    private Integer sort;
}
