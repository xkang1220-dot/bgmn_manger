-- 已有库升级：文件表增加 URL 与存储类型（可重复执行）
USE kk_manager;

SET @db = DATABASE();

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'sys_file' AND COLUMN_NAME = 'url') = 0,
    'ALTER TABLE sys_file ADD COLUMN url VARCHAR(512) DEFAULT NULL AFTER path',
    'SELECT ''skip: sys_file.url 已存在'' AS info'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'sys_file' AND COLUMN_NAME = 'storage_type') = 0,
    'ALTER TABLE sys_file ADD COLUMN storage_type VARCHAR(32) DEFAULT ''local'' AFTER url',
    'SELECT ''skip: sys_file.storage_type 已存在'' AS info'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 验证
SHOW COLUMNS FROM sys_file LIKE 'url';
SHOW COLUMNS FROM sys_file LIKE 'storage_type';
