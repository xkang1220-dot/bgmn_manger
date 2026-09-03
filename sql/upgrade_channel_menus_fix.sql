-- 补跑：月度核验审批配置 + 渠道/核验菜单 + 演示渠道（主脚本后半段曾因字段名失败）
SET NAMES utf8mb4;

INSERT INTO wf_approval_flow (type, name, pass_mode, role_codes, user_ids, timeout_hours, status, sort, remark)
SELECT 'MONTHLY_VERIFY', '月度核验', 'ANY', 'finance', NULL, 72, 1, 20, '财务审批账户截图与流水凭证'
WHERE NOT EXISTS (SELECT 1 FROM wf_approval_flow WHERE type = 'MONTHLY_VERIFY');

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

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r CROSS JOIN sys_menu m
WHERE r.code IN ('admin', 'finance') AND m.id IN (28, 281, 29, 291)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.id);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r CROSS JOIN sys_menu m
WHERE r.code = 'shareholder' AND m.id IN (28, 29)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.id);

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

UPDATE sys_menu SET visible = 0 WHERE id = 23;
