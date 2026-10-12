import { request } from '@/utils/request'

export interface WorkStatus {
  working: boolean
  changedAt?: string
}

export const workStatusApi = {
  status: () => request<WorkStatus>({ url: '/work-status', method: 'get' }),
  clockOut: () => request<WorkStatus>({ url: '/work-status/clock-out', method: 'post' }),
  clockIn: () => request<WorkStatus>({ url: '/work-status/clock-in', method: 'post' }),
}
