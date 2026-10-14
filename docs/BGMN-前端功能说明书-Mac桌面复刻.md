# BGMN 前端功能说明书 · Mac 桌面端复刻版

> **用途**：按本文即可把现有 Web 管理端（`kk-admin`）功能 **1:1 复刻** 为 Mac 桌面应用（SwiftUI / Electron / Tauri 均可）。  
> **范围**：仅前端功能面——路由、页面交互、权限、接口契约、入出参、调用时机。  
> **依据**：仓库 `kk-admin` 最新代码。  
> **配套**：业务心智与资金模型见 [`BGMN-产品与接口说明书.md`](./BGMN-产品与接口说明书.md)。

---

## 目录

1. [桌面端复刻总原则](#1-桌面端复刻总原则)
2. [HTTP / 会话 / 权限 / 通知基础设施](#2-http--会话--权限--通知基础设施)
3. [完整路由表（从上到下）](#3-完整路由表从上到下)
4. [壳层 · AdminLayout](#4-壳层--adminlayout)
5. [页面功能说明书（逐路由）](#5-页面功能说明书逐路由)
6. [共享组件](#6-共享组件)
7. [前端 API 完整契约](#7-前端-api-完整契约)
8. [枚举与字典速查](#8-枚举与字典速查)
9. [Mac 实现清单 Checklist](#9-mac-实现清单-checklist)

---

## 1. 桌面端复刻总原则

### 1.1 必须保持一致的行为

| 项 | Web 现状 | Mac 要求 |
|----|----------|----------|
| API 前缀 | `baseURL = /api` | 可配置后端根地址，所有路径仍挂在 `/api` 下 |
| Token | Header `Authorization: <token>`（**不是** `Bearer ` 前缀） | 同左；本地安全存储（Keychain 推荐） |
| 响应信封 | `{ code, message, data }`，成功 `code===200`，业务层只拿 `data` | 同左 |
| 401 | 提示过期 → 清会话 → 登录页 | 同左 |
| 菜单 | 来自 `GET /auth/info` 的 `menus`，不是写死侧栏 | 同左：桌面导航由服务端菜单驱动 |
| 按钮权限 | `hasPermission(code)`；`admin` 角色或 `*:*:*` 全过 | 同左 |
| 默认落地 | 登录成功 → `/account` | 同左（个人中心） |
| 重定向路由 | 旧路径自动跳到合并后的页面 | 实现等价跳转或直接不暴露旧入口 |

### 1.2 不需要像素级复刻的部分

- 毛玻璃 / Element Plus 视觉样式可按 macOS HIG 重做。
- 移动端抽屉菜单可改为原生侧栏 / Toolbar。
- Web 特有的 `v-permission` 改为「无权限则不渲染控件」。

### 1.3 推荐桌面导航映射

| Web | Mac 建议 |
|-----|----------|
| 左侧 `el-menu` | `NavigationSplitView` 侧栏 |
| 顶栏通知铃铛 | Toolbar 铃铛 + 下拉 / popover |
| 用户芯片 → 资料 | 侧栏底部或 Toolbar 菜单 |
| 路由 `path` | 用菜单 `path` 做页面 ID 映射表 |

---

## 2. HTTP / 会话 / 权限 / 通知基础设施

### 2.1 请求层（对应 `utils/request.ts`）

```text
Base URL:   {API_HOST}/api
Timeout:    30000ms
Header:     Authorization: <token>   // 有 token 时
成功:       code === 200 → 返回 data
失败:       code !== 200 → 抛错，展示 message
            code === 401 → logout（logout 请求本身除外）
网络错误:   展示错误信息
Blob:       responseType=blob 时不走信封解包（当前业务上传多用 multipart JSON 回包）
```

分页通用结构：

```ts
interface PageResult<T> {
  list: T[]
  total: number
  page: number
  pageSize: number
}
```

### 2.2 用户会话（对应 `stores/user.ts`）

**持久化字段：** 仅 `token`（Web 用 pinia-persist）。

**状态：**

| 字段 | 类型 | 来源 |
|------|------|------|
| `token` | string \| null | login |
| `user` | UserInfo \| null | login / getInfo |
| `roles` | string[] | getInfo |
| `permissions` | string[] | getInfo |
| `menus` | MenuInfo[] | getInfo |

**方法：**

| 方法 | 行为 |
|------|------|
| `login({username,password,totpCode?})` | `POST /auth/login` → 存 token/user → `getInfo()`；getInfo 失败则回滚清空 |
| `getInfo()` | `GET /auth/info` → 写 user/roles/permissions/menus |
| `logout()` | 先清空本地 → `POST /auth/logout`（失败忽略）→ 跳登录 |
| `hasPermission(p)` | `roles` 含 `admin` → true；或 `permissions` 含 `p` 或 `*:*:*` |

**UserInfo：**

```ts
{
  id: number
  username: string
  nickname: string
  avatar?: string
  email?: string
  phone?: string
  gender?: number
  status?: number
  deptId?: number
  roleIds?: number[]
  userDepts?: { deptId?, dataScope?, isPrimary?, deptName?, companyId? }[]
  roleBindings?: { roleId?, companyId?, roleName?, roleCode?, companyName? }[]
  taskQueryCode?: string
}
```

**MenuInfo：**

```ts
{
  id: number
  parentId: number
  name: string
  type: number        // 1目录 2菜单 3按钮
  path: string        // 路由 path，如 /project/list
  component: string
  permission: string
  icon: string
  sort: number
  visible: number     // 0 隐藏
  status: number
  children?: MenuInfo[]
}
```

### 2.3 路由守卫（对应 `router/index.ts`）

```text
进入页面:
  1. 设置窗口标题 = "{meta.title || BGMN} - BGMN"
  2. 若 requiresAuth === false（仅登录页）:
       已有 token 且目标是 login → 跳 /account
       否则放行
  3. 无 token → /login
  4. 有 token 但无 user → await getInfo()；失败则 logout → /login
  5. 放行
```

> 路由 **不做** 按菜单权限拦截；无菜单的页面只要知道 URL 仍可打开，靠按钮权限与接口权限兜底。Mac 侧建议：侧栏只渲染有菜单的项，但深链仍可进（与 Web 一致）。

### 2.4 通知 WebSocket（对应 `utils/notificationWs.ts`）

```text
URL:  ws(s)://{API_HOST}/ws/notification?token={encodeURIComponent(token)}
     （Web 走同域 /ws，由 vite 代理；桌面直连后端 host）

连接时机: 进入主壳 onMounted
断开时机: logout / 主壳销毁

心跳: 每 25s 发送文本 "ping"；收到 "pong" 忽略
重连: 指数退避 1s → 2s → 4s → 8s → 16s，上限 30s（用户主动断开不重连）
去重: 按通知 id，本地 seen 集合最多约 200

推送 JSON 字段:
  type?: "notification"（其它 type 忽略）
  id, title, content, bizType, bizId, link, readFlag

客户端行为:
  onPush → 刷新未读数；若列表打开则插入；系统通知点击 → markRead + 跳转 link 或 /workflow/center
```

REST 配套见第 7 章 `notificationApi`；布局层另每 **30s** 轮询 `unread-count`。

---

## 3. 完整路由表（从上到下）

| # | Path | 组件 | meta.title | 鉴权 | 说明 |
|---|------|------|------------|------|------|
| 1 | `/login` | `LoginPage.vue` | 登录 | 否 | |
| 2 | `/` | `AdminLayout.vue` | — | 是 | redirect → `/account` |
| 3 | `/dashboard` | `dashboard/index.vue` | 首页 | 是 | |
| 4 | `/finance/pool` | — | — | 是 | **redirect** → `/finance/ledger` |
| 5 | `/finance/ledger` | `finance/ledger.vue` | 公司总账 | 是 | |
| 6 | `/finance/project-account` | `finance/project-account.vue` | 项目账款 | 是 | |
| 7 | `/finance/project-share` | — | — | 是 | **redirect** → project-account |
| 8 | `/finance/distribute` | — | — | 是 | **redirect** → project-account |
| 9 | `/finance/wallet-board` | `finance/wallet-board.vue` | 全员钱包 | 是 | |
| 10 | `/finance/wallet` | — | — | 是 | **redirect** → `/account` |
| 11 | `/finance/pay-channel` | `finance/pay-channel.vue` | 收款渠道 | 是 | |
| 12 | `/finance/month-verify` | `finance/month-verify.vue` | 月度核验 | 是 | |
| 13 | `/workflow/center` | `workflow/center.vue` | 审批中心 | 是 | |
| 14 | `/workflow/flow` | `workflow/flow.vue` | 审批配置 | 是 | |
| 15 | `/project/list` | `project/list.vue` | 项目管理 | 是 | |
| 16 | `/project/task` | `project/task.vue` | 任务管理 | 是 | |
| 17 | `/hr/archive` | `hr/archive.vue` | 人员档案 | 是 | |
| 18 | `/hr/depr-category` | `hr/depr-category.vue` | 折旧类别 | 是 | |
| 19 | `/hr/asset` | `hr/asset.vue` | 资产台账 | 是 | |
| 20 | `/hr/salary` | `hr/salary.vue` | 工资配置 | 是 | |
| 21 | `/hr/salary-run` | `hr/salary-run.vue` | 发薪记录 | 是 | |
| 22 | `/file/list` | `file/list.vue` | 文件管理 | 是 | |
| 23 | `/system/user` | `system/user/index.vue` | 账号管理 | 是 | |
| 24 | `/system/role` | `system/role/index.vue` | 角色权限 | 是 | |
| 25 | `/system/dept` | `system/dept/index.vue` | 部门管理 | 是 | |
| 26 | `/system/menu` | `system/menu/index.vue` | 菜单管理 | 是 | |
| 27 | `/account` | `account/index.vue` | 个人中心 | 是 | **默认首页** |
| 28 | `/account/profile` | `account/profile.vue` | 账号资料 | 是 | |
| 29 | `/account/salary-confirm` | `account/salary-confirm.vue` | 工资确认 | 是 | |
| 30 | `/account/overview` | — | — | 是 | **redirect** → `/account` |
| 31 | `/account/wallet` | — | — | 是 | **redirect** → `/account` |
| 32 | `/account/approval` | — | — | 是 | **redirect** → `/account` |
| 33 | `/account/projects` | — | — | 是 | **redirect** → `/account` |
| 34 | `/account/calendar` | — | — | 是 | **redirect** → `/account` |

**活页面共 24 个**（含登录 + 布局子页，不含纯 redirect）。

---

## 4. 壳层 · AdminLayout

**文件：** `layouts/AdminLayout.vue`

### 4.1 UI 结构

- 左侧：Logo（点 → `/dashboard`）+ 菜单树（来自 `userStore.menus`）
- 顶栏：通知铃铛（未读角标）+ 用户芯片（→ `/account/profile`）+ 退出
- 主区：子路由出口
- 窄屏：汉堡打开左侧抽屉菜单（桌面端可忽略）

### 4.2 菜单渲染规则

```text
menus
  .filter(m => m.visible !== 0 && m.type !== 3)
  .map(children 同样过滤)
菜单项 index = menu.path（用于路由跳转）
```

### 4.3 接口与时机

| 时机 | 接口 | 入参 | 出参（UI 使用） |
|------|------|------|----------------|
| 进入壳 | `GET /notification/unread-count` | — | `{ count }` |
| 每 30s | 同上 | | |
| 打开通知列表 | `GET /notification/page` | `{ page:1, pageSize:15 }` | `list[]`: `id,title,content,createTime,readFlag,link,bizType,bizId` |
| 点一条 | `POST /notification/{id}/read` | — | void；再跳 `link` 或 `/workflow/center` |
| 全部已读 | `POST /notification/read-all` | — | void |
| 退出 | 断 WS + `userStore.logout()` → `POST /auth/logout` | | |

---

## 5. 页面功能说明书（逐路由）

每页固定格式：**UI → 权限 → 进入加载 → 操作×接口×入出参**。

---

### 5.1 `/login` 登录

**UI：** 用户名、密码、条件显示 6 位 TOTP、登录按钮。

**进入：** 无接口。

| 操作 | 时机 | Method Path | 入参 | 出参 |
|------|------|-------------|------|------|
| 探测 TOTP | 用户名失焦 | `GET /auth/totp/status/username` | query `username` | `boolean` |
| 登录 | 提交 | `POST /auth/login` | `{ username, password, totpCode? }` | `{ token, user }` |
| 拉会话 | 登录成功后自动 | `GET /auth/info` | — | `{ user, roles, permissions, menus }` |

成功跳转：`/account`。

---

### 5.2 `/dashboard` 首页

**UI：** 4 张指标卡 + 资金池表格。

| 时机 | 接口 | 出参字段 |
|------|------|----------|
| onMounted | `GET /finance/summary` | `poolTotal, walletTotal, poolCount, projectCount, pools[{name,balance,isDefault,remark}]` |

`isDefault===1` 显示「默认」。

---

### 5.3 `/account` 个人中心（默认首页）

**UI 区块：**

1. 钱包统计（余额 / 冻结 / 可用）
2. 钱包流水（关键词、金额区间、日期、分页）
3. 日历 + 当日任务
4. 审批：待办 / 我发起
5. 我的任务
6. 我的项目

**权限门控（无权限则不请求对应块）：**

- `finance:wallet:list`
- `workflow:list`
- `project:task:list` / 角色 `admin`|`shareholder` 看全量任务
- `project:list`（项目块）

| 时机 / 操作 | 接口 | 入参 | 出参使用字段 |
|-------------|------|------|--------------|
| 进页·钱包 | `GET /finance/wallet/mine` | — | `balance, frozen, available` |
| 进页·流水 / 搜索翻页 | `GET /finance/wallet/mine/ledger` | `page, pageSize, keyword?, minAmount?, maxAmount?, startTime?, endTime?`（日期拼 `00:00:00` / `23:59:59`） | `list[{occurTime,bizNo,title,projectName,amount}], total` |
| 进页·审批待办 | `GET /workflow/approval/page` | `scope=todo, page, pageSize` | `id,title,typeLabel,bizNo,statusLabel` |
| 进页·我发起 | 同上 | `scope=mine` | 同上 |
| 进页·任务 | 管理员/股东：`GET /task/page`；否则 `GET /task/related` | page 或无 | `id,projectId,title,projectName,participantNames,dueDate,overdue,status,statusLabel` |
| 进页·项目 | `GET /project/mine` | — | `id,name,code,ownerId,status,statusLabel` |

---

### 5.4 `/account/profile` 账号资料

| 操作 | 接口 | 入参 | 出参 |
|------|------|------|------|
| 进页 | `GET /account/profile` | — | 赋表单：`nickname,email,phone,gender,taskQueryCode,…` |
| 进页 | `GET /auth/totp/status` | — | `boolean` |
| 保存资料 | `PUT /account/profile` | 表单对象（至少 nickname/email/phone/gender） | void |
| 改密 | `PUT /account/password` | `{ oldPassword, newPassword }` | void |
| 开 TOTP | `POST /auth/totp/generate` | — | `{ accountName, secretKey, qrString }` |
| 确认 TOTP | `POST /auth/totp/verify` | `{ totpCode }` | `boolean` |
| 生成查询码 | `POST /account/task-query-code` | — | `string` |

---

### 5.5 `/account/salary-confirm` 工资确认

| 操作 | 接口 | 入参 | 出参字段 |
|------|------|------|----------|
| 进页 / 换月 | `GET /hr/salary/my-confirm` | `yearMonth?`（`yyyy-MM`） | `[{ lineId, companyName, yearMonth, status, totalAmount, payload.items[{projectName,amount}] }]` |
| 确认 | `POST /hr/salary/my-confirm/{lineId}` | — | void |
| 撤销 | `POST /hr/salary/my-confirm/{lineId}/revoke` | — | void |

状态枚举：`PENDING_CONFIRM` | `CONFIRMED`。

---

### 5.6 `/finance/ledger` 公司总账

**UI：** 汇总卡、筛选、流水表、详情抽屉、登记弹窗、出账阈值弹窗。

| 操作 | 接口 | 入参 | 出参 |
|------|------|------|------|
| 进页 | `GET /finance/pool/list` | — | 池列表 |
| 进页 | `GET /finance/pay-channel/list` | `all=false` | 渠道 |
| 进页 | `GET /finance/summary` | — | `assetsTotal, poolTotal, projectTotal, walletTotal,…` |
| 加载流水 | `GET /finance/ledger/page` | `page=1, pageSize=500, accountType=POOL, bizType?, channelId?, keyword?, minAmount?, maxAmount?, startTime?, endTime?` | 见下 |
| 合并展示 | 同上 | `accountType=WALLET`（按 `relatedId` 拼对手方） | |
| 选项 | `GET /project/list` | — | 项目下拉 |
| 上传凭证 | `POST /finance/ledger/voucher` | multipart `file` | `{ id, url, originalName, contentType,… }` |
| 登记 | `POST /finance/ledger/register` | 见下 | `{ mode, message, approval? }` |
| 公司列表 | `GET /sys/dept/my-companies` | — | |
| 读阈值 | `GET /finance/ledger-threshold` | `companyId` | `{ enabled, notifyThreshold, approveThreshold,… }` |
| 存阈值 | `PUT /finance/ledger-threshold` | `{ companyId, enabled, notifyThreshold, approveThreshold }` | void |

**登记 body：**

```json
{
  "bizType": "INCOME | EXPENSE",
  "accountType": "POOL",
  "poolId": 1,
  "channelId": 1,
  "projectId": null,
  "amount": 1000,
  "feeMode": "FIXED | PERCENT",
  "feeValue": 6,
  "title": "标题",
  "remark": "",
  "voucherFileIds": [1]
}
```

规则：入账必选 `channelId`；手续费仅入账；`mode` 为直接入账或转审批。

**流水行使用字段：** `id, relatedId, accountType, occurTime, bizNo, bizType, title, projectName, amount, afterBalance, userName, vouchers[]`。

---

### 5.7 `/finance/project-account` 项目账款

**UI：** 项目卡片列表 → 详情（指标 + Tab「项目流水 / 分成与分钱」）→ 弹窗：预支 / 报销 / 发工资 / 配置 / 分钱 / 退回。

| 操作 | 接口 | 入参 | 出参字段 |
|------|------|------|----------|
| 进页 | `GET /finance/project-account/list` | — | `projectId, projectName, ownerName, balance, advanceAmount, expenseAmount, settleAmount` |
| 进详情 | `GET /sys/user/list` | — | 用户字典 |
| 进详情 | `GET /finance/project-account/{projectId}` | | 账款详情 |
| 进详情 | `GET /finance/project-share/{projectId}` | | `budget, reservePercent, settlePercent, members[{userId,layer,percent,remark}], poolId, ownerName` |
| 流水 | `GET /finance/project-account/{id}/ledger` | `page, pageSize` | `occurTime,bizNo,bizType,title,amount,accountType,userName,…` |
| 收款方式 | `GET /hr/archive/my-pay-methods` | — | `id, methodType, methodTypeLabel, accountName, accountNo, bankName, isDefault` |
| 上传凭证 | `POST /workflow/voucher` | multipart `file` | 文件含 `id` |
| 资金配置 | `POST /workflow/approval` | type `SHARE_CONFIG`，见下 | 审批单 |
| 分钱 | 同上 | `PROJECT_SETTLE` | |
| 退回 | 同上 | `RESERVE_RETURN` | |
| 预支 | 同上 | `PROJECT_ADVANCE` | |
| 报销 | 同上 | `REIMBURSE_PROJECT` | |
| 发工资 | 同上 | `SALARY_APPLY` | |

**SHARE_CONFIG：**

```json
{
  "type": "SHARE_CONFIG",
  "title": "资金配置 · {项目名}",
  "projectId": 1,
  "poolId": 1,
  "payload": {
    "poolId": 1,
    "budget": 100000,
    "expensePercent": 0,
    "reservePercent": 20,
    "settlePercent": 80,
    "members": [{ "userId": 1, "layer": "负责人", "percent": 50, "remark": "" }]
  },
  "remark": "…"
}
```

**PROJECT_SETTLE：**

```json
{
  "type": "PROJECT_SETTLE",
  "title": "项目分钱 · …",
  "projectId": 1,
  "poolId": 1,
  "amount": 10000,
  "remark": "",
  "payload": { "items": [{ "userId": 1, "amount": 3000, "layer": "负责人" }] }
}
```

**RESERVE_RETURN：** `{ type, title, projectId, poolId?, amount: 当前结余, remark }`

**PROJECT_ADVANCE：** `{ type, title, projectId, amount, remark }`（无 poolId）

**REIMBURSE_PROJECT / SALARY_APPLY：**

```json
{
  "type": "REIMBURSE_PROJECT",
  "title": "项目报销 · …",
  "projectId": 1,
  "amount": 500,
  "remark": "",
  "voucherFileIds": [11],
  "payload": { "payMethodId": 3 }
}
```

报销需凭证；金额 ≤ `balance`。

---

### 5.8 `/finance/wallet-board` 全员钱包

| 操作 | 接口 | 入参 | 出参 |
|------|------|------|------|
| 进页 | `GET /finance/summary` | — | `poolTotal, walletTotal, walletCount` |
| 进页 | `GET /finance/wallet/page` | `page=1, pageSize=200` | `list[{id,userId,nickname,username,realName,balance,frozen,available}]` |
| 打开某人 | `GET /finance/wallet/{userId}/ledger` | `page, pageSize`（汇总可用 500） | `occurTime,bizType,title,projectName,amount` |

---

### 5.9 `/finance/pay-channel` 收款渠道

**权限：** `finance:channel:edit`（新建/编辑按钮）

| 操作 | 接口 | 入参 | 出参 |
|------|------|------|------|
| 进页 | `GET /finance/pay-channel/list` | `all=true` | `id,channelType,channelTypeLabel,name,balance,accountName,accountNo,poolName,status,poolId,…` |
| 进页 | `GET /finance/pool/list` | — | 池下拉 |
| 保存 | `POST` 新建 / `PUT` 编辑 `/finance/pay-channel` | `{ id?, poolId, channelType, name, accountNo, accountName, bankName, sort, status, remark }` | void |

`channelType`：`ALIPAY|WECHAT|BANK|CASH|OTHER`；`status`：`0|1`。

---

### 5.10 `/finance/month-verify` 月度核验

| 操作 | 接口 | 入参 | 出参 |
|------|------|------|------|
| 进页 | `GET /finance/month-verify/list` | — | `verifyMonth,channelName,channelId,systemBalance,statementBalance,diffAmount,status,vouchers[]` |
| 进页 | `GET /finance/pay-channel/list` | `all=true` | 渠道（含 `balance,poolId`） |
| 上传 | `POST /workflow/voucher` | file | `id,url,originalName` |
| 提交 | `POST /workflow/approval` | 见下 | 审批单 |

```json
{
  "type": "MONTHLY_VERIFY",
  "title": "月度核验 · {YYYY-MM} · {渠道名}",
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

---

### 5.11 `/workflow/center` 审批中心

**权限：** 处理 `workflow:handle`；传回执还可 `finance:ledger:add`。

**UI：** scope 单选 `todo|mine|all` + 类型/状态/金额/时间/关键词筛选 + 表格 + 详情抽屉 + 回退弹窗。

| 操作 | 接口 | 入参 | 出参关键字段 |
|------|------|------|--------------|
| 进页/筛选 | `GET /workflow/approval/page` | `page,pageSize,scope,type?,status?,keyword?,minAmount?,maxAmount?,startTime?,endTime?` | 列表见下 |
| 详情 | `GET /workflow/approval/{id}` | — | 详情见下 |
| 通过 | `POST /workflow/approval/{id}/approve` | `{ comment? }` | void |
| 拒绝 | `POST …/reject` | `{ comment? }` | void |
| 撤回 | `POST …/withdraw` | — | void |
| 确认到账 | `POST …/confirm` | — | void |
| 上传回执 | `POST /workflow/voucher` → `POST …/receipt` | `{ fileIds:[id] }` | void |
| 资金回退 | `POST /workflow/approval/rollback` | `{ approvalId, mode:'FULL'\|'PARTIAL', amount?, reason? }` | 新审批单 |

**列表字段：** `id,bizNo,typeLabel,title,applicantName,projectName,amount,statusLabel,canConfirm,canUploadReceipt,canHandle,createTime`

**详情字段：** `bizNo,type,typeLabel,title,applicantName,companyName,projectName,amount,statusLabel,flowTip,passModeLabel,passMode,confirmStatus,createTime,remark,voucherFiles[],receiptFiles[],payloadData,tasks[{assigneeName,action,comment}],logs[{createTime,operatorName,action,remark}],canHandle,canWithdraw,canUploadReceipt,canConfirm,canRollback`

**payloadData 按 type 展示**（桌面需同样分支渲染）：

| type | 读取字段 |
|------|----------|
| `SHARE_CONFIG` | budget, settlePercent, reservePercent, members |
| `PROJECT_SETTLE` | items[{userName,layer,amount}] |
| `SALARY_MONTHLY` | userName, yearMonth, totalAmount, items |
| `LEDGER_REGISTER` | bizTypeLabel, amount, title, remark |
| `ASSET_*` | assetCode, assetName, originalValue, remark |
| 报销/工资申请 | payMethod{…}, remark |
| `PROJECT_CREATE` | name, code, scale, ownerName, budget, dates, description, members |
| `PROJECT_SCALE_CHANGE` | fromScale, toScale, name |
| `ROLLBACK` | originBizNo, mode, amount, reason |

筛选类型下拉含全部内置审批 type；状态 `PENDING|APPROVED|REJECTED|WITHDRAWN`；`confirmStatus` 0–3 文案见第 8 章。

---

### 5.12 `/workflow/flow` 审批配置

**权限：** `workflow:flow:edit`

| 操作 | 接口 | 入参 | 出参 |
|------|------|------|------|
| 进页 | `GET /sys/role/list` | — | 角色 |
| 进页 | `GET /sys/dept/my-companies` | — | 公司 |
| 换公司 | `GET /workflow/flow/list` | `companyId` | 流列表 |
| 选人 | `GET /sys/user/list` | `companyId` | 用户 |
| 保存 | `PUT /workflow/flow` | `{ id?, companyId, companyName?, type, name, passMode, roleCodeList, userIdList, timeoutHours, status, sort, remark }` | void |
| 删除 | `DELETE /workflow/flow/{id}` | — | void |
| 复制 | `POST /workflow/flow/copy` | `{ fromCompanyId, toCompanyId }` | `number`（条数） |

`passMode`：`ALL` 会签 / `ANY` 或签；`status`：`0|1`。

内置 type 列表（16）：`PROJECT_CREATE, PROJECT_DELETE, PROJECT_SCALE_CHANGE, SHARE_CONFIG, ROLLBACK, REIMBURSE_PERSONAL, REIMBURSE_PROJECT, SALARY_APPLY, SALARY_MONTHLY, PROJECT_ADVANCE, PROJECT_SETTLE, RESERVE_RETURN, LEDGER_REGISTER, MONTHLY_VERIFY, ASSET_BORROW, ASSET_RETURN`。

---

### 5.13 `/project/list` 项目管理

**权限：** `project:add|edit|remove`，`project:task:add`

**UI：** 筛选卡片列表 → 详情（看板 / 任务表 / 项目信息 / 操作记录）→ 创建编辑弹窗 → 嵌入 `TaskKanban` + `TaskDetailDrawer`。

| 操作 | 接口 | 入参 | 出参 |
|------|------|------|------|
| 进页 | `GET /sys/dept/my-companies`, `GET /sys/user/list`, `GET /project/page` | page 查询 `{ page, pageSize, name, status? }` | 项目分页 |
| 详情 | `GET /project/{id}` | | 见字段 |
| 预览编号 | `GET /project/next-code` | `companyId` | `string` |
| 负责人候选 | `GET /sys/user/list` | `companyId` | |
| 保存 | `POST`/`PUT /project` | 见下 | 可能直接成功或返回审批 `{ type, bizNo, flowTip }` |
| 删除前提示 | `GET /workflow/flow/describe` | `type=PROJECT_DELETE, companyId` | `{ tip,… }` |
| 删除 | `DELETE /project/{id}` | | 审批单 |
| 任务统计 | `GET /task/summary` | `projectId` | `total,todo,doing,done,overdue` |
| 任务表 | `GET /task/page` | `{ page, pageSize, projectId, status? }` | |
| 操作记录 | `GET /project/{id}/flows` | | `createTime, actionLabel, operatorName, summary, approvalId` |

**保存 body：**

```json
{
  "id": null,
  "name": "项目名",
  "companyId": 1,
  "ownerId": 2,
  "status": 1,
  "scale": "NORMAL",
  "startDate": "2026-09-01",
  "endDate": "2026-12-31",
  "actualEndDate": null,
  "description": "",
  "members": [{ "userId": 3, "layer": "设计", "percent": 0 }]
}
```

不传 `code`（后端生成）。`members.layer` 为职责，必填。

**列表/详情字段：** `id,name,code,companyId,companyName,ownerId,ownerName,status,scale,startDate,endDate,actualEndDate,description,members[{userId,nickname,layer}]`

状态：`0` 筹备 `1` 进行中 `2` 已完成 `3` 已关闭；规模：`NORMAL|KEY|MAJOR`。

---

### 5.14 `/project/task` 任务管理

**权限：** `project:task:add`（新建）

| 操作 | 接口 | 入参 | 出参 |
|------|------|------|------|
| 进页 | `GET /project/list`, `GET /sys/user/list` | | 字典 |
| 统计 | `GET /task/summary` | `projectId?` | `todo,doing,done,overdue,total` |
| 列表/筛选 | `GET /task/page` | `{ page, pageSize, title?, projectId?, status?, priority?, participantId?, overdue? }` | 行字段见下 |

**行字段：** `id,title,content,projectName,priority,participantNames,progress,status,startDate,dueDate,overdue`

打开行 → `TaskDetailDrawer`（第 6 章）。

---

### 5.15 `/hr/archive` 人员档案

**权限：** `hr:archive:add|edit|remove`

| 操作 | 接口 | 入参 | 出参 |
|------|------|------|------|
| 进页 | `GET /sys/user/list`, `GET /hr/archive/page` | `{ page, pageSize, realName?, employeeNo? }` | `id,realName,employeeNo,username,entryDate,phone` |
| 详情 | `GET /hr/archive/{id}` | | 含 payMethods、deptName 等 |
| 保存 | `POST`/`PUT /hr/archive` | 见下 | void |
| 删除 | `DELETE /hr/archive/{id}` | | void |

```json
{
  "id": null,
  "userId": 1,
  "realName": "张三",
  "employeeNo": "E001",
  "position": "",
  "education": "",
  "entryDate": "2026-01-01",
  "idCard": "",
  "address": "",
  "emergencyContact": "",
  "emergencyPhone": "",
  "remark": "",
  "payMethods": [
    {
      "id": null,
      "methodType": "BANK",
      "accountName": "",
      "accountNo": "",
      "bankName": "",
      "isDefault": 1,
      "sort": 0,
      "remark": ""
    }
  ]
}
```

`methodType`：`BANK|ALIPAY|WECHAT`。

---

### 5.16 `/hr/depr-category` 折旧类别

**权限：** `fa:category:add|edit|remove`

| 操作 | 接口 | 入参 |
|------|------|------|
| 进页 | `GET /sys/dept/my-companies`, `GET /fa/category/page` | `{ page, pageSize, companyId?, name? }` |
| 保存 | `POST`/`PUT /fa/category` | `{ id?, companyId, name, months, residualRate, status, remark }`（UI 百分比 /100 存 rate） |
| 删除 | `DELETE /fa/category/{id}` | |

---

### 5.17 `/hr/asset` 资产台账

**权限：** `fa:asset:add|edit`

| 操作 | 接口 | 入参 |
|------|------|------|
| 进页 | companies + `GET /fa/asset/page` | `{ page, pageSize, companyId?, status?, keyword? }` |
| 类别 | `GET /fa/category/list` | `companyId` |
| 详情 | `GET /fa/asset/{id}` | 含 `events[]` |
| 保存 | `POST`/`PUT /fa/asset` | `{ id?, companyId, categoryId, assetCode, name, originalValue, purchaseDate?, deprStartDate?, remark }` |
| 领用/归还前 | `GET /workflow/flow/describe` | `type, companyId` |
| 领用 | `POST /workflow/approval` | `{ type:'ASSET_BORROW', companyId, title, amount:originalValue, payload:{ assetId, companyId } }` |
| 归还 | 同上 | `ASSET_RETURN` |

列表字段：`assetCode,name,companyName,categoryName,originalValue,netValue,status,holderName,lockFreeze`  
状态：`IN_STOCK|IN_USE`。

---

### 5.18 `/hr/salary` 工资配置

**权限：** `hr:salary:edit`, `hr:salary:schedule`

| 操作 | 接口 | 入参 |
|------|------|------|
| 换公司 | `GET /project/list`, `GET /sys/user/list?companyId=`, `GET /hr/salary/items?companyId=`, `GET /hr/salary/schedule?companyId=` | |
| 存项 | `POST`/`PUT /hr/salary/item` | `{ id?, companyId, userId, projectId, amount, enabled, remark }` |
| 删项 | `DELETE /hr/salary/item/{id}` | |
| 存时刻表 | `PUT /hr/salary/schedule` | `{ companyId, payDay, payHour, payMinute, previewDays, enabled, submitterUserId }` |

---

### 5.19 `/hr/salary-run` 发薪记录

**权限：** `hr:salary:run:manual`（手动预告/发薪）

| 操作 | 接口 | 入参 | 出参 |
|------|------|------|------|
| 列表 | `GET /hr/salary/runs` | `companyId, yearMonth?` | `id,yearMonth,phase,status,triggerType,message,startedAt` |
| 行 | `GET /hr/salary/runs/{runId}/lines` | | `userName,totalAmount,status,skipReason,approvalId,confirmedAt` |
| 预告 | `POST /hr/salary/preview` | `{ companyId, yearMonth? }` | 含 `message?` |
| 发薪 | `POST /hr/salary/pay` | `{ companyId, yearMonth? }` | 含 `message?` |

`phase`：`PREVIEW|PAY`。

---

### 5.20 `/file/list` 文件管理

| 操作 | 接口 | 入参 | 出参 |
|------|------|------|------|
| 列表 | `GET /file/page` | `{ page, pageSize, originalName? }` | `id,originalName,size,uploaderName,createTime,storageType,bizType,url,contentType` |
| 上传 | `POST /file/upload` | multipart `file` + `bizType=common`，Header Authorization | 信封 `{ code, data }`（Web 用 raw fetch） |
| 删除 | `DELETE /file/{id}` | | void |
| 预览 | `GET /file/preview/{id}` | | 资源流 |
| 下载 | `GET /file/download/{id}` | | 资源流 |

---

### 5.21 `/system/user` 账号管理

**权限：** `system:user:add|edit|resetPwd|remove`

| 操作 | 接口 | 入参 |
|------|------|------|
| 进页 | `role/list`, `dept/tree`, `dept/companies`, `user/page` | page: `{ page, pageSize, username?, nickname? }` |
| 打开 | `GET /sys/user/{id}` | 含 userDepts、roleBindings |
| 保存 | `POST`/`PUT /sys/user` | 见下 |
| 删除 | `DELETE /sys/user/{id}` | |
| 重置密码 | `PUT /sys/user/reset-pwd` | `{ id, password:'123456' }` |

```json
{
  "id": null,
  "username": "zhangsan",
  "nickname": "张三",
  "password": "123456",
  "phone": "",
  "email": "",
  "status": 1,
  "gender": 0,
  "deptId": 1,
  "userDepts": [{ "deptId": 1, "dataScope": 5, "isPrimary": 1 }],
  "roleBindings": [{ "roleId": 2, "companyId": 1 }]
}
```

`dataScope`：`1` 公司全部 / `3` 本部门 / `4` 本部门及以下 / `5` 仅本人。

---

### 5.22 `/system/role` 角色权限

**权限：** `system:role:add|edit|remove`

| 操作 | 接口 | 入参 |
|------|------|------|
| 进页 | `menu/tree`, `dept/tree`, `role/list` | |
| 打开 | `GET /sys/role/{id}` | 含 `menuIds, deptIds` |
| 保存 | `POST`/`PUT /sys/role` | `{ id?, name, code, sort, status, dataScope, remark, menuIds, deptIds }` |
| 删除 | `DELETE /sys/role/{id}` | |

角色 `dataScope`：`1全部|2自定义部门|3本部门|4本部门及以下|5仅本人`。

---

### 5.23 `/system/dept` 部门管理

**权限：** `system:dept:add|edit|remove`

| 操作 | 接口 | 入参 |
|------|------|------|
| 进页 | `GET /sys/dept/tree` | 树 `id,name,leader,phone,sort,children,parentId` |
| 保存 | `POST`/`PUT /sys/dept` | `{ id?, parentId, name, sort, leader, phone, email, status }` |
| 删除 | `DELETE /sys/dept/{id}` | |

---

### 5.24 `/system/menu` 菜单管理

**权限：** `system:menu:add|edit|remove`

| 操作 | 接口 | 入参 |
|------|------|------|
| 进页 | `GET /sys/menu/tree` | |
| 保存 | `POST`/`PUT /sys/menu` | `{ id?, parentId, name, type, path, component, permission, icon, sort, visible, status }` |
| 删除 | `DELETE /sys/menu/{id}` | |

`type`：`1` 目录 `2` 菜单 `3` 按钮。

---

## 6. 共享组件

### 6.1 `TaskDetailDrawer`

**触发：** 项目详情任务行 / 看板卡片 / 任务管理行 / 新建任务。

| 操作 | 接口 | 入参 |
|------|------|------|
| 打开已有 | 并行 `GET /task/{id}`, `/comments`, `/flows`；`GET /project/list`；`GET /project/{projectId}`（候选人） | |
| 保存 | `POST`/`PUT /task` | `{ id?, title, projectId, participantIds, status, priority, startDate, dueDate, progress, content, imageFileIds }` |
| 关闭任务 | `PUT /task/{id}/status` | `{ status: 3, remark? }` |
| 移交 | `PUT /task/{id}/transfer` | `{ assigneeId, remark?, imageFileIds? }` |
| 上传图 | `POST /task/image` | multipart `file` |
| 删图 | `DELETE /task/image/{fileId}` | |
| 评论 | `POST /task/{id}/comments` | `{ content }` |
| 删评 | `DELETE /task/comment/{commentId}` | |

权限：`project:task:edit` 且详情 `canEdit` / `canTransfer`。  
详情字段：`title,status,priority,overdue,projectName,projectId,participantNames,participantIds,progress,dates,content,images[{id,url,originalName}],canEdit,canTransfer`。

### 6.2 `TaskKanban`

| 操作 | 接口 | 入参 |
|------|------|------|
| 加载 | `GET /task/board` | `projectId` |
| 拖拽改列 | `PUT /task/{id}/status` | `{ status }`（列对应 0/1/2） |

卡片：`id,title,status,priority,progress,overdue,dueDate,participantNames,canEdit`。

---

## 7. 前端 API 完整契约

> 路径均相对 `{HOST}/api`。返回均为解包后的 `data`。

### 7.1 authApi

| 函数 | Method | Path | 入参 | 出参 |
|------|--------|------|------|------|
| login | POST | `/auth/login` | `{ username, password, totpCode? }` | `{ token, user: UserInfo }` |
| logout | POST | `/auth/logout` | — | void |
| getInfo | GET | `/auth/info` | — | `{ user, roles, permissions, menus }` |
| getTotpStatusByUsername | GET | `/auth/totp/status/username` | query `username` | `boolean` |
| getTotpStatus | GET | `/auth/totp/status` | — | `boolean` |
| generateTotp | POST | `/auth/totp/generate` | — | `{ accountName, secretKey, qrString }` |
| verifyTotp | POST | `/auth/totp/verify` | `{ totpCode }` | `boolean` |

### 7.2 notificationApi

| 函数 | Method | Path | 入参 | 出参 |
|------|--------|------|------|------|
| page | GET | `/notification/page` | `{ page?, pageSize?, unreadOnly? }` | `PageResult<NotificationItem>` |
| unreadCount | GET | `/notification/unread-count` | — | `{ count }` |
| markRead | POST | `/notification/{id}/read` | — | void |
| markAllRead | POST | `/notification/read-all` | — | void |

`NotificationItem`：`id,userId,title,content?,bizType?,bizId?,link?,readFlag,createTime?`

### 7.3 sysApi

| 函数 | Method | Path | 入参 | 出参 |
|------|--------|------|------|------|
| userPage | GET | `/sys/user/page` | Record | `PageResult` |
| userList | GET | `/sys/user/list` | `{ companyId? }` | `any[]` |
| userDetail | GET | `/sys/user/{id}` | — | user |
| saveUser | POST/PUT | `/sys/user` | user body | void |
| deleteUser | DELETE | `/sys/user/{id}` | — | void |
| resetPwd | PUT | `/sys/user/reset-pwd` | `{ id, password }` | void |
| changeUserStatus | PUT | `/sys/user/status` | `{ id, status }` | void（Web 页未用） |
| roleList | GET | `/sys/role/list` | — | `any[]` |
| roleDetail | GET | `/sys/role/{id}` | — | role |
| saveRole | POST/PUT | `/sys/role` | role body | void |
| deleteRole | DELETE | `/sys/role/{id}` | — | void |
| menuTree | GET | `/sys/menu/tree` | — | tree |
| saveMenu | POST/PUT | `/sys/menu` | menu body | void |
| deleteMenu | DELETE | `/sys/menu/{id}` | — | void |
| deptTree | GET | `/sys/dept/tree` | — | tree |
| deptCompanies | GET | `/sys/dept/companies` | — | companies |
| myCompanies | GET | `/sys/dept/my-companies` | — | companies |
| saveDept | POST/PUT | `/sys/dept` | dept body | void |
| deleteDept | DELETE | `/sys/dept/{id}` | — | void |
| profile | GET | `/account/profile` | — | profile |
| updateProfile | PUT | `/account/profile` | profile | void |
| updatePassword | PUT | `/account/password` | `{ oldPassword, newPassword }` | void |
| generateTaskQueryCode | POST | `/account/task-query-code` | — | `string` |

### 7.4 workflowApi

| 函数 | Method | Path | 入参 | 出参 |
|------|--------|------|------|------|
| page | GET | `/workflow/approval/page` | 筛选 Record | `PageResult` |
| detail | GET | `/workflow/approval/{id}` | — | detail |
| submit | POST | `/workflow/approval` | ApprovalSubmitRequest | approval |
| uploadVoucher | POST | `/workflow/voucher` | FormData `file` | file |
| approve | POST | `/workflow/approval/{id}/approve` | `{ comment? }` | void |
| reject | POST | `/workflow/approval/{id}/reject` | `{ comment? }` | void |
| withdraw | POST | `/workflow/approval/{id}/withdraw` | — | void |
| uploadReceipt | POST | `/workflow/approval/{id}/receipt` | `{ fileIds: number[] }` | void |
| confirm | POST | `/workflow/approval/{id}/confirm` | — | void |
| rollback | POST | `/workflow/approval/rollback` | `{ approvalId, mode, amount?, reason? }` | approval |
| flowList | GET | `/workflow/flow/list` | `companyId` | flows |
| flowDescribe | GET | `/workflow/flow/describe` | `type, companyId` | `{ tip, passModeLabel, timeoutLabel, assigneeCount }` |
| saveFlow | PUT | `/workflow/flow` | flow body | void |
| deleteFlow | DELETE | `/workflow/flow/{id}` | — | void |
| copyFlows | POST | `/workflow/flow/copy` | `{ fromCompanyId, toCompanyId }` | `number` |

**ApprovalSubmitRequest：**

```ts
{
  type: string
  title?: string
  amount?: number
  projectId?: number
  poolId?: number
  companyId?: number
  remark?: string
  payload?: Record<string, unknown>
  voucherFileIds?: number[]
}
```

### 7.5 bizApi（财务 / HR / 项目 / 任务 / 文件）

| 函数 | Method | Path | 入参 | 出参 |
|------|--------|------|------|------|
| summary | GET | `/finance/summary` | — | summary |
| poolList | GET | `/finance/pool/list` | — | pools |
| savePool | POST/PUT | `/finance/pool` | pool | void（活路由未用） |
| walletPage | GET | `/finance/wallet/page` | Record | Page |
| myWallet | GET | `/finance/wallet/mine` | — | wallet |
| myWalletLedger | GET | `/finance/wallet/mine/ledger` | Record | Page |
| walletUserLedger | GET | `/finance/wallet/{userId}/ledger` | Record | Page |
| ledgerPage | GET | `/finance/ledger/page` | Record | Page |
| createLedger | POST | `/finance/ledger` | any | void（**勿用**，后端抛错） |
| registerLedger | POST | `/finance/ledger/register` | register body | `{ mode, message, approval? }` |
| getLedgerThreshold | GET | `/finance/ledger-threshold` | `companyId` | threshold |
| saveLedgerThreshold | PUT | `/finance/ledger-threshold` | threshold | void |
| uploadLedgerVoucher | POST | `/finance/ledger/voucher` | FormData file | file |
| settle / settleManual / saveProjectShare | — | — | — | **勿用**（走审批） |
| payChannelList | GET | `/finance/pay-channel/list` | Record? | channels |
| savePayChannel | POST/PUT | `/finance/pay-channel` | channel | void |
| monthVerifyList | GET | `/finance/month-verify/list` | Record? | list |
| projectShareDetail | GET | `/finance/project-share/{projectId}` | — | share |
| projectAccountList | GET | `/finance/project-account/list` | — | list |
| projectAccountDetail | GET | `/finance/project-account/{projectId}` | — | detail |
| projectAccountLedger | GET | `/finance/project-account/{projectId}/ledger` | Record | Page |
| archivePage | GET | `/hr/archive/page` | Record | Page |
| archiveDetail | GET | `/hr/archive/{id}` | — | detail |
| myPayMethods | GET | `/hr/archive/my-pay-methods` | — | methods |
| saveArchive | POST/PUT | `/hr/archive` | archive | void |
| deleteArchive | DELETE | `/hr/archive/{id}` | — | void |
| salaryItems | GET | `/hr/salary/items` | `{ companyId, userId? }` | items |
| saveSalaryItem | POST/PUT | `/hr/salary/item` | item | void |
| deleteSalaryItem | DELETE | `/hr/salary/item/{id}` | — | void |
| salarySchedule | GET | `/hr/salary/schedule` | `companyId` | schedule |
| saveSalarySchedule | PUT | `/hr/salary/schedule` | schedule | void |
| salaryRuns | GET | `/hr/salary/runs` | `{ companyId, yearMonth? }` | runs |
| salaryRunLines | GET | `/hr/salary/runs/{runId}/lines` | — | lines |
| salaryPreview | POST | `/hr/salary/preview` | `{ companyId, yearMonth? }` | run |
| salaryPay | POST | `/hr/salary/pay` | `{ companyId, yearMonth? }` | run |
| mySalaryConfirm | GET | `/hr/salary/my-confirm` | `yearMonth?` | lines |
| confirmSalary | POST | `/hr/salary/my-confirm/{lineId}` | — | void |
| revokeSalaryConfirm | POST | `/hr/salary/my-confirm/{lineId}/revoke` | — | void |
| faCategoryPage | GET | `/fa/category/page` | Record | Page |
| faCategoryList | GET | `/fa/category/list` | Record? | list |
| saveFaCategory | POST/PUT | `/fa/category` | category | void |
| deleteFaCategory | DELETE | `/fa/category/{id}` | — | void |
| faAssetPage | GET | `/fa/asset/page` | Record | Page |
| faAssetDetail | GET | `/fa/asset/{id}` | — | detail |
| saveFaAsset | POST/PUT | `/fa/asset` | asset | void |
| projectPage | GET | `/project/page` | Record | Page |
| projectList | GET | `/project/list` | — | list |
| myProjects | GET | `/project/mine` | — | list |
| projectDetail | GET | `/project/{id}` | — | detail |
| projectNextCode | GET | `/project/next-code` | `companyId` | `string` |
| saveProject | POST/PUT | `/project` | project | project \| approval |
| projectFlows | GET | `/project/{id}/flows` | — | flows |
| deleteProject | DELETE | `/project/{id}` | — | approval |
| taskPage | GET | `/task/page` | Record | Page |
| taskRelated | GET | `/task/related` | — | list |
| taskSummary | GET | `/task/summary` | `projectId?` | summary |
| taskDetail | GET | `/task/{id}` | — | detail |
| saveTask | POST/PUT | `/task` | task | void |
| taskBoard | GET | `/task/board` | `projectId` | tasks |
| updateTaskStatus | PUT | `/task/{id}/status` | `{ status, imageFileIds?, remark? }` | void |
| taskComments | GET | `/task/{taskId}/comments` | — | comments |
| addTaskComment | POST | `/task/{taskId}/comments` | `{ content }` | comment |
| deleteTaskComment | DELETE | `/task/comment/{commentId}` | — | void |
| taskFlows | GET | `/task/{taskId}/flows` | — | flows |
| transferTask | PUT | `/task/{taskId}/transfer` | `{ assigneeId, remark?, imageFileIds? }` | void |
| uploadTaskImage | POST | `/task/image` | FormData file | file |
| deleteTaskImage | DELETE | `/task/image/{fileId}` | — | void |
| filePage | GET | `/file/page` | Record | Page |
| deleteFile | DELETE | `/file/{id}` | — | void |

另：公开任务查询（非 api 模块导出，但后端存在）`POST /public/task-query` body `{ code }` → `{ nickname, tasks[] }`。

---

## 8. 枚举与字典速查

| 域 | 值 |
|----|-----|
| 项目 status | 0 筹备 / 1 进行中 / 2 已完成 / 3 已关闭 |
| 项目 scale | NORMAL 常规 / KEY 重点 / MAJOR 重大 |
| 任务 status | 0 待办 / 1 进行中 / 2 已完成 / 3 已关闭 |
| 任务 priority | 1 高 / 2 中 / 3 低 |
| 审批 status | PENDING / APPROVED / REJECTED / WITHDRAWN / TIMEOUT_PASS / ROLLING / ROLLED |
| confirmStatus | 0 无需 / 1 待财务回执 / 2 待确认到账 / 3 已确认 |
| passMode | ALL 会签 / ANY 或签 |
| rollback mode | FULL / PARTIAL |
| ledger bizType | INCOME EXPENSE TRANSFER SETTLE ADVANCE RESERVE REIMBURSE SALARY ROLLBACK FEE |
| accountType | POOL / PROJECT / WALLET |
| feeMode | FIXED / PERCENT |
| 渠道类型 | ALIPAY WECHAT BANK CASH OTHER |
| 收款方式 | BANK ALIPAY WECHAT |
| 资产 status | IN_STOCK / IN_USE |
| 工资行 status | PENDING_CONFIRM CONFIRMED APPROVAL_CREATED SKIPPED FAILED |
| 发薪 phase | PREVIEW / PAY |
| 菜单 type | 1 目录 / 2 菜单 / 3 按钮 |
| 用户 dataScope | 1 / 3 / 4 / 5（见上） |
| 角色 dataScope | 1 / 2 / 3 / 4 / 5 |

---

## 9. Mac 实现清单 Checklist

按此顺序实现，可最快达到「能用的桌面端」：

1. **网络层**：可配置 API Host；Authorization；信封解包；401 登出  
2. **登录 + Keychain token + getInfo**  
3. **主壳**：菜单树、未读角标、WS、退出  
4. **个人中心**（默认页）  
5. **审批中心**（详情状态机：通过/拒绝/回执/确认/回退）——几乎所有写操作的枢纽  
6. **项目列表 + 看板 + TaskDetailDrawer**  
7. **项目账款**（预支/报销/工资/分成/分钱/退回）  
8. **公司总账 + 渠道 + 月核验 + 全员钱包**  
9. **工资确认 + HR 五页**  
10. **文件**  
11. **系统四页**  
12. **资料 / TOTP / 查询码**  
13. Redirect 路由兼容（可选）

### 桌面特有建议

- 设置页：API Base URL（开发/生产切换）  
- 深链：`bgmn://workflow/center?id=` 打开审批详情  
- 通知用 macOS Notification Center，点击走与 Web 相同的 markRead + navigate  
- 文件预览优先用返回的 `url`，否则拼 `{HOST}/api/file/preview/{id}`  

---

*本文档只描述前端功能与接口契约。资金动账规则、审批生效时机等业务语义以产品说明书为准。*
