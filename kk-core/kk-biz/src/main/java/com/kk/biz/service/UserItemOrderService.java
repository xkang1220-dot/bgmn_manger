package com.kk.biz.service;

import java.util.List;

public interface UserItemOrderService {
    String PROJECT = "PROJECT";
    String TASK = "TASK";

    List<Long> getOrder(Long userId, String scopeType, Long scopeId);

    void saveOrder(Long userId, String scopeType, Long scopeId, List<Long> itemIds);
}
