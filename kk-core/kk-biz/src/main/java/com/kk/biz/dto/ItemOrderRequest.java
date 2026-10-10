package com.kk.biz.dto;

import lombok.Data;
import java.util.List;

@Data
public class ItemOrderRequest {
    private Long scopeId;
    private List<Long> itemIds;
}
