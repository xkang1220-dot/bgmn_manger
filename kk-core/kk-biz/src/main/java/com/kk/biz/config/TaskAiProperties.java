package com.kk.biz.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "ai")
public class TaskAiProperties {
    private String baseUrl = "https://api.deepseek.com";
    private String model = "deepseek-chat";
    private String apiKey;
    private int maxToolCalls = 6;
    private boolean toolTraceEnabled = false;
    private WorkTime workTime = new WorkTime();

    @Data
    public static class WorkTime {
        private String morningStart = "09:30";
        private String morningEnd = "12:00";
        private String afternoonStart = "13:30";
        private String afternoonEnd = "18:00";
        private String eveningEnd = "21:00";
    }
}
