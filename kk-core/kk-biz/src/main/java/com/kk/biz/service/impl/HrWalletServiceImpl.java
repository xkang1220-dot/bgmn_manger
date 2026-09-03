package com.kk.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kk.biz.entity.HrArchive;
import com.kk.biz.entity.HrWallet;
import com.kk.biz.mapper.HrArchiveMapper;
import com.kk.biz.mapper.HrWalletMapper;
import com.kk.biz.service.HrWalletService;
import com.kk.common.exception.BusinessException;
import com.kk.system.entity.SysUser;
import com.kk.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class HrWalletServiceImpl extends ServiceImpl<HrWalletMapper, HrWallet> implements HrWalletService {

    private final SysUserService userService;
    private final HrArchiveMapper archiveMapper;

    @Override
    public Page<HrWallet> pageWallets(long page, long pageSize, Long userId) {
        Page<HrWallet> result = page(new Page<>(page, pageSize), new LambdaQueryWrapper<HrWallet>()
                .eq(userId != null, HrWallet::getUserId, userId)
                .orderByDesc(HrWallet::getId));
        fillUsers(result.getRecords());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public synchronized HrWallet getOrCreate(Long userId) {
        HrWallet wallet = getOne(new LambdaQueryWrapper<HrWallet>().eq(HrWallet::getUserId, userId).last("LIMIT 1"));
        if (wallet != null) {
            fillUser(wallet);
            return wallet;
        }
        wallet = new HrWallet();
        wallet.setUserId(userId);
        wallet.setBalance(BigDecimal.ZERO);
        wallet.setFrozen(BigDecimal.ZERO);
        wallet.setStatus(1);
        save(wallet);
        fillUser(wallet);
        return wallet;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrWallet changeBalance(Long userId, BigDecimal delta) {
        HrWallet wallet = getOrCreate(userId);
        if (wallet.getStatus() != null && wallet.getStatus() == 0) {
            throw new BusinessException("该人员钱包已禁用");
        }
        if (delta.compareTo(BigDecimal.ZERO) < 0) {
            BigDecimal abs = delta.abs();
            boolean ok = lambdaUpdate()
                    .eq(HrWallet::getUserId, userId)
                    .ge(HrWallet::getBalance, abs)
                    .setSql("balance = balance - " + abs.toPlainString())
                    .update();
            if (!ok) {
                throw new BusinessException("钱包余额不足");
            }
        } else if (delta.compareTo(BigDecimal.ZERO) > 0) {
            lambdaUpdate()
                    .eq(HrWallet::getUserId, userId)
                    .setSql("balance = balance + " + delta.toPlainString())
                    .update();
        }
        return getOrCreate(userId);
    }

    private void fillUser(HrWallet wallet) {
        fillUsers(List.of(wallet));
    }

    private void fillUsers(List<HrWallet> wallets) {
        if (wallets == null || wallets.isEmpty()) {
            return;
        }
        Set<Long> userIds = wallets.stream()
                .map(HrWallet::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return;
        }
        Map<Long, SysUser> userMap = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> u, (a, b) -> a));
        Map<Long, HrArchive> archiveMap = archiveMapper.selectList(new LambdaQueryWrapper<HrArchive>()
                        .in(HrArchive::getUserId, userIds)).stream()
                .collect(Collectors.toMap(HrArchive::getUserId, a -> a, (a, b) -> a));
        for (HrWallet wallet : wallets) {
            SysUser user = userMap.get(wallet.getUserId());
            if (user != null) {
                wallet.setUsername(user.getUsername());
                wallet.setNickname(user.getNickname());
            }
            HrArchive archive = archiveMap.get(wallet.getUserId());
            if (archive != null) {
                wallet.setRealName(archive.getRealName());
            }
        }
    }
}
