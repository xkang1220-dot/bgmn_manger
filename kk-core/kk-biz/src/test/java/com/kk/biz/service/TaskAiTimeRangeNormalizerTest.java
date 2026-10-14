package com.kk.biz.service;

import com.kk.biz.config.TaskAiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
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
    }

    @Test void normalizesOtherDefaultPeriodsAndToday() {
        assertRange("中午", "2026-10-14 12:00:00", "2026-10-14 13:30:00");
        assertRange("下午", "2026-10-14 13:30:00", "2026-10-14 18:00:00");
        assertRange("晚上", "2026-10-14 18:00:00", "2026-10-14 21:00:00");
        assertRange("今天", "2026-10-14 09:30:00", "2026-10-14 11:20:00");
    }

    @Test void rejectsUnknownExpression() { assertThat(normalizer.normalize("有空的时候", now)).isEmpty(); }

    private void assertRange(String expression, String start, String end) {
        Map<String, String> range = normalizer.normalize(expression, now).orElseThrow();
        assertThat(range).containsEntry("start", start).containsEntry("end", end);
    }
}
