package com.kk.biz.service;

import com.kk.biz.config.TaskAiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class TaskAiTimeRangeNormalizer {
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern CLOCK_RANGE = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3])[:：]([0-5]\\d)\\s*(?:到|至|~|～|-)\\s*([01]?\\d|2[0-3])[:：]([0-5]\\d)(?!\\d)");
    private static final Pattern CLOCK_TO_NOW = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3])(?:[:：]([0-5]\\d)|点(半|[0-5]?\\d分?))?\\s*(?:到|至)现在");
    private static final Pattern PAST_HOURS = Pattern.compile("(?:过去|最近|刚才)([一二两三四五六七八九十\\d]+)个?小时(?:到现在)?");
    private final TaskAiProperties properties;

    public Optional<Map<String, String>> normalize(String expression, LocalDateTime now) {
        if (!StringUtils.hasText(expression)) return Optional.empty();
        String text = expression.trim().replaceAll("\\s+", "");
        var day = text.contains("昨天") ? now.toLocalDate().minusDays(1) : now.toLocalDate();
        if (text.contains("下个月") || text.contains("下月")) {
            var month = YearMonth.from(now).plusMonths(1);
            return result(month.atDay(1).atStartOfDay(), month.atEndOfMonth().atTime(23, 59, 59));
        }
        if (text.contains("本月") || text.contains("这个月")) {
            var month = YearMonth.from(now);
            return result(month.atDay(1).atStartOfDay(), month.atEndOfMonth().atTime(23, 59, 59));
        }
        Matcher range = CLOCK_RANGE.matcher(text);
        if (range.find()) return result(day.atTime(integer(range, 1), integer(range, 2)), day.atTime(integer(range, 3), integer(range, 4)));
        Matcher past = PAST_HOURS.matcher(text);
        if (past.find()) { int hours = chineseNumber(past.group(1)); if (hours > 0) return result(now.minusHours(hours), now); }
        Matcher clockNow = CLOCK_TO_NOW.matcher(text);
        if (clockNow.find()) return result(day.atTime(integer(clockNow, 1), minute(clockNow.group(2), clockNow.group(3))), now);
        if (text.matches(".*(?:九点半|9点半)(?:到|至)现在.*")) return result(day.atTime(9, 30), now);
        boolean toNow = text.contains("到现在") || text.contains("至今");
        if (containsAny(text, "下午")) return result(day.atTime(afternoonStart()), toNow ? now : day.atTime(afternoonEnd()));
        if (containsAny(text, "中午")) return result(day.atTime(12, 0), toNow ? now : day.atTime(13, 30));
        if (containsAny(text, "晚上")) return result(day.atTime(18, 0), toNow ? now : day.atTime(eveningEnd()));
        if (text.contains("上班")) {
            LocalDateTime end = now.isBefore(day.atTime(morningStart())) ? day.atTime(morningEnd()) : now;
            return result(day.atTime(morningStart()), end);
        }
        if (containsAny(text, "早上", "早晨", "一早", "上午", "上班")) return result(day.atTime(morningStart()), toNow ? now : day.atTime(morningEnd()));
        if (text.contains("今天") || text.contains("今日") || text.contains("昨天")) {
            LocalDateTime start = day.atTime(morningStart());
            LocalDateTime end = day.equals(now.toLocalDate()) && now.isBefore(day.atTime(afternoonEnd())) ? now : day.atTime(afternoonEnd());
            if (end.isBefore(start)) end = start;
            return result(start, end);
        }
        return Optional.empty();
    }

    public Map<String, String> defaultWorkToNow(LocalDateTime now) {
        LocalDateTime start = now.toLocalDate().atTime(morningStart());
        LocalDateTime end = now.isBefore(start) ? now.toLocalDate().atTime(morningEnd()) : now;
        return result(start, end).orElseThrow();
    }

    private Optional<Map<String, String>> result(LocalDateTime start, LocalDateTime end) {
        if (end.isBefore(start)) return Optional.empty();
        String label = start.toLocalDate().equals(end.toLocalDate())
                ? start.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " ~ " + end.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                : start.format(DT) + " ~ " + end.format(DT);
        return Optional.of(Map.of("start", start.format(DT), "end", end.format(DT), "label", label));
    }
    private LocalTime morningStart() { return LocalTime.parse(properties.getWorkTime().getMorningStart()); }
    private LocalTime morningEnd() { return LocalTime.parse(properties.getWorkTime().getMorningEnd()); }
    private LocalTime afternoonStart() { return LocalTime.parse(properties.getWorkTime().getAfternoonStart()); }
    private LocalTime afternoonEnd() { return LocalTime.parse(properties.getWorkTime().getAfternoonEnd()); }
    private LocalTime eveningEnd() { return LocalTime.parse(properties.getWorkTime().getEveningEnd()); }
    private static int integer(Matcher matcher, int group) { return Integer.parseInt(matcher.group(group)); }
    private static int minute(String numeric, String chinese) { if (numeric != null) return Integer.parseInt(numeric); if ("半".equals(chinese)) return 30; return chinese == null || chinese.isBlank() ? 0 : Integer.parseInt(chinese.replace("分", "")); }
    private static int chineseNumber(String value) { try { return Integer.parseInt(value); } catch (NumberFormatException ignored) {} return switch (value) { case "一" -> 1; case "二", "两" -> 2; case "三" -> 3; case "四" -> 4; case "五" -> 5; case "六" -> 6; case "七" -> 7; case "八" -> 8; case "九" -> 9; case "十" -> 10; default -> 0; }; }
    private static boolean containsAny(String value, String... candidates) { for (String candidate : candidates) if (value.contains(candidate)) return true; return false; }
}
