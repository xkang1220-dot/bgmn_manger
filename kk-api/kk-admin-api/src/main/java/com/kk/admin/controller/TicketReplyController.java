package com.kk.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.kk.biz.ticket.dto.TicketMarkReadRequest;
import com.kk.biz.ticket.dto.TicketReplyRequest;
import com.kk.biz.ticket.dto.TicketUnreadSummaryRequest;
import com.kk.biz.ticket.service.TicketReplyService;
import com.kk.biz.ticket.vo.TicketReplyMessageVO;
import com.kk.biz.ticket.vo.TicketReplySummaryVO;
import com.kk.common.result.PageResult;
import com.kk.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ticket/order")
@RequiredArgsConstructor
@SaCheckLogin
public class TicketReplyController {

    private final TicketReplyService replyService;

    @GetMapping("/{ticketId:\\d+}/reply/page")
    public Result<PageResult<TicketReplyMessageVO>> page(@PathVariable Long ticketId,
                                                         @RequestParam(defaultValue = "1") long page,
                                                         @RequestParam(defaultValue = "50") long pageSize,
                                                         @RequestParam(defaultValue = "asc") String order) {
        return Result.ok(PageResult.of(replyService.pageReplies(ticketId, page, pageSize, order)));
    }

    @PostMapping("/{ticketId:\\d+}/reply")
    public Result<TicketReplyMessageVO> reply(@PathVariable Long ticketId, @RequestBody TicketReplyRequest request) {
        return Result.ok(replyService.reply(ticketId, request));
    }

    @PutMapping("/{ticketId:\\d+}/reply/read")
    public Result<Void> markRead(@PathVariable Long ticketId, @RequestBody(required = false) TicketMarkReadRequest request) {
        replyService.markRead(ticketId, request);
        return Result.ok();
    }

    @PostMapping("/reply/unread-summary")
    public Result<Map<Long, TicketReplySummaryVO>> unreadSummary(@RequestBody TicketUnreadSummaryRequest request) {
        return Result.ok(replyService.unreadSummary(request == null ? null : request.getTicketIds()));
    }

    @GetMapping("/reply/unread-count")
    public Result<Map<String, Long>> unreadCount(@RequestParam(defaultValue = "my") String scope) {
        return Result.ok(replyService.unreadCount(scope));
    }
}
