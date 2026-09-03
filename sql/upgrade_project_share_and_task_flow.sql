-- 项目分层独立到财务 + 任务流转记录（可重复执行）
USE kk_manager;
SET NAMES utf8mb4;

-- 任务流转
CREATE TABLE IF NOT EXISTS pm_task_flow (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id       BIGINT        NOT NULL,
    action        VARCHAR(32)   NOT NULL COMMENT 'CREATE/ASSIGN/STATUS/TRANSFER',
    from_user_id  BIGINT        DEFAULT NULL,
    to_user_id    BIGINT        DEFAULT NULL,
    from_status   TINYINT       DEFAULT NULL,
    to_status     TINYINT       DEFAULT NULL,
    remark        VARCHAR(500)  DEFAULT NULL,
    create_time   DATETIME      DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by     BIGINT        DEFAULT NULL,
    update_by     BIGINT        DEFAULT NULL,
    deleted       TINYINT       DEFAULT 0,
    KEY idx_task_id (task_id)
) COMMENT='任务流转记录';

-- 财务：项目分层菜单
INSERT INTO sys_menu (id, parent_id, name, type, path, component, permission, icon, sort, visible, status)
SELECT 25, 2, '项目分层', 2, '/finance/project-share', 'finance/project-share', 'finance:share:edit', 'PieChart', 2, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 25);

UPDATE sys_menu SET name = '项目分层', path = '/finance/project-share', component = 'finance/project-share',
    permission = 'finance:share:edit', icon = 'PieChart', sort = 2
WHERE id = 25;

-- 进出账 / 项目分钱 排序微调
UPDATE sys_menu SET sort = 3 WHERE id = 22;
UPDATE sys_menu SET sort = 4, name = '项目分钱' WHERE id = 24;
UPDATE sys_menu SET sort = 5 WHERE id = 23;

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, 25 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 25);

SELECT id, parent_id, name, path, permission, sort FROM sys_menu WHERE id IN (21, 22, 23, 24, 25) ORDER BY sort;
SHOW COLUMNS FROM pm_task_flow;
