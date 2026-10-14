# BGMN 全量接口文档

> **版本基准**：`master` @ `22845ab`（代码优化）  
> **范围**：后端全部 HTTP + WebSocket，与 Web 管理端（`kk-admin`）实际调用对齐  
> **配套**：[全量页面功能文档](./BGMN-全量页面功能文档.md)  
> **约定**：路径均相对 `{HOST}/api`；成功信封 `{ code:200, message, data }`，业务层取 `data`；鉴权头 `Authorization: <token>`（无 Bearer 前缀）

---

## 1. 通用约定

### 1.1 响应结构

```ts
interface Result<T> { code: number; message: string; data: T }
interface PageResult<T> { list: T[]; total: number; page: number; pageSize: number }
```

### 1.2 鉴权

| 规则 | 说明 |
|------|------|
| 默认 | 所有 `/api/**` 需登录（Sa-Token） |
| 权限 | `@SaCheckPermission("a:b:c")`；角色 `admin` 等价全权限 |
| 公开白名单 | 见下表 |

**公开（免登录）**

| Method | Path | 说明 |
|--------|------|------|
| POST | `/auth/login` | 登录 |
| GET | `/auth/totp/status/username` | 登录前 TOTP 探测 |
| POST | `/public/task-query` | 任务查询码对外查询 |
| GET | `/file/preview/**` | 预览 |
| GET | `/file/download/**` | 下载 |

> `/auth/captcha` 在白名单中，但当前无实现，勿依赖。

### 1.3 WebSocket

| 项 | 值 |
|----|-----|
| URL | `ws(s)://{host}/ws/notification?token={token}` |
| 心跳 | 客户端每 25s 发 `ping`，服务端回 `pong` |
| 推送 | `{ type:"notification", id, title, content, bizType, bizId, link, readFlag }` |

### 1.4 审批类型总表

| type | 中文 | 需回执+确认 | 可资金回退 |
|------|------|-------------|------------|
| PROJECT_CREATE | 创建项目 | | |
| PROJECT_DELETE | 删除项目 | | |
| PROJECT_SCALE_CHANGE | 变更项目规模 | | |
| REIMBURSE_PERSONAL | 个人报销 | ✓ | ✓ |
| WALLET_WITHDRAW | 钱包提现 | ✓ | ✓ |
| REIMBURSE_PROJECT | 项目报销 | ✓ | ✓ |
| PROJECT_BALANCE_APPLY | 项目余额申请 | | ✓ |
| PROJECT_ADVANCE | 项目预支 | | ✓ |
| SHARE_CONFIG | 分成/资金配置 | | |
| PROJECT_SETTLE | 项目分钱 | | ✓ |
| SALARY_APPLY | 工资申请 | ✓ | ✓ |
| SALARY_MONTHLY | 月度工资 | | ✓ |
| RESERVE_RETURN | 预留回公司 | | ✓ |
| LEDGER_REGISTER | 总账登记 | | ✓ |
| ROLLBACK | 资金回退 | | |
| MONTHLY_VERIFY | 月度核验 | | |
| ASSET_BORROW | 资产领用 | | |
| ASSET_RETURN | 资产归还 | | |

**confirmStatus**：`0`无需 `1`待财务回执 `2`待确认到账 `3`已确认  
**审批 status**：`PENDING|APPROVED|REJECTED|WITHDRAWN|TIMEOUT_PASS|ROLLING|ROLLED`

### 1.5 统一提交审批 Body

```ts
interface ApprovalSubmitRequest {
  type: string           // 必填
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

---

## 2. 认证 Auth `/auth`

| Method | Path | 权限 | 入参 | 出参 data | 说明 |
|--------|------|------|------|-----------|------|
| POST | `/auth/login` | 公开 | `{ username, password, totpCode? }` | `{ token, user }` | 登录 |
| POST | `/auth/logout` | 登录 | — | void | 登出 |
| GET | `/auth/info` | 登录 | — | `{ user, roles[], permissions[], menus[] }` | 会话+菜单 |
| POST | `/auth/totp/generate` | 登录 | — | `{ accountName, secretKey, qrString }` | 开始绑 TOTP |
| POST | `/auth/totp/verify` | 登录 | `{ totpCode }` | `boolean` | 确认开启 |
| GET | `/auth/totp/status` | 登录 | — | `boolean` | 当前用户是否开启 |
| GET | `/auth/totp/status/user/{userId}` | 登录 | path | `boolean` | 指定用户 |
| GET | `/auth/totp/status/username` | 公开 | query `username` | `boolean` | 登录前探测 |

**UserInfo 常用字段**：`id, username, nickname, avatar, email, phone, gender, status, deptId, roleIds, userDepts[], roleBindings[], taskQueryCode, totpEnabled`

**MenuInfo**：`id, parentId, name, type(1目录/2菜单/3按钮), path, component, permission, icon, sort, visible, status, children[]`

---

## 3. 账号 Account `/account`

| Method | Path | 权限 | 入参 | 出参 | 说明 |
|--------|------|------|------|------|------|
| GET | `/account/profile` | 登录 | — | SysUser | 个人资料 |
| PUT | `/account/profile` | 登录 | `{ nickname, email, phone, gender, avatar? }` | void | 更新资料 |
| PUT | `/account/password` | 登录 | `{ oldPassword, newPassword }` | void | 改密 |
| POST | `/account/task-query-code` | 登录 | — | `string` | 生成任务查询码 |

---

## 4. 通知 Notification `/notification`

| Method | Path | 权限 | 入参 | 出参 | 说明 |
|--------|------|------|------|------|------|
| GET | `/notification/page` | 登录 | `page?, pageSize?, unreadOnly?` | Page\<SysNotification\> | 列表 |
| GET | `/notification/unread-count` | 登录 | — | `{ count }` | 未读数 |
| POST | `/notification/{id}/read` | 登录 | — | void | 已读一条 |
| POST | `/notification/read-all` | 登录 | — | void | 全部已读 |

**SysNotification**：`id, userId, title, content, bizType, bizId, link, readFlag, createTime`

---

## 5. 公开 Public `/public`

| Method | Path | 权限 | 入参 | 出参 | 说明 |
|--------|------|------|------|------|------|
| POST | `/public/task-query` | 公开 | `{ code }` | `{ nickname, tasks[] }` | 凭查询码查任务 |

**PublicTaskItem**：`id, projectId, title, status, statusLabel, priority, startDate, dueDate, progress, projectName, assigneeName, participantNames, overdue, jumpUrl`

---

## 6. 系统管理 System

### 6.1 用户 `/sys/user`

| Method | Path | 权限 | 入参 | 出参 |
|--------|------|------|------|------|
| GET | `/sys/user/page` | `system:user:list` | `page, pageSize, username?, nickname?, deptId?, status?` | Page\<SysUser\> |
| GET | `/sys/user/list` | 登录 | `companyId?` | SysUser[]（下拉，受数据范围） |
| GET | `/sys/user/{id}` | `system:user:list` | — | 详情含 userDepts/roleBindings |
| POST | `/sys/user` | `system:user:add` | 见下 | void |
| PUT | `/sys/user` | `system:user:edit` | 见下 | void |
| DELETE | `/sys/user/{id}` | `system:user:remove` | — | void |
| PUT | `/sys/user/reset-pwd` | `system:user:resetPwd` | `{ id, password? }` 默认 `123456` | void |
| PUT | `/sys/user/status` | `system:user:edit` | `{ id, status }` | void |

**保存 Body（Web 实际）**：

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

`dataScope`：`1`公司全部 `3`本部门 `4`本部门及以下 `5`仅本人

### 6.2 角色 `/sys/role`

| Method | Path | 权限 | 入参 | 出参 |
|--------|------|------|------|------|
| GET | `/sys/role/list` | `system:role:list` | — | SysRole[] |
| GET | `/sys/role/{id}` | `system:role:list` | — | 含 `menuIds, deptIds` |
| POST/PUT | `/sys/role` | add/edit | `{ id?, name, code, sort, status, dataScope, remark, menuIds, deptIds }` | void |
| DELETE | `/sys/role/{id}` | remove | — | void |

角色 `dataScope`：`1`全部 `2`自定义部门 `3`本部门 `4`本部门及以下 `5`仅本人

### 6.3 部门 `/sys/dept`

| Method | Path | 权限 | 入参 | 出参 |
|--------|------|------|------|------|
| GET | `/sys/dept/tree` | 登录 | — | 树 |
| GET | `/sys/dept/companies` | 登录 | — | 全部公司顶层 |
| GET | `/sys/dept/my-companies` | 登录 | — | 当前可见公司 |
| POST/PUT | `/sys/dept` | add/edit | `{ id?, parentId, name, sort, leader, phone, email, status }` | void |
| DELETE | `/sys/dept/{id}` | remove | — | void |

### 6.4 菜单 `/sys/menu`

| Method | Path | 权限 | 入参 | 出参 |
|--------|------|------|------|------|
| GET | `/sys/menu/tree` | `system:menu:list` | — | 树 |
| POST/PUT | `/sys/menu` | add/edit | `{ id?, parentId, name, type, path, component, permission, icon, sort, visible, status }` | void |
| DELETE | `/sys/menu/{id}` | remove | — | void |

`type`：`1`目录 `2`菜单 `3`按钮

---

## 7. 文件 File `/file`

| Method | Path | 权限 | 入参 | 出参 |
|--------|------|------|------|------|
| GET | `/file/page` | `file:list` | `page, pageSize, originalName?, bizType?` | Page\<SysFile\> |
| POST | `/file/upload` | `file:upload` | multipart `file` + `bizType?` + `bizId?` | SysFile |
| DELETE | `/file/{id}` | `file:remove` | — | void |
| GET | `/file/preview/{id}` | 公开 | — | 资源流/302 |
| GET | `/file/download/{id}` | 公开 | — | 资源流/302 |

**SysFile**：`id, originalName, storedName, path, url, storageType, contentType, size, bizType, bizId, uploaderName`

---

## 8. 项目 Project `/project`

| Method | Path | 权限 | 入参 | 出参 | 说明 |
|--------|------|------|------|------|------|
| GET | `/project/page` | `project:list` | `page, pageSize, name?, status?, companyId?` | Page\<PmProject\> | 分页 |
| GET | `/project/list` | 登录 | — | PmProject[] | 下拉 |
| GET | `/project/mine` | 登录 | — | PmProject[] | 我的项目 |
| GET | `/project/{id}` | `project:list` | — | 详情 | 含 parentId/childCount/members |
| GET | `/project/{id}/children` | `project:list` | — | PmProject[] | 重大项目下小项目 |
| GET | `/project/{id}/flows` | `project:list` | — | PmProjectFlow[] | 操作记录 |
| GET | `/project/next-code` | `project:add` | `companyId` | `string` | 预览编号 |
| POST | `/project` | `project:add` | PmProject（可含 `parentId`） | PmProject **或** WfApproval | 创建 |
| PUT | `/project` | `project:edit` | PmProject | Void **或** WfApproval | 编辑/改规模 |
| DELETE | `/project/{id}` | `project:remove` | — | Void **或** WfApproval | 删除审批 |

**创建/保存 Body（Web）**：

```json
{
  "id": null,
  "name": "项目名",
  "companyId": 1,
  "parentId": null,
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

- `scale`：`NORMAL`常规 / `KEY`重点 / `MAJOR`重大（重点/重大创建或升档需审批）  
- `parentId`：挂在 MAJOR 壳下的小项目，强制 `scale=KEY`  
- `code` 后端生成，前端勿传  
- `status`：`0`筹备 `1`进行中 `2`已完成 `3`已关闭  

---

## 9. 任务 Task `/task`

| Method | Path | 权限 | 入参 | 出参 |
|--------|------|------|------|------|
| GET | `/task/page` | `project:task:list` | `page, pageSize, projectId?, status?, statuses?, priority?, participantId?, title?, overdue?` | Page\<PmTask\> |
| GET | `/task/board` | 同上 | `projectId` | PmTask[] |
| GET | `/task/summary` | 同上 | `projectId?, priority?, participantId?, title?` | `{ todo, doing, done, overdue, total, … }` |
| GET | `/task/related` | 登录 | — | PmTask[] |
| GET | `/task/{id}` | list | — | 详情含 images/canEdit/canTransfer |
| POST | `/task` | add | PmTask + participantIds + imageFileIds | void |
| PUT | `/task` | edit | 同上 | void |
| PUT | `/task/{id}/status` | edit | `{ status, imageFileIds?, remark? }` | void |
| DELETE | `/task/{id}` | remove | — | void |
| POST | `/task/image` | add\|\|edit | multipart `file` | SysFile（图/视频附件） |
| DELETE | `/task/image/{fileId}` | add\|\|edit | — | void |
| GET | `/task/{id}/comments` | list | — | comments |
| POST | `/task/{id}/comments` | add\|\|edit | `{ content }` | comment |
| DELETE | `/task/comment/{commentId}` | add\|\|edit | — | void |
| GET | `/task/{id}/flows` | list | — | flows |
| PUT | `/task/{id}/transfer` | edit\|\|list | `{ assigneeId, remark?, imageFileIds? }` | void |

**任务 status**：`0`待办 `1`进行中 `2`已完成 `3`已关闭  
**priority**：`1`高 `2`中 `3`低  
关闭任务（status=3）通常需 `remark`。

---

## 10. 审批 Workflow `/workflow`

| Method | Path | 权限 | 入参 | 出参 |
|--------|------|------|------|------|
| GET | `/workflow/flow/list` | flow:edit \|\| list | `companyId` | WfApprovalFlow[] |
| GET | `/workflow/flow/describe` | 登录 | `type, companyId` | `{ tip, passModeLabel, timeoutLabel, assigneeCount, soleApproverSelf?, … }` |
| PUT | `/workflow/flow` | `workflow:flow:edit` | flow body | void |
| DELETE | `/workflow/flow/{id}` | 同上 | — | void |
| POST | `/workflow/flow/copy` | 同上 | `{ fromCompanyId, toCompanyId }` | `number` |
| POST | `/workflow/approval` | `workflow:list` | ApprovalSubmitRequest | WfApproval |
| POST | `/workflow/voucher` | list | multipart `file` | SysFile |
| GET | `/workflow/approval/page` | list | `page, pageSize, type?, status?, scope, projectId?, poolId?, minAmount?, maxAmount?, startTime?, endTime?, keyword?` | Page\<WfApproval\> |
| GET | `/workflow/approval/{id}` | list | — | 详情（tasks/logs/payloadData/凭证/can*） |
| POST | `/workflow/approval/{id}/approve` | handle | `{ comment? }` | void |
| POST | `/workflow/approval/{id}/reject` | handle | `{ comment? }` | void |
| POST | `/workflow/approval/{id}/withdraw` | list | — | void |
| POST | `/workflow/approval/{id}/receipt` | handle \|\| ledger:add | `{ fileIds: number[] }` | void |
| POST | `/workflow/approval/{id}/confirm` | list | — | void |
| POST | `/workflow/approval/rollback` | list | `{ approvalId, mode: FULL\|PARTIAL, amount?, reason? }` | WfApproval |
| POST | `/workflow/approval/auto-pass` | handle | — | `{ count }` |

**flow 保存 Body**：`{ id?, companyId, type, name, passMode(ALL|ANY), roleCodeList, userIdList, timeoutHours, status, sort, remark }`

**scope**：`todo` 待办 / `mine` 我发起 / `all` 全部

---

## 11. 财务 Finance `/finance`

### 11.1 汇总 / 资金池

| Method | Path | 权限 | 入参 | 出参 |
|--------|------|------|------|------|
| GET | `/finance/summary` | pool:list \|\| ledger:list | — | 见下 |
| GET | `/finance/pool/list` | pool/ledger 相关 | — | FinPool[] |
| POST/PUT | `/finance/pool` | `finance:pool:edit` | FinPool | void |

**summary 常用字段**：`poolTotal, projectTotal, walletTotal, assetsTotal, poolCount, walletCount, projectCount, pools[], companyBalances[], projectBalances[], companyAssets[]`

### 11.2 钱包

| Method | Path | 权限 | 入参 | 出参 |
|--------|------|------|------|------|
| GET | `/finance/wallet/page` | wallet:list \|\| board | `page, pageSize, userId?` | Page\<HrWallet\> |
| GET | `/finance/wallet/mine` | `finance:wallet:list` | — | HrWallet |
| GET | `/finance/wallet/mine/ledger` | 同上 | 分页+筛选（bizType/companyId/projectId/金额/时间/keyword） | Page\<FinLedger\> |
| GET | `/finance/wallet/mine/board` | 同上 | `period=daily\|monthly` | 看板指标+trend+来源拆分 |
| GET | `/finance/wallet/mine/balance-apply-projects` | 同上 | — | 可申请余额的项目账款[] |
| GET | `/finance/wallet/mine/withdraw-config` | 同上 | `companyId?, amount?` | 税率预览（FLAT/TIER） |
| GET | `/finance/wallet/{userId}/ledger` | board\|\|list\|\|ledger:list | `page, pageSize` | Page\<FinLedger\> |

**HrWallet**：`id, userId, balance, frozen, status, available(=balance-frozen), nickname, username, realName`

**withdraw-config 出参**：`taxMode, taxRate, defaultTaxRate, tax, net, tiers[], breakdown[]`

### 11.3 提现税率配置

| Method | Path | 权限 | 入参 | 出参 |
|--------|------|------|------|------|
| GET | `/finance/wallet-withdraw-tax` | ledger:list\|\|add\|\|pool:edit | `companyId` | `{ companyId, taxMode, defaultTaxRate, tiers[] }` |
| PUT | `/finance/wallet-withdraw-tax` | pool:edit\|\|ledger:add | `{ companyId, tiers:[{ minAmount, maxAmount, taxRate, sort }] }` | void |

空 tiers → 回退配置文件一口价税率。

### 11.4 总账

| Method | Path | 权限 | 入参 | 出参 |
|--------|------|------|------|------|
| GET | `/finance/ledger/page` | `finance:ledger:list` | `page, pageSize, bizType?, accountType?, userId?, companyId?, poolId?, projectId?, channelId?, minAmount?, maxAmount?, startTime?, endTime?, keyword?` | Page\<FinLedger\> |
| POST | `/finance/ledger` | add | — | **禁止**，请用 register |
| POST | `/finance/ledger/register` | `finance:ledger:add` | LedgerCreateRequest | `{ mode: DIRECT\|APPROVAL, message, approval? }` |
| GET | `/finance/ledger-threshold` | 相关 | `companyId` | FinLedgerThreshold |
| PUT | `/finance/ledger-threshold` | pool:edit\|\|ledger:add | `{ companyId, enabled, notifyThreshold, approveThreshold }` | void |
| POST | `/finance/ledger/voucher` | add | multipart `file` | SysFile |

**LedgerCreateRequest（Web 登记）**：

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

**FinLedger 字段**：`id, companyId, bizNo, bizType, accountType(POOL|PROJECT|WALLET), poolId, channelId, userId, amount, grossAmount, feeAmount, feeMode, beforeBalance, afterBalance, projectId, relatedId, approvalId, title, remark, occurTime, userName, projectName, channelName, vouchers[]`

**bizType 字典**：`INCOME EXPENSE TRANSFER SETTLE ADVANCE RESERVE SALARY REIMBURSE WITHDRAW PAYOUT ROLLBACK FEE`

### 11.5 项目分成 / 结算（写接口已废弃）

| Method | Path | 说明 |
|--------|------|------|
| GET | `/finance/project-share/{projectId}` | 读分成配置（PmProject） |
| PUT | `/finance/project-share` | **抛错** → 走 `SHARE_CONFIG` 审批 |
| POST | `/finance/settle` | **抛错** → 走 `PROJECT_SETTLE` |
| POST | `/finance/settle/manual` | **抛错** → 走审批 |

### 11.6 收款渠道 / 月度核验（同 `/finance` 前缀）

| Method | Path | 权限 | 入参 | 出参 |
|--------|------|------|------|------|
| GET | `/finance/pay-channel/list` | channel\|\|ledger | `poolId?, all?` | FinPayChannel[] |
| POST/PUT | `/finance/pay-channel` | `finance:channel:edit` | `{ id?, poolId, channelType, name, accountNo, accountName, bankName, sort, status, remark }` | void |
| GET | `/finance/month-verify/list` | `finance:verify:list` | `verifyMonth?, channelId?` | FinMonthVerify[] |

`channelType`：`ALIPAY|WECHAT|BANK|CASH|OTHER`

### 11.7 项目账款 `/finance/project-account`

| Method | Path | 权限 | 入参 | 出参 |
|--------|------|------|------|------|
| GET | `/finance/project-account/list` | `finance:project:list` | `companyId?, scale?` | FinProjectAccount[]（含 scale/parentId/majorShell/childCount） |
| GET | `/finance/project-account/{projectId}` | 同上 | — | 详情 |
| GET | `/finance/project-account/{projectId}/children` | 同上 | — | 子项目账款 |
| GET | `/finance/project-account/{projectId}/ledger` | 同上 | `page, pageSize, bizType?` | Page\<FinLedger\> |

**FinProjectAccount**：`balance, advanceAmount, expenseAmount, settleAmount, reserveAmount, reserveHeld, projectName, ownerName, companyPoolId, companyPoolBalance, scale, parentId, majorShell, childCount`

---

## 12. 人事 HR

### 12.1 档案 `/hr/archive`

| Method | Path | 权限 | 入参 | 出参 |
|--------|------|------|------|------|
| GET | `/hr/archive/page` | list | `page, pageSize, realName?, employeeNo?` | Page |
| GET | `/hr/archive/my-pay-methods` | 登录 | — | HrPayMethod[] |
| GET | `/hr/archive/{id}` | list | — | 含 payMethods |
| POST/PUT | `/hr/archive` | add/edit | 档案+payMethods | void |
| DELETE | `/hr/archive/{id}` | remove | — | void |

**payMethods**：`methodType(BANK|ALIPAY|WECHAT), accountName, accountNo, bankName, isDefault, sort, remark`

### 12.2 工资 `/hr/salary`

| Method | Path | 权限 | 入参 | 出参 |
|--------|------|------|------|------|
| GET | `/hr/salary/items` | config | `companyId, userId?` | items |
| POST/PUT | `/hr/salary/item` | edit\|\|config | `{ id?, companyId, userId, projectId, amount, enabled, remark }` | void |
| DELETE | `/hr/salary/item/{id}` | 同上 | — | void |
| GET | `/hr/salary/schedule` | config | `companyId` | schedule |
| PUT | `/hr/salary/schedule` | schedule | `{ companyId, payDay, payHour, payMinute, previewDays, enabled, submitterUserId }` | void |
| GET | `/hr/salary/runs` | run | `companyId, yearMonth?` | runs |
| GET | `/hr/salary/runs/{runId}/lines` | run | — | lines |
| POST | `/hr/salary/preview` | run:manual | `{ companyId, yearMonth? }` | run |
| POST | `/hr/salary/pay` | run:manual | `{ companyId, yearMonth? }` | run |
| GET | `/hr/salary/my-confirm` | 登录 | `yearMonth?` | 确认队列 |
| POST | `/hr/salary/my-confirm/{lineId}` | 登录 | — | void |
| POST | `/hr/salary/my-confirm/{lineId}/revoke` | 登录 | — | void |

---

## 13. 固定资产 FA

### `/fa/category`

| Method | Path | 权限 | 入参 |
|--------|------|------|------|
| GET | `/fa/category/page` | list | `page, pageSize, companyId?, name?` |
| GET | `/fa/category/list` | list\|\|asset | `companyId?` |
| POST/PUT | `/fa/category` | add/edit | `{ companyId, name, months, residualRate, status, remark }` |
| DELETE | `/fa/category/{id}` | remove | — |

### `/fa/asset`

| Method | Path | 权限 | 入参 |
|--------|------|------|------|
| GET | `/fa/asset/page` | list | `page, pageSize, companyId?, status?, holderUserId?, keyword?` |
| GET | `/fa/asset/{id}` | list | 含 events |
| POST/PUT | `/fa/asset` | add/edit | `{ companyId, categoryId, assetCode, name, originalValue, purchaseDate?, deprStartDate?, remark }` |

领用/归还：**无独立接口**，走审批 `ASSET_BORROW` / `ASSET_RETURN`。  
资产 status：`IN_STOCK` / `IN_USE`。

---

## 14. 常用审批 payload（与 Web 对齐）

### WALLET_WITHDRAW（个人中心提现）

```json
{
  "type": "WALLET_WITHDRAW",
  "title": "申请提现",
  "amount": 1000,
  "companyId": 1,
  "remark": "",
  "payload": {
    "taxMode": "FLAT | TIER",
    "taxRate": 0.06,
    "tax": 60,
    "net": 940,
    "taxBreakdown": [],
    "payMethodId": 3
  }
}
```

服务端会重算税并冻结钱包；需回执+确认后动账：钱包扣全额，税进公司池。

### PROJECT_BALANCE_APPLY

```json
{
  "type": "PROJECT_BALANCE_APPLY",
  "title": "项目余额申请 · xxx",
  "projectId": 1,
  "amount": 500,
  "remark": ""
}
```

通过即：项目结余 → 个人钱包（无需发票）。

### REIMBURSE_PERSONAL

```json
{
  "type": "REIMBURSE_PERSONAL",
  "title": "个人报销",
  "amount": 200,
  "companyId": 1,
  "remark": "",
  "voucherFileIds": [1],
  "payload": { "payMethodId": 3 }
}
```

### SHARE_CONFIG / PROJECT_SETTLE / PROJECT_ADVANCE / RESERVE_RETURN / REIMBURSE_PROJECT / SALARY_APPLY / MONTHLY_VERIFY / ASSET_*

见[页面功能文档](./BGMN-全量页面功能文档.md)对应操作节。

---

## 15. 接口数量统计

| 模块 | 约计 |
|------|------|
| Auth + TOTP + Account | 12 |
| Notification | 4 |
| System (user/role/dept/menu) | 23 |
| File + Public | 6 |
| Project | 10 |
| Task | 16 |
| Workflow | 16 |
| Finance（含渠道/核验/项目账款/税率） | 31 |
| HR + FA | 28 |
| **HTTP 合计** | **≈146** |
| WebSocket | 1 |

---

*文档随 `kk-api` Controllers 与 `kk-admin/src/api/*` 更新；若冲突以代码为准。*
