-- 修复 sys_menu 菜单名称乱码（Windows mysql 客户端未指定 utf8mb4 时可能出现）
USE kk_manager;
SET NAMES utf8mb4;

UPDATE sys_menu SET name = '项目分钱' WHERE id = 24;
UPDATE sys_menu SET name = '任务管理' WHERE id = 32;

SELECT id, parent_id, name, path FROM sys_menu WHERE id IN (24, 32);
