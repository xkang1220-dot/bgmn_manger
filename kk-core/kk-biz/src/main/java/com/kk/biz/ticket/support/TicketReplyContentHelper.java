package com.kk.biz.ticket.support;

import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

public final class TicketReplyContentHelper {

    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern EMOJI = Pattern.compile(
            "[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}\\s]+");

    private TicketReplyContentHelper() {
    }

    public static String plainText(String html) {
        if (!StringUtils.hasText(html)) {
            return "";
        }
        return TAG.matcher(html)
                .replaceAll("")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .trim();
    }

    public static String detectContentType(String content) {
        String plain = plainText(content);
        if (!StringUtils.hasText(plain)) {
            return "html";
        }
        String withoutEmoji = EMOJI.matcher(plain).replaceAll("");
        return withoutEmoji.isEmpty() ? "emoji" : "html";
    }

    public static String preview(String content, int maxLen) {
        String plain = plainText(content);
        if (plain.length() <= maxLen) {
            return plain;
        }
        return plain.substring(0, maxLen) + "…";
    }
}
