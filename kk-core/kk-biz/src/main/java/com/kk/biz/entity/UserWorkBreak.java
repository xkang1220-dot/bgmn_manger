package com.kk.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kk.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user_work_break")
public class UserWorkBreak extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
}
