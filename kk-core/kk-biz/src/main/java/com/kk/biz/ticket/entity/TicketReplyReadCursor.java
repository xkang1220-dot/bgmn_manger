package com.kk.biz.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ticket_reply_read_cursor")
public class TicketReplyReadCursor {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ticketId;

    private Long userId;

    private Long lastReadMessageId;

    private LocalDateTime lastReadTime;

    private LocalDateTime updateTime;
}
