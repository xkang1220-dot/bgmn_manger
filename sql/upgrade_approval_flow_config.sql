-- 审批流配置：按类型配置审批人（角色/指定人）与会签/或签
-- 用法: mysql -uroot -p123456 --default-character-set=utf8mb4 < sql/upgrade_approval_flow_config.sql
USE kk_manager;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS wf_approval_flow (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    type            VARCHAR(64)  NOT NULL COMMENT '审批类型编码',
    name            VARCHAR(64)  NOT NULL COMMENT '显示名',
    pass_mode       VARCHAR(16)  NOT NULL DEFAULT 'ALL' COMMENT 'ALL会签 ANY或签',
    role_codes      VARCHAR(255) DEFAULT NULL COMMENT '角色编码逗号分隔，如 finance,shareholder',
    user_ids        VARCHAR(512) DEFAULT NULL COMMENT '指定审批人用户ID逗号分隔',
    timeout_hours   INT          DEFAULT 0 COMMENT '超时自动通过小时数，0关闭',
    status          TINYINT      DEFAULT 1 COMMENT '1启用 0停用',
    sort            INT          DEFAULT 0,
    remark          VARCHAR(500) DEFAULT NULL,
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       DEFAULT NULL,
    update_by       BIGINT       DEFAULT NULL,
    deleted         TINYINT      DEFAULT 0,
    UNIQUE KEY uk_type (type)
) COMMENT='审批流配置';

-- 审批单快照：提交时写入，避免中途改配置影响在途单
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'kk_manager' AND TABLE_NAME = 'wf_approval' AND COLUMN_NAME = 'pass_mode'
);
SET @sql := IF(@col_exists = 0,
  'ALTER TABLE wf_approval ADD COLUMN pass_mode VARCHAR(16) DEFAULT ''ALL'' COMMENT ''提交时快照：ALL会签 ANY或签'' AFTER auto_pass',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 默认配置（与旧逻辑对齐，可在页面改）
INSERT INTO wf_approval_flow (type, name, pass_mode, role_codes, user_ids, timeout_hours, status, sort, remark) VALUES
('PROJECT_CREATE',     '创建项目',   'ALL', 'shareholder', NULL, 72, 1, 1,  '全体股东会签'),
('PROJECT_DELETE',     '删除项目',   'ALL', 'shareholder', NULL, 72, 1, 2,  '全体股东会签'),
('SHARE_CONFIG',       '资金配置',   'ALL', 'shareholder', NULL, 72, 1, 3,  '全体股东会签'),
('ROLLBACK',           '资金回退',   'ALL', 'shareholder', NULL, 72, 1, 4,  '股东会签'),
('REIMBURSE_PERSONAL', '个人报销',   'ANY', 'finance',     NULL, 0,  1, 10, '财务审批，任一人通过即可'),
('REIMBURSE_PROJECT',  '项目报销',   'ANY', 'finance',     NULL, 0,  1, 11, '财务审批，任一人通过即可'),
('SALARY_APPLY',       '工资申请',   'ANY', 'finance',     NULL, 0,  1, 12, '财务审批，任一人通过即可'),
('PROJECT_ADVANCE',    '项目预支',   'ANY', 'finance',     NULL, 0,  1, 13, '财务审批'),
('PROJECT_SETTLE',     '项目分钱',   'ANY', 'finance',     NULL, 0,  1, 14, '财务审批'),
('RESERVE_RETURN',     '预留回公司', 'ANY', 'finance',     NULL, 0,  1, 15, '财务审批'),
('LEDGER_REGISTER',    '总账登记',   'ANY', 'finance',     NULL, 0,  1, 16, '财务审批')
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  deleted = 0,
  status = 1;

-- 菜单：审批配置
INSERT INTO sys_menu (id, parent_id, name, type, path, component, permission, icon, sort, visible, status)
SELECT 72, 7, '审批配置', 2, '/workflow/flow', 'workflow/flow', 'workflow:flow:edit', 'Setting', 2, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 72 OR path = '/workflow/flow');

UPDATE sys_menu SET name = '审批配置', path = '/workflow/flow', component = 'workflow/flow',
    permission = 'workflow:flow:edit', icon = 'Setting', sort = 2, visible = 1, status = 1, deleted = 0
WHERE id = 72;

-- 授权管理员 + 财务可看配置（财务便于自管）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 72 FROM sys_role r
WHERE r.code IN ('admin', 'finance')
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 72);

SELECT 'upgrade_approval_flow_config done' AS result;
SELECT type, name, pass_mode, role_codes, timeout_hours FROM wf_approval_flow WHERE deleted = 0 ORDER BY sort;
