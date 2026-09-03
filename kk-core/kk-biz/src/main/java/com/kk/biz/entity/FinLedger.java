package com.kk.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kk.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fin_ledger")
public class FinLedger extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 唯一业务编号 */
    private String bizNo;

    /** INCOME / EXPENSE / TRANSFER / SETTLE / ADVANCE / RESERVE / SALARY / REIMBURSE / ROLLBACK */
    private String bizType;

    /** POOL(=公司) / PROJECT / WALLET(=个人) */
    private String accountType;

    private Long poolId;

    private Long channelId;

    private Long userId;

    private BigDecimal amount;

    /** 入账总额（手续费前） */
    private BigDecimal grossAmount;

    /** 手续费金额 */
    private BigDecimal feeAmount;

    /** FIXED / PERCENT */
    private String feeMode;

    private BigDecimal beforeBalance;

    private BigDecimal afterBalance;

    private Long projectId;

    private Long relatedId;

    private Long approvalId;

    private String title;

    private String remark;

    private LocalDateTime occurTime;

    @TableField(exist = false)
    private String userName;

    @TableField(exist = false)
    private String poolName;

    @TableField(exist = false)
    private String projectName;

    @TableField(exist = false)
    private String channelName;

    @TableField(exist = false)
    private String channelType;

    @TableField(exist = false)
    private List<SysFile> vouchers;
}
