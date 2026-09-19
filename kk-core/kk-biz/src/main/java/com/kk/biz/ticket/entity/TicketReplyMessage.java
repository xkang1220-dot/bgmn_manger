package com.kk.biz.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kk.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ticket_reply_message")
public class TicketReplyMessage extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ticketId;

    /** submitter / handler */
    private String senderType;

    private Long senderId;

    private String senderName;

    private String content;

    /** html / emoji */
    private String contentType;
}
