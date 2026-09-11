package com.kk.biz.workflow;

import com.kk.common.exception.BusinessException;
import org.springframework.util.StringUtils;

/**
 * 项目规模：常规免审创建；重点/重大创建与改档需审批。
 */
public final class ProjectScales {

    public static final String NORMAL = "NORMAL";
    public static final String KEY = "KEY";
    public static final String MAJOR = "MAJOR";

    private ProjectScales() {
    }

    public static String normalize(String scale) {
        if (!StringUtils.hasText(scale)) {
            throw new BusinessException("请选择项目规模");
        }
        String v = scale.trim().toUpperCase();
        return switch (v) {
            case NORMAL, KEY, MAJOR -> v;
            default -> throw new BusinessException("不支持的项目规模：" + scale);
        };
    }

    /** 目标规模是否需要审批（创建或改到该档） */
    public static boolean needsApproval(String scale) {
        String v = normalize(scale);
        return KEY.equals(v) || MAJOR.equals(v);
    }

    public static String label(String scale) {
        if (scale == null) {
            return "—";
        }
        return switch (scale.trim().toUpperCase()) {
            case NORMAL -> "常规";
            case KEY -> "重点";
            case MAJOR -> "重大";
            default -> scale;
        };
    }
}
