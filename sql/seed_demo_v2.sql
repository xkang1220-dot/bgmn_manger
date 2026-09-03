-- =============================================================================
-- KK Manager 演示数据 v2（可重复执行）
-- 用法:
--   mysql -uroot -p123456 --default-character-set=utf8mb4 kk_manager < sql/seed_demo_v2.sql
--
-- 会清空并重建：财务流水/项目账款/钱包余额/项目任务/审批演示数据
-- 保留：账号、角色、菜单、部门、审批流配置
--
-- 账号（密码均为 admin123）:
--   admin / 系统管理员（admin+股东）
--   zhangsan / 张三（员工，APP 负责人）
--   lisi / 李四（员工）
--   wangwu / 王五（员工）
--   caiwu / 财务小陈
--   gudong / 股东老周
--   zhuguan / 业务主管
-- =============================================================================
USE kk_manager;
SET NAMES utf8mb4;

-- ========== 0. 清理业务演示数据 ==========
DELETE FROM wf_approval_log;
DELETE FROM wf_approval_task;
DELETE FROM wf_rollback;
DELETE FROM wf_approval;

DELETE FROM fin_month_verify;
DELETE FROM fin_ledger;
DELETE FROM fin_project_account;

DELETE FROM pm_task_comment;
DELETE FROM pm_task_flow;
DELETE FROM pm_task_member;
DELETE FROM pm_task;

DELETE FROM pm_project_member;
DELETE FROM pm_project;

DELETE FROM hr_wallet;
DELETE FROM sys_notification;

UPDATE sys_file SET biz_id = NULL, deleted = 1
WHERE biz_type IN ('ledger', 'ledger_voucher', 'task_image', 'task_flow', 'month_verify');

-- ========== 1. 确保演示账号齐全 ==========
INSERT INTO sys_user (id, dept_id, username, password, nickname, gender, status, remark)
SELECT 2, 3, 'zhangsan', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '张三', 1, 1, '演示-员工'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 2);
INSERT INTO sys_user (id, dept_id, username, password, nickname, gender, status, remark)
SELECT 3, 3, 'lisi', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '李四', 1, 1, '演示-员工'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 3);
INSERT INTO sys_user (id, dept_id, username, password, nickname, gender, status, remark)
SELECT 4, 2, 'caiwu', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '财务小陈', 2, 1, '演示-财务'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 4);
INSERT INTO sys_user (id, dept_id, username, password, nickname, gender, status, remark)
SELECT 5, 1, 'gudong', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '股东老周', 1, 1, '演示-股东'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 5);
INSERT INTO sys_user (id, dept_id, username, password, nickname, gender, status, remark)
SELECT 6, 3, 'zhuguan', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '业务主管', 1, 1, '演示-主管'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 6);
INSERT INTO sys_user (id, dept_id, username, password, nickname, gender, status, remark)
SELECT 7, 3, 'wangwu', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '王五', 2, 1, '演示-员工'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 7);

INSERT IGNORE INTO sys_user_role (user_id, role_id) VALUES
(1, 1), (1, 4),
(2, 3), (3, 3), (7, 3),
(4, 5), (5, 4), (6, 2);

INSERT INTO hr_archive (user_id, real_name, employee_no, entry_date, remark)
SELECT 2, '张三', 'EMP001', '2024-03-01', '演示' WHERE NOT EXISTS (SELECT 1 FROM hr_archive WHERE user_id = 2);
INSERT INTO hr_archive (user_id, real_name, employee_no, entry_date, remark)
SELECT 3, '李四', 'EMP002', '2024-06-15', '演示' WHERE NOT EXISTS (SELECT 1 FROM hr_archive WHERE user_id = 3);
INSERT INTO hr_archive (user_id, real_name, employee_no, entry_date, remark)
SELECT 7, '王五', 'EMP003', '2025-01-08', '演示' WHERE NOT EXISTS (SELECT 1 FROM hr_archive WHERE user_id = 7);

-- ========== 2. 收款渠道（余额与入账一致）==========
INSERT INTO fin_pay_channel (id, pool_id, channel_type, name, account_no, account_name, bank_name, balance, sort, status, remark)
SELECT 1, 1, 'ALIPAY', '公司支付宝', '***8888', 'KK科技', NULL, 0, 1, 1, '演示'
WHERE NOT EXISTS (SELECT 1 FROM fin_pay_channel WHERE id = 1 AND deleted = 0);
INSERT INTO fin_pay_channel (id, pool_id, channel_type, name, account_no, account_name, bank_name, balance, sort, status, remark)
SELECT 2, 1, 'BANK', '对公招商银行', '****6601', 'KK科技有限公司', '招商银行深圳支行', 0, 2, 1, '演示'
WHERE NOT EXISTS (SELECT 1 FROM fin_pay_channel WHERE id = 2 AND deleted = 0);

UPDATE fin_pay_channel SET balance = 297000.00, remark = '演示：客户A回款净额（含手续费留痕）' WHERE id = 1;
UPDATE fin_pay_channel SET balance = 200000.00, remark = '演示：客户B对公回款' WHERE id = 2;

-- ========== 3. 三个项目 ==========
-- 分成+预留=100%；支出不占比例
INSERT INTO pm_project (
  id, name, code, owner_id, pool_id, budget, settled_amount, reserve_amount,
  expense_percent, reserve_percent, settle_percent,
  status, approve_status, start_date, end_date, description
) VALUES
(1, '官网改版', 'WEB-2026', 1, 1, 200000.00, 70000.00, 20000.00,
 0.00, 10.00, 90.00, 1, 1, '2026-01-01', '2026-10-31',
 '主项目：分成90% / 预留10%；看板+日历演示'),
(2, '移动端APP', 'APP-2026', 2, 1, 150000.00, 40000.00, 0.00,
 0.00, 0.00, 100.00, 1, 1, '2026-02-01', '2026-11-30',
 '张三负责；看板分布完整'),
(3, '内部管理系统', 'OPS-2026', 6, 1, 80000.00, 0.00, 8000.00,
 0.00, 10.00, 90.00, 1, 1, '2026-06-01', '2026-12-31',
 '主管负责的筹备/进行中项目');

INSERT INTO pm_project_member (project_id, user_id, layer, percent, remark) VALUES
(1, 1, '主理人', 40.00, 'admin'),
(1, 2, '执行',   35.00, 'zhangsan'),
(1, 3, '协助',   25.00, 'lisi'),
(2, 2, '主理人', 50.00, 'zhangsan'),
(2, 3, '执行',   30.00, 'lisi'),
(2, 7, '协助',   20.00, 'wangwu'),
(3, 6, '主理人', 60.00, 'zhuguan'),
(3, 2, '执行',   40.00, 'zhangsan');

-- ========== 4. 项目账款快照 ==========
-- 官网：预支15万 − 报销3万 − 分钱7万 = 5万
-- APP：预支10万 − 分钱4万 = 6万
-- 内部：预支5万 = 5万
INSERT INTO fin_project_account (
  project_id, balance, advance_amount, expense_amount, settle_amount, reserve_amount, reserve_held, status
) VALUES
(1, 50000.00, 150000.00, 30000.00, 70000.00, 0.00, 0.00, 1),
(2, 60000.00, 100000.00,     0.00, 40000.00, 0.00, 0.00, 1),
(3, 50000.00,  50000.00,     0.00,     0.00, 0.00, 0.00, 1);

-- ========== 5. 公司资金池 & 钱包 ==========
-- 入账净额 497000 − 公司支出 20000 − 预支 300000 = 177000
UPDATE fin_pool SET
  balance = 177000.00,
  remark = '演示v2：净入账497000−出账20000−预支300000'
WHERE id = 1;

INSERT INTO hr_wallet (user_id, balance, frozen, status, remark) VALUES
(1, 28000.00, 0.00, 1, '官网分成40%'),
(2, 44500.00, 0.00, 1, '官网35% + APP50%'),
(3, 29500.00, 0.00, 1, '官网25% + APP30%'),
(4,     0.00, 0.00, 1, '财务'),
(5,     0.00, 0.00, 1, '股东'),
(6,     0.00, 0.00, 1, '主管'),
(7,  8000.00, 0.00, 1, 'APP协助20%');

-- ========== 6. 财务流水（守恒）==========
-- ① 支付宝入账：毛 30万，手续费 1% = 3000，净入池 297000
INSERT INTO fin_ledger
(id, biz_no, biz_type, account_type, pool_id, channel_id, user_id, amount, gross_amount, fee_amount, fee_mode,
 before_balance, after_balance, project_id, related_id, title, remark, occur_time) VALUES
(1, 'LG20260105001', 'INCOME', 'POOL', 1, 1, NULL, 300000.00, 300000.00, 3000.00, 'PERCENT',
 0.00, 300000.00, NULL, 1, '客户A 季度回款（支付宝）', '毛额入账', '2026-01-05 10:00:00'),
(2, 'LG20260105002', 'FEE', 'POOL', 1, 1, NULL, -3000.00, 300000.00, 3000.00, 'PERCENT',
 300000.00, 297000.00, NULL, 1, '手续费 · 客户A回款', '支付宝 1%', '2026-01-05 10:00:01');

-- ② 对公入账 20万
INSERT INTO fin_ledger
(id, biz_no, biz_type, account_type, pool_id, channel_id, user_id, amount, gross_amount, fee_amount, fee_mode,
 before_balance, after_balance, project_id, related_id, title, remark, occur_time) VALUES
(3, 'LG20260108001', 'INCOME', 'POOL', 1, 2, NULL, 200000.00, 200000.00, 0.00, 'FIXED',
 297000.00, 497000.00, NULL, 3, '客户B 对公回款', '招商银行', '2026-01-08 14:30:00');

-- ③ 公司出账
INSERT INTO fin_ledger
(id, biz_no, biz_type, account_type, pool_id, channel_id, user_id, amount,
 before_balance, after_balance, project_id, related_id, title, remark, occur_time) VALUES
(4, 'LG20260115001', 'EXPENSE', 'POOL', 1, NULL, NULL, -20000.00,
 497000.00, 477000.00, NULL, 4, '办公租金', '钱离开系统', '2026-01-15 11:00:00');

-- ④ 预支官网 15万
INSERT INTO fin_ledger
(id, biz_no, biz_type, account_type, pool_id, channel_id, user_id, amount,
 before_balance, after_balance, project_id, related_id, title, remark, occur_time) VALUES
(5, 'LG20260201001', 'ADVANCE', 'POOL', 1, NULL, NULL, -150000.00,
 477000.00, 327000.00, 1, 5, '预支到项目 · 官网改版', '公司→项目', '2026-02-01 09:30:00'),
(6, 'LG20260201002', 'ADVANCE', 'PROJECT', 1, NULL, NULL, 150000.00,
 0.00, 150000.00, 1, 5, '公司转入 · 官网改版', NULL, '2026-02-01 09:30:01');

-- ⑤ 官网报销 3万
INSERT INTO fin_ledger
(id, biz_no, biz_type, account_type, pool_id, channel_id, user_id, amount,
 before_balance, after_balance, project_id, related_id, title, remark, occur_time) VALUES
(7, 'LG20260210001', 'EXPENSE', 'PROJECT', NULL, NULL, NULL, -30000.00,
 150000.00, 120000.00, 1, 7, '项目报销 · 服务器采购', '离开系统', '2026-02-10 11:00:00');

-- ⑥ 官网分钱 7万：40/35/25
INSERT INTO fin_ledger
(id, biz_no, biz_type, account_type, pool_id, channel_id, user_id, amount,
 before_balance, after_balance, project_id, related_id, title, remark, occur_time) VALUES
(8,  'LG20260220001', 'SETTLE', 'PROJECT', 1, NULL, NULL, -70000.00,
 120000.00, 50000.00, 1, 8, '项目分钱 · 官网改版', '40/35/25', '2026-02-20 16:00:00'),
(9,  'LG20260220002', 'SETTLE', 'WALLET', 1, NULL, 1, 28000.00,
 0.00, 28000.00, 1, 8, '项目分钱 · 官网改版 / 主理人', NULL, '2026-02-20 16:00:01'),
(10, 'LG20260220003', 'SETTLE', 'WALLET', 1, NULL, 2, 24500.00,
 0.00, 24500.00, 1, 8, '项目分钱 · 官网改版 / 执行', NULL, '2026-02-20 16:00:02'),
(11, 'LG20260220004', 'SETTLE', 'WALLET', 1, NULL, 3, 17500.00,
 0.00, 17500.00, 1, 8, '项目分钱 · 官网改版 / 协助', NULL, '2026-02-20 16:00:03');

-- ⑦ 预支 APP 10万
INSERT INTO fin_ledger
(id, biz_no, biz_type, account_type, pool_id, channel_id, user_id, amount,
 before_balance, after_balance, project_id, related_id, title, remark, occur_time) VALUES
(12, 'LG20260301001', 'ADVANCE', 'POOL', 1, NULL, NULL, -100000.00,
 327000.00, 227000.00, 2, 12, '预支到项目 · 移动端APP', '公司→项目', '2026-03-01 10:00:00'),
(13, 'LG20260301002', 'ADVANCE', 'PROJECT', 1, NULL, NULL, 100000.00,
 0.00, 100000.00, 2, 12, '公司转入 · 移动端APP', NULL, '2026-03-01 10:00:01');

-- ⑧ APP 分钱 4万：50/30/20
INSERT INTO fin_ledger
(id, biz_no, biz_type, account_type, pool_id, channel_id, user_id, amount,
 before_balance, after_balance, project_id, related_id, title, remark, occur_time) VALUES
(14, 'LG20260315001', 'SETTLE', 'PROJECT', 1, NULL, NULL, -40000.00,
 100000.00, 60000.00, 2, 14, '项目分钱 · 移动端APP', '50/30/20', '2026-03-15 15:30:00'),
(15, 'LG20260315002', 'SETTLE', 'WALLET', 1, NULL, 2, 20000.00,
 24500.00, 44500.00, 2, 14, '项目分钱 · 移动端APP / 主理人', NULL, '2026-03-15 15:30:01'),
(16, 'LG20260315003', 'SETTLE', 'WALLET', 1, NULL, 3, 12000.00,
 17500.00, 29500.00, 2, 14, '项目分钱 · 移动端APP / 执行', NULL, '2026-03-15 15:30:02'),
(17, 'LG20260315004', 'SETTLE', 'WALLET', 1, NULL, 7, 8000.00,
 0.00, 8000.00, 2, 14, '项目分钱 · 移动端APP / 协助', NULL, '2026-03-15 15:30:03');

-- ⑨ 预支内部系统 5万
INSERT INTO fin_ledger
(id, biz_no, biz_type, account_type, pool_id, channel_id, user_id, amount,
 before_balance, after_balance, project_id, related_id, title, remark, occur_time) VALUES
(18, 'LG20260601001', 'ADVANCE', 'POOL', 1, NULL, NULL, -50000.00,
 227000.00, 177000.00, 3, 18, '预支到项目 · 内部管理系统', '公司→项目', '2026-06-01 09:00:00'),
(19, 'LG20260601002', 'ADVANCE', 'PROJECT', 1, NULL, NULL, 50000.00,
 0.00, 50000.00, 3, 18, '公司转入 · 内部管理系统', NULL, '2026-06-01 09:00:01');

-- ========== 7. 任务（围绕 2026-09 日历）==========
INSERT INTO pm_task
(id, project_id, title, content, status, priority, assignee_id, start_date, due_date, progress, create_by, create_time) VALUES
-- 官网：待办 / 进行中 / 完成
(1, 1, '首页改版验收', '桌面端+移动端走查，整理缺陷清单', 2, 1, 2, '2026-08-01', '2026-08-20', 100, 1, '2026-08-02 10:00:00'),
(2, 1, '内容后台联调', '文章/Banner 接口联调与权限校验', 1, 1, 3, '2026-08-25', '2026-09-05', 70, 2, '2026-08-26 11:00:00'),
(3, 1, 'SEO 与 sitemap', '标题描述、sitemap、结构化数据', 1, 2, 3, '2026-09-01', '2026-09-12', 35, 1, '2026-09-01 09:30:00'),
(4, 1, '上线前回归', '主流程回归 + 埋点核对', 0, 1, 1, '2026-09-10', '2026-09-18', 0, 1, '2026-09-02 14:00:00'),
(5, 1, '旧站 301 配置', '配置跳转规则，避免收录丢失', 0, 2, 2, '2026-09-12', '2026-09-22', 0, 2, '2026-09-03 10:00:00'),
(6, 1, '客户培训材料', '后台操作手册与录屏', 0, 3, 7, '2026-09-15', '2026-09-28', 0, 1, '2026-09-03 16:00:00'),

-- APP：看板完整
(7,  2, '需求评审与排期', '确认 MVP 范围', 2, 1, 2, '2026-07-01', '2026-07-10', 100, 2, '2026-07-02 10:00:00'),
(8,  2, '登录注册流程', '手机号登录、验证码、协议页', 2, 1, 2, '2026-07-15', '2026-08-05', 100, 2, '2026-07-16 11:00:00'),
(9,  2, '首页信息流', '推荐流、下拉刷新、分页', 1, 1, 3, '2026-08-20', '2026-09-08', 55, 2, '2026-08-21 09:00:00'),
(10, 2, '消息推送接入', '厂商推送与点击跳转', 1, 2, 2, '2026-09-01', '2026-09-15', 30, 3, '2026-09-01 15:00:00'),
(11, 2, '个人中心与设置', '资料、安全、关于', 0, 2, 7, '2026-09-08', '2026-09-20', 0, 2, '2026-09-03 10:00:00'),
(12, 2, '性能与包体积', '启动耗时、图片压缩', 0, 3, 3, '2026-09-18', '2026-09-30', 0, 2, '2026-09-03 14:00:00'),
(13, 2, '应用商店提审材料', '截图、描述、隐私协议', 0, 2, 2, '2026-09-22', '2026-10-08', 0, 1, '2026-09-03 16:30:00'),
(14, 2, '逾期演示：埋点终版确认', '故意逾期，用于看板红标', 1, 1, 3, '2026-08-01', '2026-08-28', 20, 2, '2026-08-02 10:00:00'),

-- 内部系统
(15, 3, '流程痛点访谈', '运营/客服/财务各访谈 2 人', 1, 2, 6, '2026-08-20', '2026-09-10', 50, 6, '2026-08-21 10:00:00'),
(16, 3, '技术方案选型', '自研 vs 采购对比', 0, 2, 2, '2026-09-05', '2026-09-25', 0, 6, '2026-09-02 11:00:00'),
(17, 3, '立项材料准备', '预算、人力、里程碑', 0, 3, 6, '2026-09-15', '2026-09-30', 0, 6, '2026-09-03 09:00:00'),
(18, 3, '权限模型草案', '角色与数据范围初稿', 0, 2, 1, '2026-09-20', '2026-10-05', 0, 6, '2026-09-03 15:00:00');

INSERT INTO pm_task_member (task_id, user_id) VALUES
(1, 1), (1, 3),
(2, 2),
(3, 2),
(4, 2), (4, 3),
(5, 3),
(6, 2),
(8, 3), (8, 7),
(9, 2), (9, 7),
(10, 3),
(11, 2),
(12, 2), (12, 7),
(14, 2), (14, 7),
(15, 2),
(16, 1),
(17, 2),
(18, 2);

INSERT INTO pm_task_comment (task_id, content, create_by, create_time) VALUES
(1, '验收缺陷已关单，可以进入联调。', 2, '2026-08-18 16:00:00'),
(2, 'Banner 上传接口超时，今晚加超时重试。', 3, '2026-09-02 20:10:00'),
(4, '回归 checklist 放飞书「官网/上线」目录。', 1, '2026-09-03 11:00:00'),
(9, '空态文案：暂时没有内容，去看看别的。', 2, '2026-09-01 10:00:00'),
(10, '华为通道还在审核，先用小米联调。', 2, '2026-09-02 16:00:00'),
(14, '埋点终版请本周确认，否则无法排期。', 2, '2026-08-30 09:30:00'),
(15, '访谈纪要已同步，Top5 痛点标红。', 6, '2026-09-03 17:00:00');

INSERT INTO pm_task_flow (task_id, action, from_user_id, to_user_id, from_status, to_status, remark, create_by, create_time) VALUES
(1, 'STATUS', 2, NULL, 1, 2, '验收通过，置为完成', 2, '2026-08-18 17:00:00'),
(8, 'STATUS', 2, NULL, 1, 2, '登录链路提测通过', 2, '2026-08-05 18:00:00'),
(9, 'TRANSFER', 2, 3, NULL, NULL, '信息流转交李四继续', 2, '2026-08-25 10:00:00'),
(10, 'STATUS', 2, NULL, 0, 1, '开始接入推送', 2, '2026-09-01 15:10:00');

-- ========== 8. 审批演示（轻量）==========
INSERT INTO wf_approval
(id, biz_no, type, title, status, applicant_id, amount, project_id, pool_id, payload, confirm_status, pass_mode, remark, create_time) VALUES
(1, 'AP20260903001', 'EXPENSE', '个人报销 · 打车费', 'PENDING', 2, 186.50, NULL, NULL,
 '{"amount":186.50,"remark":"客户拜访打车"}', 0, 'ALL', '演示待办', '2026-09-02 10:00:00'),
(2, 'AP20260903002', 'PROJECT_EXPENSE', '项目报销 · 测试机采购', 'PENDING', 2, 3999.00, 2, NULL,
 '{"amount":3999.00,"projectId":2,"remark":"采购测试机 1 台"}', 0, 'ALL', '演示待办', '2026-09-03 09:20:00'),
(3, 'AP20260901001', 'WALLET_WITHDRAW', '钱包提现 · 张三', 'APPROVED', 2, 5000.00, NULL, NULL,
 '{"amount":5000.00}', 1, 'ALL', '演示已通过', '2026-09-01 14:00:00');

INSERT INTO wf_approval_task (approval_id, assignee_id, action, comment, act_time, create_time) VALUES
(1, 4, 'PENDING', NULL, NULL, '2026-09-02 10:00:00'),
(2, 4, 'PENDING', NULL, NULL, '2026-09-03 09:20:00'),
(3, 4, 'APPROVE', '同意提现', '2026-09-01 15:00:00', '2026-09-01 14:00:00');

INSERT INTO wf_approval_log (approval_id, action, operator_id, remark, create_time) VALUES
(1, 'SUBMIT', 2, '提交报销', '2026-09-02 10:00:00'),
(2, 'SUBMIT', 2, '提交项目报销', '2026-09-03 09:20:00'),
(3, 'SUBMIT', 2, '提交提现', '2026-09-01 14:00:00'),
(3, 'APPROVE', 4, '同意提现', '2026-09-01 15:00:00');

INSERT INTO sys_notification (user_id, title, content, biz_type, biz_id, read_flag, create_time) VALUES
(4, '待审批：个人报销 · 打车费', '张三提交了一笔报销，请处理', 'APPROVAL', 1, 0, '2026-09-02 10:00:01'),
(4, '待审批：项目报销 · 测试机采购', '张三提交了项目报销，请处理', 'APPROVAL', 2, 0, '2026-09-03 09:20:01'),
(2, '提现已通过', '你的提现申请已通过', 'APPROVAL', 3, 0, '2026-09-01 15:00:01');

-- ========== 9. 自增复位 ==========
ALTER TABLE fin_ledger AUTO_INCREMENT = 200;
ALTER TABLE pm_project AUTO_INCREMENT = 20;
ALTER TABLE fin_project_account AUTO_INCREMENT = 20;
ALTER TABLE hr_wallet AUTO_INCREMENT = 50;
ALTER TABLE pm_task AUTO_INCREMENT = 100;
ALTER TABLE pm_task_comment AUTO_INCREMENT = 100;
ALTER TABLE pm_task_flow AUTO_INCREMENT = 100;
ALTER TABLE wf_approval AUTO_INCREMENT = 100;
ALTER TABLE wf_approval_task AUTO_INCREMENT = 100;

-- ========== 10. 校验 ==========
SELECT '=== 资金守恒 ===' AS section;
SELECT
  (SELECT balance FROM fin_pool WHERE id = 1) AS company,
  (SELECT IFNULL(SUM(balance),0) FROM fin_project_account WHERE deleted = 0) AS projects,
  (SELECT IFNULL(SUM(balance),0) FROM hr_wallet WHERE deleted = 0) AS wallets,
  (SELECT balance FROM fin_pool WHERE id = 1)
    + (SELECT IFNULL(SUM(balance),0) FROM fin_project_account WHERE deleted = 0)
    + (SELECT IFNULL(SUM(balance),0) FROM hr_wallet WHERE deleted = 0) AS assets_total,
  447000.00 AS expect_assets,
  (SELECT IFNULL(SUM(balance),0) FROM fin_pay_channel WHERE deleted = 0) AS channel_total;

SELECT '=== 项目看板分布 ===' AS section;
SELECT p.name,
       SUM(t.status = 0) AS todo,
       SUM(t.status = 1) AS doing,
       SUM(t.status = 2) AS done,
       COUNT(*) AS total
FROM pm_project p
LEFT JOIN pm_task t ON t.project_id = p.id AND t.deleted = 0
WHERE p.deleted = 0
GROUP BY p.id, p.name
ORDER BY p.id;

SELECT '=== 9月到期任务（日历）===' AS section;
SELECT t.due_date, p.name AS project, t.title,
       CASE t.status WHEN 0 THEN '待办' WHEN 1 THEN '进行中' WHEN 2 THEN '已完成' ELSE '?' END AS st,
       u.nickname AS assignee
FROM pm_task t
JOIN pm_project p ON p.id = t.project_id
LEFT JOIN sys_user u ON u.id = t.assignee_id
WHERE t.deleted = 0 AND t.due_date BETWEEN '2026-09-01' AND '2026-09-30'
ORDER BY t.due_date, t.id;

SELECT '导入完成。密码均为 admin123。建议登录 admin / zhangsan / caiwu / gudong 分别看个人中心与审批。' AS tip;
