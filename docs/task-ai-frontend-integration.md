# BGMN AI 助手前端联调说明

## 1. 联调信息

- 协议版本：`1`
- 后端基础地址（dev）：`http://localhost:8999`
- 鉴权：沿用后台 `Authorization` 请求头
- 统一响应：`{ "code": 200, "message": "success", "data": ... }`
- 时区：`Asia/Shanghai`（GMT+8）
- `chat` 建议超时：120 秒；`confirm` 建议超时：60 秒

后端对外提供四个接口。AI 仅能调用后端注册的查询/草稿工具，不能执行 SQL，也不能直接创建任务；只有用户调用 `confirm` 后才会在一个事务中创建任务。

## 2. 接口

### 2.1 发起或继续对话

`POST /api/task-ai/chat`

首次请求不传 `sessionId`：

```json
{
  "message": "今天上午在 ERP 项目处理线上问题，已完成"
}
```

后续请求必须回传响应中的 `sessionId`：

```json
{
  "sessionId": "0d55d42a-f1d4-44cd-a08d-e7ca44f1955e",
  "message": "ERP 客户端",
  "clarifyAnswer": {
    "field": "project",
    "optionId": "12",
    "label": "ERP 客户端"
  }
}
```

`message` 与 `clarifyAnswer` 至少有一个。点击澄清选项时建议两者都传；后端会把 `optionId` 作为结构化信息优先交给 Agent。

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "sessionId": "0d55d42a-f1d4-44cd-a08d-e7ca44f1955e",
    "protocolVersion": 1,
    "replyText": "请确认以下记工内容",
    "blocks": [
      {
        "type": "confirm_card",
        "items": [
          {
            "tempId": "draft-1",
            "projectId": 12,
            "projectName": "ERP",
            "title": "线上问题处理",
            "content": "排查并处理线上问题",
            "startAt": "2026-10-14 09:30:00",
            "endAt": "2026-10-14 12:00:00",
            "status": 2,
            "statusLabel": "已完成"
          }
        ]
      }
    ],
    "pendingConfirm": true,
    "toolTrace": [{ "name": "draft_log_tasks", "ok": true }]
  }
}
```

渲染规则：严格按 `blocks` 顺序渲染；`replyText` 只用于日志或没有 block 时的降级文案。`pendingConfirm=true` 时展示确认/取消操作并禁止重复发送确认请求。

### 2.2 确认记工

`POST /api/task-ai/confirm`

前端卡片允许编辑时，应把编辑后的完整 `items` 回传；不传 `items` 则使用服务端会话草稿。

```json
{
  "sessionId": "0d55d42a-f1d4-44cd-a08d-e7ca44f1955e",
  "items": [
    {
      "tempId": "draft-1",
      "projectId": 12,
      "projectName": "ERP",
      "title": "线上问题处理",
      "content": "排查并处理线上问题",
      "startAt": "2026-10-14 09:30:00",
      "endAt": "2026-10-14 12:00:00",
      "status": 2,
      "statusLabel": "已完成"
    }
  ]
}
```

成功响应：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "sessionId": "0d55d42a-f1d4-44cd-a08d-e7ca44f1955e",
    "protocolVersion": 1,
    "blocks": [
      { "type": "done", "taskIds": [101], "message": "已成功登记 1 条任务" }
    ],
    "done": { "taskIds": [101], "message": "已成功登记 1 条任务" }
  }
}
```

确认操作为整批事务，任意一项校验失败则全部回滚。同一已提交会话再次确认不会重复创建任务，会返回原任务 ID 和“请勿重复提交”提示。任务可在 `GET /api/task/related` 中验证。

### 2.3 取消会话

`POST /api/task-ai/cancel`

```json
{ "sessionId": "0d55d42a-f1d4-44cd-a08d-e7ca44f1955e" }
```

成功时 `data=null`。取消后会话不能继续聊天或确认；前端应清除本地 `sessionId`。

### 2.4 查看工具列表

`GET /api/task-ai/tools`

用于 dev 联调，返回工具名和描述。当前包括项目/人员消歧、时间归一化、任务查询与聚合、图表、工时、草稿管理等工具。

## 3. blocks 协议

前端需要支持以下 `type`：

| type | 关键字段 | 说明 |
|---|---|---|
| `markdown` | `content` | 普通说明或错误提示 |
| `clarify` | `field`, `prompt`, `options[{id,label}]` | 项目、人员、时间等消歧 |
| `confirm_card` | `items[]` | 待用户编辑/确认的记工草稿 |
| `task_list` | `title`, `items[]` | 任务列表 |
| `timeline` | `title`, `events[]` | 任务时序 |
| `line_chart` | `title`, `series[].points[]` | 完成趋势 |
| `bar_chart` | `categories`, `series[].values` | 项目分布 |
| `stat_row` | `items[{label,value,hint?}]` | 汇总数字 |
| `done` | `taskIds`, `message` | 入库完成 |

图表、列表和统计数据均由后端工具查询真实数据后生成，前端无需二次计算。未知 `type` 请降级展示 JSON/提示，不要导致整个会话白屏。

时间查询口径为：任务的计划开始日、截止日、完成时间或创建时间任一落在所选区间即命中。单次查询最多返回 200 条，趋势图最多展示 31 天。

## 4. 权限与状态

- 用户只能确认给自己记工，`assigneeId`、`holderId`、参与人均由后端强制设为当前登录用户。
- 项目创建资格继续使用现有项目负责人/项目成员校验。
- 查询自己任务无需额外权限。
- 查询他人需要全局管理员或 `task:ai:query-others`；项目负责人可查询其负责项目与目标人员共同参与的范围。
- `status`：`0` 待办、`1` 进行中、`2` 已完成、`3` 已关闭、`4` 待确认完成。
- 确认传 `4` 时后端按完成流程处理；有任务报酬时仍会遵守现有完成审批规则进入待确认状态。

## 5. 错误处理

参数、会话或确认校验失败沿用全局业务错误响应，前端展示响应 `message`。Agent 的工具失败、无权限和需要澄清通常以 HTTP 200 的 `markdown`/`clarify` block 返回。

常见业务消息：

- `会话不存在或无权访问`
- `当前没有待确认的记工`
- `项目不能为空` / `任务标题不能为空`
- `无权查询该人员的任务，查询范围仅限您负责的共同项目`
- `仅项目负责人或项目参与人可以创建任务`

## 6. 推荐联调顺序

1. 调用 `/tools` 验证登录态和后端部署。
2. 发送“我今天做了什么”，验证 `stat_row`、`timeline` 与数据库数据一致。
3. 发送模糊项目名，验证 `clarify(field=project)` 和选项回传。
4. 发送一条完整记工，检查 `confirm_card` 与 `pendingConfirm=true`。
5. 修改卡片标题后调用 `/confirm`，检查 `done.taskIds` 及 `/api/task/related`。
6. 对相同会话重复确认，验证没有重复任务。
7. 分别用普通用户、项目负责人、管理员查询他人任务，验证权限边界。
8. 调用 `/cancel` 后再次确认，验证会话不可提交。

## 7. 部署配置

dev 已定义 `ai.base-url`、`ai.model`、`ai.api-key`、最大工具调用次数及 tool trace。环境变量 `AI_BASE_URL`、`AI_MODEL`、`AI_API_KEY` 可覆盖配置。生产环境务必通过密钥管理或环境变量注入 `AI_API_KEY`，并关闭调试 trace。

数据库需执行 [ai_task_log_session.sql](../sql/ai_task_log_session.sql)。该表保存会话归属、状态、草稿、历史消息与已提交任务 ID；可按 `update_time` 定期清理过期的 cancelled/committed 会话。
