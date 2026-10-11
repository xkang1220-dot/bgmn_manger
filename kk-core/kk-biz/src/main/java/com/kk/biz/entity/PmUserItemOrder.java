package com.kk.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("pm_user_item_order")
public class PmUserItemOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String scopeType;
    private Long scopeId;
    private Long itemId;
    private Integer sortNo;
}
