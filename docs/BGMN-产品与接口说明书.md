# BGMN 管理系统 · 产品与接口说明书

> **读者**：即将基于本系统开发 **App（或新客户端）** 的产品 / 前端 / 后端同学  
> **依据**：仓库最新 `master`（约 `4bb0399`）前后端代码  
> **目标**：读完本文即可理解「谁在什么场景做什么事、钱怎么动、调哪个接口、入参出参与调用时机」

---

## 目录

1. [产品定位](#1-产品定位)
2. [技术约定](#2-技术约定)
3. [核心领域模型](#3-核心领域模型)
4. [认证 · 权限 · 导航 · 通知](#4-认证--权限--导航--通知)
5. [资金与审批总览（产品心智）](#5-资金与审批总览产品心智)
6. [模块与页面详解](#6-模块与页面详解)
7. [审批类型全表](#7-审批类型全表)
8. [App 信息架构建议](#8-app-信息架构建议)
9. [附录：接口速查](#9-附录接口速查)

---

## 1. 产品定位

BGMN（仓库内也称 KK Manager）是一套 **多公司协同的项目管理 + 资金闭环 + 人事资产** 后台。

它不是单纯的「任务看板」或「记账工具」，而是把下面几件事绑在一条业务链上：

```text
组织（公司/部门/账号/角色）
   → 立项（规模决定是否审批）
   → 任务协作
   → 项目资金（公司池预支 → 项目结余 → 报销/工资/分成 → 个人钱包）
   → 统一审批（配置化会签/或签）
   → 财务回执 + 申请人确认到账（部分类型）
```

### 1.1 典型角色（产品视角）

| 角色画像 | 日常在系统里做什么 |
|----------|-------------------|
| 普通员工 | 看个人中心、做任务、确认工资、发起个人报销、确认到账 |
| 项目负责人 / 成员 | 管项目与任务、配置分成、预支、项目报销/发工资、分钱、结束退回 |
| 财务 | 公司总账登记、收款渠道、月度核验、上传付款回执、看全员钱包 |
| 审批人 | 审批中心处理待办 |
| 行政 / HR | 人员档案、工资配置、发薪记录、固定资产 |
| 系统管理员 | 用户 / 角色 / 部门 / 菜单、审批流配置 |

菜单与按钮权限都来自后端；**App 不要写死菜单树**，登录后拉 `GET /api/auth/info`，用返回的 `menus` + `permissions` 决定可见入口。

### 1.2 当前 Web 端入口地图（活路由）

| 产品区域 | 路由 | 说明 |
|----------|------|------|
| 登录 | `/login` | 账号密码 + 可选 TOTP |
| 个人中心（默认首页） | `/account` | 钱包 / 待办 / 任务 / 项目聚合 |
| 账号资料 | `/account/profile` | 资料、改密、TOTP、任务查询码 |
| 工资确认 | `/account/salary-confirm` | 员工确认本月工资条 |
| 工作台 | `/dashboard` | 公司资金概览 |
| 公司总账 | `/finance/ledger` | 入账/出账登记 + 阈值 |
| 项目账款 | `/finance/project-account` | 项目钱的主操作台 |
| 全员钱包 | `/finance/wallet-board` | 管理视角 |
| 收款渠道 | `/finance/pay-channel` | 公司收款方式 |
| 月度核验 | `/finance/month-verify` | 渠道对账审批 |
| 审批中心 | `/workflow/center` | 待办 / 我发起 |
| 审批配置 | `/workflow/flow` | 按公司配置审批流 |
| 项目管理 | `/project/list` | 项目 + 看板 + 任务抽屉 |
| 任务管理 | `/project/task` | 跨项目任务列表 |
| 人员档案 / 折旧类别 / 资产 / 工资配置 / 发薪 | `/hr/*` | 人事与固定资产 |
| 文件管理 | `/file/list` | 通用文件 |
| 系统管理 | `/system/*` | 用户角色部门菜单 |

> 历史页面如资金池、我的钱包、分成页、手动分钱页已 **路由重定向合并** 进总账 / 个人中心 / 项目账款；App **按合并后的产品面实现**，不必复刻孤儿页。

---

## 2. 技术约定

### 2.1 环境

| 项 | 值 |
|----|-----|
| 后端端口（dev） | `8999` |
| Context path | 无 |
| API 前缀 | `/api` |
| Token 头 | `Authorization: <token>`（Sa-Token，uuid，超时 86400s） |
| 统一响应 | `{ code: number, message: string, data: T }`，成功 `code === 200` |
| 分页 | `{ list, total, page, pageSize }` |
| 时区 / 时间格式 | `GMT+8`，`yyyy-MM-dd HH:mm:ss` |
| WebSocket | `ws(s)://{host}/ws/notification?token=...` |

前端开发代理示例：`/api`、`/ws` → 后端。

### 2.2 调用约定（App 必遵）

1. 除白名单外，所有 `/api/**` 需登录。
2. `code === 401`：清本地会话，回登录。
3. 业务写操作很多会返回 **审批单** 而不是「直接成功改库」——UI 要提示「已提交审批」。
4. 文件上传多为 `multipart/form-data`，字段名 `file`；先拿文件 `id`，再塞进业务请求的 `voucherFileIds` / `imageFileIds`。
5. 权限码形如 `module:resource:action`（如 `project:task:edit`）；角色码 `admin` 等价全权限 `*:*:*`。

### 2.3 公开接口（无需登录）

| 方法 | 路径 | 用途 |
|------|------|------|
| `POST` | `/api/auth/login` | 登录 |
| `GET` | `/api/auth/totp/status/username?username=` | 登录前判断是否要 TOTP |
| `POST` | `/api/public/task-query` | 凭个人任务查询码查任务（对外） |
| `GET` | `/api/file/preview/{id}` | 预览 |
| `GET` | `/api/file/download/{id}` | 下载 |

> `/api/auth/captcha` 在白名单里，但当前 **无实现**，App 不要依赖验证码流程。

---

## 3. 核心领域模型

### 3.1 组织

```text
公司（顶层 SysDept）
  └── 部门树
账号 SysUser
  ├── 多部门绑定 SysUserDept（含 dataScope）
  └── 多公司角色绑定 roleBindings
```

- **公司** = 部门树顶层节点；业务单据几乎都带 `companyId`。
- **数据范围**（`SysUserDept.dataScope`）：`1` 公司全部 / `3` 本部门 / `4` 本部门及以下 / `5` 仅本人。
- 全局管理员：角色码 `admin` 且 `companyId` 为 `null` 或 `0`。

### 3.2 三本账（资金心智模型）★

系统里所有钱只落在三类账户，流水表 `fin_ledger.accountType` 对应：

| accountType | 实体 | 产品含义 | 能否直接登记进出 |
|-------------|------|----------|------------------|
| `POOL` | `FinPool` | **公司总账 / 资金池** | 可以（入账/出账登记） |
| `PROJECT` | `FinProjectAccount` | **项目结余** | 否，只能走预支/支出/分成等流程 |
| `WALLET` | `HrWallet` | **个人钱包**（一人一本） | 否，只能走报销/工资/分成等 |

关系简图：

```text
          公司资金池 (POOL)
               │
               │ PROJECT_ADVANCE 预支
               ▼
          项目结余 (PROJECT)
           │    │    │
           │    │    └─ PROJECT_SETTLE 分钱 ──────► 个人钱包
           │    └───── REIMBURSE_PROJECT / SALARY_*  ► 个人钱包（公司池不动）
           └───────── RESERVE_RETURN 结束退回 ─────► 公司池

个人报销 REIMBURSE_PERSONAL：公司池 → 个人钱包（不经项目）
```

**项目结余恒等式（理解对账的关键）：**

```text
balance ≈ advanceAmount − expenseAmount − settleAmount − reserveHeld
```

**个人钱包：**

```text
available = balance − frozen   // 资产领用会冻结
```

### 3.3 项目

| 字段 | 含义 |
|------|------|
| `scale` | `NORMAL` 常规（创建免审）/ `KEY` 重点 / `MAJOR` 重大（创建或改到该档需审） |
| `status` | `0` 筹备 / `1` 进行中 / `2` 已完成 / `3` 已关闭 |
| `approveStatus` | `0` 待审 / `1` 已生效 / `2` 已拒绝 |
| `code` | 创建时后端生成：公司名拼音首字母 + `-` + 三位序号，**不可改** |
| `endDate` | 预计结束 |
| `actualEndDate` | 实际结束（手填） |
| `members[].layer` | 参与人 **职责**（短文本，必填） |
| `members[].percent` | 分成比例（资金配置时维护） |

### 3.4 任务

| 字段 | 含义 |
|------|------|
| `status` | `0` 待办 / `1` 进行中 / `2` 已完成 / `3` 已关闭 |
| `priority` | `1` 高 / `2` 中 / `3` 低 |
| `progress` | 0–100 |
| 参与人 / 移交对象 | 必须 ⊆（项目负责人 ∪ 项目成员） |

### 3.5 审批单生命周期

`WfApproval.status`：

| 值 | 含义 |
|----|------|
| `PENDING` | 待审批 |
| `APPROVED` | 已通过 |
| `REJECTED` | 已拒绝 |
| `WITHDRAWN` | 已撤回 |
| `TIMEOUT_PASS` | 超时通过 |
| `ROLLING` | 回退审批中 |
| `ROLLED` | 已回退 |

`confirmStatus`（钱相关二次确认）：

| 值 | 含义 |
|----|------|
| `0` | 无需 |
| `1` | 待财务回执 |
| `2` | 待申请人确认到账 |
| `3` | 已确认（已动账） |

需要「审批通过 → 财务回执 → 申请人确认」才动账的类型：

- `REIMBURSE_PERSONAL`
- `REIMBURSE_PROJECT`
- `SALARY_APPLY`

其它类型：审批通过（或超时通过）后 **立即动账 / 立即生效**。

审批流配置 `passMode`：`ALL` 会签 / `ANY` 或签；可配角色码列表、指定用户、超时小时数。

---

## 4. 认证 · 权限 · 导航 · 通知

### 4.1 登录页（产品）

**用户目标**：进入系统。

**流程：**

1. 输入用户名；失焦时调用 TOTP 状态接口，决定是否展示 6 位验证码框。
2. 提交登录；成功后立刻拉用户信息（菜单/权限）。
3. 进入个人中心（Web 默认 `/account`）。

| 时机 | 接口 | 入参 | 出参要点 |
|------|------|------|----------|
| 用户名失焦 / 登录前 | `GET /api/auth/totp/status/username` | `username` | `data: boolean` 是否启用 TOTP |
| 点登录 | `POST /api/auth/login` | `{ username, password, totpCode? }` | `{ token, user }` |
| 登录成功后 / 冷启动有 token | `GET /api/auth/info` | — | `{ user, roles, permissions, menus }` |
| 退出 | `POST /api/auth/logout` | — | void |

**App 启动逻辑建议：**

```text
有本地 token？
  → GET /auth/info
      成功：进入主壳，渲染 menus
      失败：清 token → 登录页
无 token → 登录页
```

### 4.2 菜单与按钮权限

- **侧栏 / Tab 可见性**：用 `menus`（过滤 `visible !== 0`，`type !== 3` 按钮型菜单不进导航）。
- **按钮级**：用 `permissions` 字符串精确匹配；`admin` 或含 `*:*:*` 视为全过。
- App 可用统一 `hasPermission(code)`，隐藏无权限操作，而不是只靠接口 403。

### 4.3 通知

| 时机 | 接口 / 通道 | 说明 |
|------|-------------|------|
| 进入主壳 | `GET /api/notification/unread-count` | 角标；Web 另每 30s 轮询 |
| 打开通知列表 | `GET /api/notification/page?page&pageSize&unreadOnly?` | 列表 |
| 点一条 | `POST /api/notification/{id}/read` | 已读后按 `link` 跳转，默认去审批中心 |
| 全部已读 | `POST /api/notification/read-all` | |
| 实时推送 | `WS /ws/notification?token=` | JSON；`ping`/`pong` 心跳；类型含 notification |

推送字段与列表项一致：`id, title, content, bizType, bizId, link, readFlag, createTime`。

---

## 5. 资金与审批总览（产品心智）

做 App 时，**任何「花钱 / 分钱 / 进钱」操作都先问三句话：**

1. 钱从哪本账出、进哪本账？
2. 要不要走审批？
3. 审批通过后是立刻动账，还是还要财务回执 + 确认到账？

### 5.1 写死规则（后端已强制）

| 动作 | 正确路径 | 错误路径 |
|------|----------|----------|
| 改分成/预算/预留比例 | 提交 `SHARE_CONFIG` 审批 | 直接 `PUT /finance/project-share`（会抛错） |
| 项目分钱 | `PROJECT_SETTLE` 审批 | 直接 `/finance/settle*`（会抛错） |
| 公司总账记一笔 | `POST /finance/ledger/register`（可能转审批） | 直接 `POST /finance/ledger`（会抛错） |
| 个人钱包直接加减 | 不允许 | 只能报销 / 工资 / 分成等 |

### 5.2 统一提交审批接口

几乎所有业务审批都走：

```http
POST /api/workflow/approval
Authorization: <token>
Content-Type: application/json
```

**公共入参 `ApprovalSubmitRequest`：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | string | 审批类型，见第 7 章 |
| `title` | string | 展示标题 |
| `amount` | number? | 金额 |
| `projectId` | number? | 项目 |
| `poolId` | number? | 资金池 |
| `companyId` | number? | 公司；无项目/池可继承时必填 |
| `remark` | string? | 备注 |
| `payload` | object? | **按类型不同** |
| `voucherFileIds` | number[]? | 凭证文件 id |

**出参：** `WfApproval` 对象（含 `id, bizNo, status, …`）。前端常用 toast：已提交审批 + 单号。

**配套：**

| 动作 | 接口 |
|------|------|
| 上传审批凭证 | `POST /api/workflow/voucher` multipart `file` → 返回文件（含 `id`） |
| 审批前预览文案 | `GET /api/workflow/flow/describe?type&companyId` → `{ tip, passModeLabel, timeoutLabel, assigneeCount }` |

### 5.3 审批中心操作（所有类型共用）

| 用户动作 | 接口 | 入参 | 权限 |
|----------|------|------|------|
| 列表 | `GET /api/workflow/approval/page` | `page, pageSize, scope(todo\|mine\|…), type?, status?, keyword?, minAmount?, maxAmount?, startTime?, endTime?` | `workflow:list` |
| 详情 | `GET /api/workflow/approval/{id}` | | `workflow:list` |
| 通过 | `POST /api/workflow/approval/{id}/approve` | `{ comment? }` | `workflow:handle` |
| 拒绝 | `POST /api/workflow/approval/{id}/reject` | `{ comment? }` | `workflow:handle` |
| 撤回（申请人） | `POST /api/workflow/approval/{id}/withdraw` | | `workflow:list` |
| 上传付款回执 | `POST /api/workflow/approval/{id}/receipt` | `{ fileIds: number[] }` | `workflow:handle` 或 `finance:ledger:add` |
| 确认到账 | `POST /api/workflow/approval/{id}/confirm` | | `workflow:list` |
| 发起资金回退 | `POST /api/workflow/approval/rollback` | `{ approvalId, mode: FULL\|PARTIAL, amount?, reason? }` | `workflow:list` |

**App 状态机（确认类）：**

```text
PENDING
  →（审批人通过）APPROVED + confirmStatus=1  「待财务回执」
  →（财务上传回执）confirmStatus=2           「待确认到账」
  →（申请人确认）confirmStatus=3 + 动账
```

---

## 6. 模块与页面详解

以下每个小节结构统一为：**产品目标 → 页面结构 → 操作×接口×时机×入出参 → 数据流转**。

---

### 6.1 个人中心 `/account`

**产品目标：** 登录后默认落地页；把「我的钱、我的审批、我的任务、我的项目」聚在一起，减少找菜单成本。

**页面结构：**

- 统计卡片（钱包余额等）
- 我的钱包流水（筛选 / 分页）
- 日历 + 当日任务
- 审批待办 / 我发起
- 我的任务列表
- 我的项目列表
- 入口：去发起报销（可跳到具备报销能力的财务面；Web 历史钱包页已并入）

| 操作 | 时机 | 接口 | 入参 | 出参 | 权限门控 |
|------|------|------|------|------|----------|
| 拉我的钱包 | 进入页 | `GET /api/finance/wallet/mine` | — | `HrWallet` | `finance:wallet:list` |
| 钱包流水 | 进入 / 搜索 / 翻页 | `GET /api/finance/wallet/mine/ledger` | `page, pageSize, …` | `PageResult<FinLedger>` | 同上 |
| 审批列表 | 进入 | `GET /api/workflow/approval/page` | `scope=todo` 或 `mine` | 分页审批 | `workflow:list` |
| 任务 | 进入 | 管理员/股东：`GET /api/task/page`；普通：`GET /api/task/related` | 分页或 — | 任务 | 登录 / `project:task:list` |
| 我的项目 | 进入 | `GET /api/project/mine` | — | `PmProject[]` | 登录 |

**数据流转：** 只读聚合；写操作跳到对应业务页或审批详情。

---

### 6.2 账号资料 `/account/profile`

**产品目标：** 维护个人信息、密码、二次验证、对外任务查询码。

| 操作 | 时机 | 接口 | 入参 | 出参 |
|------|------|------|------|------|
| 拉资料 | 进入 | `GET /api/account/profile` | — | `SysUser` |
| TOTP 是否开启 | 进入 | `GET /api/auth/totp/status` | — | `boolean` |
| 保存资料 | 点保存 | `PUT /api/account/profile` | `{ nickname, email, phone, gender, avatar? }` | void |
| 改密码 | 提交 | `PUT /api/account/password` | `{ oldPassword, newPassword }` | void |
| 开始绑定 TOTP | 打开绑定 | `POST /api/auth/totp/generate` | — | `{ accountName, secretKey, qrString }` |
| 确认绑定 | 输入验证码 | `POST /api/auth/totp/verify` | `{ totpCode }` | `boolean` |
| 生成任务查询码 | 点生成 | `POST /api/account/task-query-code` | — | `string`（新码） |

**对外查询（可不登录）：**

`POST /api/public/task-query` body `{ code }` → `{ nickname, tasks[] }`（含状态文案、是否逾期、`jumpUrl`）。适合 App 外链 / 小程序轻量查询。

---

### 6.3 工资确认 `/account/salary-confirm`

**产品目标：** 月度发薪前，员工确认自己的工资明细；确认后系统才进入后续审批/入账链路。

| 操作 | 时机 | 接口 | 入参 | 出参 |
|------|------|------|------|------|
| 拉待确认 | 进入 / 换月 / 刷新 | `GET /api/hr/salary/my-confirm` | `yearMonth?`（`YYYY-MM`） | 行列表（含明细） |
| 确认一条 | 点确认 | `POST /api/hr/salary/my-confirm/{lineId}` | — | void |
| 撤销确认 | 点撤销 | `POST /api/hr/salary/my-confirm/{lineId}/revoke` | — | void |

**行状态（后端）：** `PENDING_CONFIRM` → `CONFIRMED` →（系统）`APPROVAL_CREATED` 等。

**流转：** HR 配置项 + 定时/手动预告 → 生成 run/lines → 员工确认 → 生成 `SALARY_MONTHLY` 审批 → 通过后项目结余 → 钱包。

---

### 6.4 工作台 `/dashboard`

**产品目标：** 公司资金一眼概览（偏管理）。

| 操作 | 时机 | 接口 | 出参要点 |
|------|------|------|----------|
| 汇总 | 进入 | `GET /api/finance/summary` | 资金池总额、个人分成总额、池数量、进行中项目等；含池列表 |

权限：`finance:pool:list` 或 `finance:ledger:list`。

---

### 6.5 公司总账 `/finance/ledger`

**产品目标：** 公司资金池的流水中心；登记真实入账/出账；配置大额出账阈值。

**页面结构：** 筛选条 → 汇总 → 流水表 → 详情抽屉 → 登记弹窗 → 阈值弹窗。

| 操作 | 时机 | 接口 | 入参 | 出参 |
|------|------|------|------|------|
| 资金池列表 | 进入 | `GET /api/finance/pool/list` | — | `FinPool[]` |
| 收款渠道 | 进入 | `GET /api/finance/pay-channel/list` | 可选 `all` | 渠道[] |
| 汇总 | 加载 | `GET /api/finance/summary` | — | 汇总 |
| 流水 | 筛选/翻页 | `GET /api/finance/ledger/page` | `page, pageSize, accountType=POOL, bizType?, channelId?, keyword?, minAmount?, maxAmount?, 时间…` | 分页流水 |
| （展示合并）钱包侧相关流水 | 同一次加载 | 同上 `accountType=WALLET` | 用于分钱/报销等对端展示 | |
| 上传凭证 | 登记弹窗 | `POST /api/finance/ledger/voucher` | multipart `file` | 文件（`id`） |
| 登记 | 提交 | `POST /api/finance/ledger/register` | 见下 | `{ mode, message, approval? }` |
| 读阈值 | 打开阈值 | `GET /api/finance/ledger-threshold?companyId=` | | 阈值对象 |
| 存阈值 | 保存 | `PUT /api/finance/ledger-threshold` | `{ companyId, enabled, notifyThreshold, approveThreshold }` | void |
| 公司列表 | 阈值 | `GET /api/sys/dept/my-companies` | — | 公司[] |

**登记入参（UI 实际）：**

```json
{
  "bizType": "INCOME | EXPENSE",
  "accountType": "POOL",
  "poolId": 1,
  "channelId": 1,
  "projectId": null,
  "amount": 1000,
  "feeMode": "FIXED | PERCENT | 空",
  "feeValue": 0.6,
  "title": "标题",
  "remark": "备注",
  "voucherFileIds": [1, 2]
}
```

产品规则：

- **入账** 必须选收款渠道；可带手续费；通常 **提交财务审批** 后才真正入池。
- **出账** 受阈值影响：超过通知/审批阈值时走 `LEDGER_REGISTER` 审批。
- `mode` 告诉前端是直接记账还是已转审批。

流水 `bizType` 展示字典：`INCOME/EXPENSE/TRANSFER/SETTLE/ADVANCE/RESERVE/REIMBURSE/SALARY/ROLLBACK/FEE`。

---

### 6.6 项目账款 `/finance/project-account` ★（App 财务核心页）

**产品目标：** 一个项目从「公司拿钱」到「花掉 / 分掉 / 退回」的操作台。Web 已把旧「分成页 / 手动分钱页」合并到这里。

**页面结构：**

1. 项目账款卡片列表  
2. 进入详情：项目流水 Tab + 分成与分钱 Tab  
3. 弹窗：从公司转入（预支）、报销、发工资、配置分成、按预设分钱、预留回公司  

| 操作 | 时机 | 接口 | 入参要点 | 动账时机 |
|------|------|------|----------|----------|
| 列表 | 进入 | `GET /api/finance/project-account/list` | — | — |
| 用户字典 | 进入 | `GET /api/sys/user/list` | `companyId?` | — |
| 账款详情 | 点进项目 | `GET /api/finance/project-account/{projectId}` | | |
| 分成详情 | 同左 | `GET /api/finance/project-share/{projectId}` | | 含 budget、百分比、members |
| 项目流水 | 同左 / 翻页 | `GET /api/finance/project-account/{projectId}/ledger` | `page, pageSize, bizType?` | |
| 收款方式 | 打开报销/工资弹窗 | `GET /api/hr/archive/my-pay-methods` | — | |
| 上传凭证 | 报销等 | `POST /api/workflow/voucher` | file | |
| 资金配置 | 保存分成 | `POST /api/workflow/approval` type=`SHARE_CONFIG` | 见下 | 通过后改规则，**不直接分钱** |
| 按预设分钱 | 提交 | type=`PROJECT_SETTLE` | `amount` + `payload.items[]` | 通过后立即：项目→各钱包 |
| 预留回公司 | 提交 | type=`RESERVE_RETURN` | `amount=当前结余` | 通过后：项目→公司池 |
| 项目预支 | 提交 | type=`PROJECT_ADVANCE` | `projectId, amount, remark` | 通过后：公司池→项目 |
| 项目报销 | 提交 | type=`REIMBURSE_PROJECT` | 金额+凭证+可选 payMethodId | **确认到账后** 项目→钱包 |
| 项目发工资 | 提交 | type=`SALARY_APPLY` | 金额+可选凭证/收款方式 | **确认到账后** 项目→钱包 |

**SHARE_CONFIG payload：**

```json
{
  "poolId": 1,
  "budget": 100000,
  "expensePercent": 0,
  "reservePercent": 20,
  "settlePercent": 80,
  "members": [{ "userId": 1, "layer": "负责人", "percent": 50, "remark": "" }]
}
```

规则：`reservePercent + settlePercent === 100`；成员 `percent` 合计 100。

**PROJECT_SETTLE payload：**

```json
{ "items": [{ "userId": 1, "amount": 3000, "layer": "负责人" }] }
```

**REIMBURSE_PROJECT / SALARY_APPLY：**

```json
{
  "type": "REIMBURSE_PROJECT",
  "title": "项目报销 · xxx",
  "projectId": 1,
  "amount": 500,
  "remark": "...",
  "voucherFileIds": [11],
  "payload": { "payMethodId": 3 }
}
```

金额不得超过项目 `balance`。

---

### 6.7 全员钱包 `/finance/wallet-board`

**产品目标：** 管理者查看所有人钱包余额与流水。

| 操作 | 时机 | 接口 |
|------|------|------|
| 汇总 + 列表 | 进入 | `GET /api/finance/summary` + `GET /api/finance/wallet/page` |
| 某人流水 | 点开抽屉 / 筛选 | `GET /api/finance/wallet/{userId}/ledger` |

权限：`finance:wallet:board` 或 `finance:wallet:list`。

---

### 6.8 收款渠道 `/finance/pay-channel`

**产品目标：** 维护公司收款方式（支付宝 / 微信 / 银行 / 现金），供入账与月度核验使用。

| 操作 | 接口 | 入参 |
|------|------|------|
| 列表 | `GET /api/finance/pay-channel/list` | `all?`, `poolId?` |
| 池列表 | `GET /api/finance/pool/list` | |
| 新建/编辑 | `POST` 或 `PUT /api/finance/pay-channel` | `{ id?, poolId, channelType, name, accountNo, accountName, bankName, sort, status, remark }` |

权限编辑：`finance:channel:edit`。

---

### 6.9 月度核验 `/finance/month-verify`

**产品目标：** 每月核对渠道系统余额与对账单余额，上传截图凭证走审批；**不改账**，只留核验记录。

| 操作 | 接口 | 入参 |
|------|------|------|
| 列表 | `GET /api/finance/month-verify/list` | `verifyMonth?, channelId?` |
| 渠道 | `GET /api/finance/pay-channel/list` | |
| 提交核验 | `POST /api/workflow/approval` type=`MONTHLY_VERIFY` | 见下 |

```json
{
  "type": "MONTHLY_VERIFY",
  "title": "月度核验 · 2026-09 · 渠道名",
  "poolId": 1,
  "remark": "",
  "voucherFileIds": [1],
  "payload": {
    "verifyMonth": "2026-09",
    "channelId": 1,
    "systemBalance": 12345.67,
    "statementBalance": 12300,
    "voucherFileIds": [1]
  }
}
```

通过后写 `FinMonthVerify.status=PASSED`。

---

### 6.10 审批配置 `/workflow/flow`

**产品目标：** 按公司为每种审批类型配置审批人（角色和/或指定用户）、会签或签、超时自动通过。

| 操作 | 接口 | 入参 |
|------|------|------|
| 我的公司 | `GET /api/sys/dept/my-companies` | |
| 角色 | `GET /api/sys/role/list` | |
| 用户 | `GET /api/sys/user/list` | |
| 流列表 | `GET /api/workflow/flow/list?companyId=` | |
| 保存 | `PUT /api/workflow/flow` | `{ id?, companyId, type, name, passMode, roleCodeList, userIdList, timeoutHours, status, sort, remark }` |
| 删除 | `DELETE /api/workflow/flow/{id}` | |
| 跨公司复制 | `POST /api/workflow/flow/copy` | `{ fromCompanyId, toCompanyId }` → 复制条数 |

权限：`workflow:flow:edit`。

可配置 `type` 见第 7 章（与 Web `flow.vue` 内置列表一致）。

---

### 6.11 项目管理 `/project/list`

**产品目标：** 立项与项目全生命周期；详情内嵌看板与任务。

**列表区：**

| 操作 | 接口 | 入参 / 出参 |
|------|------|-------------|
| 公司 / 用户 | `GET /api/sys/dept/my-companies`, `GET /api/sys/user/list` | |
| 分页 | `GET /api/project/page` | `page, pageSize, name?, status?` → `PageResult<PmProject>` |
| 预览编号 | `GET /api/project/next-code?companyId=` | 返回建议 `code` 字符串（只读展示） |
| 保存 | `POST /api/project` 或 `PUT /api/project` | 见下；可能直接成功或返回审批单 |
| 删除 | `DELETE /api/project/{id}` | **始终走** `PROJECT_DELETE` 审批 |

**保存 body：**

```json
{
  "id": null,
  "name": "项目名",
  "companyId": 1,
  "ownerId": 2,
  "status": 1,
  "scale": "NORMAL | KEY | MAJOR",
  "startDate": "2026-09-01",
  "endDate": "2026-12-31",
  "actualEndDate": null,
  "description": "",
  "members": [{ "userId": 3, "layer": "设计", "percent": 0 }]
}
```

**创建流转：**

```text
scale=NORMAL → 直接落库 approveStatus=1，建项目账款
scale=KEY/MAJOR → 创建 PROJECT_CREATE 审批；通过后才建项目与账款
```

**改规模流转：**

```text
改到 KEY/MAJOR（含 KEY↔MAJOR）→ 其它字段先存，规模保持旧值，另开 PROJECT_SCALE_CHANGE
改到 NORMAL 或不变 → 直接存
```

**详情区：**

| Tab / 动作 | 接口 |
|------------|------|
| 进入详情 | `GET /api/project/{id}` |
| 操作记录 | `GET /api/project/{id}/flows` |
| 任务统计 | `GET /api/task/summary?projectId=` |
| 任务列表 | `GET /api/task/page?projectId=&…` |
| 看板 | `GET /api/task/board?projectId=` |
| 拖拽改状态 | `PUT /api/task/{id}/status` `{ status, imageFileIds?, remark? }` |

任务详情抽屉见 6.12 共用组件能力。

权限：`project:list|add|edit|remove`，任务 `project:task:add|edit|…`。

---

### 6.12 任务管理 `/project/task` + 任务详情抽屉

**产品目标：** 跨项目看任务；在抽屉里完成创建、编辑、评论、传图、移交、关闭。

**列表页：**

| 操作 | 接口 |
|------|------|
| 项目/用户字典 | `GET /api/project/list`, `GET /api/sys/user/list` |
| 统计卡片 | `GET /api/task/summary` |
| 列表 | `GET /api/task/page`（`status/priority/participantId/title/overdue/projectId`） |

**详情抽屉：**

| 操作 | 接口 | 入参 |
|------|------|------|
| 详情 | `GET /api/task/{id}` | |
| 评论列表 | `GET /api/task/{taskId}/comments` | |
| 流转日志 | `GET /api/task/{taskId}/flows` | |
| 候选人（负责人+成员） | `GET /api/project/{id}` | 用项目成员 |
| 新建/保存 | `POST` / `PUT /api/task` | `{ id?, title, projectId, participantIds, status, priority, startDate, dueDate, progress, content, imageFileIds }` |
| 改状态/关闭 | `PUT /api/task/{id}/status` | `{ status, imageFileIds?, remark? }` |
| 上传图片 | `POST /api/task/image` | multipart |
| 删图片 | `DELETE /api/task/image/{fileId}` | |
| 移交 | `PUT /api/task/{taskId}/transfer` | `{ assigneeId, remark?, imageFileIds? }` |
| 发评论 | `POST /api/task/{taskId}/comments` | `{ content }` |
| 删评论 | `DELETE /api/task/comment/{commentId}` | |

**看板组件：** `GET /api/task/board?projectId=` + 拖拽 `PUT .../status`。

---

### 6.13 人员档案 `/hr/archive`

**产品目标：** 员工档案 + 收款方式（银行卡/支付宝/微信），供报销发薪选用。

| 操作 | 接口 | 入参 |
|------|------|------|
| 分页 | `GET /api/hr/archive/page` | `page, pageSize, realName?, employeeNo?` |
| 详情 | `GET /api/hr/archive/{id}` | 含 `payMethods` |
| 保存 | `POST` / `PUT /api/hr/archive` | 档案字段 + `payMethods[{ methodType, accountName, accountNo, bankName, isDefault, sort, remark }]` |
| 删除 | `DELETE /api/hr/archive/{id}` | |
| 我的收款方式 | `GET /api/hr/archive/my-pay-methods` | 登录用户自己（报销弹窗用） |

`methodType`：`BANK` / `ALIPAY` / `WECHAT`。

---

### 6.14 折旧类别 `/hr/depr-category`

| 操作 | 接口 |
|------|------|
| 公司 | `GET /api/sys/dept/my-companies` |
| 分页 | `GET /api/fa/category/page` |
| 保存 | `POST` / `PUT /api/fa/category` → `{ companyId, name, months, residualRate, status, remark }` |
| 删除 | `DELETE /api/fa/category/{id}` |

---

### 6.15 资产台账 `/hr/asset`

**产品目标：** 固定资产入库；领用/归还走审批；领用会冻结个人钱包可用额度（按原值）。

| 操作 | 接口 | 说明 |
|------|------|------|
| 字典 | `my-companies`, `GET /api/fa/category/list` | |
| 分页 | `GET /api/fa/asset/page` | `companyId?, status?, keyword?` |
| 详情 | `GET /api/fa/asset/{id}` | 含事件流水 |
| 入库/编辑 | `POST` / `PUT /api/fa/asset` | `{ companyId, categoryId, assetCode, name, originalValue, purchaseDate, deprStartDate, remark }` |
| 领用 | `workflow submit` `ASSET_BORROW` | `payload: { assetId, companyId }`，`amount=原值` |
| 归还 | `ASSET_RETURN` | 同形状 |

资产 `status`：`IN_STOCK` / `IN_USE`。

---

### 6.16 工资配置 `/hr/salary`

**产品目标：** 按公司配置「谁每月从哪个项目扣多少」+ 发薪时刻表。

| 操作 | 接口 | 入参 |
|------|------|------|
| 字典 | `my-companies`, `project/list`, `user/list` | |
| 工资项列表 | `GET /api/hr/salary/items?companyId=&userId?` | |
| 存/改项 | `POST` / `PUT /api/hr/salary/item` | `{ companyId, userId, projectId?, amount, enabled, remark }` |
| 删项 | `DELETE /api/hr/salary/item/{id}` | |
| 读时刻表 | `GET /api/hr/salary/schedule?companyId=` | |
| 存时刻表 | `PUT /api/hr/salary/schedule` | `{ companyId, payDay, payHour, payMinute, previewDays, enabled, submitterUserId }` |

**时刻表语义：** 到点预告（提前 `previewDays`）→ 员工确认 → 正式发薪；`submitterUserId` 为系统代提审批的账号。

---

### 6.17 发薪记录 `/hr/salary-run`

| 操作 | 接口 | 入参 |
|------|------|------|
| 跑批列表 | `GET /api/hr/salary/runs?companyId=&yearMonth?` | |
| 行明细 | `GET /api/hr/salary/runs/{runId}/lines` | |
| 手动预告 | `POST /api/hr/salary/preview` | `{ companyId, yearMonth? }` |
| 手动发薪 | `POST /api/hr/salary/pay` | `{ companyId, yearMonth? }` |

`HrSalaryRun.phase`：`PREVIEW` / `PAY`；`triggerType`：`CRON` / `MANUAL`。

月度审批类型为 `SALARY_MONTHLY`（系统生成，App 一般只读审批中心，不手填 payload）。通过后按明细从各项目结余转入员工钱包（**无需**回执确认）。

---

### 6.18 文件管理 `/file/list`

| 操作 | 接口 | 说明 |
|------|------|------|
| 分页 | `GET /api/file/page` | `originalName?, bizType?` |
| 上传 | `POST /api/file/upload` | multipart `file` + `bizType?` + `bizId?` |
| 删除 | `DELETE /api/file/{id}` | |
| 预览/下载 | `GET /api/file/preview/{id}` / `download/{id}` | 公开 |

---

### 6.19 系统管理

#### 用户 `/system/user`

| 操作 | 接口 |
|------|------|
| 分页 | `GET /api/sys/user/page` |
| 详情 | `GET /api/sys/user/{id}`（含 `userDepts`, `roleBindings`） |
| 保存 | `POST` / `PUT /api/sys/user` |
| 删除 | `DELETE /api/sys/user/{id}` |
| 重置密码 | `PUT /api/sys/user/reset-pwd` `{ id, password? }` 默认 `123456` |
| 启停 | `PUT /api/sys/user/status` `{ id, status }`（API 有，Web 页未必暴露） |
| 字典 | `role/list`, `dept/tree`, `dept/companies` |

保存时重点字段：多部门 `userDepts[{ deptId, dataScope, isPrimary }]`、多公司角色 `roleBindings[{ roleId, companyId }]`。

#### 角色 `/system/role`

`GET/POST/PUT/DELETE /api/sys/role`；详情含 `menuIds`, `deptIds`；`dataScope` 1–5。

#### 部门 `/system/dept`

`GET /tree` / `companies` / `my-companies`；`POST/PUT/DELETE`。

#### 菜单 `/system/menu`

`GET /tree`；`POST/PUT/DELETE`。`type`：目录 / 菜单 / 按钮；按钮的 `permission` 即权限码。

---

## 7. 审批类型全表

| type | 中文 | 谁发起 | 通过后效果 | 要回执确认？ | 可资金回退？ |
|------|------|--------|------------|--------------|--------------|
| `PROJECT_CREATE` | 创建项目 | 建重点/重大项目 | 落库项目+账款 | 否 | 否 |
| `PROJECT_DELETE` | 删除项目 | 删除 | 删项目 | 否 | 否 |
| `PROJECT_SCALE_CHANGE` | 变更规模 | 改到重点/重大 | 更新 scale | 否 | 否 |
| `SHARE_CONFIG` | 资金配置 | 项目账款 | 写预算/预留/分成规则 | 否 | 否 |
| `PROJECT_ADVANCE` | 项目预支 | 项目账款 | 池→项目 | 否 | 是 |
| `PROJECT_SETTLE` | 项目分钱 | 项目账款 | 项目→多人钱包 | 否 | 是 |
| `RESERVE_RETURN` | 预留回公司 | 项目账款 | 项目→池 | 否 | 标记可回退但实现可能不全 |
| `REIMBURSE_PROJECT` | 项目报销 | 项目账款 | 项目→钱包 | **是** | 是 |
| `SALARY_APPLY` | 工资申请 | 项目账款 | 项目→钱包 | **是** | 是 |
| `SALARY_MONTHLY` | 月度工资 | 系统/发薪 | 各项目→钱包 | 否 | 是 |
| `REIMBURSE_PERSONAL` | 个人报销 | 个人 | 池→钱包 | **是** | 是 |
| `LEDGER_REGISTER` | 总账登记 | 总账 | 池入/出 | 否 | 是 |
| `MONTHLY_VERIFY` | 月度核验 | 核验页 | 写核验记录 | 否 | 否 |
| `ASSET_BORROW` | 资产领用 | 资产页 | 资产 IN_USE + 冻钱包 | 否 | 否 |
| `ASSET_RETURN` | 资产归还 | 资产页 | 归还 + 解冻 | 否 | 否 |
| `ROLLBACK` | 资金回退 | 审批中心 | 冲正原单 | 否 | — |

`ROLLBACK` 入参模式：`FULL`（全额）/ `PARTIAL`（部分，`amount`≤原单）。原单须已通过；若原单是确认类，须 `confirmStatus=3`。

---

## 8. App 信息架构建议

按角色拆 Tab，而不是 1:1 搬 Web 侧栏：

### 建议 Tab

1. **工作台 / 我的** —— 对应 `/account`：钱包、待办数、今日任务、快捷入口  
2. **项目** —— 项目列表 → 详情（信息 / 看板 / 任务 / 账款入口）  
3. **审批** —— 待办 / 我发起 / 详情状态机（含回执上传、确认到账、回退）  
4. **财务**（有权限才显示）—— 总账、项目账款、渠道、核验、全员钱包  
5. **更多** —— HR、系统、文件、个人资料  

### App 开发顺序建议

1. 登录 + `auth/info` + 权限工具 + 通知 WS  
2. 审批中心（读列表/详情/通过拒绝）—— 几乎所有业务的枢纽  
3. 个人中心 + 任务（含详情抽屉能力）  
4. 项目列表/详情/看板  
5. 项目账款（资金主路径）  
6. 公司总账 / 渠道 / 核验  
7. 工资确认 + HR  
8. 系统管理（可二期）

### 客户端状态要注意的坑

1. **创建项目返回值可能是项目，也可能是审批单** —— 用字段区分（如存在 `bizNo`/`type`）。  
2. **确认类审批通过 ≠ 钱已到** —— UI 必须展示 `confirmStatus`。  
3. **个人钱包禁止直接调记账接口**。  
4. **任务参与人必须是项目成员范围**，否则后端拒绝。  
5. **菜单以服务端为准**；本地路由表只做 path → 页面映射。  
6. 文件先上传拿 `id`，再提交业务。  

---

## 9. 附录：接口速查

### 9.1 Auth / Account / Notification

| Method | Path |
|--------|------|
| POST | `/api/auth/login` |
| POST | `/api/auth/logout` |
| GET | `/api/auth/info` |
| GET | `/api/auth/totp/status/username` |
| GET | `/api/auth/totp/status` |
| POST | `/api/auth/totp/generate` |
| POST | `/api/auth/totp/verify` |
| GET/PUT | `/api/account/profile` |
| PUT | `/api/account/password` |
| POST | `/api/account/task-query-code` |
| POST | `/api/public/task-query` |
| GET | `/api/notification/page` |
| GET | `/api/notification/unread-count` |
| POST | `/api/notification/{id}/read` |
| POST | `/api/notification/read-all` |
| WS | `/ws/notification?token=` |

### 9.2 System

| Method | Path |
|--------|------|
| GET | `/api/sys/user/page` `/list` `/{id}` |
| POST/PUT | `/api/sys/user` |
| DELETE | `/api/sys/user/{id}` |
| PUT | `/api/sys/user/reset-pwd` `/status` |
| GET | `/api/sys/role/list` `/{id}` |
| POST/PUT/DELETE | `/api/sys/role` |
| GET | `/api/sys/dept/tree` `/companies` `/my-companies` |
| POST/PUT/DELETE | `/api/sys/dept` |
| GET | `/api/sys/menu/tree` |
| POST/PUT/DELETE | `/api/sys/menu` |

### 9.3 Project / Task / File

| Method | Path |
|--------|------|
| GET | `/api/project/page` `/list` `/mine` `/{id}` `/{id}/flows` `/next-code` |
| POST/PUT/DELETE | `/api/project` |
| GET | `/api/task/page` `/board` `/summary` `/related` `/{id}` `/{id}/comments` `/{id}/flows` |
| POST/PUT | `/api/task` |
| PUT | `/api/task/{id}/status` `/{id}/transfer` |
| POST | `/api/task/{id}/comments` `/image` |
| DELETE | `/api/task/{id}` `/comment/{commentId}` `/image/{fileId}` |
| GET | `/api/file/page` `/preview/{id}` `/download/{id}` |
| POST | `/api/file/upload` |
| DELETE | `/api/file/{id}` |

### 9.4 Workflow

| Method | Path |
|--------|------|
| GET | `/api/workflow/flow/list` `/flow/describe` |
| PUT | `/api/workflow/flow` |
| DELETE | `/api/workflow/flow/{id}` |
| POST | `/api/workflow/flow/copy` |
| POST | `/api/workflow/approval` `/voucher` `/approval/{id}/approve|reject|withdraw|receipt|confirm` `/approval/rollback` |
| GET | `/api/workflow/approval/page` `/approval/{id}` |

### 9.5 Finance

| Method | Path |
|--------|------|
| GET | `/api/finance/summary` |
| GET/POST/PUT | `/api/finance/pool` … |
| GET | `/api/finance/wallet/page` `/mine` `/mine/ledger` `/{userId}/ledger` |
| GET | `/api/finance/ledger/page` |
| POST | `/api/finance/ledger/register` `/ledger/voucher` |
| GET/PUT | `/api/finance/ledger-threshold` |
| GET | `/api/finance/project-share/{projectId}` |
| GET | `/api/finance/project-account/list` `/{projectId}` `/{projectId}/ledger` |
| GET/POST/PUT | `/api/finance/pay-channel` … |
| GET | `/api/finance/month-verify/list` |

### 9.6 HR / FA / Salary

| Method | Path |
|--------|------|
| GET/POST/PUT/DELETE | `/api/hr/archive` … |
| GET | `/api/hr/archive/my-pay-methods` |
| GET/POST/PUT/DELETE | `/api/hr/salary/items|item|schedule|runs…` |
| GET/POST | `/api/hr/salary/my-confirm…` `/preview` `/pay` |
| GET/POST/PUT/DELETE | `/api/fa/category` … |
| GET/POST/PUT | `/api/fa/asset` … |

---

## 附：一张总流转图（给产品/客户端对齐用）

```text
登录 → auth/info（菜单+权限）→ 主壳 + 通知 WS
                │
                ├─ 我的：钱包流水 / 待办 / 任务 / 项目 / 工资确认 / 资料
                │
                ├─ 项目：创建(或审批) → 看板任务 → 项目账款
                │                      │
                │         预支(池→项) ─┼─ 报销/工资(项→钱包,需回执确认)
                │         配置分成 ───┼─ 分钱(项→钱包)
                │                    └─ 结束退回(项→池)
                │
                ├─ 财务：总账登记 / 渠道 / 月核验 / 全员钱包
                │
                ├─ 审批中心：待办处理 / 回执 / 确认到账 / 回退
                │
                └─ HR：档案收款方式 / 工资配置与发薪 / 资产领还
```

---

*文档生成自当前仓库前后端实现。若接口变更，以 Controller 与 `kk-admin/src/api/*` 为准，并同步改本章。*
