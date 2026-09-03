-- 收款渠道 + 流水手续费字段 + 月度核验 + 任务流转图片菜单占位
SET NAMES utf8mb4;

-- ========== 1. 收款渠道账户 ==========
CREATE TABLE IF NOT EXISTS fin_pay_channel (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    pool_id       BIGINT       NOT NULL COMMENT '归属资金池',
    channel_type  VARCHAR(32)  NOT NULL COMMENT 'ALIPAY/WECHAT/BANK/CASH/OTHER',
    name          VARCHAR(64)  NOT NULL COMMENT '显示名，如公司支付宝',
    account_no    VARCHAR(64)  DEFAULT NULL COMMENT '账号/卡号',
    account_name  VARCHAR(64)  DEFAULT NULL COMMENT '户名',
    bank_name     VARCHAR(128) DEFAULT NULL COMMENT '开户行（银行卡）',
    balance       DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '渠道账面余额（净额累计）',
    sort          INT          DEFAULT 0,
    status        TINYINT      DEFAULT 1 COMMENT '1启用 0停用',
    remark        VARCHAR(255) DEFAULT NULL,
    create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by     BIGINT       DEFAULT NULL,
    update_by     BIGINT       DEFAULT NULL,
    deleted       TINYINT      DEFAULT 0
) COMMENT='公司收款渠道（支付宝/微信/银行卡等）';

-- ========== 2. 流水扩展：渠道 + 手续费留痕 ==========
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fin_ledger' AND COLUMN_NAME = 'channel_id');
SET @sql := IF(@col = 0,
    'ALTER TABLE fin_ledger ADD COLUMN channel_id BIGINT DEFAULT NULL COMMENT ''收款渠道'' AFTER pool_id',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fin_ledger' AND COLUMN_NAME = 'gross_amount');
SET @sql := IF(@col = 0,
    'ALTER TABLE fin_ledger ADD COLUMN gross_amount DECIMAL(18,2) DEFAULT NULL COMMENT ''入账总额（含手续费前）'' AFTER amount',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fin_ledger' AND COLUMN_NAME = 'fee_amount');
SET @sql := IF(@col = 0,
    'ALTER TABLE fin_ledger ADD COLUMN fee_amount DECIMAL(18,2) DEFAULT NULL COMMENT ''手续费金额'' AFTER gross_amount',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fin_ledger' AND COLUMN_NAME = 'fee_mode');
SET @sql := IF(@col = 0,
    'ALTER TABLE fin_ledger ADD COLUMN fee_mode VARCHAR(16) DEFAULT NULL COMMENT ''FIXED/PERCENT'' AFTER fee_amount',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ========== 3. 月度核验记录（审批通过后落库） ==========
CREATE TABLE IF NOT EXISTS fin_month_verify (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT,
    verify_month      CHAR(7)       NOT NULL COMMENT 'YYYY-MM',
    channel_id        BIGINT        NOT NULL,
    pool_id           BIGINT        DEFAULT NULL,
    system_balance    DECIMAL(18,2) DEFAULT NULL COMMENT '提交时系统渠道余额',
    statement_balance DECIMAL(18,2) DEFAULT NULL COMMENT '截图/对账单余额',
    diff_amount       DECIMAL(18,2) DEFAULT NULL,
    status            VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PASSED/REJECTED',
    approval_id       BIGINT        DEFAULT NULL,
    remark            VARCHAR(500)  DEFAULT NULL,
    create_time       DATETIME      DEFAULT CURRENT_TIMESTAMP,
    update_time       DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by         BIGINT        DEFAULT NULL,
    update_by         BIGINT        DEFAULT NULL,
    deleted           TINYINT       DEFAULT 0,
    UNIQUE KEY uk_month_channel (verify_month, channel_id, deleted)
) COMMENT='渠道月度核验';

-- 审批类型默认配置：月度核验 → 财务
INSERT INTO wf_approval_flow (type, name, pass_mode, role_codes, user_ids, timeout_hours, status, sort, remark)
SELECT 'MONTHLY_VERIFY', '月度核验', 'ANY', 'finance', NULL, 72, 1, 20, '财务审批账户截图与流水凭证'
WHERE NOT EXISTS (SELECT 1 FROM wf_approval_flow WHERE type = 'MONTHLY_VERIFY');

-- 菜单：渠道账户看板 + 月度核验（财务下）
INSERT INTO sys_menu (id, parent_id, name, type, path, component, permission, icon, sort, visible, status)
SELECT 28, 2, '收款渠道', 2, '/finance/pay-channel', 'finance/pay-channel', 'finance:channel:list', 'CreditCard', 5, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 28);

INSERT INTO sys_menu (id, parent_id, name, type, path, component, permission, icon, sort, visible, status)
SELECT 281, 28, '编辑渠道', 3, '', '', 'finance:channel:edit', '', 1, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 281);

INSERT INTO sys_menu (id, parent_id, name, type, path, component, permission, icon, sort, visible, status)
SELECT 29, 2, '月度核验', 2, '/finance/month-verify', 'finance/month-verify', 'finance:verify:list', 'DocumentChecked', 6, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 29);

INSERT INTO sys_menu (id, parent_id, name, type, path, component, permission, icon, sort, visible, status)
SELECT 291, 29, '提交核验', 3, '', '', 'finance:verify:submit', '', 1, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 291);

-- 管理员 / 财务 / 股东（只读渠道+核验列表）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
CROSS JOIN sys_menu m
WHERE r.code IN ('admin', 'finance')
  AND m.id IN (28, 281, 29, 291)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.id);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
CROSS JOIN sys_menu m
WHERE r.code = 'shareholder'
  AND m.id IN (28, 29)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.id);

-- 演示渠道（挂到默认资金池）
INSERT INTO fin_pay_channel (pool_id, channel_type, name, account_no, account_name, balance, sort, status, remark)
SELECT p.id, 'ALIPAY', '公司支付宝', '138****0001', 'KK科技', 0, 1, 1, '演示'
FROM fin_pool p
WHERE p.is_default = 1 AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM fin_pay_channel WHERE name = '公司支付宝' AND deleted = 0)
LIMIT 1;

INSERT INTO fin_pay_channel (pool_id, channel_type, name, account_no, account_name, bank_name, balance, sort, status, remark)
SELECT p.id, 'BANK', '对公招商银行', '****8888', 'KK科技有限公司', '招商银行深圳分行', 0, 2, 1, '演示'
FROM fin_pool p
WHERE p.is_default = 1 AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM fin_pay_channel WHERE name = '对公招商银行' AND deleted = 0)
LIMIT 1;

-- 隐藏侧栏「我的钱包」，统一走顶栏个人中心
UPDATE sys_menu SET visible = 0, name = '我的钱包(已并入个人中心)' WHERE id = 23;
