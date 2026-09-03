package com.kk.biz.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kk.biz.entity.HrWallet;

import java.math.BigDecimal;

public interface HrWalletService extends IService<HrWallet> {

    Page<HrWallet> pageWallets(long page, long pageSize, Long userId);

    HrWallet getOrCreate(Long userId);

    HrWallet changeBalance(Long userId, BigDecimal delta);
}
