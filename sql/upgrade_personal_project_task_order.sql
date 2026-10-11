CREATE TABLE IF NOT EXISTS pm_user_item_order (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    scope_type  VARCHAR(16) NOT NULL COMMENT 'PROJECT/TASK',
    scope_id    BIGINT      NOT NULL DEFAULT 0 COMMENT '任务所属项目，0表示全部；项目排序固定为0',
    item_id     BIGINT      NOT NULL,
    sort_no     INT         NOT NULL,
    UNIQUE KEY uk_user_scope_item (user_id, scope_type, scope_id, item_id),
    KEY idx_user_scope_sort (user_id, scope_type, scope_id, sort_no)
) COMMENT='用户个人项目及任务排序';
