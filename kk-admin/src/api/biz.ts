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
  myWalletBoard(params?: { period?: string }) {
    return request<any>({ url: '/finance/wallet/mine/board', method: 'get', params })
  },
  balanceApplyProjects() {
    return request<any[]>({ url: '/finance/wallet/mine/balance-apply-projects', method: 'get' })
  },
  withdrawConfig(params?: { companyId?: number; amount?: number }) {
    return request<{
      taxMode?: string
      taxRate?: number
      defaultTaxRate?: number
      tax?: number
      net?: number
      tiers?: any[]
      breakdown?: any[]
    }>({ url: '/finance/wallet/mine/withdraw-config', method: 'get', params })
  },
  getWithdrawTaxTiers(companyId: number) {
    return request<{
      companyId: number
      taxMode: string
      defaultTaxRate: number
      tiers: any[]
    }>({ url: '/finance/wallet-withdraw-tax', method: 'get', params: { companyId } })
  },
  saveWithdrawTaxTiers(data: { companyId: number; tiers: any[] }) {
    return request<void>({ url: '/finance/wallet-withdraw-tax', method: 'put', data })
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
  registerLedger(data: any) {
    return request<{ mode: string; message: string; approval?: any }>({
      url: '/finance/ledger/register',
      method: 'post',
      data,
    })
  },
  getLedgerThreshold(companyId: number) {
    return request<any>({ url: '/finance/ledger-threshold', method: 'get', params: { companyId } })
  },
  saveLedgerThreshold(data: any) {
    return request<void>({ url: '/finance/ledger-threshold', method: 'put', data })
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
  myPayMethods() {
    return request<any[]>({ url: '/hr/archive/my-pay-methods', method: 'get' })
  },
  myArchive() {
    return request<any>({ url: '/hr/archive/mine', method: 'get' })
  },
  saveMyArchive(data: any) {
    return request<void>({ url: '/hr/archive/mine', method: 'put', data })
  },
  saveArchive(data: any, isEdit: boolean) {
    return request<void>({ url: '/hr/archive', method: isEdit ? 'put' : 'post', data })
  },
  deleteArchive(id: number) {
    return request<void>({ url: `/hr/archive/${id}`, method: 'delete' })
  },
  faCategoryPage(params: Record<string, unknown>) {
    return request<PageResult<any>>({ url: '/fa/category/page', method: 'get', params })
  },
  faCategoryList(params?: Record<string, unknown>) {
    return request<any[]>({ url: '/fa/category/list', method: 'get', params })
  },
  saveFaCategory(data: any, isEdit: boolean) {
    return request<void>({ url: '/fa/category', method: isEdit ? 'put' : 'post', data })
  },
  deleteFaCategory(id: number) {
    return request<void>({ url: `/fa/category/${id}`, method: 'delete' })
  },
  faAssetPage(params: Record<string, unknown>) {
    return request<PageResult<any>>({ url: '/fa/asset/page', method: 'get', params })
  },
  faAssetDetail(id: number) {
    return request<any>({ url: `/fa/asset/${id}`, method: 'get' })
  },
  faAssetItemTypes() {
    return request<Record<string, string>>({ url: '/fa/asset/item-types', method: 'get' })
  },
  uploadFaAssetImage(file: File) {
    const form = new FormData()
    form.append('file', file)
    return request<any>({
      url: '/fa/asset/image',
      method: 'post',
      data: form,
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },
  saveFaAsset(data: any, isEdit: boolean) {
    return request<void>({ url: '/fa/asset', method: isEdit ? 'put' : 'post', data })
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
  projectNextCode(companyId: number) {
    return request<string>({ url: '/project/next-code', method: 'get', params: { companyId } })
  },
  saveProject(data: any, isEdit: boolean) {
    return request<any>({ url: '/project', method: isEdit ? 'put' : 'post', data })
  },
  projectFlows(id: number) {
    return request<any[]>({ url: `/project/${id}/flows`, method: 'get' })
  },
  deleteProject(id: number) {
    return request<any>({ url: `/project/${id}`, method: 'delete' })
  },
  taskPage(params: Record<string, unknown>) {
    return request<PageResult<any>>({ url: '/task/page', method: 'get', params })
  },
  taskRelated() {
    return request<any[]>({ url: '/task/related', method: 'get' })
  },
  taskSummary(params?: {
    projectId?: number
    priority?: number
    participantId?: number
    title?: string
  }) {
    return request<any>({ url: '/task/summary', method: 'get', params: params || {} })
  },
  taskDetail(id: number) {
    return request<any>({ url: `/task/${id}`, method: 'get' })
  },
  saveTask(data: any, isEdit: boolean) {
    return request<void>({ url: '/task', method: isEdit ? 'put' : 'post', data })
  },
  taskBoard(projectId: number) {
    return request<any[]>({ url: '/task/board', method: 'get', params: { projectId } })
  },
  updateTaskStatus(id: number, status: number, imageFileIds?: number[], remark?: string) {
    return request<void>({ url: `/task/${id}/status`, method: 'put', data: { status, imageFileIds, remark } })
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
      timeout: 10 * 60 * 1000,
    })
  },
  deleteTaskImage(fileId: number) {
    return request<void>({ url: `/task/image/${fileId}`, method: 'delete' })
  },
  projectAccountList(params?: { companyId?: number; scale?: string }) {
    return request<any[]>({ url: '/finance/project-account/list', method: 'get', params })
  },
  projectAccountChildren(projectId: number) {
    return request<any[]>({ url: `/finance/project-account/${projectId}/children`, method: 'get' })
  },
  projectAccountDetail(projectId: number) {
    return request<any>({ url: `/finance/project-account/${projectId}`, method: 'get' })
  },
  projectChildren(projectId: number) {
    return request<any[]>({ url: `/project/${projectId}/children`, method: 'get' })
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
  salaryItems(params: { companyId: number; userId?: number }) {
    return request<any[]>({ url: '/hr/salary/items', method: 'get', params })
  },
  saveSalaryItem(data: any, isEdit: boolean) {
    return request<void>({ url: '/hr/salary/item', method: isEdit ? 'put' : 'post', data })
  },
  deleteSalaryItem(id: number) {
    return request<void>({ url: `/hr/salary/item/${id}`, method: 'delete' })
  },
  salarySchedule(companyId: number) {
    return request<any>({ url: '/hr/salary/schedule', method: 'get', params: { companyId } })
  },
  saveSalarySchedule(data: any) {
    return request<void>({ url: '/hr/salary/schedule', method: 'put', data })
  },
  salaryRuns(params: { companyId: number; yearMonth?: string }) {
    return request<any[]>({ url: '/hr/salary/runs', method: 'get', params })
  },
  salaryRunLines(runId: number) {
    return request<any[]>({ url: `/hr/salary/runs/${runId}/lines`, method: 'get' })
  },
  salaryPreview(data: { companyId: number; yearMonth?: string }) {
    return request<any>({ url: '/hr/salary/preview', method: 'post', data })
  },
  salaryPrepareDraft(params: { companyId: number; yearMonth?: string }) {
    return request<any[]>({ url: '/hr/salary/prepare-draft', method: 'get', params })
  },
  salaryPrepareConfirm(data: { companyId: number; yearMonth?: string; lines: any[] }) {
    return request<any>({ url: '/hr/salary/prepare-confirm', method: 'post', data })
  },
  salaryPay(data: { companyId: number; yearMonth?: string }) {
    return request<any>({ url: '/hr/salary/pay', method: 'post', data })
  },
  mySalaryConfirm(yearMonth?: string) {
    return request<any[]>({ url: '/hr/salary/my-confirm', method: 'get', params: { yearMonth } })
  },
  confirmSalary(lineId: number) {
    return request<void>({ url: `/hr/salary/my-confirm/${lineId}`, method: 'post' })
  },
  revokeSalaryConfirm(lineId: number) {
    return request<void>({ url: `/hr/salary/my-confirm/${lineId}/revoke`, method: 'post' })
  },
  submitLeave(data: { companyId: number; startDate: string; endDate: string; reason: string }) {
    return request<any>({ url: '/hr/leave/mine', method: 'post', data })
  },
  myLeave(params?: { companyId?: number; start?: string; end?: string }) {
    return request<any[]>({ url: '/hr/leave/mine', method: 'get', params })
  },
  companyLeave(params: { companyId: number; start?: string; end?: string }) {
    return request<any[]>({ url: '/hr/leave/company', method: 'get', params })
  },
}
