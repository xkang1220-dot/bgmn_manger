# BGMN 全量页面功能文档

> **版本基准**：`master` @ `22845ab`  
> **目标**：与 Web（`kk-admin`）功能 **1:1 对齐**，供 Mac 桌面端全量复刻  
> **配套**：[全量接口文档](./BGMN-全量接口文档.md)  
> **侧栏来源**：`GET /auth/info` → `menus`（非写死）；下表含路由全部活页 + 管理员侧栏可见模块

---

## 0. 全局壳与路由

### 0.1 路由守卫

```
无 token → /login
有 token 无 user → GET /auth/info；失败则 logout → /login
已登录进 /login → /account
默认落地：/account
```

### 0.2 完整路由表

| Path | 标题 | 状态 |
|------|------|------|
| `/login` | 登录 | 活 |
| `/dashboard` | 首页（工作台） | 活 |
| `/account` | 个人中心 | 活（默认） |
| `/account/profile` | 账号资料 | 活 |
| `/account/salary-confirm` | 工资确认 | 活 |
| `/finance/ledger` | 公司总账 | 活 |
| `/finance/project-account` | 项目账款 | 活 |
| `/finance/wallet-board` | 全员钱包 | 活 |
| `/finance/pay-channel` | 收款渠道 | 活 |
| `/finance/month-verify` | 月度核验 | 活 |
| `/workflow/center` | 审批中心 | 活 |
| `/workflow/flow` | 审批配置 | 活 |
| `/project/list` | 项目管理 | 活 |
| `/project/task` | 任务管理 | 活 |
| `/hr/archive` | 人员档案 | 活 |
| `/hr/depr-category` | 折旧类别 | 活 |
| `/hr/asset` | 资产台账 | 活 |
| `/hr/salary` | 工资配置 | 活 |
| `/hr/salary-run` | 发薪记录 | 活 |
| `/file/list` | 文件管理 | 活 |
| `/system/user` | 账号管理 | 活 |
| `/system/role` | 角色权限 | 活 |
| `/system/dept` | 部门管理 | 活 |
| `/system/menu` | 菜单管理 | 活 |
| `/finance/pool` | — | redirect → ledger |
| `/finance/project-share` `/finance/distribute` | — | redirect → project-account |
| `/finance/wallet` | — | redirect → account |
| `/account/overview|wallet|approval|projects|calendar` | — | redirect → account |

**勿复刻孤儿页**：`pool.vue` / `wallet.vue` / `distribute.vue` / `project-share.vue`（已合并）。

### 0.3 AdminLayout（所有已登录页外壳）

| 功能 | 接口 | 入参 | 出参 |
|------|------|------|------|
| 侧栏菜单 | 来自 store.menus（`/auth/info`） | 过滤 `visible≠0` 且 `type≠3` | path 跳转 |
| 未读角标 | `GET /notification/unread-count` | — | `{ count }`（30s 轮询） |
| 通知列表 | `GET /notification/page` | `{ page:1, pageSize:15 }` | list 字段见接口文档 |
| 点通知 | `POST /notification/{id}/read` | — | 跳 `link` 或 `/workflow/center` |
| 全部已读 | `POST /notification/read-all` | — | void |
| 实时推送 | WS `/ws/notification?token=` | ping 25s | notification JSON |
| 用户芯片 | — | — | 跳 `/account/profile` |
| 退出 | `POST /auth/logout` | — | 清 token |

---

## 1. `/login` 登录

**UI**：用户名、密码、条件 6 位 TOTP、登录。

| 操作 | 时机 | 接口 | 入参 | 出参 |
|------|------|------|------|------|
| TOTP 探测 | 用户名失焦 | `GET /auth/totp/status/username` | `username` | `boolean` |
| 登录 | 提交 | `POST /auth/login` | `{ username, password, totpCode? }` | `{ token, user }` |
| 拉权限 | 登录后 | `GET /auth/info` | — | `{ user, roles, permissions, menus }` |

成功 → `/account`。

---

## 2. `/dashboard` 首页工作台

**对应截图「工作台」**：资金池/个人分成/池数量/进行中项目 + 资金池表。

| 操作 | 接口 | 出参使用 |
|------|------|----------|
| 进入 | `GET /finance/summary` | `poolTotal, walletTotal, poolCount, projectCount, pools[{name,balance,isDefault,remark}]` |

`isDefault===1` 显示「默认」。

---

## 3. `/account` 个人中心 ★

**UI 区块**：欢迎统计；钱包看板+图表+流水；任务日历；审批/任务/项目三列；弹窗：个人报销、申请提现、申请项目余额。

**权限门控**：`finance:wallet:list` / `workflow:list` / `project:task:list` / `project:list`；角色 `admin|shareholder` 可看全部任务。

### 3.1 进入加载

| 条件 | 接口 | 入参 | 出参使用 |
|------|------|------|----------|
| 审批 | `GET /workflow/approval/page` ×2 | `scope=todo|mine, page=1, pageSize=8` | `id,title,typeLabel,bizNo,statusLabel` |
| 任务 | `GET /task/related` 或 `GET /task/page` | pageSize=200 | 日历/列表字段 |
| 项目 | `GET /project/mine` | — | `id,name,code,status,statusLabel,ownerId` |
| 有钱包权 | `GET /sys/dept/my-companies` | — | 公司筛选 |
| 有钱包权 | `GET /project/list` | — | 级联筛选 |
| 有钱包权 | `GET /finance/wallet/mine` | — | `balance,frozen,available` |
| 有钱包权 | `GET /finance/wallet/mine/ledger` | 分页筛选 | 流水 |
| 有钱包权 | `GET /finance/wallet/mine/board` | `{ period:'daily'|'monthly' }` | `monthIn/Out, pendingConfirmCount, trend[], sourceBreakdown[], withdrawTaxRate?` |

### 3.2 用户操作

| 操作 | 接口 | 入参 | 出参 |
|------|------|------|------|
| 查流水 | `GET /finance/wallet/mine/ledger` | `page,pageSize,keyword?,minAmount?,maxAmount?,companyId?,projectId?,bizType?,startTime?,endTime?` | list/total |
| 切换看板周期 | `GET /finance/wallet/mine/board` | `{ period }` | 同上 |
| 打开报销/提现 | `GET /hr/archive/my-pay-methods` | — | 收款方式 |
| 上传发票 | `POST /workflow/voucher` | file | `{ id,… }` |
| 提交个人报销 | `POST /workflow/approval` | `type=REIMBURSE_PERSONAL` + amount/companyId/voucherFileIds/payload.payMethodId | tip |
| 税率预览 | `GET /finance/wallet/mine/withdraw-config` | `companyId?, amount?` | taxMode/tax/net/tiers… |
| 提交提现 | `POST /workflow/approval` | `type=WALLET_WITHDRAW` + payload 税字段+payMethodId | tip |
| 可申请项目 | `GET /finance/wallet/mine/balance-apply-projects` | — | `{ projectId, projectName, balance, companyName }[]` |
| 项目余额申请 | `POST /workflow/approval` | `type=PROJECT_BALANCE_APPLY, projectId, amount, remark` | tip |

图表组件 `WalletBoardCharts` 无接口，纯展示 board 数据。

---

## 4. `/account/profile` 账号资料

| 操作 | 接口 | 入参 | 出参 |
|------|------|------|------|
| 进入 | `GET /account/profile` | — | 表单字段 |
| 进入 | `GET /auth/totp/status` | — | boolean |
| 保存资料 | `PUT /account/profile` | nickname/email/phone/gender… | void |
| 改密 | `PUT /account/password` | old/new | void |
| 开 TOTP | `POST /auth/totp/generate` | — | qrString 等 |
| 确认 TOTP | `POST /auth/totp/verify` | `{ totpCode }` | boolean |
| 任务查询码 | `POST /account/task-query-code` | — | string |

---

## 5. `/account/salary-confirm` 工资确认

| 操作 | 接口 | 入参 | 出参 |
|------|------|------|------|
| 加载/换月 | `GET /hr/salary/my-confirm` | `yearMonth?` | lineId/status/totalAmount/payload.items |
| 确认 | `POST /hr/salary/my-confirm/{lineId}` | — | void |
| 撤销 | `POST …/revoke` | — | void |

状态：`PENDING_CONFIRM` / `CONFIRMED`。

---

## 6. `/finance/ledger` 公司总账

**UI**：汇总卡、筛选、流水表、登记弹窗、出账阈值、**提现税率**配置、详情抽屉。

| 操作 | 接口 | 入参 | 出参 |
|------|------|------|------|
| 进入 | `GET /sys/dept/my-companies` | — | 公司 |
| 进入 | `GET /finance/pool/list` | — | 池 |
| 进入 | `GET /finance/pay-channel/list` | `all=false` | 渠道 |
| 进入 | `GET /finance/summary` | — | 四类总额等 |
| 流水 | `GET /finance/ledger/page` | `accountType=POOL` + 筛选；可再拉 WALLET 拼对手 | list |
| 阈值读 | `GET /finance/ledger-threshold` | `companyId` | enabled/阈值 |
| 阈值写 | `PUT /finance/ledger-threshold` | `{ companyId, enabled, notifyThreshold, approveThreshold }` | void |
| 税率读 | `GET /finance/wallet-withdraw-tax` | `companyId` | taxMode/tiers |
| 税率写 | `PUT /finance/wallet-withdraw-tax` | `{ companyId, tiers[] }` | void |
| 上传凭证 | `POST /finance/ledger/voucher` | file | file.id |
| 登记 | `POST /finance/ledger/register` | INCOME/EXPENSE + POOL + 可选手续费 | `{ mode, message, approval? }` |
| 选项 | `GET /project/list` | — | 项目下拉 |

登记入参见接口文档 §11.4。

---

## 7. `/finance/project-account` 项目账款

**UI**：公司/规模筛选、项目卡片、详情（概览/分成/分钱/流水）、重大项目子项目、弹窗预支/报销/发工资。

| 操作 | 接口 | 入参 | 出参 |
|------|------|------|------|
| 列表 | `GET /finance/project-account/list` | `companyId?, scale?` | 卡片字段 |
| 公司 | `GET /sys/dept/my-companies` | — | |
| 详情 | `GET /finance/project-account/{id}` | — | balance/池信息/majorShell… |
| 子项目 | `GET /finance/project-account/{id}/children` | — | 子账款 |
| 分成 | `GET /finance/project-share/{id}` | — | budget/百分比/members |
| 流水 | `GET /finance/project-account/{id}/ledger` | page | |
| 用户 | `GET /sys/user/list` | — | |
| 池余额 | `GET /finance/pool/list` | — | 预支校验 |
| 收款方式 | `GET /hr/archive/my-pay-methods` | — | |
| 凭证 | `POST /workflow/voucher` | file | |
| 配置分成 | `POST /workflow/approval` | SHARE_CONFIG | tip |
| 分钱 | 同上 | PROJECT_SETTLE | |
| 退回 | 同上 | RESERVE_RETURN | |
| 预支 | 同上 | PROJECT_ADVANCE | |
| 报销 | 同上 | REIMBURSE_PROJECT | |
| 发工资 | 同上 | SALARY_APPLY | |

**SHARE_CONFIG payload**：`{ poolId, budget, expensePercent:0, reservePercent, settlePercent, members[{userId,layer,percent,remark}] }`  
**PROJECT_SETTLE payload**：`{ items:[{userId,amount,layer}] }`  
**预支**：`{ type, title, projectId, poolId?, amount, remark }`  
**报销/工资**：`amount + voucherFileIds? + payload.payMethodId?`（报销需凭证；金额≤结余）

级联选择组件 `ProjectCascadeSelect`：无接口，modes=`filter|pick|task`。

---

## 8. `/finance/wallet-board` 全员钱包

| 操作 | 接口 | 入参 | 出参 |
|------|------|------|------|
| 进入 | `GET /finance/summary` | — | poolTotal/walletTotal/walletCount |
| 进入 | `GET /finance/wallet/page` | `page=1,pageSize=200` | 人员卡 |
| 打开某人 | `GET /finance/wallet/{userId}/ledger` | page | 流水 |

---

## 9. `/finance/pay-channel` 收款渠道

**权限**：编辑 `finance:channel:edit`

| 操作 | 接口 | 入参 |
|------|------|------|
| 进入 | my-companies + `GET /finance/pay-channel/list?all=true` + poolList | |
| 保存 | `POST`/`PUT /finance/pay-channel` | `{ id?, poolId, channelType, name, accountNo, accountName, bankName, sort, status, remark }` |

---

## 10. `/finance/month-verify` 月度核验

| 操作 | 接口 | 入参 |
|------|------|------|
| 进入 | `GET /finance/month-verify/list` + 渠道/池/公司 | |
| 上传 | `POST /workflow/voucher` | file |
| 提交 | `POST /workflow/approval` | type=`MONTHLY_VERIFY`，payload 含 verifyMonth/channelId/systemBalance/statementBalance/voucherFileIds |

---

## 11. `/workflow/center` 审批中心

**权限**：`workflow:list`；处理 `workflow:handle`；回执亦可 `finance:ledger:add`。

| 操作 | 接口 | 入参 | 出参 |
|------|------|------|------|
| 列表 | `GET /workflow/approval/page` | scope/type/status/金额时间关键词 | list+flags |
| 详情 | `GET /workflow/approval/{id}` | — | payloadData/凭证/can* |
| 通过 | `POST …/approve` | `{ comment? }` | |
| 拒绝 | `POST …/reject` | `{ comment? }` | |
| 撤回 | `POST …/withdraw` | — | |
| 确认到账 | `POST …/confirm` | — | |
| 回执 | voucher → `POST …/receipt` | `{ fileIds }` | |
| 回退 | `POST /workflow/approval/rollback` | `{ approvalId, mode, amount?, reason? }` | |

类型筛选含全部 18 种审批 type（含 `WALLET_WITHDRAW`、`PROJECT_BALANCE_APPLY`）。

详情按 type 渲染 `payloadData`（分成成员、分钱明细、工资项、总账、资产、提现税、建项字段等）。

---

## 12. `/workflow/flow` 审批配置

**权限**：`workflow:flow:edit`

| 操作 | 接口 | 入参 |
|------|------|------|
| 进入 | my-companies + roleList + flowList(companyId) | |
| 选人 | `GET /sys/user/list?companyId=` | |
| 保存 | `PUT /workflow/flow` | type/name/passMode/roleCodeList/userIdList/timeoutHours/status/sort/remark |
| 删除 | `DELETE /workflow/flow/{id}` | |
| 复制 | `POST /workflow/flow/copy` | from/to companyId |

内置 type 与审批中心一致（含提现、项目余额申请）。

---

## 13. `/project/list` 项目管理

**权限**：`project:add|edit|remove`，`project:task:add`

| 操作 | 接口 | 入参 | 出参 |
|------|------|------|------|
| 列表 | `GET /project/page` | page/name/status/companyId | 卡片 |
| 字典 | my-companies + userList | | |
| 详情 | `GET /project/{id}` | | 含 members |
| 子项目 | `GET /project/{id}/children` | | |
| 预览编号 | `GET /project/next-code` | companyId | string |
| 保存 | `POST`/`PUT /project` | 见接口文档（可 parentId） | 项目或审批单 |
| 删除前 | `GET /workflow/flow/describe` | PROJECT_DELETE | tip |
| 删除 | `DELETE /project/{id}` | | 审批或直接 |
| 操作记录 | `GET /project/{id}/flows` | | |
| 任务统计 | `GET /task/summary` | projectId | |
| 任务表 | `GET /task/page` | projectId | |
| 看板 | 见 TaskKanban | | |

嵌入：`TaskKanban`、`TaskDetailDrawer`。

---

## 14. `/project/task` 任务管理

**权限**：`project:task:add`

| 操作 | 接口 | 入参 |
|------|------|------|
| 字典 | projectList + userList | |
| 统计 | `GET /task/summary` | projectId/priority/participantId/title |
| 列表 | `GET /task/page` | 筛选；默认 statuses=0,1，participantId=自己 |
| 打开/新建 | TaskDetailDrawer | |

支持 query：`?projectId=&taskId=`。

---

## 15. 共享组件

### TaskDetailDrawer

| 操作 | 接口 | 入参 |
|------|------|------|
| 打开 | task detail + comments + flows + projectList + project detail | |
| 保存 | `POST`/`PUT /task` | title/projectId/participantIds/status/priority/dates/progress/content/**imageFileIds** |
| 关闭 | `PUT /task/{id}/status` | `{ status:3, remark }` |
| 移交 | `PUT /task/{id}/transfer` | assigneeId/remark/imageFileIds |
| 上传附件 | `POST /task/image` | 图/视频，超时可至 10min |
| 删附件 | `DELETE /task/image/{fileId}` | |
| 评论 | `POST /task/{id}/comments` | `{ content }` |
| 删评 | `DELETE /task/comment/{id}` | |

权限：`project:task:edit` 且 `canEdit`/`canTransfer`。

### TaskKanban

| 操作 | 接口 |
|------|------|
| 加载 | `GET /task/board?projectId=` |
| 拖拽 | `PUT /task/{id}/status` `{ status:0\|1\|2 }` |

### ProjectCascadeSelect / WalletBoardCharts

无网络请求，纯 UI。

---

## 16. HR 五页

### `/hr/archive`

| 操作 | 接口 | 入参要点 |
|------|------|----------|
| 分页 | `GET /hr/archive/page` | realName/employeeNo |
| 详情 | `GET /hr/archive/{id}` | |
| 保存 | `POST`/`PUT /hr/archive` | 档案+payMethods |
| 删除 | `DELETE /hr/archive/{id}` | |
| 用户下拉 | `GET /sys/user/list` | |

权限：`hr:archive:add|edit|remove`

### `/hr/depr-category`

`GET /fa/category/page` · `POST`/`PUT /fa/category` · `DELETE` · my-companies  
权限：`fa:category:*`

### `/hr/asset`

`GET /fa/asset/page` · detail · `POST`/`PUT` · category list  
领用/归还：`flow/describe` + `POST /workflow/approval`（ASSET_BORROW/RETURN，payload.assetId）  
权限：`fa:asset:add|edit`

### `/hr/salary`

items CRUD + schedule PUT；projectList + userList + ProjectCascadeSelect(pick)  
权限：`hr:salary:edit` / `hr:salary:schedule`

### `/hr/salary-run`

runs / lines / preview / pay  
权限：`hr:salary:run:manual`

---

## 17. `/file/list` 文件管理

| 操作 | 接口 | 入参 |
|------|------|------|
| 列表 | `GET /file/page` | page + originalName |
| 上传 | `POST /file/upload` | file + bizType=common（Web raw fetch） |
| 删除 | `DELETE /file/{id}` | |
| 预览/下载 | `/file/preview/{id}` `/file/download/{id}` | |

---

## 18. 系统四页

### `/system/user`

page/detail/save/delete/reset-pwd；meta：roleList、deptTree、deptCompanies  
保存含 `userDepts` + `roleBindings`  
权限：`system:user:add|edit|resetPwd|remove`

### `/system/role`

list/detail/save/delete；menuTree+deptTree  
权限：`system:role:*`

### `/system/dept`

tree/save/delete  
权限：`system:dept:*`

### `/system/menu`

tree/save/delete；type 1/2/3  
权限：`system:menu:*`

---

## 19. 与截图侧栏对照（全量清单）

| 侧栏 | 路由 | 是否覆盖 |
|------|------|----------|
| 个人中心 | `/account` | ✓ |
| 首页工作台 | `/dashboard` | ✓ |
| 公司总账 | `/finance/ledger` | ✓ |
| 项目账款 | `/finance/project-account` | ✓ |
| 全员钱包 | `/finance/wallet-board` | ✓ |
| 收款渠道 | `/finance/pay-channel` | ✓ |
| 月度核验 | `/finance/month-verify` | ✓ |
| 工资配置 | `/hr/salary` | ✓ |
| 发薪记录 | `/hr/salary-run` | ✓ |
| 审批中心 | `/workflow/center` | ✓ |
| 审批配置 | `/workflow/flow` | ✓ |
| 项目管理 | `/project/list` | ✓ |
| 任务管理 | `/project/task` | ✓ |
| 人员档案 | `/hr/archive` | ✓ |
| 折旧类别 | `/hr/depr-category` | ✓ |
| 资产台账 | `/hr/asset` | ✓ |
| 文件管理 | `/file/list` | ✓ |
| 账号/角色/部门 | `/system/*` | ✓ |
| （资料/工资确认/菜单） | profile / salary-confirm / menu | ✓ 路由存在，菜单按权限 |

---

## 20. 相对旧版新增（必须实现）

1. 个人中心：提现 `WALLET_WITHDRAW`、项目余额 `PROJECT_BALANCE_APPLY`、钱包 board 图表  
2. 总账页：提现税率阶梯配置  
3. 项目：重大壳 + `/children` 级联、parentId 创建小项目  
4. 任务：附件上传（图/视频）、移交、关闭备注  
5. 审批类型扩展：上述两类新 type  

---

*页面行为以 `kk-admin/src/views/**` 与组件为准；接口字段细节见全量接口文档。*
