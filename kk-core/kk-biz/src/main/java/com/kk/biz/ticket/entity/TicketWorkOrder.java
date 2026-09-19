package com.kk.biz.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kk.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ticket_work_order")
public class TicketWorkOrder extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long companyId;

    /** 关联项目（可选） */
    private Long projectId;

    private String ticketNo;

    private String title;

    private String type;

    private String urgency;

    private String description;

    private String status;

    private Integer progress;

    private LocalDate expectedCompleteDate;

    private Long cycleId;

    private Long submitterId;

    private String submitterName;

    private LocalDateTime completedAt;
}
