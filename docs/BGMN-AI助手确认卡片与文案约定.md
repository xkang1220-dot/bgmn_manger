# BGMN AI 助手 — 确认卡片与文案约定（前后端对齐）

> 对齐前端：`kk-admin/src/api/taskAi.ts`、`ConfirmCardBlock.vue`  
> 协议版本：`protocolVersion = 1`  
> 用途：记工场景下，后端返回固定 `confirm_card`，由前端渲染确认卡片；并约束对用户可见文案口径。

---

## 1. 核心原则

1. **对用户只给结论，不给过程**  
   不要把 Agent「思考过程、试错、工具报错、内部规则」写进对用户可见的 `markdown`。
2. **该确认就出卡**  
   记工参数已齐（或仅差用户点确认）时，必须返回 `type: "confirm_card"`，不要用纯文本表格/列表代替。
3. **数字与结构化字段来自后端确定性逻辑**  
   卡片字段由 Tools / 服务层组装；模型只负责旁白（且旁白要短）。

---

## 2. 文案禁止与推荐

### 2.1 禁止出现（对用户可见）

| 类型 | 反例 |
|------|------|
| 推导过程 | 「当前时间 2026-10-14 10:23:32，过去一小时即 09:23:32 ~ 10:23:32」 |
| 内部机制 | 「我会用草稿形式帮您登记（不会直接入库，需您确认）」 |
| 工具失败细节 | 「草稿工具持续报时间格式应为 yyyy-MM-dd HH:mm:ss，我尝试了多种字段…」 |
| 角色自述 | 「为避免反复失败，我先向您同步当前情况」 |
| 暴露 ID 堆砌 | 「已确认项目：ERP（ID 6）」——若需展示项目名即可，ID 放结构化字段 |

### 2.2 推荐写法

| 场景 | 推荐 |
|------|------|
| 说明时段 | `时间：09:23:32 ~ 10:23:32`（或交给卡片 `timeLabel`，markdown 甚至可省略） |
| 缺信息追问 | 简短 numbered 问题即可，例如「还差事项标题，例如：客服模块 bug 处理」 |
| 多项目歧义 | 用 `clarify` block，不要长篇解释匹配逻辑 |
| 确认前旁白 | 一句即可：`请确认以下登记信息`；详情全部进 `confirm_card` |

### 2.3 `markdown` 与 `confirm_card` 分工

| block | 用途 |
|-------|------|
| `markdown` | 极短旁白 / 追问补充信息 |
| `clarify` | 选项消歧（项目、人员、时间等） |
| `confirm_card` | **记工最终确认**（可多条） |
| 纯文本表格 | **禁止**用于最终确认 |

示例（正确）：

```json
{
  "sessionId": "...",
  "protocolVersion": 1,
  "pendingConfirm": true,
  "blocks": [
    { "type": "markdown", "content": "请确认以下登记信息：" },
    {
      "type": "confirm_card",
      "items": [ /* 见第 3 节 */ ]
    }
  ]
}
```

示例（错误）：用 markdown 画「项目｜事项｜时间｜时长」表，并夹带工具报错说明。

---

## 3. `confirm_card` 固定格式

### 3.1 Block 结构

```ts
{
  type: 'confirm_card'
  items: ConfirmTaskItem[]   // 至少 1 条；多任务就多项
}
```

### 3.2 `ConfirmTaskItem` 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `tempId` | string | 是 | 草稿临时 ID，会话内唯一 |
| `projectId` | number | 是 | 项目 ID |
| `projectName` | string | 是 | 项目展示名 |
| `title` | string | 是 | 事项标题 |
| `content` | string | 否 | 备注 |
| `startAt` | string | 是 | `yyyy-MM-dd HH:mm:ss`（GMT+8），入库用 |
| `endAt` | string | 是 | 同上 |
| `timeLabel` | string | 建议 | **展示用**时段，如 `09:23:32 ~ 10:23:32`；前端优先显示 |
| `durationLabel` | string | 建议 | **展示用**时长，如 `1小时` |
| `status` | 0\|1\|2\|3\|4 | 是 | 0待办 1进行中 2已完成 3已关闭 4待确认完成 |
| `statusLabel` | string | 是 | 与 status 对应中文 |

### 3.3 完整示例（对应「过去一小时 ERP 两条工单」）

```json
{
  "sessionId": "sess_xxx",
  "protocolVersion": 1,
  "pendingConfirm": true,
  "blocks": [
    {
      "type": "markdown",
      "content": "请确认以下登记信息："
    },
    {
      "type": "confirm_card",
      "items": [
        {
          "tempId": "d1",
          "projectId": 6,
          "projectName": "ERP",
          "title": "客服模块 bug 处理",
          "content": "",
          "startAt": "2026-10-14 09:40:43",
          "endAt": "2026-10-14 10:40:43",
          "timeLabel": "09:40:43 ~ 10:40:43",
          "durationLabel": "1小时",
          "status": 2,
          "statusLabel": "已完成"
        },
        {
          "tempId": "d2",
          "projectId": 6,
          "projectName": "ERP",
          "title": "人事模块业务调整",
          "content": "",
          "startAt": "2026-10-14 09:40:43",
          "endAt": "2026-10-14 10:40:43",
          "timeLabel": "09:40:43 ~ 10:40:43",
          "durationLabel": "1小时",
          "status": 2,
          "statusLabel": "已完成"
        }
      ]
    }
  ]
}
```

前端卡片将展示：序号、标题（可编辑）、项目、时间（`timeLabel`）、时长、状态下拉、开始/结束（可改）、备注；按钮「再改改」「确认入库」。

### 3.4 用户点确认后

`POST /api/task-ai/confirm`

```json
{
  "sessionId": "sess_xxx",
  "items": [ /* 用户可能改过的 ConfirmTaskItem[] */ ]
}
```

成功后返回：

```json
{
  "sessionId": "sess_xxx",
  "protocolVersion": 1,
  "blocks": [
    {
      "type": "done",
      "taskIds": [101, 102],
      "message": "已登记 2 条任务"
    }
  ],
  "done": {
    "taskIds": [101, 102],
    "message": "已登记 2 条任务"
  }
}
```

---

## 4. 缺参时怎么回（不要假装已确认）

| 情况 | 返回 |
|------|------|
| 项目歧义 | `clarify`（`field: "project"`）+ options |
| 只有项目+时段、缺事项 | 短 `markdown` 追问标题；**不要**出半空 confirm_card |
| 时间无法解析 | `clarify`（`field: "time"`）或短追问 |
| 参数齐全 | `confirm_card` + `pendingConfirm: true` |

缺参追问示例（可接受）：

```json
{
  "blocks": [
    {
      "type": "markdown",
      "content": "已匹配项目：ERP。\n时间：09:23:32 ~ 10:23:32\n\n还请补充事项，例如：\n1. 客服模块 bug 处理\n2. 人事模块业务调整"
    }
  ]
}
```

注意：即使这里提到时间，也只用「时间：起 ~ 止」，不要写「当前时间是…过去一小时即…」。

---

## 5. 后端实现提示（给联调）

1. System / 后处理提示词增加硬约束：禁止输出内部工具错误与草稿机制说明。  
2. `draft_log_tasks` 成功后，**只**通过 `suggestedBlocks` 产出 `confirm_card`，旁白最多一句。  
3. Tool 失败时：内部重试或换参；对用户改为「暂时无法生成确认信息，请补充/稍后重试」类短句，或继续追问缺字段——**不要粘贴异常原文**。  
4. `startAt`/`endAt` 必须是 `yyyy-MM-dd HH:mm:ss`；`timeLabel`/`durationLabel` 由服务层格式化，勿交给模型自由发挥。  
5. `pendingConfirm` 与是否包含 `confirm_card` 保持一致。

---

## 6. 验收清单

- [ ] 记工齐全时出现结构化确认卡片，而不是 markdown 表格  
- [ ] 卡片含项目 / 事项 / 时间 / 时长 / 状态  
- [ ] 可见文案无「当前时间…即…」「不会直接入库」「工具报错」等过程话  
- [ ] 多事项一次返回多条 `items`  
- [ ] 确认后走 `/confirm`，返回 `done`  

---

## 7. 关联文档

- 总接口说明：[BGMN-AI助手后端接口需求.md](./BGMN-AI助手后端接口需求.md)  
- 多轮槽位与时间默认（早上=09:30、短回复合并上下文）：[BGMN-AI助手多轮槽位与时间默认约定.md](./BGMN-AI助手多轮槽位与时间默认约定.md)  
- 前端联调：[BGMN-AI助手前端联调说明.md](./BGMN-AI助手前端联调说明.md)  
- 前端类型源：`kk-admin/src/api/taskAi.ts`  
- 卡片组件：`kk-admin/src/components/taskai/blocks/ConfirmCardBlock.vue`
