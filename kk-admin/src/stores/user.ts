import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi, type LoginParams } from '@/api/auth'
import type { MenuInfo, UserInfo } from '@/api/types'
import router from '@/router'

export const useUserStore = defineStore(
  'user',
  () => {
    const token = ref<string | null>(null)
    const user = ref<UserInfo | null>(null)
    const roles = ref<string[]>([])
    const permissions = ref<string[]>([])
    const menus = ref<MenuInfo[]>([])

    const isLogin = computed(() => !!token.value)
    const nickname = computed(() => user.value?.nickname || user.value?.username || '')

    async function login(data: LoginParams) {
      const res = await authApi.login(data)
      token.value = res.token
      user.value = res.user
      await getInfo()
    }

    async function getInfo() {
      const res = await authApi.getInfo()
      user.value = res.user
      roles.value = res.roles
      permissions.value = res.permissions
      menus.value = res.menus
    }

    async function logout() {
      const hadToken = !!token.value
      token.value = null
      user.value = null
      roles.value = []
      permissions.value = []
      menus.value = []
      if (hadToken) {
        try {
          await authApi.logout()
        } catch {
          /* ignore */
        }
      }
      await router.push({ name: 'login' })
    }

    function hasPermission(permission: string) {
      if (roles.value.includes('admin')) return true
      if (!permission) return true
      return permissions.value.includes(permission) || permissions.value.includes('*:*:*')
    }

    return { token, user, roles, permissions, menus, isLogin, nickname, login, getInfo, logout, hasPermission }
  },
  { persist: { pick: ['token'] } },
)
