-- 已有库升级：任务参与人员表（可重复执行）
USE kk_manager;

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS pm_task_member (
    id      BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    UNIQUE KEY uk_task_user (task_id, user_id),
    KEY idx_task_id (task_id)
) COMMENT='任务参与人员';

-- 验证
SHOW TABLES LIKE 'pm_task_member';
SHOW COLUMNS FROM pm_task_member;
