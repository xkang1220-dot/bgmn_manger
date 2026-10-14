CREATE TABLE IF NOT EXISTS `ai_task_log_session` (
  `session_id` varchar(64) NOT NULL COMMENT '会话 ID',
  `user_id` bigint NOT NULL COMMENT '会话所属用户',
  `status` varchar(32) NOT NULL DEFAULT 'chatting' COMMENT 'chatting/awaiting_confirm/committed/cancelled',
  `draft_json` text NULL COMMENT '待确认记工草稿',
  `messages_json` mediumtext NULL COMMENT '对话历史与 blocks 快照',
  `committed_task_ids` varchar(1000) NULL COMMENT '已提交任务 ID，用于幂等响应',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`session_id`),
  KEY `idx_ai_task_session_user_status` (`user_id`, `status`),
  KEY `idx_ai_task_session_update_time` (`update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 任务助手会话';
