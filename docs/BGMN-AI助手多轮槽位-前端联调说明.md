# BGMN AI 助手多轮槽位与时间默认——前端联调说明

> 协议版本：`1`  
> 时区：`Asia/Shanghai`（GMT+8）  
> 日期时间格式：`yyyy-MM-dd HH:mm:ss`

## 1. 本次变更

后端现在按会话累加记工槽位。用户只补充或纠正项目、时间、事项、状态中的一个字段时，已有字段会保留；项目、事项、时间齐全后立即返回确认卡。时间短语由后端确定性解析，前端不要自行猜测或覆盖默认时间。

前端需要做到：

1. 同一段未结束对话始终携带后端返回的 `sessionId`。
2. 严格按 `blocks` 顺序渲染；有 `blocks` 时不要重复渲染 `replyText`。
3. 收到 `clarify` 后，将选择结果通过 `clarifyAnswer` 再次请求 `/chat`。
4. 收到 `confirm_card` 后允许编辑，并将完整 `items` 提交 `/confirm`。
5. `pendingConfirm=true` 时展示确认和取消操作，并防止重复点击确认。
6. 收到 `done` 或取消成功后清除本地 `sessionId`。

## 2. 接口总览

| 方法 | 路径 | 用途 |
|---|---|---|
| `POST` | `/api/task-ai/chat` | 新建/继续会话、补槽、纠正槽位、回传澄清选择 |
| `POST` | `/api/task-ai/confirm` | 提交卡片中的完整记工数据 |
| `POST` | `/api/task-ai/cancel` | 取消当前会话 |
| `GET` | `/api/task-ai/tools` | 开发环境检查登录态和助手能力 |

接口均沿用项目现有登录鉴权及统一响应结构：`{ code, message, data }`。

## 3. 聊天与多轮补槽

### 3.1 请求

首次请求不传 `sessionId`：

```json
{
  "message": "登记 ERP 的 AI 助手功能"
}
```

后续请求必须原样携带响应中的 `sessionId`：

```json
{
  "sessionId": "0d55d42a-f1d4-44cd-a08d-e7ca44f1955e",
  "message": "早上到现在"
}
```

`message` 与 `clarifyAnswer` 至少提供一个。不要把短回复在前端拼成完整句，后端会与当前会话槽位合并。

### 3.2 响应

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "sessionId": "0d55d42a-f1d4-44cd-a08d-e7ca44f1955e",
    "protocolVersion": 1,
    "replyText": "",
    "blocks": [
      { "type": "markdown", "content": "请确认以下登记信息：" },
      {
        "type": "confirm_card",
        "items": [
          {
            "tempId": "draft-1",
            "projectId": 12,
            "projectName": "ERP",
            "title": "AI 助手功能",
            "content": "",
            "startAt": "2026-10-14 09:30:00",
            "endAt": "2026-10-14 11:20:00",
            "timeLabel": "09:30:00 ~ 11:20:00",
            "durationLabel": "1小时50分钟",
            "status": 0,
            "statusLabel": "待办"
          }
        ]
      }
    ],
    "pendingConfirm": true,
    "toolTrace": []
  }
}
```

`toolTrace` 仅在开启调试配置时可能返回内容，前端不得展示给最终用户，也不得依赖该字段完成业务流程。

### 3.3 多轮合并规则

| 当前用户回复 | 后端行为 | 前端行为 |
|---|---|---|
| `ERP`、`任务系统` | 更新项目，保留时间/事项/状态 | 继续发送原 `sessionId` |
| `早上`、`上班`、`09:00 到 11:00` | 更新时间，保留其他槽 | 不在前端解析时间 |
| `修登录 bug` | 更新事项标题 | 不要求用户重输整句 |
| `已完成`、`还在做` | 更新状态 | 渲染后端返回的新卡片/追问 |
| 澄清选项 | 按 `optionId` 写入对应槽 | 使用 `clarifyAnswer` 回传 |

项目、事项、时间齐全就会返回 `confirm_card`。状态可由后端默认，不会因为缺状态继续追问。完全缺少项目或事项时，后端每轮最多只追问一个字段。

## 4. 时间默认值

前端只展示后端返回的 `startAt`、`endAt`、`timeLabel`，不自行重算以下规则：

| 表达 | 后端默认区间 |
|---|---|
| 早上 / 早晨 / 一早 / 上午 | 当日 `09:30:00 ~ 12:00:00` |
| 早上上班 / 上午上班 / 上班 | 当日 `09:30:00 ~ 当前时间`；当前时间早于 09:30 时结束取 12:00 |
| 中午 | 当日 `12:00:00 ~ 13:30:00` |
| 下午 / 下午上班 | 当日 `13:30:00 ~ 18:00:00` |
| 晚上 | 当日 `18:00:00 ~ 21:00:00` |
| 今天 / 今天一天 | 当日 `09:30:00 ~ min(当前时间, 18:00:00)` |
| 过去一小时 / 刚才一小时 | `当前时间-1h ~ 当前时间` |
| 早上/上午/上班到现在 | 当日 `09:30:00 ~ 当前时间` |
| 下午到现在 | 当日 `13:30:00 ~ 当前时间` |
| 过去两小时到现在 | `当前时间-2h ~ 当前时间` |
| `HH:mm 到 HH:mm` / `今天 HH:mm 到 HH:mm` | 当日对应时刻 |

无法识别的时间通常返回短追问：“时间是早上、上午还是具体几点到几点？例如：09:00 到 11:00。”如果项目和事项已经齐全，后端也可能先按“今天 09:30 到当前时间”生成卡片，用户可直接在卡片中修正。

## 5. 状态默认值

| 语境 | `status` | `statusLabel` |
|---|---:|---|
| 做了、处理了、完成了、弄完了、已完成 | `2` | 已完成 |
| 正在、排查中、还没做完 | `1` | 进行中 |
| 未提供上述语境 | `0` | 待办 |

完整状态枚举：`0` 待办、`1` 进行中、`2` 已完成、`3` 已关闭、`4` 待确认完成。确认时传 `4`，后端仍按现有任务完成审批规则处理。

## 6. blocks 渲染协议

| `type` | 关键字段 | 处理 |
|---|---|---|
| `markdown` | `content` | 展示简短说明或追问 |
| `clarify` | `field`, `prompt`, `options[]` | 展示单选项并回传选择 |
| `confirm_card` | `items[]` | 展示可编辑记工确认卡 |
| `task_list` | `title`, `items[]` | 任务列表 |
| `timeline` | `title`, `events[]` | 时间线 |
| `line_chart` | `title`, `series[].points[]` | 趋势折线图 |
| `bar_chart` | `categories`, `series[].values` | 分布柱状图 |
| `stat_row` | `items[{label,value,hint?}]` | 汇总指标 |
| `done` | `taskIds`, `message` | 确认入库成功 |

未知 `type` 应降级展示提示或调试 JSON，不能导致整个对话白屏。

### 6.1 clarify 回传

后端返回：

```json
{
  "type": "clarify",
  "field": "project",
  "prompt": "请选择项目：",
  "options": [
    { "id": "12", "label": "ERP" },
    { "id": "18", "label": "ERP 客户端" }
  ]
}
```

点击后请求：

```json
{
  "sessionId": "0d55d42a-f1d4-44cd-a08d-e7ca44f1955e",
  "message": "ERP",
  "clarifyAnswer": {
    "field": "project",
    "optionId": "12",
    "label": "ERP"
  }
}
```

`optionId` 是字符串。前端不能只替换本地展示而不请求后端。

### 6.2 confirm_card 字段

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `tempId` | string | 是 | 会话内草稿标识，原样回传 |
| `projectId` | number | 是 | 项目主键 |
| `projectName` | string | 是 | 展示名称 |
| `title` | string | 是 | 可编辑，最长 200 字符 |
| `content` | string | 否 | 可编辑备注；无值时通常为空字符串 |
| `startAt` | string | 是 | GMT+8，`yyyy-MM-dd HH:mm:ss` |
| `endAt` | string | 是 | 同上，且不得早于 `startAt` |
| `timeLabel` | string | 是 | 同日为 `HH:mm:ss ~ HH:mm:ss` |
| `durationLabel` | string | 是 | 后端计算的展示时长 |
| `status` | number | 是 | 状态枚举值 |
| `statusLabel` | string | 是 | 中文状态 |

多条事项放在同一个 `confirm_card.items` 中。前端编辑时间后可本地刷新展示，但 `/confirm` 必须提交完整 item；后端会重新校验并重算项目名、时间文案、时长、状态文案。

## 7. 确认与取消

### 7.1 确认

`POST /api/task-ai/confirm`

```json
{
  "sessionId": "0d55d42a-f1d4-44cd-a08d-e7ca44f1955e",
  "items": [
    {
      "tempId": "draft-1",
      "projectId": 12,
      "projectName": "ERP",
      "title": "AI 助手功能",
      "content": "",
      "startAt": "2026-10-14 09:30:00",
      "endAt": "2026-10-14 11:20:00",
      "timeLabel": "09:30:00 ~ 11:20:00",
      "durationLabel": "1小时50分钟",
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
      { "type": "done", "taskIds": [101], "message": "已登记 1 条任务" }
    ],
    "done": { "taskIds": [101], "message": "已登记 1 条任务" }
  }
}
```

不传 `items` 时后端使用会话中最后一版草稿。建议前端只要允许编辑，就始终传完整 `items`。整批提交在同一事务中完成，任意一项失败会全部回滚。同一已提交会话重复确认不会重复创建任务，而会返回原 `taskIds`。

### 7.2 取消

`POST /api/task-ai/cancel`

```json
{ "sessionId": "0d55d42a-f1d4-44cd-a08d-e7ca44f1955e" }
```

成功时 `data=null`。取消后不能继续聊天或确认，前端必须清除该 `sessionId`。

## 8. 会话状态与错误处理

状态流转：`chatting` → `awaiting_confirm` → `committed`，或任意未提交状态 → `cancelled`。`committed`、`cancelled` 会话不能继续聊天。

参数、会话、确认校验错误沿用非 200/统一业务错误处理，展示响应 `message`。Agent 可恢复的缺参或消歧通常是 HTTP 200 下的 `markdown`/`clarify`。

常见业务消息：

- `消息或澄清选项不能为空`
- `会话不存在或无权访问`
- `当前没有待确认的记工`
- `项目不能为空`
- `任务标题不能为空`
- `时间格式应为 yyyy-MM-dd HH:mm:ss`
- `结束时间不能早于开始时间`
- `仅项目负责人或项目参与人可以创建任务`

后端不会把工具名、异常栈、服务器时间推导过程返回给用户。前端也不要展示 `toolTrace`。

## 9. 必测联调用例

| # | 操作 | 期望 |
|---:|---|---|
| 1 | 发送“今天早上在 ERP 做了线上问题，已完成” | 一轮返回卡片；起点 09:30；状态 2 |
| 2 | 先发送“登记 ERP 的 AI 功能”，再发送“早上到现在” | 第二轮保留项目和事项并返回卡片 |
| 3 | 已有完整草稿时只发送“任务系统” | 仅覆盖项目，其他字段保留并返回更新卡片 |
| 4 | 发送包含“今天 09:00 到 11:00”的记工 | 时间解析成功，不要求换说法 |
| 5 | 已有项目事项时发送“上班” | 以 09:30 为起点返回卡片 |
| 6 | 模糊项目产生多个候选，点击其中一个 | `clarifyAnswer` 后继续原会话并推进到卡片/下一缺槽 |
| 7 | 在卡片修改标题、时间、状态并确认 | `done.taskIds` 有值；数据库内容与编辑值一致 |
| 8 | 对同一会话重复确认 | 不产生重复任务，返回原任务 ID |
| 9 | 点击取消后再次聊天或确认 | 后端拒绝；前端已清除 session |
| 10 | 检查所有用户可见文案 | 无工具名、异常栈、服务器时间推导长句 |

## 10. 部署配置

后端默认配置：

```yaml
ai:
  work-time:
    morning-start: "09:30"
    morning-end: "12:00"
    afternoon-start: "13:30"
    afternoon-end: "18:00"
    evening-end: "21:00"
```

前端无需读取该配置。部署前确保已执行 [`sql/ai_task_log_session.sql`](../sql/ai_task_log_session.sql)。
