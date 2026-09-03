-- 财务流水查询性能索引（可重复执行）
USE kk_manager;
SET NAMES utf8mb4;

-- fin_ledger 常用筛选与排序
SET @idx := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'fin_ledger' AND index_name = 'idx_ledger_occur_time');
SET @sql := IF(@idx = 0, 'ALTER TABLE fin_ledger ADD INDEX idx_ledger_occur_time (occur_time)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'fin_ledger' AND index_name = 'idx_ledger_pool_id');
SET @sql := IF(@idx = 0, 'ALTER TABLE fin_ledger ADD INDEX idx_ledger_pool_id (pool_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'fin_ledger' AND index_name = 'idx_ledger_project_id');
SET @sql := IF(@idx = 0, 'ALTER TABLE fin_ledger ADD INDEX idx_ledger_project_id (project_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'fin_ledger' AND index_name = 'idx_ledger_related_id');
SET @sql := IF(@idx = 0, 'ALTER TABLE fin_ledger ADD INDEX idx_ledger_related_id (related_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'fin_ledger' AND index_name = 'idx_ledger_biz_account');
SET @sql := IF(@idx = 0, 'ALTER TABLE fin_ledger ADD INDEX idx_ledger_biz_account (biz_type, account_type)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 凭证批量查询
SET @idx := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'sys_file' AND index_name = 'idx_file_biz');
SET @sql := IF(@idx = 0, 'ALTER TABLE sys_file ADD INDEX idx_file_biz (biz_type, biz_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SHOW INDEX FROM fin_ledger WHERE Key_name LIKE 'idx_ledger%';
