# BGMN AI 助手 — 后端接口需求文档

> 版本：`protocolVersion = 1`  
> 对齐前端：`kk-admin/src/api/taskAi.ts`、`kk-admin/src/components/taskai/*`  
> 统一响应外壳：与现有后台一致 `{ code: 200, message, data }`，下文只描述 `data`。

---

## 1. 背景与目标

前端已在侧栏提供 AI 助手聊天面板。后端需提供：

1. **多轮对话 Agent**：理解自然语言 → 调用内部 Tools → 返回结构化 `blocks` 富消息。
2. **记工确认入库**：草稿经用户确认后创建 `pm_task`（禁止模型直接写库）。
3. **数据问答**：如「我今天做了什么」「徐康今天做了哪些任务」→ 查真实数据并组装图表/时序块。

**不做**：AI 直接改库、任意 SQL、无权限穿透。

---

## 2. 接口清单

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| `POST` | `/api/task-ai/chat` | 多轮对话（内部跑 Tool Agent） | 登录 |
| `POST` | `/api/task-ai/confirm` | 确认记工草稿入库 | 登录 |
| `POST` | `/api/task-ai/cancel` | 取消/结束会话 | 登录 |
| `GET` | `/api/task-ai/tools` | 调试：列出已注册 Tools（可选） | 登录 |

前端超时：`chat` 120s，`confirm` 60s。建议网关/服务端超时 ≥ 120s。

建议权限码（查他人）：`task:ai:query-others`（可挂管理员角色）。

---

## 3. 会话模型

### 3.1 存储建议

表名示例：`ai_task_log_session`

| 字段 | 类型 | 说明 |
|------|------|------|
| `session_id` | varchar(64) PK | 前端持有 |
| `user_id` | bigint | 会话所属用户 |
| `status` | varchar(32) | `chatting` / `awaiting_confirm` / `committed` / `cancelled` |
| `draft_json` | text/json | 待确认记工草稿 |
| `messages_json` | mediumtext/json | 对话历史（含 blocks 快照） |
| `create_time` / `update_time` | datetime | |

规则：

- 仅会话所属用户可 `chat` / `confirm` / `cancel`。
- `confirm` 仅当 `status = awaiting_confirm`。
- `committed` / `cancelled` 后不可再确认；可新开会话。

---

## 4. HTTP 接口详细说明

### 4.1 `POST /api/task-ai/chat`

#### 请求

```json
{
  "sessionId": "可选，空则新建",
  "message": "用户自然语言或澄清选项文案",
  "clarifyAnswer": {
    "field": "project | time | user | title | status",
    "optionId": "选项 id",
    "label": "选项展示文案，可选"
  }
}
```

- `message` 与 `clarifyAnswer` 至少有一个有效；点选澄清时两者都可带。
- 有 `clarifyAnswer` 时，后端应优先按结构化选择消歧，而不是重新模糊解析文案。

#### 响应 `data`

```json
{
  "sessionId": "uuid",
  "protocolVersion": 1,
  "replyText": "可选旁白，前端可不展示（优先 blocks）",
  "blocks": [ /* UiBlock[]，见第 5 节 */ ],
  "pendingConfirm": true,
  "toolTrace": [
    { "name": "list_tasks", "ok": true }
  ]
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `sessionId` | 是 | |
| `protocolVersion` | 建议 | 固定 `1` |
| `blocks` | 是 | 可为空数组，但建议至少有一条 `markdown` |
| `pendingConfirm` | 否 | 本轮含 `confirm_card` 且等待入库时为 `true` |
| `toolTrace` | 否 | 调试用；生产可关 |

#### 行为要求

1. 加载/创建 session，追加 user 消息。
2. 运行 Agent（最多约 6 次 tool 调用）：LLM + Tools。
3. 合并 Tools 的 `suggestedBlocks`，必要时附 `markdown` 旁白。
4. 参数齐全的记工 → 写 draft、session → `awaiting_confirm`，返回 `confirm_card`。
5. 缺参/歧义 → 返回 `clarify`，session 保持 `chatting`。
6. 问答 → 返回 `stat_row` / `timeline` / `bar_chart` / `line_chart` / `task_list` 等，**数字必须来自 Tool 查询**，禁止模型编造 series。
7. Tool 越权/失败：返回友好 `markdown` 或 `clarify`，HTTP 仍 200（业务错误放在对话里）；仅系统异常才非 200。

---

### 4.2 `POST /api/task-ai/confirm`

#### 请求

```json
{
  "sessionId": "必填",
  "items": [
    {
      "tempId": "draft-1",
      "projectId": 12,
      "projectName": "ERP",
      "title": "线上问题处理",
      "content": "可选备注",
      "startAt": "2026-10-14 09:30:00",
      "endAt": "2026-10-14 12:00:00",
      "status": 2,
      "statusLabel": "已完成"
    }
  ]
}
```

- `items` 可选：不传则用 session 内 draft；传了则以请求体为准（用户可能在卡片上改过标题/状态/时间）。
- 仅允许为**当前登录用户自己**记工。

#### 入库规则

对每条 item：

1. 组装 `PmTask`：
   - `projectId`、`title`、`content`
   - `assigneeId` / `holderId` / `participantIds` = 当前用户
   - `startDate` / `dueDate`：取自 `startAt` / `endAt` 的日期部分（时区 `Asia/Shanghai`）
   - 精确时段可写入 `content` 备注（若表无时刻字段）
2. 调用现有 `createTask`（创建强制 status=0）。
3. 若确认 `status != 0`：再落地到目标状态（建议提供 AI 专用状态落地路径，**跳过上下班校验**，仍写流转；允许 0/1/2/3，`4` 按现有完成审批规则处理或映射为 2）。
4. **整批事务**：任一条失败则全部回滚。
5. session → `committed`，清空或归档 draft。

#### 响应 `data`

```json
{
  "sessionId": "uuid",
  "protocolVersion": 1,
  "blocks": [
    { "type": "done", "taskIds": [101, 102], "message": "已成功登记 2 条任务" }
  ],
  "done": {
    "taskIds": [101, 102],
    "message": "已成功登记 2 条任务"
  }
}
```

前端优先渲染 `blocks`；若无 blocks 则用 `done`。

#### 错误

| 场景 | 建议 |
|------|------|
| session 不存在 / 非本人 | 业务失败 message |
| 非 `awaiting_confirm` | 「当前没有待确认的记工」 |
| 非项目成员 | 「无权在该项目创建任务」 |
| 缺 title / projectId | 参数错误 |

---

### 4.3 `POST /api/task-ai/cancel`

#### 请求

```json
{ "sessionId": "必填" }
```

#### 响应

`data` 可为 `null`。将 session 标为 `cancelled`（可清空 draft）。

---

### 4.4 `GET /api/task-ai/tools`（可选）

#### 响应 `data`

```json
[
  { "name": "list_tasks", "description": "按人/项目/时间查任务" }
]
```

---

## 5. 富消息协议 `blocks[]`（前后端硬约定）

一条 assistant 回复可含多个 block，按数组顺序渲染。  
未知 `type` 前端降级展示，不白屏；**只增不改**既有 type 字段语义。

### 5.1 `markdown`

```json
{ "type": "markdown", "content": "今天你共完成 3 项任务。" }
```

### 5.2 `clarify`

```json
{
  "type": "clarify",
  "field": "project",
  "prompt": "查询到多个含「客服」的项目，请选择：",
  "options": [
    { "id": "12", "label": "客服系统" },
    { "id": "18", "label": "客服机器人" }
  ]
}
```

`field`：`project` | `time` | `user` | `title` | `status`

### 5.3 `confirm_card`

```json
{
  "type": "confirm_card",
  "items": [
    {
      "tempId": "d1",
      "projectId": 12,
      "projectName": "ERP",
      "title": "线上问题处理",
      "content": "可选",
      "startAt": "2026-10-14 09:30:00",
      "endAt": "2026-10-14 12:00:00",
      "timeLabel": "09:30:00 ~ 12:00:00",
      "durationLabel": "2.5小时",
      "status": 2,
      "statusLabel": "已完成"
    }
  ]
}
```

`status`：`0` 待办 / `1` 进行中 / `2` 已完成 / `3` 已关闭 / `4` 待确认完成  
`startAt`/`endAt`：`yyyy-MM-dd HH:mm:ss`（GMT+8）。  
`timeLabel`/`durationLabel`：展示用，前端优先显示。  

**文案与出卡规则**（禁止过程话、必须用卡片而非文本表）详见：[BGMN-AI助手确认卡片与文案约定.md](./BGMN-AI助手确认卡片与文案约定.md)。

### 5.4 `task_list`

```json
{
  "type": "task_list",
  "title": "今日任务",
  "items": [
    {
      "id": 101,
      "title": "修登录 bug",
      "projectName": "ERP",
      "statusLabel": "已完成",
      "timeLabel": "09:30-11:00",
      "userName": "徐康"
    }
  ]
}
```

### 5.5 `timeline`

```json
{
  "type": "timeline",
  "title": "今日时序",
  "events": [
    {
      "id": "101",
      "time": "09:30",
      "title": "线上问题处理",
      "projectName": "ERP",
      "statusLabel": "已完成",
      "userName": "徐康"
    }
  ]
}
```

### 5.6 `line_chart`

```json
{
  "type": "line_chart",
  "title": "近 7 日完成趋势",
  "xLabel": "日期",
  "yLabel": "数量",
  "series": [
    {
      "name": "完成数",
      "points": [
        { "x": "10-08", "y": 2 },
        { "x": "10-09", "y": 5 }
      ]
    }
  ]
}
```

### 5.7 `bar_chart`

```json
{
  "type": "bar_chart",
  "title": "按项目分布",
  "categories": ["ERP", "任务系统"],
  "series": [
    { "name": "任务数", "values": [3, 2] }
  ]
}
```

### 5.8 `stat_row`

```json
{
  "type": "stat_row",
  "items": [
    { "label": "总数", "value": 5 },
    { "label": "已完成", "value": 3, "hint": "含今天完成" },
    { "label": "进行中", "value": 2 }
  ]
}
```

### 5.9 `done`

```json
{
  "type": "done",
  "taskIds": [101, 102],
  "message": "已成功登记 2 条任务"
}
```

### 5.10 推荐组合

| 场景 | blocks 建议 |
|------|-------------|
| 「我今天做了什么」 | `markdown` + `stat_row` + `timeline` + 可选 `bar_chart` |
| 项目名歧义 | `clarify(field=project)` |
| 人名歧义 | `clarify(field=user)` |
| 记工参数齐全 | `markdown` + `confirm_card`（可多项） |
| 确认入库成功 | `done` |
| 无权限查他人 | `markdown` 说明原因 |

---

## 6. Agent 与 Tool 注册表（后端内部）

对外只有 4 个 HTTP；能力通过 **Tools** 暴露给 LLM。

### 6.1 Tool 接口约定

```text
AiTool {
  name
  description
  parametersJsonSchema
  execute(ctx, args) -> ToolResult
}

ToolResult {
  ok: boolean
  code?: string
  message?: string
  data?: object
  suggestedBlocks?: UiBlock[]   // 优先用此填图表，禁止模型手填 series
}
```

`ctx` 至少含：`loginUserId`、`sessionId`、公司/可见范围。

### 6.2 必做 Tools

#### A. 上下文 / 消歧

| name | 说明 | 权限 |
|------|------|------|
| `list_my_projects` | 可见/参与项目 `{id,name}` | 登录 |
| `search_projects` | 关键字搜项目 | 登录 |
| `resolve_project` | 模糊匹配；多命中返回 candidates | 登录 |
| `search_users` | 按姓名搜用户（公司范围） | 登录（列表≠可查任务） |
| `resolve_user` | 人名消歧 | 登录 |
| `list_project_members` | 项目成员+负责人 | 成员/负责人/admin |
| `get_server_now` | 当前时间、时区 | 登录 |
| `normalize_time_range` | 时间短语 → `{start,end,label}` | 登录（确定性规则） |

**时间规则示例（可配置）：**

| 表达 | 归一化 |
|------|--------|
| 过去一小时 | `[now-1h, now]` |
| 今天上午 | 当日 `09:30–12:00` |
| 今天下午 | 当日 `13:30–18:00` |
| 今天 | 当日工作时段（如 09:00–18:00） |
| 昨天… | 昨天对应时段 |

无法解析 → 返回失败，由 Agent 发 `clarify(field=time)`。

#### B. 任务查询（读）

| name | 说明 |
|------|------|
| `list_tasks` | `userIds[]`、`projectIds?`、`timeRange`、`status?` |
| `get_task` | 单任务详情 |
| `list_task_flows` | 流转时序 |
| `summarize_tasks` | 按人/项目/状态聚合 |
| `tasks_timeline` | 直接产出 `timeline` suggestedBlocks |
| `tasks_by_project_chart` | 产出 `bar_chart` |
| `tasks_trend_chart` | 产出 `line_chart` |
| `work_duration_summary` | 有效工时（复用现有工时计算） |

**时间过滤口径（需在旁白中说明，前后端统一一种）：**  
建议：任务的 `startDate`/`dueDate`/`completedAt`/`createTime` 任一落入区间即命中；问答旁白注明「按计划日/完成日/创建日落在所选时段」。

#### C. 记工草稿（不落库）

| name | 说明 |
|------|------|
| `draft_log_tasks` | 合并 draft，返回 `confirm_card` suggestedBlocks；**仅自己** |
| `update_draft` | 改 draft 字段 |
| `clear_draft` | 清空草稿 |

**禁止**把真正入库做成 Tool；入库只走 `/confirm`。

### 6.3 读权限矩阵（`list_tasks` 及派生工具）

1. `userIds` 仅自己 → 允许。  
2. 含他人时，满足其一：
   - 全局管理员，或拥有权限 `task:ai:query-others`；
   - **当前用户是项目负责人**：自动将 `projectIds` 收窄为「我负责的项目 ∩ 对方作为负责人/成员的项目」；若交集为空 → 拒绝并说明。  
3. 禁止无项目边界的全公司扒取。

`search_users` / `resolve_user` 可返回候选人，不代表已授权查其任务。

### 6.4 Agent 循环

1. System prompt：必须用工具取数；记工必须 draft + 等用户确认；查他人先 `resolve_user` 再 list/summarize。  
2. 单轮用户消息最多 N 次 tool_calls（建议 6）。  
3. Tool 失败 JSON 回灌模型，引导 clarify 或换参。  
4. Final：合并 `suggestedBlocks`；若模型另输出 blocks，校验 type——**丢弃无 Tool 支撑的图表块**。  
5. AI 客户端：可插拔，**必须支持 tool/function calling**。

---

## 7. 验收用例（给联调）

1. 「过去一小时在 XXX 项目做了线上排查」→ `confirm_card` → confirm → `done`，任务出现在 `/api/task/related`。  
2. 「客服项目…」匹配多个项目 → `clarify(field=project)`。  
3. 「今天上午做了 ERP 线上问题处理和任务系统新功能开发」→ 卡片两项 → 两条入库。  
4. 语境「做完了」→ 卡片默认已完成 → create + 状态落地成功。  
5. 「我今天做了什么」→ `stat_row` + `timeline`，数字与库一致。  
6. 管理员：「看一下徐康和刘佳琦今天的任务」→ 成功。  
7. 项目负责人查本项目成员 → 成功；查非本项目成员 → 友好拒绝。  
8. 普通人查无关同事 → 拒绝。  
9. 人名多命中 → `clarify(field=user)`。

---

## 8. 与前端字段对照

| 前端文件 | 用途 |
|----------|------|
| `kk-admin/src/api/taskAi.ts` | 请求/响应/UiBlock TypeScript 定义（契约源） |
| `kk-admin/src/components/taskai/BlockRenderer.vue` | 按 `type` 分发渲染 |
| `kk-admin/src/components/taskai/TaskAiChatDrawer.vue` | 会话、澄清回传、确认入库 |

联调时以后端返回能否被 `BlockRenderer` 正确识别为准；新增 block type 需同步改前端。

---

## 9. 非功能建议

- 日志：记录 `sessionId`、tool 名、耗时、是否 confirm；勿落完整 prompt 密钥。  
- 限流：按用户限制 chat QPS，防止刷模型。  
- 幂等：同一 `sessionId` 重复 `confirm` 第二次应失败或返回已提交结果，避免重复建任务。  
- 时区：统一 `Asia/Shanghai` / `GMT+8`。
