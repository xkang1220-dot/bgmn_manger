import { request } from '@/utils/request'
import type { PageResult } from './types'

export type TicketVO = {
  id: number
  companyId: number
  companyName?: string
  projectId?: number
  projectName?: string
  ticketNo?: string
  title: string
  type: string
  urgency: string
  description?: string
  status: string
  progress?: number
  expectedCompleteDate?: string
  developerIds?: number[]
  developerNames?: string
  cycleId?: number
  cycleName?: string
  submitterId?: number
  submitterName?: string
  completedAt?: string
  createTime?: string
  updateTime?: string
  canUpdateProgress?: boolean
  replySummary?: {
    replyCount: number
    hasUnreadReply: boolean
    unreadReplyCount: number
    latestReply?: any
  }
}

export const ticketApi = {
  create(data: {
    companyId: number
    projectId?: number
    title: string
    type: string
    urgency: string
    description: string
  }) {
    return request<TicketVO>({ url: '/ticket/order', method: 'post', data })
  },
  page(params: Record<string, unknown>) {
    return request<PageResult<TicketVO>>({ url: '/ticket/order/page', method: 'get', params })
  },
  my(params?: { page?: number; pageSize?: number; companyId?: number; projectId?: number }) {
    return request<{
      stats: { total: number; completed: number; rate: number; unreadReplyTicketCount: number }
      page: PageResult<TicketVO>
    }>({ url: '/ticket/order/my', method: 'get', params })
  },
  detail(id: number) {
    return request<TicketVO>({ url: `/ticket/order/${id}`, method: 'get' })
  },
  update(id: number, data: Record<string, unknown>) {
    return request<TicketVO>({ url: `/ticket/order/${id}`, method: 'put', data })
  },
  batchUpdate(data: Record<string, unknown>) {
    return request<void>({ url: '/ticket/order/batch/update', method: 'put', data })
  },
  remove(id: number) {
    return request<void>({ url: `/ticket/order/${id}`, method: 'delete' })
  },
  batchRemove(ids: number[]) {
    return request<void>({ url: '/ticket/order/batch', method: 'delete', data: ids })
  },
  progress(id: number, data: { progress: number; status?: string }) {
    return request<TicketVO>({ url: `/ticket/order/${id}/progress`, method: 'put', data })
  },
  assign(id: number, data: Record<string, unknown>) {
    return request<TicketVO>({ url: `/ticket/order/${id}/assign`, method: 'put', data })
  },
  replyPage(ticketId: number, params?: Record<string, unknown>) {
    return request<PageResult<any>>({ url: `/ticket/order/${ticketId}/reply/page`, method: 'get', params })
  },
  reply(ticketId: number, content: string) {
    return request<any>({ url: `/ticket/order/${ticketId}/reply`, method: 'post', data: { content } })
  },
  markRead(ticketId: number, messageId?: number) {
    return request<void>({
      url: `/ticket/order/${ticketId}/reply/read`,
      method: 'put',
      data: messageId != null ? { messageId } : {},
    })
  },
  unreadCount(scope: 'my' | 'manage' = 'my') {
    return request<{ unreadTicketCount: number; unreadMessageCount: number }>({
      url: '/ticket/order/reply/unread-count',
      method: 'get',
      params: { scope },
    })
  },
  developerList(companyId: number, status?: number) {
    return request<any[]>({ url: '/ticket/developer/list', method: 'get', params: { companyId, status } })
  },
  createDeveloper(data: { companyId: number; name: string; role?: string; sysUserId?: number }) {
    return request<any>({ url: '/ticket/developer', method: 'post', data })
  },
  updateDeveloper(id: number, data: Record<string, unknown>) {
    return request<any>({ url: `/ticket/developer/${id}`, method: 'put', data })
  },
  deleteDeveloper(id: number) {
    return request<void>({ url: `/ticket/developer/${id}`, method: 'delete' })
  },
  updateDeveloperStatus(id: number, status: number) {
    return request<void>({ url: `/ticket/developer/${id}/status`, method: 'put', data: { status } })
  },
  cycleList(companyId: number) {
    return request<any[]>({ url: '/ticket/cycle/list', method: 'get', params: { companyId } })
  },
  cycleActive(companyId: number) {
    return request<any>({ url: '/ticket/cycle/active', method: 'get', params: { companyId } })
  },
  createCycle(data: Record<string, unknown>) {
    return request<any>({ url: '/ticket/cycle', method: 'post', data })
  },
  updateCycle(id: number, data: Record<string, unknown>) {
    return request<any>({ url: `/ticket/cycle/${id}`, method: 'put', data })
  },
  dashboardOverview(companyId: number, cycleId?: number, projectId?: number) {
    return request<any>({
      url: '/ticket/dashboard/overview',
      method: 'get',
      params: { companyId, cycleId, projectId },
    })
  },
  uploadImage(file: File) {
    const form = new FormData()
    form.append('file', file)
    return request<any>({
      url: '/ticket/order/upload/image',
      method: 'post',
      data: form,
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },
}

export const TICKET_TYPE_OPTIONS = [
  { value: 'bug', label: 'BUG' },
  { value: 'requirement', label: '需求' },
  { value: 'other', label: '其他' },
]

export const TICKET_URGENCY_OPTIONS = [
  { value: 'urgent', label: '紧急' },
  { value: 'high', label: '高' },
  { value: 'normal', label: '普通' },
  { value: 'low', label: '低' },
]

export const TICKET_STATUS_OPTIONS = [
  { value: 'pending', label: '待处理' },
  { value: 'in_progress', label: '开发中' },
  { value: 'testing', label: '测试中' },
  { value: 'completed', label: '已完成' },
  { value: 'closed', label: '已关闭' },
]

export function ticketTypeLabel(v?: string) {
  return TICKET_TYPE_OPTIONS.find((o) => o.value === v)?.label || v || '—'
}

export function ticketUrgencyLabel(v?: string) {
  return TICKET_URGENCY_OPTIONS.find((o) => o.value === v)?.label || v || '—'
}

export function ticketStatusLabel(v?: string) {
  return TICKET_STATUS_OPTIONS.find((o) => o.value === v)?.label || v || '—'
}
