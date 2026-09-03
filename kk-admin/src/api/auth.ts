import { request } from '@/utils/request'
import type { MenuInfo, UserInfo } from './types'

export interface LoginParams {
  username: string
  password: string
  totpCode?: string
}

export interface TotpGenerateResult {
  accountName: string
  secretKey: string
  qrString: string
}

export const authApi = {
  login(data: LoginParams) {
    return request<{ token: string; user: UserInfo }>({ url: '/auth/login', method: 'post', data })
  },
  logout() {
    return request<void>({ url: '/auth/logout', method: 'post' })
  },
  getInfo() {
    return request<{ user: UserInfo; roles: string[]; permissions: string[]; menus: MenuInfo[] }>({
      url: '/auth/info',
      method: 'get',
    })
  },
  getTotpStatusByUsername(username: string) {
    return request<boolean>({ url: '/auth/totp/status/username', method: 'get', params: { username } })
  },
  getTotpStatus() {
    return request<boolean>({ url: '/auth/totp/status', method: 'get' })
  },
  generateTotp() {
    return request<TotpGenerateResult>({ url: '/auth/totp/generate', method: 'post' })
  },
  verifyTotp(totpCode: string) {
    return request<boolean>({ url: '/auth/totp/verify', method: 'post', data: { totpCode } })
  },
}
