package com.kk.biz.ticket.dto;

import lombok.Data;

import java.util.List;

@Data
public class TicketUnreadSummaryRequest {
    private List<Long> ticketIds;
}
