package com.kk.biz.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kk.biz.dto.WithdrawTaxCalcResult;
import com.kk.biz.dto.WithdrawTaxTierSaveRequest;
import com.kk.biz.entity.FinWalletWithdrawTaxTier;
import com.kk.biz.mapper.FinWalletWithdrawTaxTierMapper;
import com.kk.biz.service.WalletWithdrawTaxService;
import com.kk.common.exception.BusinessException;
import com.kk.system.service.DataScopeService;
import com.kk.system.service.SysDeptService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WalletWithdrawTaxServiceImpl implements WalletWithdrawTaxService {

    private final FinWalletWithdrawTaxTierMapper tierMapper;
    private final SysDeptService deptService;
    private final DataScopeService dataScopeService;

    @Value("${wallet.withdraw.tax-rate:0.2}")
    private BigDecimal walletWithdrawTaxRate;

    @Override
    public List<FinWalletWithdrawTaxTier> listByCompany(Long companyId) {
        assertCompanyVisible(companyId, false);
        return tierMapper.selectList(new LambdaQueryWrapper<FinWalletWithdrawTaxTier>()
                .eq(FinWalletWithdrawTaxTier::getCompanyId, companyId)
                .orderByAsc(FinWalletWithdrawTaxTier::getSort)
                .orderByAsc(FinWalletWithdrawTaxTier::getMinAmount)
                .orderByAsc(FinWalletWithdrawTaxTier::getId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveTiers(WithdrawTaxTierSaveRequest request) {
        if (request == null || request.getCompanyId() == null) {
            throw new BusinessException("请选择公司");
        }
        Long companyId = request.getCompanyId();
        assertCompanyVisible(companyId, true);

        List<WithdrawTaxTierSaveRequest.TierItem> raw =
                request.getTiers() == null ? List.of() : request.getTiers();
        List<FinWalletWithdrawTaxTier> normalized = normalizeAndValidate(raw);

        tierMapper.delete(new LambdaQueryWrapper<FinWalletWithdrawTaxTier>()
                .eq(FinWalletWithdrawTaxTier::getCompanyId, companyId));

        int sort = 0;
        for (FinWalletWithdrawTaxTier row : normalized) {
            row.setId(null);
            row.setCompanyId(companyId);
            row.setSort(sort++);
            row.setDeleted(0);
            tierMapper.insert(row);
        }
    }

    @Override
    public WithdrawTaxCalcResult calculate(Long companyId, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("请填写提现金额");
        }
        BigDecimal gross = amount.setScale(2, RoundingMode.HALF_UP);
        List<FinWalletWithdrawTaxTier> tiers = companyId == null
                ? List.of()
                : tierMapper.selectList(new LambdaQueryWrapper<FinWalletWithdrawTaxTier>()
                .eq(FinWalletWithdrawTaxTier::getCompanyId, companyId)
                .orderByAsc(FinWalletWithdrawTaxTier::getSort)
                .orderByAsc(FinWalletWithdrawTaxTier::getMinAmount)
                .orderByAsc(FinWalletWithdrawTaxTier::getId));

        WithdrawTaxCalcResult result = new WithdrawTaxCalcResult();
        result.setGross(gross);
        result.setTiers(toViews(tiers));

        if (tiers.isEmpty()) {
            BigDecimal rate = defaultFlatRate();
            BigDecimal tax = gross.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal net = gross.subtract(tax);
            result.setTaxMode("FLAT");
            result.setTaxRate(rate);
            result.setTax(tax);
            result.setNet(net);
            WithdrawTaxCalcResult.BreakdownItem one = new WithdrawTaxCalcResult.BreakdownItem();
            one.setMinAmount(BigDecimal.ZERO);
            one.setMaxAmount(null);
            one.setTaxRate(rate);
            one.setTaxableAmount(gross);
            one.setTax(tax);
            result.getBreakdown().add(one);
            return result;
        }

        BigDecimal taxSum = BigDecimal.ZERO;
        List<WithdrawTaxCalcResult.BreakdownItem> breakdown = new ArrayList<>();
        BigDecimal coveredUntil = BigDecimal.ZERO;
        for (FinWalletWithdrawTaxTier tier : tiers) {
            BigDecimal min = nz(tier.getMinAmount());
            BigDecimal max = tier.getMaxAmount();
            if (gross.compareTo(min) <= 0) {
                continue;
            }
            BigDecimal upper = max == null ? gross : gross.min(max);
            BigDecimal taxable = upper.subtract(min);
            if (taxable.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal rate = nz(tier.getTaxRate());
            BigDecimal sliceTax = taxable.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            taxSum = taxSum.add(sliceTax);
            coveredUntil = upper.max(coveredUntil);

            WithdrawTaxCalcResult.BreakdownItem item = new WithdrawTaxCalcResult.BreakdownItem();
            item.setMinAmount(min);
            item.setMaxAmount(max);
            item.setTaxRate(rate);
            item.setTaxableAmount(taxable.setScale(2, RoundingMode.HALF_UP));
            item.setTax(sliceTax);
            breakdown.add(item);
        }
        if (coveredUntil.compareTo(gross) < 0) {
            throw new BusinessException("提现阶梯未覆盖金额 ¥" + gross.toPlainString() + "，请将最高档设为无上限");
        }
        taxSum = taxSum.setScale(2, RoundingMode.HALF_UP);
        BigDecimal net = gross.subtract(taxSum);
        if (net.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("提现税额计算异常");
        }
        result.setTaxMode("TIER");
        result.setTaxRate(null);
        result.setTax(taxSum);
        result.setNet(net);
        result.setBreakdown(breakdown);
        return result;
    }

    private List<FinWalletWithdrawTaxTier> normalizeAndValidate(List<WithdrawTaxTierSaveRequest.TierItem> raw) {
        List<FinWalletWithdrawTaxTier> list = new ArrayList<>();
        for (WithdrawTaxTierSaveRequest.TierItem item : raw) {
            if (item == null) {
                continue;
            }
            BigDecimal min = item.getMinAmount() == null ? BigDecimal.ZERO : item.getMinAmount();
            BigDecimal max = item.getMaxAmount();
            BigDecimal rate = item.getTaxRate();
            if (rate == null) {
                throw new BusinessException("请填写税率");
            }
            if (min.compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException("档位下限不能为负");
            }
            if (max != null && max.compareTo(min) <= 0) {
                throw new BusinessException("档位上限必须大于下限");
            }
            if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
                throw new BusinessException("税率需在 0～1 之间（如 0.2 表示 20%）");
            }
            FinWalletWithdrawTaxTier row = new FinWalletWithdrawTaxTier();
            row.setMinAmount(min.setScale(2, RoundingMode.HALF_UP));
            row.setMaxAmount(max == null ? null : max.setScale(2, RoundingMode.HALF_UP));
            row.setTaxRate(rate.setScale(4, RoundingMode.HALF_UP));
            list.add(row);
        }
        list.sort(Comparator
                .comparing(FinWalletWithdrawTaxTier::getMinAmount)
                .thenComparing(t -> t.getMaxAmount() == null ? 1 : 0));

        if (list.isEmpty()) {
            return list;
        }
        if (list.get(0).getMinAmount().compareTo(BigDecimal.ZERO) != 0) {
            throw new BusinessException("第一档下限必须从 0 开始");
        }
        FinWalletWithdrawTaxTier lastTier = list.get(list.size() - 1);
        if (lastTier.getMaxAmount() != null) {
            throw new BusinessException("最后一档上限须留空（无上限），否则更高金额无法计税");
        }
        for (int i = 0; i < list.size(); i++) {
            FinWalletWithdrawTaxTier cur = list.get(i);
            boolean last = i == list.size() - 1;
            if (last) {
                continue;
            }
            if (cur.getMaxAmount() == null) {
                throw new BusinessException("仅最后一档可不设上限");
            }
            FinWalletWithdrawTaxTier next = list.get(i + 1);
            if (cur.getMaxAmount().compareTo(next.getMinAmount()) != 0) {
                throw new BusinessException("档位必须连续衔接（上一档上限 = 下一档下限）");
            }
        }
        return list;
    }

    private List<WithdrawTaxCalcResult.TierView> toViews(List<FinWalletWithdrawTaxTier> tiers) {
        List<WithdrawTaxCalcResult.TierView> views = new ArrayList<>();
        for (FinWalletWithdrawTaxTier t : tiers) {
            WithdrawTaxCalcResult.TierView v = new WithdrawTaxCalcResult.TierView();
            v.setMinAmount(t.getMinAmount());
            v.setMaxAmount(t.getMaxAmount());
            v.setTaxRate(t.getTaxRate());
            v.setSort(t.getSort());
            views.add(v);
        }
        return views;
    }

    private BigDecimal defaultFlatRate() {
        BigDecimal rate = walletWithdrawTaxRate == null ? new BigDecimal("0.2") : walletWithdrawTaxRate;
        if (rate.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("提现税率配置无效");
        }
        return rate;
    }

    private void assertCompanyVisible(Long companyId, boolean forEdit) {
        if (companyId == null) {
            throw new BusinessException("请选择公司");
        }
        boolean isCompany = deptService.listCompanies().stream()
                .anyMatch(d -> d.getId() != null && d.getId().longValue() == companyId.longValue());
        if (!isCompany) {
            throw new BusinessException("请选择有效的公司");
        }
        if (StpUtil.isLogin()) {
            Set<Long> visible = dataScopeService.visibleCompanyIds(StpUtil.getLoginIdAsLong());
            if (!visible.contains(companyId)) {
                throw new BusinessException(forEdit ? "无权配置该公司提现税率" : "无权查看该公司提现税率");
            }
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
