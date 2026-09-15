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
@TableName("pm_project")
public class PmProject extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属公司（顶层部门 id） */
    private Long companyId;

    @TableField(exist = false)
    private String companyName;

    private String name;

    private String code;

    private Long ownerId;

    private Long poolId;

    private BigDecimal budget;

    private BigDecimal settledAmount;

    /** 预留金额 */
    private BigDecimal reserveAmount;

    /** 支出用途比例 % */
    private BigDecimal expensePercent;

    /** 预留用途比例 % */
    private BigDecimal reservePercent;

    /** 分成用途比例 % */
    private BigDecimal settlePercent;

    /** 0筹备 1进行中 2已完成 3已关闭 */
    private Integer status;

    /** NORMAL常规 / KEY重点 / MAJOR重大 */
    private String scale;

    /** 父项目 ID；非空表示重大项目下的小项目 */
    private Long parentId;

    @TableField(exist = false)
    private String parentName;

    /** 未删小项目数量（重大外壳详情用） */
    @TableField(exist = false)
    private Integer childCount;

    /** 0待审 1已生效 2已拒绝 */
    private Integer approveStatus;

    private LocalDate startDate;

    /** 预计结束时间 */
    private LocalDate endDate;

    /** 实际结束时间（手动填写） */
    private LocalDate actualEndDate;

    private String description;

    @TableField(exist = false)
    private String ownerName;

    @TableField(exist = false)
    private String poolName;

    /** 关联资金池当前余额（资金配置/预支用） */
    @TableField(exist = false)
    private BigDecimal poolBalance;

    @TableField(exist = false)
    private List<PmProjectMember> members;

    /** 项目参与人展示名（列表用） */
    @TableField(exist = false)
    private List<String> participantNames;
}
