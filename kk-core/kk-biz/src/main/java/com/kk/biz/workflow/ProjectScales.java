package com.kk.biz.workflow;

import com.kk.biz.entity.PmProject;
import com.kk.common.exception.BusinessException;
import org.springframework.util.StringUtils;

/**
 * 项目规模：常规免审创建；重点/重大创建与改到该档需审批。
 * 重大外壳（parentId 空 + MAJOR）只汇总，不可动账/配薪；小项目固定 KEY。
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

    /** 重大外壳：顶层 MAJOR，不可动账 / 不可配薪 / 不可挂任务 */
    public static boolean isMajorShell(PmProject project) {
        if (project == null) {
            return false;
        }
        if (project.getParentId() != null) {
            return false;
        }
        String scale = StringUtils.hasText(project.getScale()) ? project.getScale().trim().toUpperCase() : NORMAL;
        return MAJOR.equals(scale);
    }

    /** 可进入财务账款域（重点，或重大外壳/小项目） */
    public static boolean isFinanceVisible(String scale) {
        if (!StringUtils.hasText(scale)) {
            return false;
        }
        String v = scale.trim().toUpperCase();
        return KEY.equals(v) || MAJOR.equals(v);
    }

    /**
     * 可配薪 / 参与发薪：重点（含重大下的小项目）；排除常规与重大外壳。
     */
    public static boolean isSalaryEligible(PmProject project) {
        if (project == null || isMajorShell(project)) {
            return false;
        }
        String scale = StringUtils.hasText(project.getScale()) ? project.getScale().trim().toUpperCase() : NORMAL;
        return KEY.equals(scale);
    }
}
