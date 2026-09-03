export interface UserInfo {
  id: number
  username: string
  nickname: string
  avatar?: string
  email?: string
  phone?: string
  gender?: number
  status?: number
  deptId?: number
  roleIds?: number[]
}

export interface MenuInfo {
  id: number
  parentId: number
  name: string
  type: number
  path: string
  component: string
  permission: string
  icon: string
  sort: number
  visible: number
  status: number
  children?: MenuInfo[]
}

export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  pageSize: number
}
