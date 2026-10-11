-- 为父任务和子任务增加唯一持有人；持有人必须由业务层校验为项目负责人或项目参与人。
SET @db := DATABASE();

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = @db AND table_name = 'pm_task' AND column_name = 'holder_id') = 0,
    'ALTER TABLE pm_task ADD COLUMN holder_id BIGINT DEFAULT NULL COMMENT ''任务持有人（项目参与人员，单选）'' AFTER assignee_id',
    'SELECT ''skip: pm_task.holder_id 已存在'' AS info'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 历史任务以原负责人作为持有人，保持升级后的数据可直接编辑。
UPDATE pm_task SET holder_id = assignee_id WHERE holder_id IS NULL AND assignee_id IS NOT NULL;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = @db AND table_name = 'pm_task' AND index_name = 'idx_task_holder') = 0,
    'ALTER TABLE pm_task ADD INDEX idx_task_holder (holder_id)',
    'SELECT ''skip: idx_task_holder 已存在'' AS info'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SHOW COLUMNS FROM pm_task LIKE 'holder_id';
