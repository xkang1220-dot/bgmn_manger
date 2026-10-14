-- 员工手动上下班记录。可重复执行。
-- 请先 USE 目标数据库，或通过 mysql 的 --database 参数指定数据库。
SET @db = DATABASE();

CREATE TABLE IF NOT EXISTS sys_user_work_break (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT   NOT NULL COMMENT '员工用户ID',
    started_at      DATETIME NOT NULL COMMENT '下班时间',
    ended_at        DATETIME DEFAULT NULL COMMENT '重新上班时间',
    open_user_id    BIGINT GENERATED ALWAYS AS (CASE WHEN ended_at IS NULL AND deleted = 0 THEN user_id ELSE NULL END) STORED,
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT   DEFAULT NULL,
    update_by       BIGINT   DEFAULT NULL,
    deleted         TINYINT  DEFAULT 0,
    UNIQUE KEY uk_user_open_break (open_user_id),
    KEY idx_user_break_range (user_id, started_at, ended_at)
) COMMENT='员工手动下班区间';

SELECT COUNT(*) AS table_ready FROM information_schema.TABLES
WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'sys_user_work_break';
