package com.kk.biz.ticket.service;

import com.kk.biz.ticket.entity.TicketDeveloper;

import java.util.List;
import java.util.Map;

public interface TicketDeveloperService {

    List<TicketDeveloper> list(Long companyId, Integer status);

    TicketDeveloper create(TicketDeveloper developer);

    TicketDeveloper update(Long id, Map<String, Object> body);

    void delete(Long id);

    void updateStatus(Long id, Integer status);
}
