package com.kk.biz.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ticket_progress_log")
public class TicketProgressLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ticketId;

    private Integer progress;

    private String status;

    private Long operatorId;

    private String remark;

    private LocalDateTime createTime;
}
