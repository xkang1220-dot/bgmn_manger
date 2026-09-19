package com.kk.biz.ticket.dto;

import lombok.Data;

import java.util.List;

@Data
public class TicketAssignRequest {
    private List<Long> developerIds;
    private Long cycleId;
    private Boolean clearCycle;
    private String status;
}
