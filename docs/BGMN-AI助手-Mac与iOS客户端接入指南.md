# BGMN AI 助手 — Mac / iOS 客户端接入指南

> 协议版本：`protocolVersion = 1`  
> 适用：macOS App、iOS App（建议 SwiftUI；UIKit 同理）  
> 目标：与 Web 管理端（`kk-admin`）共用同一套后端接口与 `blocks` 富消息协议，实现记工确认、问答与图表展示。  
> Web 参考实现：`kk-admin/src/api/taskAi.ts`、`kk-admin/src/components/taskai/*`

---

## 1. 体系概览

```text
┌─────────────────────────────────────────┐
│  Mac / iOS Client                       │
│  ┌─────────┐  ┌──────────────────────┐  │
│  │ 入口按钮 │→│ AI Chat 面板（悬浮卡） │  │
│  └─────────┘  └──────────┬───────────┘  │
│                          │ HTTP JSON    │
│  ┌───────────────────────▼───────────┐  │
│  │ BlockRenderer（按 type 分发视图）   │  │
│  │ markdown / clarify / confirm_card │  │
│  │ timeline / charts / done …        │  │
│  └───────────────────────────────────┘  │
└──────────────────────┬──────────────────┘
                       │ Authorization
                       ▼
            POST/GET /api/task-ai/*
                       │
                       ▼
         后端 Tool Agent（查库 + 草稿）
         仅 confirm 后才真正 createTask
```

**原则（与 Web 一致）：**

1. 客户端**只渲染**后端下发的 `blocks`，不自己编造图表数字。  
2. 记工必须经 `confirm_card` → 用户确认 → `POST /confirm` 才入库。  
3. 短回复、时间默认（如「早上」= 09:30）由**后端槽位逻辑**处理；客户端负责把多轮 `sessionId` 与澄清选项正确回传。

---

## 2. 环境与鉴权

| 项 | 说明 |
|----|------|
| Base URL | 与现有 App 后台一致，如 `https://{host}`；本地联调可 `http://{lan-ip}:8999` |
| 路径前缀 | `/api` |
| 鉴权 | 请求头 `Authorization: {token}`（与现有登录态相同，Sa-Token） |
| 统一包体 | `{ "code": 200, "message": "...", "data": T }`；`code != 200` 时用 `message` 提示 |
| 时区 | 展示与提交时间按 `Asia/Shanghai`（GMT+8） |
| 超时 | `chat` ≥ **120s**；`confirm` ≥ **60s**；`cancel`/`tools` 常规即可 |

建议独立 `TaskAiAPIClient`，不要和普通短超时接口共用一个太短的 timeout。

---

## 3. 接口一览

| 方法 | 路径 | 用途 |
|------|------|------|
| `POST` | `/api/task-ai/chat` | 多轮对话（含澄清回传） |
| `POST` | `/api/task-ai/confirm` | 确认记工入库 |
| `POST` | `/api/task-ai/cancel` | 取消会话 |
| `GET` | `/api/task-ai/tools` | 联调查看工具列表（可选） |

详细字段见同目录 [BGMN-AI助手前端联调说明.md](./BGMN-AI助手前端联调说明.md)。

### 3.1 Chat

**请求**

```json
{
  "sessionId": "可选；首轮不传",
  "message": "用户自然语言",
  "clarifyAnswer": {
    "field": "project",
    "optionId": "12",
    "label": "ERP"
  }
}
```

- `message` 与 `clarifyAnswer` **至少其一**。  
- 点澄清选项：建议 **两者都传**（`message` 可用 `label`）。

**响应 `data`**

```json
{
  "sessionId": "uuid",
  "protocolVersion": 1,
  "replyText": "",
  "blocks": [ /* 见第 5 节 */ ],
  "pendingConfirm": false,
  "toolTrace": []
}
```

### 3.2 Confirm

```json
{
  "sessionId": "uuid",
  "items": [ /* 用户编辑后的 ConfirmTaskItem 完整列表 */ ]
}
```

成功后 `blocks` 常含 `done`；也可读 `done.taskIds`。

### 3.3 Cancel

```json
{ "sessionId": "uuid" }
```

成功后本地**必须清空** `sessionId`。

### 3.4 会话生命周期（客户端必守）

```text
无 sessionId → chat → 保存 sessionId
     │
     ├─ pendingConfirm=true → 展示确认卡 → confirm → 清空 sessionId
     ├─ 用户点「新对话」→ cancel（若有）→ 清空 sessionId
     └─ 业务错误含「会话不存在/已取消/已提交」→ 清空 sessionId
```

`committed` / `cancelled` 会话不可继续 chat；清空后下一句不传 `sessionId` 即可开新会话。

---

## 4. 界面如何显示

### 4.1 入口

| 平台 | 建议 |
|------|------|
| **Mac** | 侧栏底部或工具栏常驻入口；图标可用机器人 SVG（Web：`AiAssistantIcon.vue`）；文案「AI 助手」+ 副文案「自然语言记工 · 问答」 |
| **iOS** | Tab / 工作台悬浮钮 / 导航栏按钮；避免挡手势区 |

入口样式宜克制：深色图标槽 + 浅底卡片，悬停/按压可用青绿强调（对齐 Web，勿堆霓虹动效）。

### 4.2 聊天面板壳层

做成**悬浮卡片**，不要贴死全屏单侧直角：

| 属性 | Mac | iOS |
|------|-----|-----|
| 形态 | 浮动 Panel / `NSPanel` / SwiftUI sheet+自定义 | 半屏/全屏 `sheet` 或自定义 overlay |
| 圆角 | 四角 ≈ 20–24pt | 同左 |
| 边距 | 相对窗口 12–16pt | safeArea + 12pt |
| 毛玻璃 | `Material` / `ultraThinMaterial` | `.regularMaterial` 等 |
| 宽度 | ≈ 380–420pt | 近全宽 |
| 高度 | 窗口高度减边距 | 可用 detent medium/large |

结构自上而下：

1. **Header**：标题「AI 助手」、副标题「记工 · 问答 · 图表」、「新对话」、关闭  
2. **MessageList**：滚动消息流  
3. **GuideCarousel**（可选）：1–3 条引导，输入非空时隐藏  
4. **Composer**：一体式输入区 + 发送

### 4.3 消息气泡

| 角色 | 样式 |
|------|------|
| user | 右对齐，浅蓝/浅强调底 |
| assistant | 左对齐/通栏，浅白半透明底；**内容为 Block 栈**，不要再拼一份 `replyText` |
| loading | 「正在理解…」占位 |

仅最新一条 assistant 的 `clarify` / `confirm_card` 可交互；历史轮次禁用按钮，避免点旧卡。

### 4.4 引导条（横向轮播）

示例文案（可本地写死，与 Web 对齐）：

1. 登记一下我今天做了什么  
2. 查看一下我今天的任务时间线  
3. 汇总一下我今天完成了哪些任务  

- 一行一个 chip，自动轮播；点击即 `chat(message:)`  
- `TextField`/`TextEditor` 有内容时 `hidden`  
- 登记类无需客户端本地假回复，统一走后端 `/chat`

### 4.5 输入区

- 多行输入 + 底栏：「Enter 发送 · Shift+Enter 换行」+ 发送按钮（可带纸飞机图标）  
- **Mac / 中文输入法**：`Enter` 用于确认拼音/英文候选时**禁止发送**  
  - 监听 `isComposing` / `NSTextInputClient` composition；composition 中忽略 Return  
- 发送中禁用输入与重复发送  

---

## 5. `blocks` 协议与渲染

客户端实现一个 **`BlockRenderer`**：对 `blocks` **按数组顺序**垂直排列子视图。  
未知 `type` → 降级折叠显示原始 JSON，**禁止白屏或整页崩溃**。

有 `blocks` 时：**忽略展示 `replyText`**（兼容字段）。

### 5.1 Swift 模型建议

```swift
enum TaskAiClarifyField: String, Codable {
    case project, time, user, title, status
}

struct TaskAiClarifyOption: Codable, Identifiable {
    var id: String
    var label: String
}

struct ConfirmTaskItem: Codable, Identifiable {
    var tempId: String
    var projectId: Int
    var projectName: String
    var title: String
    var content: String?
    var startAt: String       // yyyy-MM-dd HH:mm:ss
    var endAt: String
    var timeLabel: String?
    var durationLabel: String?
    var status: Int           // 0...4
    var statusLabel: String
    var id: String { tempId }
}

enum TaskAiBlock: Codable {
    case markdown(content: String)
    case clarify(field: TaskAiClarifyField, prompt: String, options: [TaskAiClarifyOption]?)
    case confirmCard(items: [ConfirmTaskItem])
    case taskList(title: String?, items: [TaskListItem])
    case timeline(title: String?, events: [TimelineEvent])
    case lineChart(title: String?, xLabel: String?, yLabel: String?, series: [LineSeries])
    case barChart(title: String?, categories: [String], series: [BarSeries])
    case statRow(items: [StatItem])
    case done(taskIds: [Int], message: String?)
    case unknown(type: String, raw: [String: AnyCodable]) // 降级
}

// 解码时用 type 字段分发；见下节
```

状态枚举：`0` 待办 / `1` 进行中 / `2` 已完成 / `3` 已关闭 / `4` 待确认完成。

### 5.2 各 Block 如何展示

#### `markdown`

- 渲染 Markdown（iOS 15+ `AttributedString` / macOS 同；或第三方 MarkdownUI）  
- 用于短旁白、追问；**不应**出现工具报错原文、草稿机制说明  

#### `clarify`

- 展示 `prompt`  
- `options` 横向/换行 **Chip**；点击后调用：

```swift
chat(
  sessionId: current,
  message: option.label,
  clarifyAnswer: .init(field: field, optionId: option.id, label: option.label)
)
```

#### `confirm_card`（记工确认卡）

**必须做成可编辑卡片**，不要用文本表格代替。

展示建议：

| 区域 | 内容 |
|------|------|
| 头 | 「确认登记」+「共 N 条」 |
| 每条 | 序号、标题（可编，最长 **200**）、项目名、`timeLabel` 优先、`durationLabel`、状态下拉、`startAt`/`endAt`、备注 |
| 底 | 「再改改」→ 发一句补充给 `/chat`；「确认入库」→ `/confirm` |

- `timeLabel` 优先只读展示；用户改 `startAt`/`endAt` 后可本地刷新展示文案  
- `pendingConfirm == true` 时卡片可点；确认请求进行中禁止重复提交  
- Confirm 成功后清空 `sessionId`，插入 `done` 块  

字段契约详见 [BGMN-AI助手确认卡片与文案约定.md](./BGMN-AI助手确认卡片与文案约定.md)。

#### `task_list`

- 标题 + 行列表：`title`、`projectName`、`statusLabel`、`timeLabel`、`userName`

#### `timeline`

- 纵向时间轴：`time` + `title` + meta（项目/人/状态）

#### `stat_row`

- 横向指标格：`label` / `value` / 可选 `hint`  
- 用于「今天做了什么」汇总  

#### `line_chart` / `bar_chart`

- **数据已在 payload 中**，客户端只绘图  
- 推荐：`Swift Charts`（iOS 16+ / macOS 13+）

**折线**

```swift
// series: [{ name, points: [{ x: String, y: Double }] }]
Chart {
  ForEach(series, id: \.name) { s in
    ForEach(s.points, id: \.x) { p in
      LineMark(x: .value("x", p.x), y: .value("y", p.y))
        .foregroundStyle(by: .value("系列", s.name))
    }
  }
}
```

**柱状**

```swift
// categories: [String]
// series: [{ name, values: [Double] }]  // values.count == categories.count
Chart {
  ForEach(Array(categories.enumerated()), id: \.offset) { i, cat in
    ForEach(series, id: \.name) { s in
      BarMark(
        x: .value("类目", cat),
        y: .value("值", s.values[safe: i] ?? 0)
      )
      .foregroundStyle(by: .value("系列", s.name))
    }
  }
}
```

图表外层加圆角浅底容器；高度约 200–240pt。低版本系统可用 DGCharts / 自绘。

#### `done`

- 成功提示 + `taskIds`  
- 可选跳转「我的任务 / 工作台」（调用现有原生路由）

#### 未知 type

```text
「暂不支持的卡片：{type}」+ DisclosureGroup 展示 JSON
```

---

## 6. 调用流程（客户端伪代码）

```swift
class TaskAiSessionStore: ObservableObject {
  @Published var sessionId: String?
  @Published var messages: [ChatMessage] = []
  @Published var pendingConfirm = false
  @Published var sending = false

  func send(text: String) async {
    appendUser(text)
    sending = true
    defer { sending = false }
    do {
      let data = try await api.chat(.init(
        sessionId: sessionId,
        message: text,
        clarifyAnswer: nil
      ))
      apply(data)
    } catch {
      appendAssistantMarkdown(error.localizedDescription)
      if isSessionDead(error) { sessionId = nil; pendingConfirm = false }
    }
  }

  func apply(_ data: TaskAiChatResponse) {
    sessionId = data.sessionId
    pendingConfirm = data.pendingConfirm || data.blocks.contains(where: { $0.isConfirmCard })
    // 只追加 blocks，不展示 replyText（当 blocks 非空）
    appendAssistant(blocks: data.blocks)
    // 仅最新 assistant 可交互
  }

  func confirm(items: [ConfirmTaskItem]) async {
    guard let sessionId, !sending else { return }
    sending = true
    defer { sending = false }
    let data = try await api.confirm(.init(sessionId: sessionId, items: items))
    pendingConfirm = false
    self.sessionId = nil          // 提交后废弃会话
    appendAssistant(blocks: data.blocks.isEmpty
      ? [.done(taskIds: data.done?.taskIds ?? [], message: data.done?.message)]
      : data.blocks)
  }

  func newChat() async {
    if let sessionId { try? await api.cancel(sessionId) }
    self.sessionId = nil
    pendingConfirm = false
    messages = [welcome]
  }
}
```

---

## 7. 平台实现对照

| 能力 | 建议技术 |
|------|----------|
| 列表滚动 | `ScrollView` + `LazyVStack`；发消息后 `scrollTo` 底部 |
| Markdown | `AttributedString(markdown:)` 或 MarkdownUI |
| 图表 | Swift Charts |
| 网络 | URLSession / Alamofire；独立长超时 |
| JSON | `Codable` + 自定义 `init(from:)` 按 `type` 分发 |
| 键盘 | iOS：发送后收键盘可选；Mac：处理 IME Return |
| 深色模式 | 卡片用语义色（systemBackground / secondary），图表用可适配色板 |

### 推荐工程目录

```text
TaskAI/
  API/TaskAiAPI.swift
  Models/TaskAiModels.swift
  Store/TaskAiSessionStore.swift
  Views/
    TaskAiEntryButton.swift
    TaskAiChatPanel.swift
    ComposerView.swift
    GuideCarouselView.swift
    Blocks/
      BlockRenderer.swift
      MarkdownBlockView.swift
      ClarifyBlockView.swift
      ConfirmCardBlockView.swift
      TimelineBlockView.swift
      StatRowBlockView.swift
      LineChartBlockView.swift
      BarChartBlockView.swift
      TaskListBlockView.swift
      DoneBlockView.swift
      UnknownBlockView.swift
```

---

## 8. 与后端其它约定（客户端需知情）

客户端**不实现**下列逻辑，但联调时要知道期望行为：

- 「早上 / 上班」等默认 **09:30** 起，少追问，多出卡  
- 用户只回项目名时合并上下文，不要求整句重说  

详见：[BGMN-AI助手多轮槽位与时间默认约定.md](./BGMN-AI助手多轮槽位与时间默认约定.md)

---

## 9. 验收清单（Mac / iOS）

- [ ] 登录态下 `GET /api/task-ai/tools` 成功  
- [ ] 「我今天做了什么」出现 `stat_row` + `timeline`（或列表），数字与 Web 一致  
- [ ] 模糊项目名出现 `clarify`，点选后继续对话并带上 `clarifyAnswer`  
- [ ] 完整记工出现 `confirm_card`，可改标题/时间后 `confirm` 得到 `done`  
- [ ] 确认后 `sessionId` 已清空；再发消息为新会话  
- [ ] 「新对话」调用 `cancel` 并清空本地状态  
- [ ] 未知 block type 降级不崩溃  
- [ ] Mac：输入法选词回车不误发  
- [ ] 悬浮卡片四角圆角 + 边距，不贴边直角条  

---

## 10. 关联文档与 Web 参考

| 文档 / 代码 | 用途 |
|-------------|------|
| [BGMN-AI助手前端联调说明.md](./BGMN-AI助手前端联调说明.md) | HTTP 契约原文 |
| [BGMN-AI助手确认卡片与文案约定.md](./BGMN-AI助手确认卡片与文案约定.md) | 确认卡字段与文案 |
| [BGMN-AI助手多轮槽位与时间默认约定.md](./BGMN-AI助手多轮槽位与时间默认约定.md) | 后端槽位/时间默认 |
| [BGMN-AI助手后端接口需求.md](./BGMN-AI助手后端接口需求.md) | 后端总览与 Tool 表 |
| `kk-admin/src/api/taskAi.ts` | TypeScript 类型源 |
| `kk-admin/src/components/taskai/BlockRenderer.vue` | 分发渲染参考 |
| `kk-admin/src/components/taskai/blocks/*` | 各 Block UI 参考 |
| `kk-admin/src/components/taskai/TaskAiChatDrawer.vue` | 会话/引导/输入完整交互 |

---

## 11. 最小联调顺序（原生）

1. 配置 Base URL + Token，调 `/tools`  
2. 先做 `markdown` + 纯文本收发  
3. 接入 `clarify` Chip  
4. 接入 `confirm_card` + `/confirm`  
5. 接入 `stat_row` / `timeline` / `task_list`  
6. 接入 Swift Charts（`bar_chart` / `line_chart`）  
7. 补齐引导轮播、新对话、IME、悬浮壳样式  

按此顺序可在 1–2 个迭代内与 Web 能力对齐。
