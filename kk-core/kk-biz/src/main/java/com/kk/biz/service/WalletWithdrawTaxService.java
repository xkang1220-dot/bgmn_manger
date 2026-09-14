package com.kk.biz.service;

import com.kk.biz.dto.WithdrawTaxCalcResult;
import com.kk.biz.dto.WithdrawTaxTierSaveRequest;
import com.kk.biz.entity.FinWalletWithdrawTaxTier;

import java.math.BigDecimal;
import java.util.List;

public interface WalletWithdrawTaxService {

    List<FinWalletWithdrawTaxTier> listByCompany(Long companyId);

    void saveTiers(WithdrawTaxTierSaveRequest request);

    WithdrawTaxCalcResult calculate(Long companyId, BigDecimal amount);
}
