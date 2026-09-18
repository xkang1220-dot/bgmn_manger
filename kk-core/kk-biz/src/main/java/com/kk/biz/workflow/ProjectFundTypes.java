package com.kk.biz.workflow;

import com.kk.common.exception.BusinessException;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

/**
 * 项目账款资金池类型：待分成 / 非分成
 */
public final class ProjectFundTypes {

    private ProjectFundTypes() {
    }

    /** 待分成资金（默认支出、月末分层基数） */
    public static final String SHARE_PENDING = "SHARE_PENDING";
    /** 非分成资金 */
    public static final String NON_SHARE = "NON_SHARE";

    public static String normalize(String fundType) {
        if (!StringUtils.hasText(fundType) || "null".equalsIgnoreCase(fundType.trim())) {
            return SHARE_PENDING;
        }
        String t = fundType.trim().toUpperCase();
        if (SHARE_PENDING.equals(t) || NON_SHARE.equals(t)) {
            return t;
        }
        throw new BusinessException("资金类型仅支持待分成(SHARE_PENDING)或非分成(NON_SHARE)");
    }

    public static String require(String fundType) {
        if (!StringUtils.hasText(fundType) || "null".equalsIgnoreCase(fundType.trim())) {
            throw new BusinessException("请选择资金类型（待分成 / 非分成）");
        }
        return normalize(fundType);
    }

    public static String label(String fundType) {
        if (NON_SHARE.equals(fundType)) {
            return "非分成资金";
        }
        return "待分成资金";
    }

    /**
     * 支出未指定资金池时：待分成够就待分成，否则非分成；两池都不够则失败（不跨池拆扣）。
     */
    public static String pickExpensePool(BigDecimal sharePending, BigDecimal nonShare, BigDecimal amount) {
        BigDecimal need = amount == null ? BigDecimal.ZERO : amount;
        BigDecimal pending = sharePending == null ? BigDecimal.ZERO : sharePending;
        BigDecimal other = nonShare == null ? BigDecimal.ZERO : nonShare;
        if (pending.compareTo(need) >= 0) {
            return SHARE_PENDING;
        }
        if (other.compareTo(need) >= 0) {
            return NON_SHARE;
        }
        throw new BusinessException("项目资金不足：待分成 ¥" + pending.toPlainString()
                + "，非分成 ¥" + other.toPlainString()
                + "，需要 ¥" + need.toPlainString()
                + "（不可跨池拆扣）");
    }
}
