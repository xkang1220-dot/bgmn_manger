package com.kk.biz.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_task_log_session")
public class AiTaskLogSession {
    @TableId
    private String sessionId;
    private Long userId;
    private String status;
    private String draftJson;
    private String messagesJson;
    private String committedTaskIds;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
