# BGMN AI 助手 — Mac / iOS 与 Web 三端对齐说明

> 对应提交：`feature/task-ai-assistant`（2026-10-14 夜～10-15）  
> 协议版本：`protocolVersion = 1`（未升版本号，属字段与块类型增量）  
> 关联文档：  
> - [Mac与iOS客户端接入指南](./BGMN-AI助手-Mac与iOS客户端接入指南.md)  
> - [确认卡片与文案约定](./BGMN-AI助手确认卡片与文案约定.md)  
> - [多轮槽位与时间默认约定](./BGMN-AI助手多轮槽位与时间默认约定.md)

本文只列 **Mac / iOS 需要改的客户端事项**，便于与 Web（`kk-admin`）对齐。  
**纯后端行为**（时间解析、权限、代建任务范围）客户端无需改接口路径，但要能正确渲染新字段/新块。

---

## 0. 一句话结论

| 优先级 | 事项 |
|--------|------|
| P0 | 确认卡展示并回传 `assigneeId` / `assigneeName` |
| P0 | `task_list` / `timeline` 展示 `durationLabel` + 完整 `timeLabel` |
| P0 | 新增渲染 `pie_chart` |
| P0 | 多人查询：按「对比布局」排版，勿当完整详情墙 |
| P1 | 人员 `clarify`：Chip 点选 + 保留手输 |
| P1 | 有结构化块时忽略 Markdown 表格旁白 |
| P2 | 柱图按类目分色；UI 宽度/毛玻璃可自定 |

HTTP 仍为：`POST /api/task-ai/chat`、`/confirm`、`/cancel`、`GET /tools`。鉴权与现网一致。

---

## 1. 模型字段增量（Swift 建议）

### 1.1 `ConfirmTaskItem`（确认卡）

在原有字段上增加：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `assigneeId` | `Int?` | 否 | 任务负责人用户 ID；空=当前登录用户 |
| `assigneeName` | `String?` | 否 | 负责人展示名 |

**UI**：每条确认项增加「负责人」只读行（或可展示）。  
**Confirm**：`POST /confirm` 的 `items` **原样回传**（含 `assigneeId`），不要丢掉。

```swift
struct ConfirmTaskItem: Codable, Identifiable {
    var tempId: String
    var projectId: Int
    var projectName: String
    var title: String
    var content: String?
    var startAt: String
    var endAt: String
    var timeLabel: String?
    var durationLabel: String?
    var status: Int
    var statusLabel: String
    var assigneeId: Int?          // 新增
    var assigneeName: String?     // 新增
    var id: String { tempId }
}
```

### 1.2 `TaskListItem`（任务列表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `timeLabel` | `String?` | 优先展示，如 `14:00:00 ~ 16:00:00`（**不要只显示日期**） |
| `durationLabel` | `String?` | **新增展示**，如 `2小时` / `2小时30分钟` |
| `startAt` / `endAt` | `String?` | 可选，完整时刻 |
| `title` / `projectName` / `statusLabel` / `userName` | 同前 | |

### 1.3 `TimelineEvent`（时序）

| 字段 | 说明 |
|------|------|
| `time` | 后端可能已拼成 `14:00:00 ~ 16:00:00 · 2小时` |
| `durationLabel` | **新增**；若 `time` 已含时长，可不再重复展示 |

### 1.4 `stat_row` 可选 `title`

多人对比时，每人一块统计可能带：

```json
{ "type": "stat_row", "title": "杨冲", "items": [ ... ] }
```

`title` 可忽略（因上方常有 `markdown` 人名），有则可用于无障碍/调试。

### 1.5 新增 Block：`pie_chart`

```swift
struct PieSlice: Codable, Identifiable {
    var name: String
    var value: Double
    var id: String { name }
}

// TaskAiBlock 增加：
// case pieChart(title: String?, slices: [PieSlice])
```

JSON：

```json
{
  "type": "pie_chart",
  "title": "状态分布",
  "slices": [
    { "name": "已完成", "value": 4 },
    { "name": "待办", "value": 1 }
  ]
}
```

- 推荐 Swift Charts `SectorMark`（环形更佳）  
- 高度约 220–240pt，圆角浅底容器  
- **未知 type 必须降级**，勿因新块解码失败导致整条消息空白

`TaskAiBlock` 枚举建议最终包含：

`markdown` / `clarify` / `confirm_card` / `task_list` / `timeline` / `stat_row` / `bar_chart` / `line_chart` / **`pie_chart`** / `done` / `unknown`

---

## 2. 交互与布局（必须对齐）

### 2.1 人员澄清 `clarify`（field = `user`）

场景：用户说「徐姐」等模糊称呼 → 后端返回相近人选 Chip。

```json
{
  "type": "clarify",
  "field": "user",
  "prompt": "未精确匹配到「徐姐」，相近人员如下，请点选；都不是可在下方输入全名或账号：",
  "options": [
    { "id": "9", "label": "徐康" }
  ]
}
```

客户端要求：

1. Chip 点选后调用 `/chat`，带：
   - `message`: 可用 `label`
   - `clarifyAnswer`: `{ field: "user", optionId: "<id>", label: "<label>" }`
2. **输入框保持可用**：用户可手输全名/账号继续发（不必强制只能点 Chip）
3. 文案可加一行弱提示：「都不是？可在下方输入全名或账号」

### 2.2 查询结果：禁止 Markdown 表当主展示

当 `blocks` 中已有任意：

`task_list` / `timeline` / `stat_row` / `bar_chart` / `line_chart` / `pie_chart`

则：

- **以结构化块为准**绘制  
- `replyText` 或 `markdown` 里若含 `|---|` 表格，**丢弃或忽略**，避免窄屏炸版  
- 旁白最多保留一两句纯文字

Web 已按此处理；Mac/iOS 请同样过滤。

### 2.3 多人对比布局（「分别 / 对比 / A和B」）

后端在 `userIds.length >= 2` 且调用 `summarize_tasks` / `list_tasks` 时，返回**对比模式**，典型顺序：

1. `markdown`：说明「N 人对比汇总，侧重对比而非明细」  
2. `bar_chart`：`按人员任务数对比`  
3. `bar_chart`：`按人员工时对比(小时)`（可能无工时则省略）  
4. 循环每人：
   - `markdown`：`**姓名**`
   - `stat_row`：总数 / 已完成 / 进行中 / 合计工时  
   - `markdown`：要点（最多约 3 条 `- 标题（项目）`，超出有「另有 N 条未展开」）  
5. `pie_chart`：`状态分布`（整体）

**不要**：

- 把多人任务混成一张超长 `task_list`  
- 自己再拼 Markdown 大表  
- 假设一定有每人完整明细（用户要详情时会再问单人）

**单人查询**仍可能是：`stat_row` + `task_list` + `timeline` + 项目柱图/工时柱图 + `pie_chart`。

### 2.4 确认卡

| 展示项 | 说明 |
|--------|------|
| 时间 | 优先 `timeLabel` |
| 时长 | 展示 `durationLabel` |
| 负责人 | 展示 `assigneeName`（有则显示） |
| 开始/结束 | `startAt` / `endAt`，可编辑 |
| 确认入库 | 回传完整 item，含负责人字段 |

---

## 3. 图表渲染约定

### 3.1 `bar_chart`（已有，建议增强）

```json
{
  "type": "bar_chart",
  "title": "按项目工时(小时)",
  "categories": ["云控平台", "ERP"],
  "series": [{ "name": "工时", "values": [2.5, 4.5] }]
}
```

建议：

- **单系列**：每个 category 使用不同颜色（对比更清晰）  
- **多系列**：按 series 取色  
- 类目较多时可旋转 X 轴标签  

常见 title：

- `按项目任务数` / `按项目工时(小时)`  
- `按人员任务数对比` / `按人员工时对比(小时)`  

### 3.2 `pie_chart`（新增，P0）

见 §1.5。切片颜色建议与柱图共用调色板（蓝/青/琥珀/玫红/紫等），避免整图单色。

### 3.3 `line_chart`

行为不变；查询趋势时仍可能出现。

---

## 4. 后端行为摘要（客户端无需改 API，但要知悉）

以下已由服务端保证，客户端**不要重复造轮子**：

| 能力 | 说明 |
|------|------|
| 钟点解析 | 「下午两点到六点」→ 14:00~18:00 |
| 工作日默认 | 上班 09:30；午休 12:00~13:30；下班 18:00；上午/下午/晚上分段 |
| 一天多事项 | 上午/下午/晚上各条任务各自赋时，不会把晚上钟点盖到上午 |
| 代建任务 | 项目负责人可为项目内成员建任务；确认卡带 `assignee*` |
| 项目消歧 | 给他人建任务时，候选项目 = 我负责 ∩ 对方参与 |
| 模糊人名 | 「徐姐」→ 相近用户 `clarify` |
| 查询图表 | `list`/`summarize` 自带柱图/饼图；已去重，勿再要客户端拼假数据 |
| 时长入库 | 有起止时刻时写 `estimated_hours`；查询从记工时段还原 `durationLabel` |

**无新 SQL**：依赖已有 `ai_task_log_session`、`pm_task.estimated_hours`。

---

## 5. Web 已对齐点（对照实现）

路径：`kk-admin/src/components/taskai/`、`kk-admin/src/api/taskAi.ts`

| 能力 | 组件/位置 |
|------|-----------|
| 确认卡负责人 | `ConfirmCardBlock.vue` |
| 列表时长 | `TaskListBlock.vue` |
| 时序时长 | `TimelineBlock.vue` |
| 饼图 | `PieChartBlock.vue` + `BlockRenderer.vue` |
| 柱图分色 | `BarChartBlock.vue` |
| 澄清手输提示 | `ClarifyBlock.vue` |
| 丢弃 Markdown 表 | `TaskAiChatDrawer.vue` → `resolveAssistantBlocks` |

---

## 6. 验收清单（Mac / iOS 自测）

- [ ] 记工确认卡：可见时间、时长、**负责人**；确认后任务负责人正确  
- [ ] 「今天下午两点到四点…」：卡片时段为 14:00~16:00，不是整段下午或错误跨度  
- [ ] 「上午 A/B，下午 C，晚上六点到九点半 D」：四条时段分别为 09:30~12:00 / 13:30~18:00 / 18:00~21:30  
- [ ] 「给杨冲创建任务…」：确认卡负责人=杨冲；项目仅为负责人可指派范围  
- [ ] 「徐姐今天干了啥」：出现人员 Chip；可点选；也可手输「徐康」  
- [ ] 「我今天的任务」：`stat_row` + `task_list`（含时长）+ 柱图 + **饼图**；无 Markdown 大表  
- [ ] 「杨冲和刘佳琦分别…」：人员对比柱图 + 分人统计/要点；**无**两人混排超长详情列表  
- [ ] 未知 `type` 降级提示，不白屏  
- [ ] 历史轮次 `clarify`/`confirm_card` 不可点；仅最新一轮可交互  

---

## 7. 建议排期

| 顺序 | 工作项 | 预估 |
|------|--------|------|
| 1 | 模型加 `assignee*`、`durationLabel`、`pie_chart` 解码 | 0.5d |
| 2 | 确认卡 / 列表 / 时序 UI | 0.5–1d |
| 3 | `pie_chart` + 柱图分色 | 0.5d |
| 4 | 多人对比排版 + Markdown 表过滤 | 0.5d |
| 5 | 按 §6 清单联调 | 0.5d |

---

## 8. 文档维护

- 本文随协议增量更新；大改时同步改 [Mac与iOS客户端接入指南](./BGMN-AI助手-Mac与iOS客户端接入指南.md) §5 Block 一节。  
- 若未来破坏性变更，再 bump `protocolVersion` 并写迁移说明。
