-- 项目账款下补「资金配置」按钮权限（原菜单 25 已删，但接口仍用 finance:share:edit）
INSERT INTO sys_menu (id, parent_id, name, type, path, component, permission, icon, sort, visible, status)
SELECT 261, 26, '资金配置', 3, '', '', 'finance:share:edit', '', 1, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 261 OR permission = 'finance:share:edit');

-- 股东、财务可配置/查看资金方案（提交仍走审批）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 261
FROM sys_role r
WHERE r.code IN ('admin', 'shareholder', 'finance')
  AND EXISTS (SELECT 1 FROM sys_menu WHERE id = 261)
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 261
  );
