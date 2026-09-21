package com.kk.biz.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
public class WithdrawTaxCalcResult {
    /** TIER | FLAT | VOUCHER（有凭证免税） */
    private String taxMode;
    /** flat default rate, or null when TIER */
    private BigDecimal taxRate;
    private BigDecimal gross;
    private BigDecimal tax;
    private BigDecimal net;
    private List<TierView> tiers = new ArrayList<>();
    private List<BreakdownItem> breakdown = new ArrayList<>();

    @Data
    public static class TierView {
        private BigDecimal minAmount;
        private BigDecimal maxAmount;
        private BigDecimal taxRate;
        private Integer sort;
    }

    @Data
    public static class BreakdownItem {
        private BigDecimal minAmount;
        private BigDecimal maxAmount;
        private BigDecimal taxRate;
        private BigDecimal taxableAmount;
        private BigDecimal tax;
    }
}
