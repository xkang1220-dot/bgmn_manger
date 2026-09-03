-- 财务三分账户 + 审批流 + 项目账款（可重复执行）
USE kk_manager;
SET NAMES utf8mb4;

-- ========== 1. 流水编号 & 账户类型扩展 ==========
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fin_ledger' AND COLUMN_NAME = 'biz_no');
SET @sql := IF(@col = 0,
    'ALTER TABLE fin_ledger ADD COLUMN biz_no VARCHAR(32) DEFAULT NULL COMMENT ''唯一业务编号'' AFTER id',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'fin_ledger' AND index_name = 'uk_ledger_biz_no');
SET @sql := IF(@idx = 0, 'ALTER TABLE fin_ledger ADD UNIQUE INDEX uk_ledger_biz_no (biz_no)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fin_ledger' AND COLUMN_NAME = 'approval_id');
SET @sql := IF(@col = 0,
    'ALTER TABLE fin_ledger ADD COLUMN approval_id BIGINT DEFAULT NULL COMMENT ''关联审批单'' AFTER related_id',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 回填旧流水编号
UPDATE fin_ledger SET biz_no = CONCAT('LG', LPAD(id, 10, '0')) WHERE biz_no IS NULL OR biz_no = '';

-- ========== 2. 项目账款账户 ==========
CREATE TABLE IF NOT EXISTS fin_project_account (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id      BIGINT         NOT NULL,
    balance         DECIMAL(14,2)  DEFAULT 0.00 COMMENT '可用余额',
    advance_amount  DECIMAL(14,2)  DEFAULT 0.00 COMMENT '累计预支(从总账)',
    expense_amount  DECIMAL(14,2)  DEFAULT 0.00 COMMENT '累计支出',
    settle_amount   DECIMAL(14,2)  DEFAULT 0.00 COMMENT '累计分成',
    reserve_amount  DECIMAL(14,2)  DEFAULT 0.00 COMMENT '约定预留总额',
    reserve_held    DECIMAL(14,2)  DEFAULT 0.00 COMMENT '当前预留占用',
    status          TINYINT        DEFAULT 1,
    create_time     DATETIME       DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT         DEFAULT NULL,
    update_by       BIGINT         DEFAULT NULL,
    deleted         TINYINT        DEFAULT 0,
    UNIQUE KEY uk_project (project_id)
) COMMENT='项目账款账户';

-- 项目扩展：预留金额、待审批标记
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pm_project' AND COLUMN_NAME = 'reserve_amount');
SET @sql := IF(@col = 0,
    'ALTER TABLE pm_project ADD COLUMN reserve_amount DECIMAL(14,2) DEFAULT 0.00 COMMENT ''预留金额'' AFTER settled_amount',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pm_project' AND COLUMN_NAME = 'approve_status');
SET @sql := IF(@col = 0,
    'ALTER TABLE pm_project ADD COLUMN approve_status TINYINT DEFAULT 1 COMMENT ''0待审 1已生效 2已拒绝'' AFTER status',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 为已有项目补账户
INSERT INTO fin_project_account (project_id, balance, reserve_amount)
SELECT p.id, 0, IFNULL(p.reserve_amount, 0)
FROM pm_project p
WHERE p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM fin_project_account a WHERE a.project_id = p.id AND a.deleted = 0);

-- ========== 3. 审批主表 ==========
CREATE TABLE IF NOT EXISTS wf_approval (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    biz_no          VARCHAR(32)    NOT NULL COMMENT '审批单号',
    type            VARCHAR(64)    NOT NULL COMMENT '审批类型',
    title           VARCHAR(255)   NOT NULL,
    status          VARCHAR(32)    NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/WITHDRAWN/TIMEOUT_PASS/ROLLING/ROLLED',
    applicant_id    BIGINT         NOT NULL,
    amount          DECIMAL(14,2)  DEFAULT NULL,
    project_id      BIGINT         DEFAULT NULL,
    pool_id         BIGINT         DEFAULT NULL,
    payload         TEXT           DEFAULT NULL COMMENT 'JSON 业务载荷',
    receipt_file_ids VARCHAR(512)  DEFAULT NULL COMMENT '财务回执文件ID逗号分隔',
    confirm_status  TINYINT        DEFAULT 0 COMMENT '0无需确认 1待财务回执 2待申请人确认 3已确认到账',
    timeout_at      DATETIME       DEFAULT NULL,
    auto_pass       TINYINT        DEFAULT 0,
    pass_time       DATETIME       DEFAULT NULL,
    remark          VARCHAR(500)   DEFAULT NULL,
    create_time     DATETIME       DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT         DEFAULT NULL,
    update_by       BIGINT         DEFAULT NULL,
    deleted         TINYINT        DEFAULT 0,
    UNIQUE KEY uk_biz_no (biz_no),
    KEY idx_type_status (type, status),
    KEY idx_applicant (applicant_id),
    KEY idx_project (project_id)
) COMMENT='审批单';

CREATE TABLE IF NOT EXISTS wf_approval_task (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    approval_id     BIGINT         NOT NULL,
    assignee_id     BIGINT         NOT NULL,
    action          VARCHAR(32)    NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVE/REJECT/SKIP',
    comment         VARCHAR(500)   DEFAULT NULL,
    act_time        DATETIME       DEFAULT NULL,
    create_time     DATETIME       DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT         DEFAULT NULL,
    update_by       BIGINT         DEFAULT NULL,
    deleted         TINYINT        DEFAULT 0,
    KEY idx_approval (approval_id),
    KEY idx_assignee (assignee_id, action)
) COMMENT='审批任务(会签人)';

CREATE TABLE IF NOT EXISTS wf_approval_log (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    approval_id     BIGINT         NOT NULL,
    operator_id     BIGINT         DEFAULT NULL,
    action          VARCHAR(64)    NOT NULL,
    remark          VARCHAR(500)   DEFAULT NULL,
    create_time     DATETIME       DEFAULT CURRENT_TIMESTAMP,
    KEY idx_approval (approval_id)
) COMMENT='审批操作记录';

CREATE TABLE IF NOT EXISTS wf_rollback (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    biz_no          VARCHAR(32)    NOT NULL,
    approval_id     BIGINT         NOT NULL COMMENT '原审批单',
    rollback_approval_id BIGINT    DEFAULT NULL COMMENT '回退审批单',
    mode            VARCHAR(16)    NOT NULL COMMENT 'FULL/PARTIAL',
    amount          DECIMAL(14,2)  NOT NULL,
    status          VARCHAR(32)    NOT NULL DEFAULT 'PENDING',
    reason          VARCHAR(500)   DEFAULT NULL,
    create_time     DATETIME       DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT         DEFAULT NULL,
    update_by       BIGINT         DEFAULT NULL,
    deleted         TINYINT        DEFAULT 0,
    UNIQUE KEY uk_biz_no (biz_no),
    KEY idx_approval (approval_id)
) COMMENT='资金回退单';

-- ========== 4. 角色：股东 ==========
INSERT INTO sys_role (id, name, code, sort, status, data_scope, remark)
SELECT 4, '股东', 'shareholder', 4, 1, 1, '全公司股东，参与建删项目会签'
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE id = 4 OR code = 'shareholder');

-- 管理员默认也是股东（便于演示）
INSERT INTO sys_user_role (user_id, role_id)
SELECT 1, 4 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE user_id = 1 AND role_id = 4);

-- ========== 5. 菜单调整 ==========
-- 公司总账（原进出账升级文案）
UPDATE sys_menu SET name = '公司总账', path = '/finance/ledger', component = 'finance/ledger', permission = 'finance:ledger:list', sort = 1
WHERE id = 22;

-- 资金池改名为账户总览可保留，或改为公司账户
UPDATE sys_menu SET name = '公司账户', sort = 2 WHERE id = 21;

-- 项目账款
INSERT INTO sys_menu (id, parent_id, name, type, path, component, permission, icon, sort, visible, status)
SELECT 26, 2, '项目账款', 2, '/finance/project-account', 'finance/project-account', 'finance:project:list', 'FolderOpened', 3, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 26);

UPDATE sys_menu SET name = '项目账款', path = '/finance/project-account', component = 'finance/project-account',
    permission = 'finance:project:list', icon = 'FolderOpened', sort = 3
WHERE id = 26;

-- 全员钱包看板
INSERT INTO sys_menu (id, parent_id, name, type, path, component, permission, icon, sort, visible, status)
SELECT 27, 2, '全员钱包', 2, '/finance/wallet-board', 'finance/wallet-board', 'finance:wallet:board', 'DataBoard', 5, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 27);

UPDATE sys_menu SET name = '全员钱包', path = '/finance/wallet-board', component = 'finance/wallet-board',
    permission = 'finance:wallet:board', icon = 'DataBoard', sort = 5
WHERE id = 27;

UPDATE sys_menu SET sort = 4 WHERE id = 25;
UPDATE sys_menu SET sort = 6, name = '项目分钱' WHERE id = 24;
UPDATE sys_menu SET sort = 7, name = '我的钱包' WHERE id = 23;

-- 审批中心（一级菜单）
INSERT INTO sys_menu (id, parent_id, name, type, path, component, permission, icon, sort, visible, status)
SELECT 7, 0, '审批', 1, '/workflow', '', '', 'Stamp', 3, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 7);

UPDATE sys_menu SET name = '审批', path = '/workflow', icon = 'Stamp', sort = 3 WHERE id = 7;

INSERT INTO sys_menu (id, parent_id, name, type, path, component, permission, icon, sort, visible, status)
SELECT 71, 7, '审批中心', 2, '/workflow/center', 'workflow/center', 'workflow:list', 'List', 1, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 71);

UPDATE sys_menu SET name = '审批中心', path = '/workflow/center', component = 'workflow/center',
    permission = 'workflow:list', sort = 1 WHERE id = 71;

INSERT INTO sys_menu (id, parent_id, name, type, path, component, permission, icon, sort, visible, status)
SELECT 711, 71, '处理审批', 3, '', '', 'workflow:handle', '', 1, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 711);

-- 项目菜单排序后移
UPDATE sys_menu SET sort = 4 WHERE id = 3;
UPDATE sys_menu SET sort = 5 WHERE id = 4;
UPDATE sys_menu SET sort = 6 WHERE id = 5;
UPDATE sys_menu SET sort = 7 WHERE id = 6;

-- 授权管理员
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.id FROM sys_menu m
WHERE m.id IN (7, 71, 711, 26, 27)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.id);

-- 给股东角色基本审批相关菜单（只读/会签）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 4, m.id FROM sys_menu m
WHERE m.id IN (1, 7, 71, 711, 3, 31, 32)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 4 AND rm.menu_id = m.id);

SELECT 'upgrade_finance_workflow_v2 done' AS result;
