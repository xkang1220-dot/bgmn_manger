-- 任务看板演示数据：项目任务 + 参与人 + 评论（可重复执行）
-- 用法: mysql -uroot -p123456 --default-character-set=utf8mb4 < sql/seed_task_demo.sql
-- 依赖：建议先执行 seed_finance_demo.sql（会准备用户/项目）；本脚本也会尽量自补齐

USE kk_manager;
SET NAMES utf8mb4;

-- ========== 补齐演示账号（若已存在则跳过）==========
INSERT INTO sys_user (id, dept_id, username, password, nickname, gender, status, remark)
SELECT 2, 3, 'zhangsan', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '张三', 1, 1, '演示-项目负责人'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 2);
INSERT INTO sys_user (id, dept_id, username, password, nickname, gender, status, remark)
SELECT 3, 3, 'lisi', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '李四', 1, 1, '演示-项目执行'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 3);
INSERT INTO sys_user (id, dept_id, username, password, nickname, gender, status, remark)
SELECT 4, 3, 'wangwu', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '王五', 2, 1, '演示-协助人员'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 4);

INSERT IGNORE INTO sys_user_role (user_id, role_id) VALUES (2, 3), (3, 3), (4, 3);

-- ========== 补齐演示项目 ==========
INSERT INTO pm_project (id, name, code, owner_id, pool_id, budget, settled_amount, status, start_date, end_date, description)
SELECT 1, '官网改版项目', 'WEB-2026', 1, 1, 200000.00, 80000.00, 1, '2026-01-01', '2026-06-30', '客户A官网改版'
WHERE NOT EXISTS (SELECT 1 FROM pm_project WHERE id = 1 AND deleted = 0);

INSERT INTO pm_project (id, name, code, owner_id, pool_id, budget, settled_amount, status, start_date, end_date, description)
SELECT 2, '移动端APP开发', 'APP-2026', 2, 1, 150000.00, 60000.00, 1, '2026-02-01', '2026-08-31', '客户B APP项目'
WHERE NOT EXISTS (SELECT 1 FROM pm_project WHERE id = 2 AND deleted = 0);

INSERT INTO pm_project (id, name, code, owner_id, pool_id, budget, settled_amount, status, start_date, end_date, description)
SELECT 3, '内部管理系统迭代', 'OPS-2026', 1, 1, 80000.00, 0.00, 0, '2026-04-01', '2026-09-30', '演示筹备中项目'
WHERE NOT EXISTS (SELECT 1 FROM pm_project WHERE id = 3 AND deleted = 0);

-- 若项目分成缺失，补默认分成
INSERT INTO pm_project_member (project_id, user_id, layer, percent, remark)
SELECT 3, 1, '主理人', 50.00, '演示'
WHERE NOT EXISTS (SELECT 1 FROM pm_project_member WHERE project_id = 3 AND user_id = 1 AND deleted = 0);
INSERT INTO pm_project_member (project_id, user_id, layer, percent, remark)
SELECT 3, 2, '执行', 50.00, '演示'
WHERE NOT EXISTS (SELECT 1 FROM pm_project_member WHERE project_id = 3 AND user_id = 2 AND deleted = 0);

-- ========== 清理旧任务演示数据 ==========
DELETE FROM pm_task_comment WHERE task_id BETWEEN 1 AND 50;
DELETE FROM pm_task_member WHERE task_id BETWEEN 1 AND 50;
DELETE FROM pm_task WHERE id BETWEEN 1 AND 50;

-- ========== 项目1：官网改版（看板分布：待办/进行中/完成）==========
INSERT INTO pm_task
(id, project_id, title, content, status, priority, assignee_id, start_date, due_date, progress, create_by, create_time) VALUES
(1, 1, '梳理官网信息架构', '整理栏目、导航与页面清单，输出 IA 文档', 2, 2, 1, '2026-01-05', '2026-01-20', 100, 1, '2026-01-06 10:00:00'),
(2, 1, '首页视觉设计稿', '完成首页桌面端/移动端设计，标注交互说明', 2, 1, 2, '2026-01-15', '2026-02-05', 100, 1, '2026-01-16 09:30:00'),
(3, 1, '前端首页开发', '按设计稿实现首页，兼容主流浏览器', 1, 1, 2, '2026-02-06', '2026-03-01', 65, 2, '2026-02-07 11:00:00'),
(4, 1, '内容管理后台对接', '对接文章/Banner 接口，完成列表与编辑', 1, 2, 3, '2026-02-10', '2026-03-10', 40, 2, '2026-02-11 14:00:00'),
(5, 1, 'SEO 基础优化', '标题、描述、sitemap、结构化数据', 0, 3, 3, '2026-03-01', '2026-03-20', 0, 1, '2026-02-20 16:00:00'),
(6, 1, '上线前联调验收', '联调生产环境，核对埋点与表单提交', 0, 1, 1, '2026-03-15', '2026-03-28', 0, 1, '2026-02-25 10:00:00'),
(7, 1, '旧站跳转规则配置', '配置 301 与死链处理，避免收录丢失', 0, 2, 2, '2026-03-10', '2026-03-25', 0, 2, '2026-03-01 09:00:00');

-- ========== 项目2：移动端 APP ==========
INSERT INTO pm_task
(id, project_id, title, content, status, priority, assignee_id, start_date, due_date, progress, create_by, create_time) VALUES
(8,  2, '需求评审与排期', '确认 MVP 范围与里程碑', 2, 1, 2, '2026-02-01', '2026-02-08', 100, 2, '2026-02-02 10:00:00'),
(9,  2, '登录注册流程', '手机号登录、验证码、协议页', 2, 1, 2, '2026-02-09', '2026-02-25', 100, 2, '2026-02-10 11:00:00'),
(10, 2, '首页信息流', '推荐流、下拉刷新、分页加载', 1, 1, 3, '2026-02-20', '2026-03-15', 55, 2, '2026-02-21 09:00:00'),
(11, 2, '消息推送接入', '接入厂商推送，处理角标与点击跳转', 1, 2, 2, '2026-03-01', '2026-03-20', 30, 3, '2026-03-02 15:00:00'),
(12, 2, '个人中心与设置', '资料编辑、账号安全、关于我们', 0, 2, 4, '2026-03-10', '2026-03-28', 0, 2, '2026-03-05 10:00:00'),
(13, 2, '性能与包体积优化', '启动耗时、图片压缩、分包策略', 0, 3, 3, '2026-03-20', '2026-04-05', 0, 2, '2026-03-08 14:00:00'),
(14, 2, 'TestFlight / 应用商店提审材料', '截图、描述、隐私协议清单', 0, 2, 2, '2026-04-01', '2026-04-12', 0, 1, '2026-03-12 16:00:00'),
(15, 2, '逾期演示：埋点方案确认', '确认关键事件与上报字段（故意逾期）', 1, 1, 3, '2026-02-15', '2026-02-28', 20, 2, '2026-02-16 10:00:00');

-- ========== 项目3：内部系统（筹备）==========
INSERT INTO pm_task
(id, project_id, title, content, status, priority, assignee_id, start_date, due_date, progress, create_by, create_time) VALUES
(16, 3, '盘点现有流程痛点', '访谈业务同事，整理 Top10 问题', 0, 2, 1, '2026-04-01', '2026-04-15', 0, 1, '2026-03-20 10:00:00'),
(17, 3, '技术方案选型', '对比自研与采购，输出选型结论', 0, 2, 2, '2026-04-10', '2026-04-25', 0, 1, '2026-03-21 11:00:00'),
(18, 3, '立项材料准备', '预算、人力、里程碑与风险', 0, 3, 1, '2026-04-15', '2026-04-30', 0, 1, '2026-03-22 09:00:00');

-- ========== 参与人员 ==========
INSERT INTO pm_task_member (task_id, user_id) VALUES
(1, 2), (1, 3),
(2, 3), (2, 4),
(3, 3), (3, 4),
(4, 2),
(5, 2),
(6, 2), (6, 3),
(7, 3),
(8, 1), (8, 3),
(9, 3), (9, 4),
(10, 2), (10, 4),
(11, 3),
(12, 2),
(13, 2), (13, 4),
(14, 3), (14, 4),
(15, 2), (15, 4),
(16, 2),
(17, 1),
(18, 2);

-- ========== 评论 ==========
INSERT INTO pm_task_comment (task_id, content, create_by, create_time) VALUES
(1, 'IA 初稿已放网盘，请大家周五前反馈栏目命名。', 1, '2026-01-08 11:20:00'),
(1, '导航建议把「案例」提前到二级，客户更关心。', 2, '2026-01-09 15:40:00'),
(1, '已按反馈调整，任务关闭。', 1, '2026-01-18 10:05:00'),

(2, '首页首屏动画是否保留？会有性能压力。', 3, '2026-01-20 09:10:00'),
(2, '改为轻量过渡，设计稿 v2 已更新。', 2, '2026-01-22 18:30:00'),

(3, '头部吸顶在 Safari 有兼容问题，今晚修。', 2, '2026-02-18 20:15:00'),
(3, 'Banner 轮播间隔改成 4 秒更合适。', 1, '2026-02-20 09:00:00'),
(3, '进度同步：组件库已接入 70%。', 2, '2026-02-28 16:45:00'),

(4, '后台接口文档链接发一下。', 3, '2026-02-12 10:00:00'),
(4, '文档在飞书「官网/API」目录，已 @你。', 2, '2026-02-12 10:20:00'),

(6, '验收 checklist 需要补上表单反垃圾校验。', 1, '2026-02-26 14:00:00'),

(8, 'MVP 砍掉「社区」模块，先做核心交易链路。', 2, '2026-02-05 11:00:00'),
(9, '验证码通道已切到备用供应商。', 2, '2026-02-20 17:30:00'),
(10, '信息流空态文案麻烦产品确认一版。', 3, '2026-03-01 09:40:00'),
(10, '文案：暂时没有内容，去首页看看。', 2, '2026-03-01 11:05:00'),
(11, '华为通道申请还在审核，先用小米联调。', 2, '2026-03-05 16:00:00'),
(15, '埋点方案评审延期了，先按临时字段上报。', 3, '2026-03-01 10:00:00'),
(15, '请本周内确认最终事件表，否则无法排期。', 2, '2026-03-03 09:30:00'),

(16, '访谈名单：运营、客服、财务各 2 人。', 1, '2026-03-21 10:00:00'),
(17, '自研工作量偏大，建议二期再评估采购。', 2, '2026-03-22 15:20:00');

ALTER TABLE pm_task AUTO_INCREMENT = 100;
ALTER TABLE pm_task_comment AUTO_INCREMENT = 100;

-- ========== 验证 ==========
SELECT '=== 项目任务统计 ===' AS section;
SELECT p.id, p.name,
       SUM(t.status = 0) AS todo,
       SUM(t.status = 1) AS doing,
       SUM(t.status = 2) AS done,
       COUNT(*) AS total
FROM pm_project p
LEFT JOIN pm_task t ON t.project_id = p.id AND t.deleted = 0
WHERE p.id IN (1, 2, 3) AND p.deleted = 0
GROUP BY p.id, p.name
ORDER BY p.id;

SELECT '=== 任务样例 ===' AS section;
SELECT t.id, p.name AS project, t.title,
       CASE t.status WHEN 0 THEN '待办' WHEN 1 THEN '进行中' WHEN 2 THEN '已完成' ELSE '取消' END AS status,
       u.nickname AS assignee, t.progress, t.due_date
FROM pm_task t
JOIN pm_project p ON p.id = t.project_id
LEFT JOIN sys_user u ON u.id = t.assignee_id
WHERE t.id BETWEEN 1 AND 18
ORDER BY t.project_id, t.status, t.id;

SELECT '=== 评论数 ===' AS section;
SELECT task_id, COUNT(*) AS comments
FROM pm_task_comment
WHERE deleted = 0 AND task_id BETWEEN 1 AND 18
GROUP BY task_id
ORDER BY task_id;

SELECT '导入完成。登录 admin/admin123 或 zhangsan/admin123，进入项目管理查看看板。' AS tip;
