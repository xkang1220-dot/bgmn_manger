-- 任务父子层级：任务管理页使用真实子任务替代前端演示数据。
SET @db := DATABASE();

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = @db AND table_name = 'pm_task' AND column_name = 'parent_task_id') = 0,
    'ALTER TABLE pm_task ADD COLUMN parent_task_id BIGINT DEFAULT NULL COMMENT ''父任务ID，为空表示顶层任务'' AFTER project_id',
    'SELECT ''skip: pm_task.parent_task_id 已存在'' AS info'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = @db AND table_name = 'pm_task' AND index_name = 'idx_parent_task_id') = 0,
    'ALTER TABLE pm_task ADD INDEX idx_parent_task_id (parent_task_id)',
    'SELECT ''skip: idx_parent_task_id 已存在'' AS info'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SHOW COLUMNS FROM pm_task LIKE 'parent_task_id';
