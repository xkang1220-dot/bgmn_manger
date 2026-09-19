package com.kk.biz.ticket.service;

import com.kk.biz.ticket.entity.TicketDevCycle;

import java.util.List;

public interface TicketDevCycleService {

    List<TicketDevCycle> list(Long companyId);

    TicketDevCycle active(Long companyId);

    TicketDevCycle create(TicketDevCycle cycle);

    TicketDevCycle update(Long id, TicketDevCycle cycle);
}
