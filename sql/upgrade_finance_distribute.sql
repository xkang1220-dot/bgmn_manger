-- 已有库升级：增加「项目分钱」菜单（可重复执行）
USE kk_manager;
SET NAMES utf8mb4;

INSERT INTO sys_menu (id, parent_id, name, type, path, component, permission, icon, sort, visible, status)
SELECT 24, 2, '项目分钱', 2, '/finance/distribute', 'finance/distribute', 'finance:ledger:add', 'Share', 3, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 24);

-- 修复因客户端编码错误导致的菜单乱码
UPDATE sys_menu SET name = '项目分钱' WHERE id = 24;

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, 24
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 24);

UPDATE sys_menu SET sort = 4 WHERE id = 23;

SELECT id, name, path FROM sys_menu WHERE parent_id = 2 ORDER BY sort;
