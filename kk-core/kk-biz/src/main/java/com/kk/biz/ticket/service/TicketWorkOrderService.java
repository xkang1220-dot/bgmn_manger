package com.kk.biz.ticket.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kk.biz.ticket.dto.TicketAssignRequest;
import com.kk.biz.ticket.dto.TicketBatchUpdateRequest;
import com.kk.biz.ticket.dto.TicketCreateRequest;
import com.kk.biz.ticket.dto.TicketProgressRequest;
import com.kk.biz.ticket.dto.TicketUpdateRequest;
import com.kk.biz.ticket.vo.TicketVO;

import java.util.List;
import java.util.Map;

public interface TicketWorkOrderService {

    TicketVO create(TicketCreateRequest request);

    Page<TicketVO> page(long page, long pageSize, Long companyId, String title, String type,
                        String status, String urgency, Long developerId, Long cycleId,
                        Long submitterId, Long projectId);

    TicketVO detail(Long id);

    TicketVO update(Long id, TicketUpdateRequest request);

    void batchUpdate(TicketBatchUpdateRequest request);

    void delete(Long id);

    void batchDelete(List<Long> ids);

    Map<String, Object> mySubmissions(long page, long pageSize, Long companyId, Long projectId);

    TicketVO updateProgress(Long id, TicketProgressRequest request);

    TicketVO assign(Long id, TicketAssignRequest request);
}
