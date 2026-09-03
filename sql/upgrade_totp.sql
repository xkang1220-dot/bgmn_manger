-- 已有库升级：账号表增加 TOTP 两步验证字段（可重复执行）
USE kk_manager;

SET @db = DATABASE();

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'totp_secret_key') = 0,
    'ALTER TABLE sys_user ADD COLUMN totp_secret_key VARCHAR(512) DEFAULT NULL AFTER remark',
    'SELECT ''skip: sys_user.totp_secret_key 已存在'' AS info'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'totp_enabled') = 0,
    'ALTER TABLE sys_user ADD COLUMN totp_enabled TINYINT DEFAULT 0 AFTER totp_secret_key',
    'SELECT ''skip: sys_user.totp_enabled 已存在'' AS info'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'totp_verify_time') = 0,
    'ALTER TABLE sys_user ADD COLUMN totp_verify_time DATETIME DEFAULT NULL AFTER totp_enabled',
    'SELECT ''skip: sys_user.totp_verify_time 已存在'' AS info'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SHOW COLUMNS FROM sys_user LIKE 'totp_%';
