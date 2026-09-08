package com.kk.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kk.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fa_asset_event")
public class FaAssetEvent extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long assetId;

    private Long companyId;

    /** CREATE / BORROW / RETURN / DEPR / UNFREEZE */
    private String eventType;

    private BigDecimal amount;

    private Long operatorId;

    private Long approvalId;

    private String remark;

    private LocalDateTime eventTime;
}
