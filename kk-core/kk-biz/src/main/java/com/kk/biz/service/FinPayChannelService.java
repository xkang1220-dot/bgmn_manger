package com.kk.biz.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kk.biz.entity.FinPayChannel;

import java.math.BigDecimal;
import java.util.List;

public interface FinPayChannelService extends IService<FinPayChannel> {

    List<FinPayChannel> listEnabled(Long poolId);

    List<FinPayChannel> listAll(Long poolId);

    void createChannel(FinPayChannel channel);

    void updateChannel(FinPayChannel channel);

    FinPayChannel requireEnabled(Long id);

    void creditBalance(Long channelId, BigDecimal amount);

    void debitBalance(Long channelId, BigDecimal amount);
}
