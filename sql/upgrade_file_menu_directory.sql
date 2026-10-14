-- 文件：一级目录「文件」+ 二级「文件管理」（可重复执行）
USE kk_manager;

-- 1) 保证存在一级目录「文件」
INSERT INTO sys_menu (id, parent_id, name, type, path, component, permission, icon, sort, visible, status)
SELECT 50, 0, '文件', 1, '/file', '', '', 'FolderOpened', 5, 1, 1
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE type = 1 AND parent_id = 0 AND (path = '/file' OR name = '文件')
);

UPDATE sys_menu
SET name = '文件', type = 1, path = '/file', component = '', permission = '', icon = 'FolderOpened', visible = 1, status = 1
WHERE type = 1 AND parent_id = 0 AND (path = '/file' OR name = '文件');

-- 2) 若旧版 id=5 仍是「文件管理」页面：改为目录（兼容早期数据）
UPDATE sys_menu
SET name = '文件', type = 1, path = '/file', component = '', permission = '', icon = 'FolderOpened', visible = 1, status = 1
WHERE id = 5 AND path = '/file/list' AND type = 2;

-- 3) 保证二级「文件管理」页面存在
INSERT INTO sys_menu (id, parent_id, name, type, path, component, permission, icon, sort, visible, status)
SELECT 51,
       COALESCE(
           (SELECT id FROM sys_menu WHERE type = 1 AND parent_id = 0 AND path = '/file' ORDER BY id LIMIT 1),
           50
       ),
       '文件管理', 2, '/file/list', 'file/list', 'file:list', 'FolderOpened', 1, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/file/list' AND type = 2);

UPDATE sys_menu page
INNER JOIN (
    SELECT id AS dir_id FROM sys_menu WHERE type = 1 AND parent_id = 0 AND path = '/file' ORDER BY id LIMIT 1
) d
SET page.parent_id = d.dir_id,
    page.name = '文件管理',
    page.type = 2,
    page.component = 'file/list',
    page.permission = 'file:list',
    page.icon = COALESCE(page.icon, 'FolderOpened'),
    page.sort = 1,
    page.visible = 1,
    page.status = 1
WHERE page.path = '/file/list' AND page.type = 2;

-- 4) 按钮挂到「文件管理」下
UPDATE sys_menu btn
INNER JOIN (
    SELECT id AS page_id FROM sys_menu WHERE path = '/file/list' AND type = 2 ORDER BY id LIMIT 1
) p
SET btn.parent_id = p.page_id
WHERE btn.permission IN ('file:upload', 'file:remove') OR btn.id IN (511, 512);

-- 5) 角色：有文件管理页的，同步授权一级目录
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, d.dir_id
FROM sys_role_menu rm
INNER JOIN sys_menu page ON page.id = rm.menu_id AND page.path = '/file/list' AND page.type = 2
INNER JOIN (
    SELECT id AS dir_id FROM sys_menu WHERE type = 1 AND parent_id = 0 AND path = '/file' ORDER BY id LIMIT 1
) d
WHERE NOT EXISTS (
    SELECT 1 FROM sys_role_menu x WHERE x.role_id = rm.role_id AND x.menu_id = d.dir_id
);

-- 6) 角色：有旧 id=5 的，同步授权新页面 51（若存在）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, 51
FROM sys_role_menu rm
WHERE rm.menu_id = 5
  AND EXISTS (SELECT 1 FROM sys_menu WHERE id = 51)
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_menu x WHERE x.role_id = rm.role_id AND x.menu_id = 51
  );
