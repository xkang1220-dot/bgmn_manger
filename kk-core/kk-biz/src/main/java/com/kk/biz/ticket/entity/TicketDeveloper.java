package com.kk.biz.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kk.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ticket_developer")
public class TicketDeveloper extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long companyId;

    private String name;

    private String role;

    /** 1启用 0禁用 */
    private Integer status;

    private Long sysUserId;
}
