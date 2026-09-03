import { request } from '@/utils/request'
import type { PageResult } from './types'

export const sysApi = {
  userPage(params: Record<string, unknown>) {
    return request<PageResult<any>>({ url: '/sys/user/page', method: 'get', params })
  },
  userList() {
    return request<any[]>({ url: '/sys/user/list', method: 'get' })
  },
  saveUser(data: any, isEdit: boolean) {
    return request<void>({ url: '/sys/user', method: isEdit ? 'put' : 'post', data })
  },
  deleteUser(id: number) {
    return request<void>({ url: `/sys/user/${id}`, method: 'delete' })
  },
  resetPwd(id: number, password = '123456') {
    return request<void>({ url: '/sys/user/reset-pwd', method: 'put', data: { id, password } })
  },
  changeUserStatus(id: number, status: number) {
    return request<void>({ url: '/sys/user/status', method: 'put', data: { id, status } })
  },
  roleList() {
    return request<any[]>({ url: '/sys/role/list', method: 'get' })
  },
  roleDetail(id: number) {
    return request<any>({ url: `/sys/role/${id}`, method: 'get' })
  },
  saveRole(data: any, isEdit: boolean) {
    return request<void>({ url: '/sys/role', method: isEdit ? 'put' : 'post', data })
  },
  deleteRole(id: number) {
    return request<void>({ url: `/sys/role/${id}`, method: 'delete' })
  },
  menuTree() {
    return request<any[]>({ url: '/sys/menu/tree', method: 'get' })
  },
  saveMenu(data: any, isEdit: boolean) {
    return request<void>({ url: '/sys/menu', method: isEdit ? 'put' : 'post', data })
  },
  deleteMenu(id: number) {
    return request<void>({ url: `/sys/menu/${id}`, method: 'delete' })
  },
  deptTree() {
    return request<any[]>({ url: '/sys/dept/tree', method: 'get' })
  },
  saveDept(data: any, isEdit: boolean) {
    return request<void>({ url: '/sys/dept', method: isEdit ? 'put' : 'post', data })
  },
  deleteDept(id: number) {
    return request<void>({ url: `/sys/dept/${id}`, method: 'delete' })
  },
  profile() {
    return request<any>({ url: '/account/profile', method: 'get' })
  },
  updateProfile(data: any) {
    return request<void>({ url: '/account/profile', method: 'put', data })
  },
  updatePassword(data: { oldPassword: string; newPassword: string }) {
    return request<void>({ url: '/account/password', method: 'put', data })
  },
}
