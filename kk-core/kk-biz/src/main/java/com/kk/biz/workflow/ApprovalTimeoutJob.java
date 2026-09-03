package com.kk.biz.workflow;

import com.kk.biz.service.WfApprovalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalTimeoutJob {

    private final WfApprovalService approvalService;

    /** 每小时扫描一次超时自动通过 */
    @Scheduled(cron = "0 0 * * * ?")
    public void autoPass() {
        int count = approvalService.autoPassTimeout();
        if (count > 0) {
            log.info("审批超时自动通过 {} 单", count);
        }
    }
}
