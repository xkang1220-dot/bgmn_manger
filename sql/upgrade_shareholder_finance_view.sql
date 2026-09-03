-- 股东可看公司总账 / 全员钱包（首页 summary 依赖 finance:ledger:list）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 4, m.id
FROM sys_menu m
WHERE m.deleted = 0
  AND m.id IN (22, 27)
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 4 AND rm.menu_id = m.id
  );
