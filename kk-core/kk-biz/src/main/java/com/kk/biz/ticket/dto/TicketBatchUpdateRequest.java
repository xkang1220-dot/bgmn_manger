package com.kk.biz.ticket.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class TicketBatchUpdateRequest {
    private List<Long> ids;
    private String type;
    private String urgency;
    private String status;
    private LocalDate expectedCompleteDate;
    private Boolean clearExpectedCompleteDate;
}
