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
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wf_approval")
public class WfApproval extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String bizNo;

    /** PROJECT_CREATE / PROJECT_DELETE / REIMBURSE_PERSONAL / REIMBURSE_PROJECT /
     *  PROJECT_ADVANCE / SHARE_CONFIG / PROJECT_SETTLE / SALARY_APPLY / RESERVE_RETURN / ROLLBACK */
    private String type;

    private String title;

    /** PENDING / APPROVED / REJECTED / WITHDRAWN / TIMEOUT_PASS / ROLLING / ROLLED */
    private String status;

    private Long applicantId;

    private BigDecimal amount;

    private Long projectId;

    private Long poolId;

    private String payload;

    private String receiptFileIds;

    /** 0无需 1待财务回执 2待申请人确认 3已确认 */
    private Integer confirmStatus;

    private LocalDateTime timeoutAt;

    private Integer autoPass;

    /** 提交时快照：ALL 会签 / ANY 或签 */
    private String passMode;

    private LocalDateTime passTime;

    private String remark;

    @TableField(exist = false)
    private String applicantName;

    @TableField(exist = false)
    private String projectName;

    @TableField(exist = false)
    private String typeLabel;

    @TableField(exist = false)
    private String statusLabel;

    @TableField(exist = false)
    private List<WfApprovalTask> tasks;

    @TableField(exist = false)
    private List<WfApprovalLog> logs;

    @TableField(exist = false)
    private Boolean canHandle;

    @TableField(exist = false)
    private Boolean canWithdraw;

    @TableField(exist = false)
    private Boolean canConfirm;

    @TableField(exist = false)
    private Boolean canUploadReceipt;

    @TableField(exist = false)
    private Boolean canRollback;

    @TableField(exist = false)
    private Map<String, Object> payloadData;

    /** 申请人上传的发票/凭证 */
    @TableField(exist = false)
    private List<SysFile> voucherFiles;

    /** 财务上传的回执 */
    @TableField(exist = false)
    private List<SysFile> receiptFiles;
}
