-- 隐藏「公司账户/资金池」菜单，统一走公司总账（可重复执行）
USE kk_manager;
SET NAMES utf8mb4;

UPDATE sys_menu SET visible = 0, status = 0 WHERE id = 21;
UPDATE sys_menu SET visible = 0, status = 0 WHERE id = 211 OR parent_id = 21;

-- 公司总账置顶
UPDATE sys_menu SET name = '公司总账', path = '/finance/ledger', component = 'finance/ledger',
    permission = 'finance:ledger:list', sort = 1, visible = 1, status = 1
WHERE id = 22;

SELECT id, name, path, visible, status, sort FROM sys_menu WHERE id IN (21, 22, 211, 25, 26, 27) ORDER BY sort, id;
