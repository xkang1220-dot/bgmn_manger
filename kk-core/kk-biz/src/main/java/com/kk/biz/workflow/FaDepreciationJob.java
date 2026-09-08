package com.kk.biz.workflow;

import com.kk.biz.service.FaAssetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FaDepreciationJob {

    private final FaAssetService assetService;

    /** 每月 1 日 01:10 计提折旧 */
    @Scheduled(cron = "0 10 1 1 * ?")
    public void monthly() {
        int count = assetService.runMonthlyDepreciation();
        if (count > 0) {
            log.info("固定资产月度折旧完成，处理 {} 条", count);
        }
    }
}
