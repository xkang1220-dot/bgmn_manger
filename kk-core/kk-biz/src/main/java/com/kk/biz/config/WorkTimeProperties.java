package com.kk.biz.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalTime;

@Data
@Component
@ConfigurationProperties(prefix = "kk.work-time")
public class WorkTimeProperties {
    private LocalTime start = LocalTime.of(9, 0);
    private LocalTime end = LocalTime.of(18, 0);
}
