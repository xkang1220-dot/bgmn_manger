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

    private Long companyId;

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

    @TableField(exist = false)
    private String companyName;

    /** NORMAL / KEY / MAJOR */
    @TableField(exist = false)
    private String scale;

    @TableField(exist = false)
    private Long parentId;

    /** 重大外壳：只读汇总，禁止动账 */
    @TableField(exist = false)
    private Boolean majorShell;

    /** 未删小项目数量 */
    @TableField(exist = false)
    private Integer childCount;

    /** 可转入用的公司资金池 ID（项目 pool 或公司默认池） */
    @TableField(exist = false)
    private Long companyPoolId;

    /** 可转入用的公司资金池名称 */
    @TableField(exist = false)
    private String companyPoolName;

    /** 可转入用的公司资金池余额（与「从公司转入」上限一致） */
    @TableField(exist = false)
    private BigDecimal companyPoolBalance;
}
