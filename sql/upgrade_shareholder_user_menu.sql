-- 股东可管理账号：系统管理目录 + 账号管理（含按钮）+ 角色列表（新建/编辑账号时选角色）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 4, m.id
FROM sys_menu m
WHERE m.deleted = 0
  AND m.id IN (6, 61, 611, 612, 613, 614, 62)
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 4 AND rm.menu_id = m.id
  );

-- remark 可选；执行时请加 --default-character-set=utf8mb4
-- UPDATE sys_role SET remark = '项目会签、资金配置会签、账号管理' WHERE id = 4 OR code = 'shareholder';
