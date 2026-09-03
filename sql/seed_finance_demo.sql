-- 财务演示数据（干净版，匹配当前模型：公司 → 项目账款 → 个人钱包）
-- 用法: mysql -uroot -p123456 --default-character-set=utf8mb4 < sql/seed_finance_demo.sql
-- 可重复执行：会清空财务相关演示流水后重建
USE kk_manager;
SET NAMES utf8mb4;

-- ========== 0. 清理财务/审批演示脏数据 ==========
DELETE FROM wf_approval_log;
DELETE FROM wf_approval_task;
DELETE FROM wf_rollback;
DELETE FROM wf_approval;
DELETE FROM fin_ledger;
DELETE FROM fin_project_account;
DELETE FROM hr_wallet;
UPDATE sys_file SET biz_id = NULL WHERE biz_type IN ('ledger', 'ledger_voucher');

-- 任务评论/参与/流转（避免项目重建后孤儿）
DELETE FROM pm_task_comment;
DELETE FROM pm_task_flow;
DELETE FROM pm_task_member;
DELETE FROM pm_task;

DELETE FROM pm_project_member;
DELETE FROM pm_project;

-- 演示账号：保留 admin；业务账号由 upgrade_roles_menus_accounts.sql 准备
-- 本脚本只重置张三/李四钱包与角色为员工（不删财务/股东等账号）
DELETE FROM hr_archive WHERE user_id IN (2, 3);
DELETE FROM sys_user_role WHERE user_id IN (2, 3);
DELETE FROM sys_user WHERE id IN (2, 3);

INSERT INTO sys_user (id, dept_id, username, password, nickname, gender, status, remark) VALUES
(2, 3, 'zhangsan', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '张三', 1, 1, '普通员工-业务部'),
(3, 3, 'lisi',     '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '李四', 1, 1, '普通员工-业务部');

INSERT INTO sys_user_role (user_id, role_id) VALUES (2, 3), (3, 3);

INSERT INTO hr_archive (user_id, real_name, employee_no, entry_date, remark) VALUES
(2, '张三', 'EMP001', '2024-03-01', '演示数据'),
(3, '李四', 'EMP002', '2024-06-15', '演示数据');

-- ========== 1. 两个项目 + 资金配置 + 分成人员 ==========
-- 分成 + 预留 = 100%；支出不占比例
INSERT INTO pm_project (
  id, name, code, owner_id, pool_id, budget, settled_amount, reserve_amount,
  expense_percent, reserve_percent, settle_percent,
  status, approve_status, start_date, end_date, description
) VALUES
(1, '官网改版', 'WEB-2026', 1, 1, 200000.00, 70000.00, 20000.00,
 0.00, 10.00, 90.00, 1, 1, '2026-01-01', '2026-06-30',
 '演示主项目：分成90% / 预留10%；支出从结余扣'),
(2, '移动端APP', 'APP-2026', 2, 1, 100000.00, 40000.00, 0.00,
 0.00, 0.00, 100.00, 1, 1, '2026-02-01', '2026-08-31',
 '演示副项目：分成100%；公司预支后按 5:5 分钱');

INSERT INTO pm_project_member (project_id, user_id, layer, percent, remark) VALUES
(1, 1, '主理人', 40.00, 'admin'),
(1, 2, '执行',   35.00, 'zhangsan'),
(1, 3, '协助',   25.00, 'lisi'),
(2, 2, '主理人', 50.00, 'zhangsan'),
(2, 3, '执行',   50.00, 'lisi');

-- ========== 2. 项目账款（最终快照）==========
-- 项目1：预支15万 − 报销3万 − 分钱7万 = 余额5万
-- 项目2：预支8万  − 分钱4万 = 余额4万
INSERT INTO fin_project_account (
  project_id, balance, advance_amount, expense_amount, settle_amount, reserve_amount, reserve_held, status
) VALUES
(1, 50000.00, 150000.00, 30000.00, 70000.00, 0.00, 0.00, 1),
(2, 40000.00,  80000.00,     0.00, 40000.00, 0.00, 0.00, 1);

-- ========== 3. 公司资金池 & 个人钱包（最终快照）==========
-- 公司：入账50万 − 公司出账2万 − 预支15万 − 预支8万 = 25万
UPDATE fin_pool SET
  balance = 250000.00,
  remark = '演示：入账500000−出账20000−预支官网150000−预支APP80000'
WHERE id = 1;

-- 钱包：官网分钱 28000/24500/17500 + APP分钱 20000/20000；其他角色钱包 0
INSERT INTO hr_wallet (user_id, balance, frozen, status, remark) VALUES
(1, 28000.00, 0.00, 1, '官网改版分成 40%'),
(2, 44500.00, 0.00, 1, '官网35% + APP50%'),
(3, 37500.00, 0.00, 1, '官网25% + APP50%'),
(4,     0.00, 0.00, 1, '财务账号'),
(5,     0.00, 0.00, 1, '股东账号'),
(6,     0.00, 0.00, 1, '主管账号'),
(7,     0.00, 0.00, 1, '员工账号')
ON DUPLICATE KEY UPDATE balance = VALUES(balance), remark = VALUES(remark);

-- ========== 4. 流水时间线（related_id = 批次号，与当前动账写法一致）==========
-- ① 公司入账
INSERT INTO fin_ledger (id, biz_no, biz_type, account_type, pool_id, user_id, amount, before_balance, after_balance, project_id, related_id, title, remark, occur_time) VALUES
(1, 'LG0000000001', 'INCOME', 'POOL', 1, NULL, 500000.00, 0.00, 500000.00, NULL, 1,
 '客户A+B季度回款', '外部资金进入公司', '2026-01-05 10:00:00');

-- ② 公司出账（真正离开系统）
INSERT INTO fin_ledger (id, biz_no, biz_type, account_type, pool_id, user_id, amount, before_balance, after_balance, project_id, related_id, title, remark, occur_time) VALUES
(2, 'LG0000000002', 'EXPENSE', 'POOL', 1, NULL, -20000.00, 500000.00, 480000.00, NULL, 2,
 '办公租金', '公司日常支出，钱离开系统', '2026-01-12 14:00:00');

-- ③ 预支到官网改版（公司 → 项目）
INSERT INTO fin_ledger (id, biz_no, biz_type, account_type, pool_id, user_id, amount, before_balance, after_balance, project_id, related_id, title, remark, occur_time) VALUES
(3, 'LG0000000003', 'ADVANCE', 'POOL',    1, NULL, -150000.00, 480000.00, 330000.00, 1, 3,
 '预支到项目 · 官网改版', '公司拨入项目账款', '2026-02-01 09:30:00'),
(4, 'LG0000000004', 'ADVANCE', 'PROJECT', 1, NULL,  150000.00,      0.00, 150000.00, 1, 3,
 '公司转入 · 官网改版', NULL, '2026-02-01 09:30:01');

-- ④ 官网项目报销（项目支出，离开系统）
INSERT INTO fin_ledger (id, biz_no, biz_type, account_type, pool_id, user_id, amount, before_balance, after_balance, project_id, related_id, title, remark, occur_time) VALUES
(5, 'LG0000000005', 'EXPENSE', 'PROJECT', NULL, NULL, -30000.00, 150000.00, 120000.00, 1, 5,
 '项目报销 · 服务器采购', '占用报销/工资额度', '2026-02-10 11:00:00');

-- ⑤ 官网按比例分钱 7万：主理40 / 执行35 / 协助25
INSERT INTO fin_ledger (id, biz_no, biz_type, account_type, pool_id, user_id, amount, before_balance, after_balance, project_id, related_id, title, remark, occur_time) VALUES
(6,  'LG0000000006', 'SETTLE', 'PROJECT', 1, NULL, -70000.00, 120000.00, 50000.00, 1, 6,
 '项目分钱 · 官网改版', '按 40/35/25 分成', '2026-02-20 16:00:00'),
(7,  'LG0000000007', 'SETTLE', 'WALLET',  1, 1,    28000.00,      0.00, 28000.00, 1, 6,
 '项目分钱 · 官网改版 / 主理人', NULL, '2026-02-20 16:00:01'),
(8,  'LG0000000008', 'SETTLE', 'WALLET',  1, 2,    24500.00,      0.00, 24500.00, 1, 6,
 '项目分钱 · 官网改版 / 执行', NULL, '2026-02-20 16:00:02'),
(9,  'LG0000000009', 'SETTLE', 'WALLET',  1, 3,    17500.00,      0.00, 17500.00, 1, 6,
 '项目分钱 · 官网改版 / 协助', NULL, '2026-02-20 16:00:03');

-- ⑥ 预支到移动端APP
INSERT INTO fin_ledger (id, biz_no, biz_type, account_type, pool_id, user_id, amount, before_balance, after_balance, project_id, related_id, title, remark, occur_time) VALUES
(10, 'LG0000000010', 'ADVANCE', 'POOL',    1, NULL, -80000.00, 330000.00, 250000.00, 2, 10,
 '预支到项目 · 移动端APP', '公司拨入项目账款', '2026-03-01 10:00:00'),
(11, 'LG0000000011', 'ADVANCE', 'PROJECT', 1, NULL,  80000.00,      0.00,  80000.00, 2, 10,
 '公司转入 · 移动端APP', NULL, '2026-03-01 10:00:01');

-- ⑦ APP 按比例分钱 4万：张三50 / 李四50
INSERT INTO fin_ledger (id, biz_no, biz_type, account_type, pool_id, user_id, amount, before_balance, after_balance, project_id, related_id, title, remark, occur_time) VALUES
(12, 'LG0000000012', 'SETTLE', 'PROJECT', 1, NULL, -40000.00, 80000.00, 40000.00, 2, 12,
 '项目分钱 · 移动端APP', '按 50/50 分成', '2026-03-15 15:30:00'),
(13, 'LG0000000013', 'SETTLE', 'WALLET',  1, 2,    20000.00, 24500.00, 44500.00, 2, 12,
 '项目分钱 · 移动端APP / 主理人', NULL, '2026-03-15 15:30:01'),
(14, 'LG0000000014', 'SETTLE', 'WALLET',  1, 3,    20000.00, 17500.00, 37500.00, 2, 12,
 '项目分钱 · 移动端APP / 执行', NULL, '2026-03-15 15:30:02');

ALTER TABLE fin_ledger AUTO_INCREMENT = 100;
ALTER TABLE pm_project AUTO_INCREMENT = 10;
ALTER TABLE fin_project_account AUTO_INCREMENT = 10;
ALTER TABLE hr_wallet AUTO_INCREMENT = 20;
ALTER TABLE wf_approval AUTO_INCREMENT = 1;

-- ========== 5. 几条轻量任务（可选演示用）==========
INSERT INTO pm_task (id, project_id, title, status, priority, assignee_id, progress, start_date, due_date) VALUES
(1, 1, '首页视觉稿确认', 2, 2, 2, 100, '2026-01-05', '2026-01-20'),
(2, 1, '后台接口联调',   1, 1, 3,  40, '2026-02-01', '2026-02-28'),
(3, 2, '登录注册流程',   1, 2, 2,  60, '2026-02-10', '2026-03-10');
ALTER TABLE pm_task AUTO_INCREMENT = 100;

-- ========== 6. 校验：系统内资金守恒 ==========
-- 入账500000 − 公司出账20000 − 项目报销30000 = 450000
-- = 公司250000 + 项目(5万+4万) + 个人(2.8+4.45+3.75)万
SELECT '=== 公司 / 项目 / 个人 ===' AS section;
SELECT '公司' AS bucket, balance AS amount FROM fin_pool WHERE id = 1
UNION ALL
SELECT CONCAT('项目-', p.name), a.balance
FROM fin_project_account a JOIN pm_project p ON p.id = a.project_id
UNION ALL
SELECT CONCAT('钱包-', u.nickname), w.balance
FROM hr_wallet w JOIN sys_user u ON u.id = w.user_id
ORDER BY bucket;

SELECT '=== 守恒校验 ===' AS section;
SELECT
  (SELECT balance FROM fin_pool WHERE id = 1) AS company,
  (SELECT IFNULL(SUM(balance),0) FROM fin_project_account WHERE deleted = 0) AS projects,
  (SELECT IFNULL(SUM(balance),0) FROM hr_wallet WHERE deleted = 0) AS wallets,
  (SELECT balance FROM fin_pool WHERE id = 1)
    + (SELECT IFNULL(SUM(balance),0) FROM fin_project_account WHERE deleted = 0)
    + (SELECT IFNULL(SUM(balance),0) FROM hr_wallet WHERE deleted = 0) AS assets_total,
  450000.00 AS expect_assets;

SELECT '=== 流水时间线 ===' AS section;
SELECT occur_time, biz_no, biz_type, account_type, title, amount, after_balance,
       CASE user_id WHEN 1 THEN 'admin' WHEN 2 THEN '张三' WHEN 3 THEN '李四' ELSE COALESCE((SELECT nickname FROM sys_user WHERE id = user_id), '-') END AS person
FROM fin_ledger WHERE deleted = 0 ORDER BY occur_time, id;
