package com.kk.biz.service;

import com.kk.biz.config.TaskAiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class TaskAiTimeRangeNormalizer {
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 14:00到18:00 / 14：00-18：00，分钟可省略 */
    private static final Pattern CLOCK_RANGE = Pattern.compile(
            "(?<!\\d)([01]?\\d|2[0-3])(?:[:：]([0-5]\\d))?\\s*(?:到|至|~|～|-)\\s*([01]?\\d|2[0-3])(?:[:：]([0-5]\\d))?(?!\\d)");
    /** 下午2点到6点 / 下午两点到六点 / 2点到6点 */
    private static final Pattern CHINESE_CLOCK_RANGE = Pattern.compile(
            "(上午|下午|晚上|傍晚|中午)?"
                    + "([零一二两三四五六七八九十\\d]{1,3})点(半|[零一二三四五六七八九十\\d]{1,2}分?)?"
                    + "\\s*(?:到|至|~|～|-)\\s*"
                    + "(上午|下午|晚上|傍晚|中午)?"
                    + "([零一二两三四五六七八九十\\d]{1,3})点(半|[零一二三四五六七八九十\\d]{1,2}分?)?");
    private static final Pattern CLOCK_TO_NOW = Pattern.compile(
            "(?<!\\d)([01]?\\d|2[0-3])(?:[:：]([0-5]\\d)|点(半|[0-5]?\\d分?))?\\s*(?:到|至)现在");
    private static final Pattern CHINESE_CLOCK_TO_NOW = Pattern.compile(
            "(上午|下午|晚上|傍晚|中午)?"
                    + "([零一二两三四五六七八九十\\d]{1,3})点(半|[零一二三四五六七八九十\\d]{1,2}分?)?"
                    + "\\s*(?:到|至)现在");
    private static final Pattern PAST_HOURS = Pattern.compile("(?:过去|最近|刚才)([一二两三四五六七八九十\\d]+)个?小时(?:到现在)?");
    /** 一天内多时段锚点：上午/下午/晚上… */
    private static final Pattern DAY_PERIOD_ANCHOR = Pattern.compile(
            "(早上|早晨|一早|上午|中午|下午|傍晚|晚上)");
    private final TaskAiProperties properties;

    public record DaySegment(String period, String body, String start, String end, String label) {}

    /**
     * 将「上午做了A和B，下午做了C，晚上六点到九点半做了D」拆成多段，供多条记工分别赋时。
     * 工作日约定：上班 09:30，午休 12:00~13:30，下班 18:00。
     */
    public List<DaySegment> parseDaySegments(String expression, LocalDateTime now) {
        if (!StringUtils.hasText(expression)) return List.of();
        String text = expression.trim();
        Matcher anchors = DAY_PERIOD_ANCHOR.matcher(text);
        List<int[]> spans = new ArrayList<>();
        List<String> periods = new ArrayList<>();
        while (anchors.find()) {
            // 「下午两点」里的「下午」仍是时段锚点；同一钟点短语内不重复切
            periods.add(anchors.group(1));
            spans.add(new int[]{anchors.start(), anchors.end()});
        }
        if (spans.isEmpty()) return List.of();
        LocalDate day = text.contains("昨天") ? now.toLocalDate().minusDays(1) : now.toLocalDate();
        List<DaySegment> segments = new ArrayList<>();
        for (int i = 0; i < spans.size(); i++) {
            int bodyStart = spans.get(i)[0];
            int bodyEnd = i + 1 < spans.size() ? spans.get(i + 1)[0] : text.length();
            String body = text.substring(bodyStart, bodyEnd).trim();
            String period = periods.get(i);
            Map<String, String> range = resolveSegmentRange(body, period, day, now);
            if (range == null) continue;
            segments.add(new DaySegment(period, body, range.get("start"), range.get("end"), range.get("label")));
        }
        return segments;
    }

    private Map<String, String> resolveSegmentRange(String body, String period, LocalDate day, LocalDateTime now) {
        // 段内若有具体钟点，优先用钟点（如「晚上六点到九点半」）
        Optional<Map<String, String>> explicit = normalize(body, now);
        if (explicit.isPresent()) {
            String start = explicit.get().get("start");
            String end = explicit.get().get("end");
            // normalize(「下午」) 会得到整段下午；若 body 仅是时段词+事项且无钟点，用工作日默认表更稳
            boolean hasClock = CHINESE_CLOCK_RANGE.matcher(body.replaceAll("\\s+", "")).find()
                    || CLOCK_RANGE.matcher(body.replaceAll("\\s+", "")).find();
            if (hasClock) return explicit.get();
        }
        boolean toNow = body.contains("到现在") || body.contains("至今");
        return switch (canonicalizePeriod(period)) {
            case "上午" -> result(day.atTime(morningStart()), toNow ? now : day.atTime(morningEnd())).orElse(null);
            case "中午" -> result(day.atTime(12, 0), toNow ? now : day.atTime(13, 30)).orElse(null);
            case "下午" -> result(day.atTime(afternoonStart()), toNow ? now : day.atTime(afternoonEnd())).orElse(null);
            case "晚上" -> result(day.atTime(18, 0), toNow ? now : day.atTime(eveningEnd())).orElse(null);
            default -> explicit.orElse(null);
        };
    }

    private static String canonicalizePeriod(String period) {
        if (period == null) return "";
        return switch (period) {
            case "早上", "早晨", "一早", "上午" -> "上午";
            case "中午" -> "中午";
            case "下午" -> "下午";
            case "晚上", "傍晚" -> "晚上";
            default -> period;
        };
    }

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

        // 具体钟点区间优先于“下午/上午”整段默认，避免「下午两点到六点」被当成整个下午
        Matcher chineseRange = CHINESE_CLOCK_RANGE.matcher(text);
        if (chineseRange.find()) {
            String startPeriod = firstNonBlank(chineseRange.group(1), inferPeriodHint(text), chineseRange.group(4));
            String endPeriod = firstNonBlank(chineseRange.group(4), startPeriod, inferPeriodHint(text));
            LocalTime start = toClock(startPeriod, chineseNumber(chineseRange.group(2)), minute(chineseRange.group(3)));
            LocalTime end = toClock(endPeriod, chineseNumber(chineseRange.group(5)), minute(chineseRange.group(6)));
            if (start != null && end != null) return result(day.atTime(start), day.atTime(end));
        }

        Matcher range = CLOCK_RANGE.matcher(text);
        if (range.find()) {
            int sh = integer(range, 1);
            int sm = range.group(2) == null ? 0 : integer(range, 2);
            int eh = integer(range, 3);
            int em = range.group(4) == null ? 0 : integer(range, 4);
            // 「下午2:00到6:00」这类：小时 < 12 且带下午/晚上语境时按 12 小时制纠偏
            String hint = inferPeriodHint(text);
            if ("下午".equals(hint) || "晚上".equals(hint) || "傍晚".equals(hint)) {
                sh = adjustHourForPeriod(hint, sh);
                eh = adjustHourForPeriod(hint, eh);
            }
            return result(day.atTime(sh, sm), day.atTime(eh, em));
        }

        Matcher past = PAST_HOURS.matcher(text);
        if (past.find()) {
            int hours = chineseNumber(past.group(1));
            if (hours > 0) return result(now.minusHours(hours), now);
        }

        Matcher chineseNow = CHINESE_CLOCK_TO_NOW.matcher(text);
        if (chineseNow.find()) {
            String period = firstNonBlank(chineseNow.group(1), inferPeriodHint(text));
            LocalTime start = toClock(period, chineseNumber(chineseNow.group(2)), minute(chineseNow.group(3)));
            if (start != null) return result(day.atTime(start), now);
        }

        Matcher clockNow = CLOCK_TO_NOW.matcher(text);
        if (clockNow.find()) return result(day.atTime(integer(clockNow, 1), minute(clockNow.group(2), clockNow.group(3))), now);
        if (text.matches(".*(?:九点半|9点半)(?:到|至)现在.*")) return result(day.atTime(9, 30), now);

        boolean toNow = text.contains("到现在") || text.contains("至今");
        if (containsAny(text, "下午")) return result(day.atTime(afternoonStart()), toNow ? now : day.atTime(afternoonEnd()));
        if (containsAny(text, "中午")) return result(day.atTime(12, 0), toNow ? now : day.atTime(13, 30));
        if (containsAny(text, "晚上", "傍晚")) return result(day.atTime(18, 0), toNow ? now : day.atTime(eveningEnd()));
        if (text.contains("上班")) {
            LocalDateTime end = now.isBefore(day.atTime(morningStart())) ? day.atTime(morningEnd()) : now;
            return result(day.atTime(morningStart()), end);
        }
        if (containsAny(text, "早上", "早晨", "一早", "上午", "上班")) {
            return result(day.atTime(morningStart()), toNow ? now : day.atTime(morningEnd()));
        }
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

    private LocalTime toClock(String period, int hour, int minute) {
        if (hour < 0 || hour > 24 || minute < 0 || minute > 59) return null;
        int h = adjustHourForPeriod(period, hour);
        if (h == 24) h = 0;
        if (h < 0 || h > 23) return null;
        return LocalTime.of(h, minute);
    }

    private static int adjustHourForPeriod(String period, int hour) {
        if (period == null || period.isBlank()) return hour;
        return switch (period) {
            case "下午", "傍晚" -> hour < 12 ? hour + 12 : hour;
            case "晚上" -> hour < 12 ? hour + 12 : hour;
            case "中午" -> hour == 12 ? 12 : (hour < 12 ? hour + 12 : hour);
            case "上午" -> hour == 12 ? 0 : hour;
            default -> hour;
        };
    }

    private static String inferPeriodHint(String text) {
        if (text.contains("下午")) return "下午";
        if (text.contains("晚上") || text.contains("傍晚")) return "晚上";
        if (text.contains("中午")) return "中午";
        if (text.contains("上午") || text.contains("早上") || text.contains("早晨")) return "上午";
        return null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (StringUtils.hasText(value)) return value;
        }
        return null;
    }

    private LocalTime morningStart() { return LocalTime.parse(properties.getWorkTime().getMorningStart()); }
    private LocalTime morningEnd() { return LocalTime.parse(properties.getWorkTime().getMorningEnd()); }
    private LocalTime afternoonStart() { return LocalTime.parse(properties.getWorkTime().getAfternoonStart()); }
    private LocalTime afternoonEnd() { return LocalTime.parse(properties.getWorkTime().getAfternoonEnd()); }
    private LocalTime eveningEnd() { return LocalTime.parse(properties.getWorkTime().getEveningEnd()); }

    private static int integer(Matcher matcher, int group) { return Integer.parseInt(matcher.group(group)); }

    private static int minute(String token) {
        if (token == null || token.isBlank()) return 0;
        if ("半".equals(token)) return 30;
        return minute(null, token);
    }

    private static int minute(String numeric, String chinese) {
        if (numeric != null) return Integer.parseInt(numeric);
        if ("半".equals(chinese)) return 30;
        if (chinese == null || chinese.isBlank()) return 0;
        String raw = chinese.replace("分", "");
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return chineseNumber(raw);
        }
    }

    private static int chineseNumber(String value) {
        if (value == null || value.isBlank()) return -1;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
        }
        return switch (value) {
            case "零" -> 0;
            case "一" -> 1;
            case "二", "两" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            case "十" -> 10;
            case "十一" -> 11;
            case "十二" -> 12;
            case "十三" -> 13;
            case "十四" -> 14;
            case "十五" -> 15;
            case "十六" -> 16;
            case "十七" -> 17;
            case "十八" -> 18;
            case "十九" -> 19;
            case "二十" -> 20;
            case "二十一" -> 21;
            case "二十二" -> 22;
            case "二十三" -> 23;
            case "二十四" -> 24;
            default -> {
                if (value.startsWith("十") && value.length() == 2) {
                    yield 10 + chineseNumber(value.substring(1));
                }
                if (value.endsWith("十") && value.length() == 2) {
                    yield chineseNumber(value.substring(0, 1)) * 10;
                }
                yield -1;
            }
        };
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }
}
