-- 物理删除已废弃财务菜单（菜单管理不再显示）
USE kk_manager;
SET NAMES utf8mb4;

DELETE FROM sys_role_menu WHERE menu_id IN (21, 211, 24, 25);
DELETE FROM sys_menu WHERE id IN (211, 21, 24, 25);

-- 财务菜单排序整理
UPDATE sys_menu SET name = '公司总账', sort = 1, visible = 1, status = 1, deleted = 0 WHERE id = 22;
UPDATE sys_menu SET name = '项目账款', sort = 2, visible = 1, status = 1, deleted = 0 WHERE id = 26;
UPDATE sys_menu SET name = '全员钱包', sort = 3, visible = 1, status = 1, deleted = 0 WHERE id = 27;
UPDATE sys_menu SET name = '我的钱包', sort = 4, visible = 1, status = 1, deleted = 0 WHERE id = 23;
UPDATE sys_menu SET name = '登记总账', visible = 1, status = 1, deleted = 0 WHERE id = 221;

SELECT id, parent_id, name, path, deleted FROM sys_menu
WHERE parent_id = 2 OR id IN (2, 22, 221, 23, 26, 27)
ORDER BY parent_id, sort, id;
