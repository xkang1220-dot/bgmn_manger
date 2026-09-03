-- 清理冗余财务菜单 + 配置角色菜单权限 + 演示账号
-- 用法: mysql -uroot -p123456 --default-character-set=utf8mb4 < sql/upgrade_roles_menus_accounts.sql
-- 可重复执行；密码均为 admin123
USE kk_manager;
SET NAMES utf8mb4;

-- ========== 1. 删除已并入/废弃的菜单（逻辑删除，菜单管理里不再显示）==========
-- 21 公司账户/资金池、211 编辑资金池、24 项目分钱、25 项目分层
UPDATE sys_menu SET deleted = 1, visible = 0, status = 0, update_time = NOW()
WHERE id IN (21, 211, 24, 25) AND deleted = 0;

DELETE FROM sys_role_menu WHERE menu_id IN (21, 211, 24, 25);
-- 物理删除，避免菜单管理因逻辑删除未过滤仍可见
DELETE FROM sys_menu WHERE id IN (211, 21, 24, 25);

-- 保留财务可见菜单排序与文案
UPDATE sys_menu SET name = '公司总账', sort = 1, visible = 1, status = 1, deleted = 0 WHERE id = 22;
UPDATE sys_menu SET name = '项目账款', sort = 2, visible = 1, status = 1, deleted = 0 WHERE id = 26;
UPDATE sys_menu SET name = '全员钱包', sort = 3, visible = 1, status = 1, deleted = 0 WHERE id = 27;
UPDATE sys_menu SET name = '我的钱包', sort = 4, visible = 1, status = 1, deleted = 0 WHERE id = 23;
UPDATE sys_menu SET name = '登记总账', permission = 'finance:ledger:add', visible = 1, status = 1, deleted = 0 WHERE id = 221;

-- ========== 2. 确保角色存在 ==========
INSERT INTO sys_role (id, name, code, sort, status, data_scope, remark)
SELECT 2, '部门主管', 'dept_lead', 2, 1, 4, '本部门业务与档案'
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE id = 2 OR code = 'dept_lead');

INSERT INTO sys_role (id, name, code, sort, status, data_scope, remark)
SELECT 3, '普通员工', 'staff', 3, 1, 5, '个人钱包、任务、发起/确认审批'
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE id = 3 OR code = 'staff');

INSERT INTO sys_role (id, name, code, sort, status, data_scope, remark)
SELECT 4, '股东', 'shareholder', 4, 1, 1, '建删项目/资金配置会签'
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE id = 4 OR code = 'shareholder');

INSERT INTO sys_role (id, name, code, sort, status, data_scope, remark)
SELECT 5, '财务', 'finance', 5, 1, 1, '公司总账、项目账款、资金审批与回执'
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE id = 5 OR code = 'finance');

UPDATE sys_role SET name = '超级管理员', remark = '全部权限' WHERE id = 1;
UPDATE sys_role SET name = '部门主管', remark = '部门业务与人事档案' WHERE id = 2;
UPDATE sys_role SET name = '普通员工', remark = '我的钱包、任务、审批发起/确认' WHERE id = 3;
UPDATE sys_role SET name = '股东', remark = '项目会签、资金配置会签' WHERE id = 4;
UPDATE sys_role SET name = '财务', remark = '总账/项目账款/审批处理与回执' WHERE id = 5;

-- ========== 3. 重配角色-菜单（先清非管理员，再写入）==========
DELETE FROM sys_role_menu WHERE role_id IN (2, 3, 4, 5);

-- 管理员：全部未删除菜单
DELETE FROM sys_role_menu WHERE role_id = 1;
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE deleted = 0;

-- 财务 finance：财务全模块 + 审批处理 + 项目查看 + 文件
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 5, id FROM sys_menu WHERE deleted = 0 AND id IN (
  1,
  2, 22, 221, 26, 261, 27, 23,
  7, 71, 711,
  3, 31, 32,
  5, 511, 512
);

-- 股东 shareholder：财务总览（总账/项目账款/钱包/资金配置）+ 会签 + 项目 + 账号管理
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 4, id FROM sys_menu WHERE deleted = 0 AND id IN (
  1,
  2, 22, 26, 261, 23, 27,
  7, 71, 711,
  3, 31, 311, 312, 313, 32,
  5, 511,
  6, 61, 611, 612, 613, 614, 62
);

-- 普通员工 staff：我的钱包 + 审批（发起/确认）+ 项目任务 + 文件上传
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 3, id FROM sys_menu WHERE deleted = 0 AND id IN (
  1,
  2, 23,
  7, 71,
  3, 31, 32, 321, 322,
  5, 511
);

-- 部门主管 dept_lead：员工能力 + 人事档案 + 项目账款只读
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 2, id FROM sys_menu WHERE deleted = 0 AND id IN (
  1,
  2, 23, 26,
  7, 71,
  3, 31, 32, 321, 322,
  4, 41, 411, 412,
  5, 511
);

-- ========== 4. 演示账号（密码均为 admin123）==========
-- 保留 admin；重建业务账号
DELETE FROM hr_archive WHERE user_id IN (2, 3, 4, 5, 6, 7);
DELETE FROM hr_wallet WHERE user_id IN (2, 3, 4, 5, 6, 7);
DELETE FROM sys_user_role WHERE user_id IN (2, 3, 4, 5, 6, 7);
DELETE FROM sys_user WHERE id IN (2, 3, 4, 5, 6, 7);

-- bcrypt of admin123
SET @pwd = '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2';

INSERT INTO sys_user (id, dept_id, username, password, nickname, gender, status, remark) VALUES
(2, 3, 'zhangsan', @pwd, '张三', 1, 1, '普通员工-业务部'),
(3, 3, 'lisi',     @pwd, '李四', 1, 1, '普通员工-业务部'),
(4, 4, 'caiwu',    @pwd, '财务小陈', 2, 1, '财务角色'),
(5, 2, 'gudong',   @pwd, '股东老周', 1, 1, '股东角色'),
(6, 3, 'zhuguan',  @pwd, '业务主管', 1, 1, '部门主管角色'),
(7, 3, 'wangwu',   @pwd, '王五', 2, 1, '普通员工');

INSERT INTO sys_user_role (user_id, role_id) VALUES
(2, 3),   -- zhangsan → staff
(3, 3),   -- lisi → staff
(4, 5),   -- caiwu → finance
(5, 4),   -- gudong → shareholder
(6, 2),   -- zhuguan → dept_lead
(7, 3);   -- wangwu → staff

-- admin 保留超管；额外挂股东便于会签演示（去掉重复的 finance，避免和专职财务混淆）
DELETE FROM sys_user_role WHERE user_id = 1;
INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1), (1, 4);

INSERT INTO hr_archive (user_id, real_name, employee_no, entry_date, remark) VALUES
(2, '张三', 'EMP001', '2024-03-01', '演示-员工'),
(3, '李四', 'EMP002', '2024-06-15', '演示-员工'),
(4, '财务小陈', 'FIN001', '2024-01-01', '演示-财务'),
(5, '股东老周', 'SHR001', '2023-01-01', '演示-股东'),
(6, '业务主管', 'LEAD01', '2023-06-01', '演示-主管'),
(7, '王五', 'EMP003', '2025-01-10', '演示-员工');

INSERT INTO hr_wallet (user_id, balance, frozen, status) VALUES
(2, 0.00, 0.00, 1),
(3, 0.00, 0.00, 1),
(4, 0.00, 0.00, 1),
(5, 0.00, 0.00, 1),
(6, 0.00, 0.00, 1),
(7, 0.00, 0.00, 1);

-- ========== 5. 核对 ==========
SELECT '--- roles ---' AS section;
SELECT id, name, code FROM sys_role ORDER BY id;

SELECT '--- finance menus (visible) ---' AS section;
SELECT id, name, path, visible, status, deleted, sort
FROM sys_menu WHERE parent_id = 2 OR id = 2 ORDER BY sort, id;

SELECT '--- role menu counts ---' AS section;
SELECT r.code, COUNT(rm.menu_id) AS menu_cnt
FROM sys_role r
LEFT JOIN sys_role_menu rm ON r.id = rm.role_id
GROUP BY r.id, r.code ORDER BY r.id;

SELECT '--- demo accounts ---' AS section;
SELECT u.id, u.username, u.nickname, d.name AS dept, GROUP_CONCAT(r.name) AS roles
FROM sys_user u
LEFT JOIN sys_dept d ON u.dept_id = d.id
LEFT JOIN sys_user_role ur ON u.id = ur.user_id
LEFT JOIN sys_role r ON ur.role_id = r.id
GROUP BY u.id ORDER BY u.id;
