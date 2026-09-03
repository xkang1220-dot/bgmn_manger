import { request } from '@/utils/request'
import type { PageResult } from './types'

export const bizApi = {
  summary() {
    return request<any>({ url: '/finance/summary', method: 'get' })
  },
  poolList() {
    return request<any[]>({ url: '/finance/pool/list', method: 'get' })
  },
  savePool(data: any, isEdit: boolean) {
    return request<void>({ url: '/finance/pool', method: isEdit ? 'put' : 'post', data })
  },
  walletPage(params: Record<string, unknown>) {
    return request<PageResult<any>>({ url: '/finance/wallet/page', method: 'get', params })
  },
  myWallet() {
    return request<any>({ url: '/finance/wallet/mine', method: 'get' })
  },
  myWalletLedger(params: Record<string, unknown>) {
    return request<PageResult<any>>({ url: '/finance/wallet/mine/ledger', method: 'get', params })
  },
  walletUserLedger(userId: number, params: Record<string, unknown>) {
    return request<PageResult<any>>({ url: `/finance/wallet/${userId}/ledger`, method: 'get', params })
  },
  ledgerPage(params: Record<string, unknown>) {
    return request<PageResult<any>>({ url: '/finance/ledger/page', method: 'get', params })
  },
  payChannelList(params?: Record<string, unknown>) {
    return request<any[]>({ url: '/finance/pay-channel/list', method: 'get', params })
  },
  savePayChannel(data: any, isEdit: boolean) {
    return request<void>({ url: '/finance/pay-channel', method: isEdit ? 'put' : 'post', data })
  },
  monthVerifyList(params?: Record<string, unknown>) {
    return request<any[]>({ url: '/finance/month-verify/list', method: 'get', params })
  },
  createLedger(data: any) {
    return request<void>({ url: '/finance/ledger', method: 'post', data })
  },
  uploadLedgerVoucher(file: File) {
    const form = new FormData()
    form.append('file', file)
    return request<any>({
      url: '/finance/ledger/voucher',
      method: 'post',
      data: form,
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },
  settle(data: any) {
    return request<void>({ url: '/finance/settle', method: 'post', data })
  },
  settleManual(data: any) {
    return request<void>({ url: '/finance/settle/manual', method: 'post', data })
  },
  projectShareDetail(projectId: number) {
    return request<any>({ url: `/finance/project-share/${projectId}`, method: 'get' })
  },
  saveProjectShare(data: any) {
    return request<void>({ url: '/finance/project-share', method: 'put', data })
  },
  archivePage(params: Record<string, unknown>) {
    return request<PageResult<any>>({ url: '/hr/archive/page', method: 'get', params })
  },
  archiveDetail(id: number) {
    return request<any>({ url: `/hr/archive/${id}`, method: 'get' })
  },
  saveArchive(data: any, isEdit: boolean) {
    return request<void>({ url: '/hr/archive', method: isEdit ? 'put' : 'post', data })
  },
  deleteArchive(id: number) {
    return request<void>({ url: `/hr/archive/${id}`, method: 'delete' })
  },
  projectPage(params: Record<string, unknown>) {
    return request<PageResult<any>>({ url: '/project/page', method: 'get', params })
  },
  projectList() {
    return request<any[]>({ url: '/project/list', method: 'get' })
  },
  myProjects() {
    return request<any[]>({ url: '/project/mine', method: 'get' })
  },
  projectDetail(id: number) {
    return request<any>({ url: `/project/${id}`, method: 'get' })
  },
  saveProject(data: any, isEdit: boolean) {
    return request<void>({ url: '/project', method: isEdit ? 'put' : 'post', data })
  },
  deleteProject(id: number) {
    return request<void>({ url: `/project/${id}`, method: 'delete' })
  },
  taskPage(params: Record<string, unknown>) {
    return request<PageResult<any>>({ url: '/task/page', method: 'get', params })
  },
  taskSummary(projectId?: number) {
    return request<any>({ url: '/task/summary', method: 'get', params: { projectId } })
  },
  taskDetail(id: number) {
    return request<any>({ url: `/task/${id}`, method: 'get' })
  },
  saveTask(data: any, isEdit: boolean) {
    return request<void>({ url: '/task', method: isEdit ? 'put' : 'post', data })
  },
  deleteTask(id: number) {
    return request<void>({ url: `/task/${id}`, method: 'delete' })
  },
  taskBoard(projectId: number) {
    return request<any[]>({ url: '/task/board', method: 'get', params: { projectId } })
  },
  updateTaskStatus(id: number, status: number, imageFileIds?: number[]) {
    return request<void>({ url: `/task/${id}/status`, method: 'put', data: { status, imageFileIds } })
  },
  taskComments(taskId: number) {
    return request<any[]>({ url: `/task/${taskId}/comments`, method: 'get' })
  },
  addTaskComment(taskId: number, content: string) {
    return request<any>({ url: `/task/${taskId}/comments`, method: 'post', data: { content } })
  },
  deleteTaskComment(commentId: number) {
    return request<void>({ url: `/task/comment/${commentId}`, method: 'delete' })
  },
  taskFlows(taskId: number) {
    return request<any[]>({ url: `/task/${taskId}/flows`, method: 'get' })
  },
  transferTask(taskId: number, data: { assigneeId: number; remark?: string; imageFileIds?: number[] }) {
    return request<void>({ url: `/task/${taskId}/transfer`, method: 'put', data })
  },
  uploadTaskImage(file: File) {
    const form = new FormData()
    form.append('file', file)
    return request<any>({
      url: '/task/image',
      method: 'post',
      data: form,
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },
  deleteTaskImage(fileId: number) {
    return request<void>({ url: `/task/image/${fileId}`, method: 'delete' })
  },
  projectAccountList() {
    return request<any[]>({ url: '/finance/project-account/list', method: 'get' })
  },
  projectAccountDetail(projectId: number) {
    return request<any>({ url: `/finance/project-account/${projectId}`, method: 'get' })
  },
  projectAccountLedger(projectId: number, params: Record<string, unknown>) {
    return request<PageResult<any>>({ url: `/finance/project-account/${projectId}/ledger`, method: 'get', params })
  },
  filePage(params: Record<string, unknown>) {
    return request<PageResult<any>>({ url: '/file/page', method: 'get', params })
  },
  deleteFile(id: number) {
    return request<void>({ url: `/file/${id}`, method: 'delete' })
  },
}
