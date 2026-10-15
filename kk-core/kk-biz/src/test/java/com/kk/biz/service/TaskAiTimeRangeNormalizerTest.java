package com.kk.biz.service;

import com.kk.biz.config.TaskAiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TaskAiTimeRangeNormalizerTest {
    private TaskAiTimeRangeNormalizer normalizer;
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 14, 11, 20);

    @BeforeEach void setUp() { normalizer = new TaskAiTimeRangeNormalizer(new TaskAiProperties()); }

    @Test void normalizesMorningDefaults() {
        assertRange("早上", "2026-10-14 09:30:00", "2026-10-14 12:00:00");
        assertRange("上午", "2026-10-14 09:30:00", "2026-10-14 12:00:00");
        assertRange("上班", "2026-10-14 09:30:00", "2026-10-14 11:20:00");
    }

    @Test void normalizesRangesEndingNow() {
        assertRange("早上九点半到现在", "2026-10-14 09:30:00", "2026-10-14 11:20:00");
        assertRange("9:30 到现在", "2026-10-14 09:30:00", "2026-10-14 11:20:00");
        assertRange("过去两小时到现在", "2026-10-14 09:20:00", "2026-10-14 11:20:00");
    }

    @Test void normalizesExplicitClockRange() {
        assertRange("今天 09:00 到 11:00", "2026-10-14 09:00:00", "2026-10-14 11:00:00");
        assertRange("今天下午两点到六点", "2026-10-14 14:00:00", "2026-10-14 18:00:00");
        assertRange("下午2点到6点", "2026-10-14 14:00:00", "2026-10-14 18:00:00");
        assertRange("下午两点到六点", "2026-10-14 14:00:00", "2026-10-14 18:00:00");
        assertRange("上午九点半到十一点", "2026-10-14 09:30:00", "2026-10-14 11:00:00");
        assertRange("14点到18点", "2026-10-14 14:00:00", "2026-10-14 18:00:00");
    }

    @Test void normalizesOtherDefaultPeriodsAndToday() {
        assertRange("中午", "2026-10-14 12:00:00", "2026-10-14 13:30:00");
        assertRange("下午", "2026-10-14 13:30:00", "2026-10-14 18:00:00");
        assertRange("晚上", "2026-10-14 18:00:00", "2026-10-14 21:00:00");
        assertRange("今天", "2026-10-14 09:30:00", "2026-10-14 11:20:00");
    }

    @Test void rejectsUnknownExpression() { assertThat(normalizer.normalize("有空的时候", now)).isEmpty(); }

    @Test void parsesMultiPeriodDaySegments() {
        String text = "帮我登记任务，我今天上午做了云控平台的联调工作和爱康官网的 bug 修复。"
                + "下午主要开发 ERP 的新功能，晚上六点到九点半重点在优化工单系统的 AI 助手";
        List<TaskAiTimeRangeNormalizer.DaySegment> segments = normalizer.parseDaySegments(text, now);
        assertThat(segments).hasSize(3);
        assertThat(segments.get(0).start()).isEqualTo("2026-10-14 09:30:00");
        assertThat(segments.get(0).end()).isEqualTo("2026-10-14 12:00:00");
        assertThat(segments.get(0).body()).contains("云控").contains("爱康");
        assertThat(segments.get(1).start()).isEqualTo("2026-10-14 13:30:00");
        assertThat(segments.get(1).end()).isEqualTo("2026-10-14 18:00:00");
        assertThat(segments.get(1).body()).containsIgnoringCase("ERP");
        assertThat(segments.get(2).start()).isEqualTo("2026-10-14 18:00:00");
        assertThat(segments.get(2).end()).isEqualTo("2026-10-14 21:30:00");
        assertThat(segments.get(2).body()).contains("工单");
    }

    private void assertRange(String expression, String start, String end) {
        Map<String, String> range = normalizer.normalize(expression, now).orElseThrow();
        assertThat(range).containsEntry("start", start).containsEntry("end", end);
    }
}
