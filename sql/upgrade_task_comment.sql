-- 已有库升级：任务评论表（可重复执行）
USE kk_manager;

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS pm_task_comment (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id     BIGINT        NOT NULL,
    content     VARCHAR(2000) NOT NULL,
    create_time DATETIME      DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by   BIGINT        DEFAULT NULL,
    update_by   BIGINT        DEFAULT NULL,
    deleted     TINYINT       DEFAULT 0,
    KEY idx_task_id (task_id)
) COMMENT='任务评论';

SHOW TABLES LIKE 'pm_task_comment';
SHOW COLUMNS FROM pm_task_comment;
