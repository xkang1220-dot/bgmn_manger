package com.kk.biz.util;

import net.sourceforge.pinyin4j.PinyinHelper;
import org.springframework.util.StringUtils;

/**
 * 公司名 → 项目编号前缀（拼音首字母大写）。
 */
public final class ProjectCodeUtil {

    private ProjectCodeUtil() {
    }

    public static String companyPrefix(String companyName) {
        if (!StringUtils.hasText(companyName)) {
            return "PRJ";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : companyName.trim().toCharArray()) {
            if (c >= 'A' && c <= 'Z') {
                sb.append(c);
                continue;
            }
            if (c >= 'a' && c <= 'z') {
                sb.append(Character.toUpperCase(c));
                continue;
            }
            if (Character.isDigit(c) || Character.isWhitespace(c)) {
                continue;
            }
            String[] arr = PinyinHelper.toHanyuPinyinStringArray(c);
            if (arr != null && arr.length > 0 && StringUtils.hasText(arr[0])) {
                sb.append(Character.toUpperCase(arr[0].charAt(0)));
            }
        }
        String prefix = sb.toString().replaceAll("[^A-Z]", "");
        return prefix.isEmpty() ? "PRJ" : prefix;
    }

    public static String formatCode(String prefix, int seq) {
        String p = StringUtils.hasText(prefix) ? prefix : "PRJ";
        if (seq < 1) {
            seq = 1;
        }
        if (seq <= 999) {
            return p + "-" + String.format("%03d", seq);
        }
        return p + "-" + seq;
    }
}
