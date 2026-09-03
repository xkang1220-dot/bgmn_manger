-- 隐藏独立「项目分钱」菜单，功能已并入「项目账款 → 分成与分钱」（可重复执行）
USE kk_manager;
SET NAMES utf8mb4;

UPDATE sys_menu SET visible = 0, status = 0, name = '项目分钱(已并入项目账款)' WHERE id = 24;

-- 项目账款保持可见
UPDATE sys_menu SET name = '项目账款', path = '/finance/project-account', component = 'finance/project-account',
    permission = 'finance:project:list', sort = 2, visible = 1, status = 1
WHERE id = 26;

SELECT id, name, path, visible, status, sort FROM sys_menu
WHERE id IN (21, 22, 24, 25, 26, 27) ORDER BY sort, id;
