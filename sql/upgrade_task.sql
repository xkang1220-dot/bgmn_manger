-- 已有库升级：任务表增加开始日期与进度字段（可重复执行）
USE kk_manager;

SET @db = DATABASE();

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'pm_task' AND COLUMN_NAME = 'start_date') = 0,
    'ALTER TABLE pm_task ADD COLUMN start_date DATE DEFAULT NULL AFTER assignee_id',
    'SELECT ''skip: pm_task.start_date 已存在'' AS info'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'pm_task' AND COLUMN_NAME = 'progress') = 0,
    'ALTER TABLE pm_task ADD COLUMN progress TINYINT DEFAULT 0 COMMENT ''完成进度0-100'' AFTER due_date',
    'SELECT ''skip: pm_task.progress 已存在'' AS info'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE pm_task SET progress = 100 WHERE status = 2 AND (progress IS NULL OR progress = 0);
UPDATE pm_task SET progress = 0 WHERE progress IS NULL;

-- 验证
SHOW COLUMNS FROM pm_task LIKE 'start_date';
SHOW COLUMNS FROM pm_task LIKE 'progress';
