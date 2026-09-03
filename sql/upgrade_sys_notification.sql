-- 站内通知（审批待办/结果推送）
-- 用法: mysql -uroot -p123456 --default-character-set=utf8mb4 < sql/upgrade_sys_notification.sql
USE kk_manager;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS sys_notification (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL COMMENT '接收人',
    title        VARCHAR(128) NOT NULL,
    content      VARCHAR(500) DEFAULT NULL,
    biz_type     VARCHAR(32)  DEFAULT 'approval' COMMENT '业务类型',
    biz_id       BIGINT       DEFAULT NULL COMMENT '业务ID，如审批单ID',
    link         VARCHAR(256) DEFAULT '/workflow/center' COMMENT '跳转路径',
    read_flag    TINYINT      NOT NULL DEFAULT 0 COMMENT '0未读 1已读',
    create_time  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by    BIGINT       DEFAULT NULL,
    update_by    BIGINT       DEFAULT NULL,
    deleted      TINYINT      DEFAULT 0,
    KEY idx_user_read (user_id, read_flag, id),
    KEY idx_biz (biz_type, biz_id)
) COMMENT='站内通知';

SELECT 'upgrade_sys_notification done' AS result;
