import { request } from '@/utils/request'

export interface CompanyTaskShareOption {
  id: number
  name: string
}

export interface CompanyTaskShareState {
  companyId: number
  companyName: string
  active: boolean
  path?: string | null
  userIds?: number[]
  dateFrom?: string | null
  dateTo?: string | null
}

export interface PublicCompanyTaskComment {
  authorName?: string
  content?: string
  createTime?: string
}

export interface PublicCompanyTaskPerson {
  userId: number
  name: string
  taskCount: number
  overdueCount: number
}

export interface PublicCompanyTaskItem {
  id: number
  title?: string
  content?: string
  status?: number
  statusLabel?: string
  priority?: number
  startDate?: string
  dueDate?: string
  progress?: number
  projectName?: string
  participantNames?: string[]
  overdue?: boolean
  relatedUserIds?: number[]
  comments?: PublicCompanyTaskComment[]
}

export interface PublicCompanyTaskBoard {
  companyName: string
  from: string
  to: string
  people: PublicCompanyTaskPerson[]
  tasks: PublicCompanyTaskItem[]
}

export const companyTaskShareApi = {
  options() {
    return request<CompanyTaskShareOption[]>({ url: '/task/company-share/options', method: 'get' })
  },
  members(companyId: number) {
    return request<CompanyTaskShareOption[]>({ url: '/task/company-share/members', method: 'get', params: { companyId } })
  },
  state(companyId: number) {
    return request<CompanyTaskShareState>({ url: '/task/company-share', method: 'get', params: { companyId } })
  },
  generate(companyId: number, userIds: number[], from: string, to: string) {
    return request<CompanyTaskShareState>({ url: '/task/company-share', method: 'post', data: { companyId, userIds, from, to } })
  },
  updateUsers(companyId: number, userIds: number[], from: string, to: string) {
    return request<CompanyTaskShareState>({ url: '/task/company-share', method: 'put', data: { companyId, userIds, from, to } })
  },
  revoke(companyId: number) {
    return request<void>({ url: '/task/company-share', method: 'delete', params: { companyId } })
  },
  board(token: string) {
    return request<PublicCompanyTaskBoard>({
      url: `/public/company-tasks/${encodeURIComponent(token)}`,
      method: 'get',
    })
  },
}
