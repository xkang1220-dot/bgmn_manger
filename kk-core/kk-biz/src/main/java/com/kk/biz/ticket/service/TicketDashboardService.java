package com.kk.biz.ticket.service;

import java.util.Map;

public interface TicketDashboardService {

    Map<String, Object> overview(Long companyId, Long cycleId, Long projectId);

    Map<String, Object> cycleSnapshot(Long companyId, int limit);
}
