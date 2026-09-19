package com.kk.biz.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ticket_work_order_assignee")
public class TicketWorkOrderAssignee {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ticketId;

    private Long developerId;

    private LocalDateTime createTime;
}
