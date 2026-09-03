-- 隐藏「项目分层/项目分成」独立菜单，功能并入项目账款（可重复执行）
USE kk_manager;
SET NAMES utf8mb4;

-- 项目分层（旧独立页）
UPDATE sys_menu SET visible = 0, status = 0, name = '项目分层(已并入项目账款)' WHERE id = 25;

-- 若仍显示「公司账户」再藏一次
UPDATE sys_menu SET visible = 0, status = 0 WHERE id IN (21, 211);

-- 项目账款文案
UPDATE sys_menu SET name = '项目账款', path = '/finance/project-account', component = 'finance/project-account',
    permission = 'finance:project:list', sort = 2, visible = 1, status = 1
WHERE id = 26;

SELECT id, name, path, visible, status, sort FROM sys_menu
WHERE id IN (21, 22, 24, 25, 26, 27) ORDER BY sort, id;
