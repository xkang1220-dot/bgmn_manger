package com.kk.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kk.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("pm_company_task_share")
public class PmCompanyTaskShare extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long companyId;

    /** 外链 token；作废后为空 */
    private String token;

    /** 0作废 1有效 */
    private Integer status;

    /** 指定人员用户 ID，逗号分隔 */
    private String userIds;

    /** 发起人指定的开始日期 */
    private LocalDate dateFrom;

    /** 发起人指定的结束日期 */
    private LocalDate dateTo;
}
