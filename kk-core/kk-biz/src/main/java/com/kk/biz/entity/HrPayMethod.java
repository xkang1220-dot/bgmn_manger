package com.kk.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kk.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_pay_method")
public class HrPayMethod extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long archiveId;

    /** BANK / ALIPAY / WECHAT */
    private String methodType;

    private String accountName;

    private String accountNo;

    private String bankName;

    /** 1 默认 */
    private Integer isDefault;

    private Integer sort;

    private String remark;

    @TableField(exist = false)
    private String methodTypeLabel;
}
