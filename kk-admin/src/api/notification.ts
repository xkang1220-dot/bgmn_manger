import { request } from '@/utils/request'
import type { PageResult } from './types'

export interface NotificationItem {
  id: number
  userId: number
  title: string
  content?: string
  bizType?: string
  bizId?: number
  link?: string
  readFlag: number
  createTime?: string
}

export const notificationApi = {
  page(params: { page?: number; pageSize?: number; unreadOnly?: boolean }) {
    return request<PageResult<NotificationItem>>({
      url: '/notification/page',
      method: 'get',
      params,
    })
  },
  unreadCount() {
    return request<{ count: number }>({ url: '/notification/unread-count', method: 'get' })
  },
  markRead(id: number) {
    return request<void>({ url: `/notification/${id}/read`, method: 'post' })
  },
  markAllRead() {
    return request<void>({ url: '/notification/read-all', method: 'post' })
  },
}
