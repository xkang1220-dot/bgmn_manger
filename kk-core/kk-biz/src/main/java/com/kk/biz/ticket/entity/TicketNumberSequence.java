package com.kk.biz.ticket.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

@Data
@TableName("ticket_number_sequence")
public class TicketNumberSequence {

    private Long companyId;

    private LocalDate sequenceDate;

    private Long currentValue;
}
