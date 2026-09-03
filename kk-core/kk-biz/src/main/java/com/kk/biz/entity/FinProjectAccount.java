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
@TableName("fin_project_account")
public class FinProjectAccount extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    /** 可用余额 */
    private BigDecimal balance;

    /** 累计预支 */
    private BigDecimal advanceAmount;

    /** 累计支出 */
    private BigDecimal expenseAmount;

    /** 累计分成 */
    private BigDecimal settleAmount;

    /** 约定预留 */
    private BigDecimal reserveAmount;

    /** 当前预留占用 */
    private BigDecimal reserveHeld;

    private Integer status;

    @TableField(exist = false)
    private String projectName;

    @TableField(exist = false)
    private String ownerName;
}
