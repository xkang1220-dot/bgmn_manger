-- 项目资金用途比例：支出 / 预留 / 分成（合计 100%，可重复执行）
USE kk_manager;
SET NAMES utf8mb4;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pm_project' AND COLUMN_NAME = 'expense_percent');
SET @sql := IF(@col = 0,
    'ALTER TABLE pm_project ADD COLUMN expense_percent DECIMAL(8,2) DEFAULT 0.00 COMMENT ''支出比例%'' AFTER reserve_amount',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pm_project' AND COLUMN_NAME = 'reserve_percent');
SET @sql := IF(@col = 0,
    'ALTER TABLE pm_project ADD COLUMN reserve_percent DECIMAL(8,2) DEFAULT 0.00 COMMENT ''预留比例%'' AFTER expense_percent',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pm_project' AND COLUMN_NAME = 'settle_percent');
SET @sql := IF(@col = 0,
    'ALTER TABLE pm_project ADD COLUMN settle_percent DECIMAL(8,2) DEFAULT 100.00 COMMENT ''分成比例%'' AFTER reserve_percent',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 旧数据：未配置时默认全部可分成
UPDATE pm_project
SET expense_percent = IFNULL(expense_percent, 0),
    reserve_percent = IFNULL(reserve_percent, 0),
    settle_percent = IFNULL(NULLIF(settle_percent, 0), 100)
WHERE deleted = 0;

SELECT id, name, expense_percent, reserve_percent, settle_percent, reserve_amount, budget
FROM pm_project WHERE deleted = 0 LIMIT 20;
