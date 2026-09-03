CREATE DATABASE IF NOT EXISTS kk_manager DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE kk_manager;

DROP TABLE IF EXISTS sys_file;
DROP TABLE IF EXISTS pm_task_comment;
DROP TABLE IF EXISTS pm_task_flow;
DROP TABLE IF EXISTS pm_task_member;
DROP TABLE IF EXISTS pm_task;
DROP TABLE IF EXISTS pm_project_member;
DROP TABLE IF EXISTS pm_project;
DROP TABLE IF EXISTS fin_ledger;
DROP TABLE IF EXISTS fin_pool;
DROP TABLE IF EXISTS hr_wallet;
DROP TABLE IF EXISTS hr_archive;
DROP TABLE IF EXISTS sys_role_dept;
DROP TABLE IF EXISTS sys_role_menu;
DROP TABLE IF EXISTS sys_user_role;
DROP TABLE IF EXISTS sys_menu;
DROP TABLE IF EXISTS sys_role;
DROP TABLE IF EXISTS sys_user;
DROP TABLE IF EXISTS sys_dept;

CREATE TABLE sys_dept (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    parent_id   BIGINT       DEFAULT 0,
    name        VARCHAR(64)  NOT NULL,
    sort        INT          DEFAULT 0,
    leader      VARCHAR(64)  DEFAULT NULL,
    phone       VARCHAR(32)  DEFAULT NULL,
    email       VARCHAR(128) DEFAULT NULL,
    status      TINYINT      DEFAULT 1,
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by   BIGINT       DEFAULT NULL,
    update_by   BIGINT       DEFAULT NULL,
    deleted     TINYINT      DEFAULT 0
) COMMENT='部门';

CREATE TABLE sys_user (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    dept_id     BIGINT       DEFAULT NULL,
    username    VARCHAR(64)  NOT NULL,
    password    VARCHAR(128) NOT NULL,
    nickname    VARCHAR(64)  DEFAULT NULL,
    avatar      VARCHAR(255) DEFAULT NULL,
    email       VARCHAR(128) DEFAULT NULL,
    phone       VARCHAR(32)  DEFAULT NULL,
    gender      TINYINT      DEFAULT 0,
    status      TINYINT      DEFAULT 1,
    remark      VARCHAR(255) DEFAULT NULL,
    totp_secret_key  VARCHAR(512) DEFAULT NULL,
    totp_enabled     TINYINT      DEFAULT 0,
    totp_verify_time DATETIME     DEFAULT NULL,
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by   BIGINT       DEFAULT NULL,
    update_by   BIGINT       DEFAULT NULL,
    deleted     TINYINT      DEFAULT 0,
    UNIQUE KEY uk_username (username)
) COMMENT='系统账号';

CREATE TABLE sys_role (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    name        VARCHAR(64)  NOT NULL,
    code        VARCHAR(64)  NOT NULL,
    sort        INT          DEFAULT 0,
    status      TINYINT      DEFAULT 1,
    data_scope  TINYINT      DEFAULT 1 COMMENT '1全部 2自定义 3本部门 4本部门及以下 5仅本人',
    remark      VARCHAR(255) DEFAULT NULL,
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by   BIGINT       DEFAULT NULL,
    update_by   BIGINT       DEFAULT NULL,
    deleted     TINYINT      DEFAULT 0,
    UNIQUE KEY uk_code (code)
) COMMENT='角色';

CREATE TABLE sys_menu (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    parent_id   BIGINT       DEFAULT 0,
    name        VARCHAR(64)  NOT NULL,
    type        TINYINT      DEFAULT 2 COMMENT '1目录 2菜单 3按钮',
    path        VARCHAR(128) DEFAULT NULL,
    component   VARCHAR(128) DEFAULT NULL,
    permission  VARCHAR(128) DEFAULT NULL,
    icon        VARCHAR(64)  DEFAULT NULL,
    sort        INT          DEFAULT 0,
    visible     TINYINT      DEFAULT 1,
    status      TINYINT      DEFAULT 1,
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by   BIGINT       DEFAULT NULL,
    update_by   BIGINT       DEFAULT NULL,
    deleted     TINYINT      DEFAULT 0
) COMMENT='菜单';

CREATE TABLE sys_user_role (
    id      BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL
) COMMENT='用户角色';

CREATE TABLE sys_role_menu (
    id      BIGINT PRIMARY KEY AUTO_INCREMENT,
    role_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL
) COMMENT='角色菜单';

CREATE TABLE sys_role_dept (
    id      BIGINT PRIMARY KEY AUTO_INCREMENT,
    role_id BIGINT NOT NULL,
    dept_id BIGINT NOT NULL
) COMMENT='角色部门';

CREATE TABLE hr_archive (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id           BIGINT       NOT NULL,
    real_name         VARCHAR(64)  NOT NULL,
    employee_no       VARCHAR(64)  DEFAULT NULL,
    id_card           VARCHAR(32)  DEFAULT NULL,
    birthday          DATE         DEFAULT NULL,
    entry_date        DATE         DEFAULT NULL,
    position          VARCHAR(64)  DEFAULT NULL,
    education         VARCHAR(32)  DEFAULT NULL,
    address           VARCHAR(255) DEFAULT NULL,
    emergency_contact VARCHAR(64)  DEFAULT NULL,
    emergency_phone   VARCHAR(32)  DEFAULT NULL,
    remark            VARCHAR(255) DEFAULT NULL,
    create_time       DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by         BIGINT       DEFAULT NULL,
    update_by         BIGINT       DEFAULT NULL,
    deleted           TINYINT      DEFAULT 0,
    UNIQUE KEY uk_user (user_id)
) COMMENT='人员档案';

CREATE TABLE hr_wallet (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id     BIGINT         NOT NULL,
    balance     DECIMAL(14,2)  DEFAULT 0.00,
    frozen      DECIMAL(14,2)  DEFAULT 0.00,
    status      TINYINT        DEFAULT 1,
    remark      VARCHAR(255)   DEFAULT NULL,
    create_time DATETIME       DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by   BIGINT         DEFAULT NULL,
    update_by   BIGINT         DEFAULT NULL,
    deleted     TINYINT        DEFAULT 0,
    UNIQUE KEY uk_wallet_user (user_id)
) COMMENT='个人钱包';

CREATE TABLE fin_pool (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    name        VARCHAR(64)    NOT NULL,
    balance     DECIMAL(14,2)  DEFAULT 0.00,
    is_default  TINYINT        DEFAULT 0,
    status      TINYINT        DEFAULT 1,
    remark      VARCHAR(255)   DEFAULT NULL,
    create_time DATETIME       DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by   BIGINT         DEFAULT NULL,
    update_by   BIGINT         DEFAULT NULL,
    deleted     TINYINT        DEFAULT 0
) COMMENT='资金池';

CREATE TABLE fin_ledger (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    biz_type       VARCHAR(32)    NOT NULL COMMENT 'INCOME EXPENSE TRANSFER SETTLE',
    account_type   VARCHAR(32)    NOT NULL COMMENT 'POOL WALLET',
    pool_id        BIGINT         DEFAULT NULL,
    user_id        BIGINT         DEFAULT NULL,
    amount         DECIMAL(14,2)  NOT NULL,
    before_balance DECIMAL(14,2)  DEFAULT NULL,
    after_balance  DECIMAL(14,2)  DEFAULT NULL,
    project_id     BIGINT         DEFAULT NULL,
    related_id     BIGINT         DEFAULT NULL,
    title          VARCHAR(128)   DEFAULT NULL,
    remark         VARCHAR(255)   DEFAULT NULL,
    occur_time     DATETIME       DEFAULT CURRENT_TIMESTAMP,
    create_time    DATETIME       DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by      BIGINT         DEFAULT NULL,
    update_by      BIGINT         DEFAULT NULL,
    deleted        TINYINT        DEFAULT 0,
    KEY idx_ledger_occur_time (occur_time),
    KEY idx_ledger_pool_id (pool_id),
    KEY idx_ledger_project_id (project_id),
    KEY idx_ledger_related_id (related_id),
    KEY idx_ledger_biz_account (biz_type, account_type)
) COMMENT='进出账流水';

CREATE TABLE pm_project (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    name           VARCHAR(128)   NOT NULL,
    code           VARCHAR(64)    DEFAULT NULL,
    owner_id       BIGINT         DEFAULT NULL,
    pool_id        BIGINT         DEFAULT NULL,
    budget         DECIMAL(14,2)  DEFAULT 0.00,
    settled_amount DECIMAL(14,2)  DEFAULT 0.00,
    status         TINYINT        DEFAULT 1 COMMENT '0筹备 1进行中 2已完成 3已关闭',
    start_date     DATE           DEFAULT NULL,
    end_date       DATE           DEFAULT NULL,
    description    VARCHAR(500)   DEFAULT NULL,
    create_time    DATETIME       DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by      BIGINT         DEFAULT NULL,
    update_by      BIGINT         DEFAULT NULL,
    deleted        TINYINT        DEFAULT 0
) COMMENT='项目';

CREATE TABLE pm_project_member (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id  BIGINT        NOT NULL,
    user_id     BIGINT        NOT NULL,
    layer       VARCHAR(64)   DEFAULT NULL COMMENT '分层角色',
    percent     DECIMAL(6,2)  DEFAULT 0.00 COMMENT '分成百分比',
    remark      VARCHAR(255)  DEFAULT NULL,
    create_time DATETIME      DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by   BIGINT        DEFAULT NULL,
    update_by   BIGINT        DEFAULT NULL,
    deleted     TINYINT       DEFAULT 0
) COMMENT='项目参与人分成';

CREATE TABLE pm_task (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id  BIGINT        NOT NULL,
    title       VARCHAR(128)  NOT NULL,
    content     VARCHAR(1000) DEFAULT NULL,
    status      TINYINT       DEFAULT 0 COMMENT '0待办 1进行中 2已完成 3已取消',
    priority    TINYINT       DEFAULT 2 COMMENT '1高 2中 3低',
    assignee_id BIGINT        DEFAULT NULL,
    start_date  DATE          DEFAULT NULL,
    due_date    DATE          DEFAULT NULL,
    progress    TINYINT       DEFAULT 0 COMMENT '完成进度0-100',
    create_time DATETIME      DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by   BIGINT        DEFAULT NULL,
    update_by   BIGINT        DEFAULT NULL,
    deleted     TINYINT       DEFAULT 0
) COMMENT='任务';

CREATE TABLE pm_task_member (
    id      BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    UNIQUE KEY uk_task_user (task_id, user_id),
    KEY idx_task_id (task_id)
) COMMENT='任务参与人员';

CREATE TABLE pm_task_comment (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id     BIGINT        NOT NULL,
    content     VARCHAR(2000) NOT NULL,
    create_time DATETIME      DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by   BIGINT        DEFAULT NULL,
    update_by   BIGINT        DEFAULT NULL,
    deleted     TINYINT       DEFAULT 0,
    KEY idx_task_id (task_id)
) COMMENT='任务评论';

CREATE TABLE pm_task_flow (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id       BIGINT        NOT NULL,
    action        VARCHAR(32)   NOT NULL COMMENT 'CREATE/ASSIGN/STATUS/TRANSFER',
    from_user_id  BIGINT        DEFAULT NULL,
    to_user_id    BIGINT        DEFAULT NULL,
    from_status   TINYINT       DEFAULT NULL,
    to_status     TINYINT       DEFAULT NULL,
    remark        VARCHAR(500)  DEFAULT NULL,
    create_time   DATETIME      DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by     BIGINT        DEFAULT NULL,
    update_by     BIGINT        DEFAULT NULL,
    deleted       TINYINT       DEFAULT 0,
    KEY idx_task_id (task_id)
) COMMENT='任务流转记录';

CREATE TABLE sys_file (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    original_name VARCHAR(255) NOT NULL,
    stored_name   VARCHAR(128) NOT NULL,
    path          VARCHAR(255) NOT NULL,
    url           VARCHAR(512) DEFAULT NULL,
    storage_type  VARCHAR(32)  DEFAULT 'local',
    content_type  VARCHAR(128) DEFAULT NULL,
    size          BIGINT       DEFAULT 0,
    biz_type      VARCHAR(64)  DEFAULT NULL,
    biz_id        BIGINT       DEFAULT NULL,
    create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by     BIGINT       DEFAULT NULL,
    update_by     BIGINT       DEFAULT NULL,
    deleted       TINYINT      DEFAULT 0,
    KEY idx_file_biz (biz_type, biz_id)
) COMMENT='文件';

INSERT INTO sys_dept (id, parent_id, name, sort, leader, status) VALUES
(1, 0, 'KK公司', 1, '管理员', 1),
(2, 1, '管理部', 1, '管理员', 1),
(3, 1, '业务部', 2, NULL, 1),
(4, 1, '财务部', 3, NULL, 1);

INSERT INTO sys_role (id, name, code, sort, status, data_scope, remark) VALUES
(1, '超级管理员', 'admin', 1, 1, 1, '全部权限'),
(2, '部门主管', 'dept_lead', 2, 1, 4, '本部门及以下'),
(3, '普通员工', 'staff', 3, 1, 5, '仅本人');

-- 密码 admin123
INSERT INTO sys_user (id, dept_id, username, password, nickname, gender, status, remark) VALUES
(1, 2, 'admin', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '系统管理员', 1, 1, '默认管理员');

INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1);

INSERT INTO sys_menu (id, parent_id, name, type, path, component, permission, icon, sort, visible, status) VALUES
(1, 0, '首页', 2, '/dashboard', 'dashboard/index', '', 'Odometer', 1, 1, 1),
(2, 0, '财务', 1, '/finance', '', '', 'Wallet', 2, 1, 1),
(21, 2, '资金池', 2, '/finance/pool', 'finance/pool', 'finance:pool:list', 'Coin', 1, 1, 1),
(25, 2, '项目分层', 2, '/finance/project-share', 'finance/project-share', 'finance:share:edit', 'PieChart', 2, 1, 1),
(22, 2, '进出账', 2, '/finance/ledger', 'finance/ledger', 'finance:ledger:list', 'List', 3, 1, 1),
(24, 2, '项目分钱', 2, '/finance/distribute', 'finance/distribute', 'finance:ledger:add', 'Share', 4, 1, 1),
(23, 2, '个人钱包', 2, '/finance/wallet', 'finance/wallet', 'finance:wallet:list', 'Money', 5, 1, 1),
(3, 0, '项目', 1, '/project', '', '', 'Folder', 3, 1, 1),
(31, 3, '项目管理', 2, '/project/list', 'project/list', 'project:list', 'Collection', 1, 1, 1),
(32, 3, '任务管理', 2, '/project/task', 'project/task', 'project:task:list', 'Checked', 2, 1, 1),
(4, 0, '人事', 1, '/hr', '', '', 'User', 4, 1, 1),
(41, 4, '人员档案', 2, '/hr/archive', 'hr/archive', 'hr:archive:list', 'Postcard', 1, 1, 1),
(5, 0, '文件管理', 2, '/file/list', 'file/list', 'file:list', 'FolderOpened', 5, 1, 1),
(6, 0, '系统管理', 1, '/system', '', '', 'Setting', 6, 1, 1),
(61, 6, '账号管理', 2, '/system/user', 'system/user/index', 'system:user:list', 'Avatar', 1, 1, 1),
(62, 6, '角色权限', 2, '/system/role', 'system/role/index', 'system:role:list', 'Lock', 2, 1, 1),
(63, 6, '部门管理', 2, '/system/dept', 'system/dept/index', 'system:dept:list', 'OfficeBuilding', 3, 1, 1),
(64, 6, '菜单管理', 2, '/system/menu', 'system/menu/index', 'system:menu:list', 'Menu', 4, 1, 1),
(211, 21, '编辑资金池', 3, '', '', 'finance:pool:edit', '', 1, 1, 1),
(221, 22, '登记进出账', 3, '', '', 'finance:ledger:add', '', 1, 1, 1),
(311, 31, '新增项目', 3, '', '', 'project:add', '', 1, 1, 1),
(312, 31, '编辑项目', 3, '', '', 'project:edit', '', 1, 1, 1),
(313, 31, '删除项目', 3, '', '', 'project:remove', '', 1, 1, 1),
(321, 32, '新增任务', 3, '', '', 'project:task:add', '', 1, 1, 1),
(322, 32, '编辑任务', 3, '', '', 'project:task:edit', '', 1, 1, 1),
(323, 32, '删除任务', 3, '', '', 'project:task:remove', '', 1, 1, 1),
(411, 41, '新增档案', 3, '', '', 'hr:archive:add', '', 1, 1, 1),
(412, 41, '编辑档案', 3, '', '', 'hr:archive:edit', '', 1, 1, 1),
(413, 41, '删除档案', 3, '', '', 'hr:archive:remove', '', 1, 1, 1),
(511, 5, '上传文件', 3, '', '', 'file:upload', '', 1, 1, 1),
(512, 5, '删除文件', 3, '', '', 'file:remove', '', 1, 1, 1),
(611, 61, '新增账号', 3, '', '', 'system:user:add', '', 1, 1, 1),
(612, 61, '编辑账号', 3, '', '', 'system:user:edit', '', 1, 1, 1),
(613, 61, '删除账号', 3, '', '', 'system:user:remove', '', 1, 1, 1),
(614, 61, '重置密码', 3, '', '', 'system:user:resetPwd', '', 1, 1, 1),
(621, 62, '新增角色', 3, '', '', 'system:role:add', '', 1, 1, 1),
(622, 62, '编辑角色', 3, '', '', 'system:role:edit', '', 1, 1, 1),
(623, 62, '删除角色', 3, '', '', 'system:role:remove', '', 1, 1, 1),
(631, 63, '新增部门', 3, '', '', 'system:dept:add', '', 1, 1, 1),
(632, 63, '编辑部门', 3, '', '', 'system:dept:edit', '', 1, 1, 1),
(633, 63, '删除部门', 3, '', '', 'system:dept:remove', '', 1, 1, 1),
(641, 64, '新增菜单', 3, '', '', 'system:menu:add', '', 1, 1, 1),
(642, 64, '编辑菜单', 3, '', '', 'system:menu:edit', '', 1, 1, 1),
(643, 64, '删除菜单', 3, '', '', 'system:menu:remove', '', 1, 1, 1);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu;

INSERT INTO hr_archive (user_id, real_name, employee_no, position, entry_date)
VALUES (1, '系统管理员', 'KK0001', '管理员', CURDATE());

INSERT INTO hr_wallet (user_id, balance, frozen, status) VALUES (1, 0.00, 0.00, 1);

INSERT INTO fin_pool (name, balance, is_default, status, remark)
VALUES ('公司主资金池', 0.00, 1, 1, '系统初始化');
