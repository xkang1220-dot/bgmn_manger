import { request } from '@/utils/request'

/** 与后端 protocolVersion 对齐 */
export const TASK_AI_PROTOCOL_VERSION = 1

export type ClarifyField = 'project' | 'time' | 'user' | 'title' | 'status'

export interface ClarifyOption {
  id: string
  label: string
}

export interface ConfirmTaskItem {
  tempId: string
  projectId: number
  projectName: string
  title: string
  content?: string
  /** 开始时间，格式 yyyy-MM-dd HH:mm:ss（GMT+8） */
  startAt: string
  /** 结束时间，格式 yyyy-MM-dd HH:mm:ss（GMT+8） */
  endAt: string
  /** 展示用时段，如「09:30:00 ~ 12:00:00」；前端优先展示 */
  timeLabel?: string
  /** 展示用时长，如「1小时」「2小时30分钟」 */
  durationLabel?: string
  status: 0 | 1 | 2 | 3 | 4
  statusLabel: string
  /** 任务负责人；为空表示当前登录用户 */
  assigneeId?: number
  assigneeName?: string
}

export interface TaskListItem {
  id?: number
  projectId?: number
  title: string
  projectName?: string
  statusLabel?: string
  /** 时段展示，如「14:00:00 ~ 16:00:00」 */
  timeLabel?: string
  /** 时长展示，如「2小时」 */
  durationLabel?: string
  startAt?: string
  endAt?: string
  userName?: string
}

export interface TimelineEvent {
  id?: string | number
  projectId?: number
  time: string
  title: string
  projectName?: string
  statusLabel?: string
  userName?: string
  durationLabel?: string
}

export type UiBlock =
  | { type: 'markdown'; content: string }
  | {
      type: 'clarify'
      field: ClarifyField
      prompt: string
      options?: ClarifyOption[]
    }
  | { type: 'confirm_card'; items: ConfirmTaskItem[] }
  | { type: 'task_list'; title?: string; items: TaskListItem[]; emptyText?: string }
  | { type: 'timeline'; title?: string; events: TimelineEvent[] }
  | {
      type: 'line_chart'
      title?: string
      xLabel?: string
      yLabel?: string
      series: { name: string; points: { x: string; y: number }[] }[]
    }
  | {
      type: 'bar_chart'
      title?: string
      categories: string[]
      series: { name: string; values: number[] }[]
    }
  | {
      type: 'pie_chart'
      title?: string
      slices: { name: string; value: number }[]
    }
  | {
      type: 'stat_row'
      items: { label: string; value: string | number; hint?: string }[]
    }
  | { type: 'done'; taskIds: number[]; message?: string }
  | { type: string; [key: string]: unknown }

export interface ToolTraceItem {
  name: string
  ok: boolean
}

export interface TaskAiChatResponse {
  sessionId: string
  protocolVersion?: number
  replyText?: string
  blocks: UiBlock[]
  pendingConfirm?: boolean
  toolTrace?: ToolTraceItem[]
}

export interface TaskAiChatRequest {
  sessionId?: string
  /** 与 clarifyAnswer 至少有一个 */
  message?: string
  clarifyAnswer?: {
    field: ClarifyField
    optionId: string
    label?: string
  }
}

export interface TaskAiConfirmRequest {
  sessionId: string
  items?: ConfirmTaskItem[]
}

export interface TaskAiConfirmResponse {
  sessionId: string
  protocolVersion?: number
  blocks: UiBlock[]
  done?: { taskIds: number[]; message?: string }
}

export interface TaskAiToolInfo {
  name: string
  description?: string
}

export const TASK_STATUS_OPTIONS = [
  { value: 0, label: '待办' },
  { value: 1, label: '进行中' },
  { value: 2, label: '已完成' },
  { value: 3, label: '已关闭' },
  { value: 4, label: '待确认完成' },
] as const

export function hasConfirmCard(blocks?: UiBlock[]) {
  return (blocks || []).some((b) => b?.type === 'confirm_card')
}

export const taskAiApi = {
  chat: (data: TaskAiChatRequest) =>
    request<TaskAiChatResponse>({
      url: '/task-ai/chat',
      method: 'post',
      data,
      timeout: 120000,
    }),

  confirm: (data: TaskAiConfirmRequest) =>
    request<TaskAiConfirmResponse>({
      url: '/task-ai/confirm',
      method: 'post',
      data,
      timeout: 60000,
    }),

  cancel: (sessionId: string) =>
    request<null>({
      url: '/task-ai/cancel',
      method: 'post',
      data: { sessionId },
    }),

  tools: () =>
    request<TaskAiToolInfo[]>({
      url: '/task-ai/tools',
      method: 'get',
    }),
}
