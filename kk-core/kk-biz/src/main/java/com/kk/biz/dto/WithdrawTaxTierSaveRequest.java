package com.kk.biz.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
public class WithdrawTaxTierSaveRequest {
    private Long companyId;
    /** empty list clears company tiers → fall back to yml flat rate */
    private List<TierItem> tiers = new ArrayList<>();

    @Data
    public static class TierItem {
        private BigDecimal minAmount;
        private BigDecimal maxAmount;
        private BigDecimal taxRate;
        private Integer sort;
    }
}
