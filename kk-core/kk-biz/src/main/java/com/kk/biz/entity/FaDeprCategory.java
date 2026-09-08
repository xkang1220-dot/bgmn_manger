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
@TableName("fa_depr_category")
public class FaDeprCategory extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long companyId;

    private String name;

    /** 折旧月数 */
    private Integer months;

    /** 残值率 0-1 */
    private BigDecimal residualRate;

    private Integer status;

    private String remark;

    @TableField(exist = false)
    private String companyName;
}
