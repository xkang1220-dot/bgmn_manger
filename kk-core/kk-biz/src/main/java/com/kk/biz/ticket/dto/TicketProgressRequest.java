package com.kk.biz.ticket.dto;

import lombok.Data;

@Data
public class TicketProgressRequest {
    private Integer progress;
    private String status;
}
