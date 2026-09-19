package com.kk.biz.ticket.support;

import com.kk.common.exception.BusinessException;
import org.springframework.util.StringUtils;

import java.util.Set;
import java.util.regex.Pattern;

public final class TicketValidation {

    private static final Set<String> TYPES = Set.of("bug", "requirement", "other");
    private static final Set<String> URGENCIES = Set.of("urgent", "high", "normal", "low");
    private static final Set<String> STATUSES = Set.of("pending", "in_progress", "testing", "completed", "closed");
    private static final Set<String> CYCLE_STATUSES = Set.of("planning", "active", "completed");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    private TicketValidation() {
    }

    public static void requireTitle(String title) {
        if (!StringUtils.hasText(title)) {
            throw new BusinessException(400, "标题不能为空");
        }
        String t = title.trim();
        if (t.length() < 2 || t.length() > 120) {
            throw new BusinessException(400, "标题长度需在 2~120 之间");
        }
    }

    public static String requireType(String type) {
        if (!StringUtils.hasText(type) || !TYPES.contains(type.trim())) {
            throw new BusinessException(400, "工单类型不合法");
        }
        return type.trim();
    }

    public static String requireUrgency(String urgency) {
        if (!StringUtils.hasText(urgency) || !URGENCIES.contains(urgency.trim())) {
            throw new BusinessException(400, "紧急程度不合法");
        }
        return urgency.trim();
    }

    public static String requireStatus(String status) {
        if (!StringUtils.hasText(status) || !STATUSES.contains(status.trim())) {
            throw new BusinessException(400, "工单状态不合法");
        }
        return status.trim();
    }

    public static String requireCycleStatus(String status) {
        if (!StringUtils.hasText(status) || !CYCLE_STATUSES.contains(status.trim())) {
            throw new BusinessException(400, "周期状态不合法");
        }
        return status.trim();
    }

    public static void requireDescription(String description) {
        if (!StringUtils.hasText(description)) {
            throw new BusinessException(400, "描述不能为空");
        }
        String plain = TAG.matcher(description).replaceAll("").replace("&nbsp;", " ").trim();
        boolean hasImg = description.toLowerCase().contains("<img");
        if (!StringUtils.hasText(plain) && !hasImg) {
            throw new BusinessException(400, "描述不能为空");
        }
    }

    public static boolean isCompletedStatus(String status) {
        return "completed".equals(status) || "closed".equals(status);
    }

    public static int clampProgress(Integer progress) {
        if (progress == null) {
            return 0;
        }
        return Math.max(0, Math.min(100, progress));
    }
}
