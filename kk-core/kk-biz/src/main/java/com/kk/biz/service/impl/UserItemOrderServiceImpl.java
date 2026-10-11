package com.kk.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kk.biz.entity.PmUserItemOrder;
import com.kk.biz.mapper.PmUserItemOrderMapper;
import com.kk.biz.service.UserItemOrderService;
import com.kk.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class UserItemOrderServiceImpl implements UserItemOrderService {
    private static final int MAX_ITEMS = 5000;
    private final PmUserItemOrderMapper mapper;

    @Override
    public List<Long> getOrder(Long userId, String scopeType, Long scopeId) {
        return mapper.selectList(query(userId, scopeType, scopeId).orderByAsc(PmUserItemOrder::getSortNo))
                .stream().map(PmUserItemOrder::getItemId).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveOrder(Long userId, String scopeType, Long scopeId, List<Long> itemIds) {
        Set<Long> distinctIds = new LinkedHashSet<>();
        if (itemIds != null) {
            itemIds.stream().filter(id -> id != null && id > 0).forEach(distinctIds::add);
        }
        if (distinctIds.size() > MAX_ITEMS) {
            throw new BusinessException("排序条目不能超过" + MAX_ITEMS + "个");
        }
        mapper.delete(query(userId, scopeType, scopeId));
        List<Long> ids = new ArrayList<>(distinctIds);
        for (int i = 0; i < ids.size(); i++) {
            PmUserItemOrder order = new PmUserItemOrder();
            order.setUserId(userId);
            order.setScopeType(scopeType);
            order.setScopeId(normalizeScopeId(scopeId));
            order.setItemId(ids.get(i));
            order.setSortNo(i);
            mapper.insert(order);
        }
    }

    private LambdaQueryWrapper<PmUserItemOrder> query(Long userId, String scopeType, Long scopeId) {
        return new LambdaQueryWrapper<PmUserItemOrder>()
                .eq(PmUserItemOrder::getUserId, userId)
                .eq(PmUserItemOrder::getScopeType, scopeType)
                .eq(PmUserItemOrder::getScopeId, normalizeScopeId(scopeId));
    }

    private long normalizeScopeId(Long scopeId) {
        return scopeId == null ? 0L : scopeId;
    }
}
