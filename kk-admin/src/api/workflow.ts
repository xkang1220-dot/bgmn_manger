import { request } from '@/utils/request'
import type { PageResult } from './types'

export const workflowApi = {
  page(params: Record<string, unknown>) {
    return request<PageResult<any>>({ url: '/workflow/approval/page', method: 'get', params })
  },
  detail(id: number) {
    return request<any>({ url: `/workflow/approval/${id}`, method: 'get' })
  },
  submit(data: any) {
    return request<any>({ url: '/workflow/approval', method: 'post', data })
  },
  /** 上传发票/凭证（提交审批前） */
  uploadVoucher(file: File) {
    const form = new FormData()
    form.append('file', file)
    return request<any>({
      url: '/workflow/voucher',
      method: 'post',
      data: form,
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },
  approve(id: number, comment?: string) {
    return request<void>({ url: `/workflow/approval/${id}/approve`, method: 'post', data: { comment } })
  },
  reject(id: number, comment?: string) {
    return request<void>({ url: `/workflow/approval/${id}/reject`, method: 'post', data: { comment } })
  },
  withdraw(id: number) {
    return request<void>({ url: `/workflow/approval/${id}/withdraw`, method: 'post' })
  },
  uploadReceipt(id: number, fileIds: number[]) {
    return request<void>({ url: `/workflow/approval/${id}/receipt`, method: 'post', data: { fileIds } })
  },
  confirm(id: number) {
    return request<void>({ url: `/workflow/approval/${id}/confirm`, method: 'post' })
  },
  rollback(data: { approvalId: number; mode: string; amount?: number; reason?: string }) {
    return request<any>({ url: '/workflow/approval/rollback', method: 'post', data })
  },
  flowList() {
    return request<any[]>({ url: '/workflow/flow/list', method: 'get' })
  },
  saveFlow(data: any) {
    return request<void>({ url: '/workflow/flow', method: 'put', data })
  },
  deleteFlow(id: number) {
    return request<void>({ url: `/workflow/flow/${id}`, method: 'delete' })
  },
}
