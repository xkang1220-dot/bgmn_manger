package com.kk.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kk.biz.entity.FinPayChannel;
import com.kk.biz.entity.FinPool;
import com.kk.biz.mapper.FinPayChannelMapper;
import com.kk.biz.mapper.FinPoolMapper;
import com.kk.biz.service.FinPayChannelService;
import com.kk.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FinPayChannelServiceImpl extends ServiceImpl<FinPayChannelMapper, FinPayChannel>
        implements FinPayChannelService {

    private final FinPoolMapper poolMapper;

    @Override
    public List<FinPayChannel> listEnabled(Long poolId) {
        List<FinPayChannel> list = list(new LambdaQueryWrapper<FinPayChannel>()
                .eq(poolId != null, FinPayChannel::getPoolId, poolId)
                .eq(FinPayChannel::getStatus, 1)
                .orderByAsc(FinPayChannel::getSort)
                .orderByAsc(FinPayChannel::getId));
        fillExtras(list);
        return list;
    }

    @Override
    public List<FinPayChannel> listAll(Long poolId) {
        List<FinPayChannel> list = list(new LambdaQueryWrapper<FinPayChannel>()
                .eq(poolId != null, FinPayChannel::getPoolId, poolId)
                .orderByAsc(FinPayChannel::getSort)
                .orderByAsc(FinPayChannel::getId));
        fillExtras(list);
        return list;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createChannel(FinPayChannel channel) {
        validate(channel);
        if (channel.getBalance() == null) {
            channel.setBalance(BigDecimal.ZERO);
        }
        if (channel.getStatus() == null) {
            channel.setStatus(1);
        }
        save(channel);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateChannel(FinPayChannel channel) {
        if (channel.getId() == null) {
            throw new BusinessException("缺少渠道 ID");
        }
        FinPayChannel db = getById(channel.getId());
        if (db == null) {
            throw new BusinessException("渠道不存在");
        }
        validate(channel);
        channel.setBalance(null);
        updateById(channel);
    }

    @Override
    public FinPayChannel requireEnabled(Long id) {
        if (id == null) {
            throw new BusinessException("请选择收款渠道");
        }
        FinPayChannel channel = getById(id);
        if (channel == null || (channel.getDeleted() != null && channel.getDeleted() == 1)) {
            throw new BusinessException("收款渠道不存在");
        }
        if (channel.getStatus() != null && channel.getStatus() == 0) {
            throw new BusinessException("收款渠道已停用");
        }
        return channel;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void creditBalance(Long channelId, BigDecimal amount) {
        if (channelId == null || amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            debitBalance(channelId, amount.abs());
            return;
        }
        boolean ok = lambdaUpdate()
                .eq(FinPayChannel::getId, channelId)
                .setSql("balance = balance + " + amount.toPlainString())
                .update();
        if (!ok) {
            throw new BusinessException("更新渠道余额失败");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void debitBalance(Long channelId, BigDecimal amount) {
        if (channelId == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        boolean ok = lambdaUpdate()
                .eq(FinPayChannel::getId, channelId)
                .ge(FinPayChannel::getBalance, amount)
                .setSql("balance = balance - " + amount.toPlainString())
                .update();
        if (!ok) {
            throw new BusinessException("渠道余额不足");
        }
    }

    private void validate(FinPayChannel channel) {
        if (!StringUtils.hasText(channel.getName())) {
            throw new BusinessException("请填写渠道名称");
        }
        if (!StringUtils.hasText(channel.getChannelType())) {
            throw new BusinessException("请选择渠道类型");
        }
        if (channel.getPoolId() == null) {
            throw new BusinessException("请选择归属资金池");
        }
        FinPool pool = poolMapper.selectById(channel.getPoolId());
        if (pool == null) {
            throw new BusinessException("资金池不存在");
        }
    }

    private void fillExtras(List<FinPayChannel> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Set<Long> poolIds = list.stream().map(FinPayChannel::getPoolId).filter(id -> id != null).collect(Collectors.toSet());
        Map<Long, String> poolNames = new HashMap<>();
        if (!poolIds.isEmpty()) {
            poolMapper.selectBatchIds(poolIds).forEach(p -> poolNames.put(p.getId(), p.getName()));
        }
        for (FinPayChannel c : list) {
            c.setPoolName(poolNames.get(c.getPoolId()));
            c.setChannelTypeLabel(typeLabel(c.getChannelType()));
        }
    }

    public static String typeLabel(String type) {
        if (type == null) return "—";
        return switch (type) {
            case "ALIPAY" -> "支付宝";
            case "WECHAT" -> "微信";
            case "BANK" -> "银行卡";
            case "CASH" -> "现金";
            default -> "其他";
        };
    }
}
