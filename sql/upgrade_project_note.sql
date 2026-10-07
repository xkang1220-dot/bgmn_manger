-- 已有库升级：项目备注表（可重复执行）
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS pm_project_note (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id  BIGINT        NOT NULL,
    content     TEXT          NOT NULL,
    create_time DATETIME      DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by   BIGINT        DEFAULT NULL,
    update_by   BIGINT        DEFAULT NULL,
    deleted     TINYINT       DEFAULT 0,
    KEY idx_project_id (project_id)
) COMMENT='项目备注';
