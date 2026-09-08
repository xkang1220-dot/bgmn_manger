package com.kk.biz.workflow;

import com.kk.biz.service.HrSalaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SalaryPayrollJob {

    private final HrSalaryService salaryService;

    /** 每小时整点扫描发薪日程（预告 / 发薪） */
    @Scheduled(cron = "0 0 * * * ?")
    public void scan() {
        try {
            salaryService.scanSchedules();
        } catch (Exception e) {
            log.error("月度工资定时扫描失败: {}", e.getMessage());
        }
    }
}
