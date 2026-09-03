import axios, { type AxiosRequestConfig, type AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
}

const service = axios.create({
  baseURL: '/api',
  timeout: 30000,
})

service.interceptors.request.use((config) => {
  const userStore = useUserStore()
  if (userStore.token) {
    config.headers.Authorization = userStore.token
  }
  return config
})

let isLoggingOut = false

service.interceptors.response.use(
  (response: AxiosResponse<ApiResponse>) => {
    if (response.config.responseType === 'blob') {
      return response.data as any
    }
    const res = response.data
    if (res.code !== 200) {
      const isLogout = response.config.url?.includes('/auth/logout')
      if (res.code === 401 && !isLoggingOut && !isLogout) {
        isLoggingOut = true
        ElMessage.error('登录已过期，请重新登录')
        const userStore = useUserStore()
        void userStore.logout().finally(() => { isLoggingOut = false })
        return Promise.reject(new Error('登录已过期'))
      }
      if (!isLogout) {
        ElMessage.error(res.message || '请求失败')
      }
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    return res.data as any
  },
  (error) => {
    ElMessage.error(error.response?.data?.message || error.message || '网络错误')
    return Promise.reject(error)
  },
)

export function request<T = unknown>(config: AxiosRequestConfig): Promise<T> {
  return service(config) as Promise<T>
}

export default service
