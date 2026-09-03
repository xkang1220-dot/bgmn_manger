-- 个人中心侧栏保留；账号资料仅顶栏进入（可重复执行）
USE kk_manager;

INSERT INTO sys_menu (id, parent_id, name, type, path, component, permission, icon, sort, visible, status)
SELECT 8, 0, '个人中心', 2, '/account', 'account/index', 'account:view', 'User', 2, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 8);

UPDATE sys_menu
SET parent_id = 0, name = '个人中心', type = 2, path = '/account', component = 'account/index',
    permission = 'account:view', icon = 'User', sort = 2, visible = 1, status = 1
WHERE id = 8;

-- 子菜单全部隐藏（不再拆页）
UPDATE sys_menu SET visible = 0 WHERE id IN (81, 82, 83, 84, 85, 86);

-- 财务旧「我的钱包」隐藏
UPDATE sys_menu SET visible = 0, name = '我的钱包(已并入个人中心)', path = '/account' WHERE id = 23;

UPDATE sys_menu SET sort = 1 WHERE id = 1;
UPDATE sys_menu SET sort = 2 WHERE id = 8;
UPDATE sys_menu SET sort = 3 WHERE id = 2;
UPDATE sys_menu SET sort = 4 WHERE id = 7;
UPDATE sys_menu SET sort = 5 WHERE id = 3;
UPDATE sys_menu SET sort = 6 WHERE id = 4;
UPDATE sys_menu SET sort = 7 WHERE id = 5;
UPDATE sys_menu SET sort = 8 WHERE id = 6;

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 8
FROM sys_role r
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 8);
