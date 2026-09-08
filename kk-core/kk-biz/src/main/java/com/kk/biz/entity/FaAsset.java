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
@TableName("fa_asset")
public class FaAsset extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long companyId;

    private Long categoryId;

    private String assetCode;

    private String name;

    private BigDecimal originalValue;

    private BigDecimal residualValue;

    private Integer deprMonths;

    private BigDecimal netValue;

    private BigDecimal accumDepr;

    private LocalDate purchaseDate;

    private LocalDate deprStartDate;

    /** 上次计提年月 yyyy-MM */
    private String lastDeprYm;

    /** IN_STOCK / IN_USE */
    private String status;

    private Long holderUserId;

    private BigDecimal frozenAmount;

    /** 1=仍占用钱包冻结 */
    private Integer lockFreeze;

    private String remark;

    @TableField(exist = false)
    private String companyName;

    @TableField(exist = false)
    private String categoryName;

    @TableField(exist = false)
    private String holderName;

    @TableField(exist = false)
    private List<FaAssetEvent> events;
}
