package com.kk.biz.workflow;

import com.kk.biz.service.HrHolidayCalendarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Year;

@Slf4j
@Component
@RequiredArgsConstructor
public class HolidayCalendarSyncJob {

    private final HrHolidayCalendarService holidayCalendarService;

    /** 每年 1 月 2 日 02:20 同步当年国务院节假日及调休数据。 */
    @Scheduled(cron = "0 20 2 2 1 ?", zone = "Asia/Shanghai")
    public void annual() {
        sync(Year.now().getValue());
    }

    /** 新部署首次启动时补齐当年数据，已有数据时不重复访问远端。 */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeCurrentYear() {
        int year = Year.now().getValue();
        if (!holidayCalendarService.hasYear(year)) {
            sync(year);
        }
    }

    private void sync(int year) {
        try {
            int count = holidayCalendarService.syncYear(year);
            log.info("{} 年中国节假日同步完成，共 {} 条", year, count);
        } catch (Exception e) {
            log.warn("{} 年中国节假日同步失败，继续使用本地日历数据：{}", year, e.getMessage());
        }
    }
}
