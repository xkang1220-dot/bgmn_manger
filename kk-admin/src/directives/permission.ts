import type { App, Directive } from 'vue'
import { watch, type WatchStopHandle } from 'vue'
import { useUserStore } from '@/stores/user'

type PermEl = HTMLElement & {
  __permStop?: WatchStopHandle
  __permValue?: string | string[]
}

/** 无权限时隐藏节点。用法：v-permission="'project:add'" 或 v-permission="['a','b']"（任一即可） */
const permission: Directive<PermEl, string | string[] | undefined> = {
  mounted(el, binding) {
    el.__permValue = binding.value
    apply(el, binding.value)
    const userStore = useUserStore()
    el.__permStop = watch(
      () => [userStore.permissions.join('\0'), userStore.roles.join('\0')] as const,
      () => apply(el, el.__permValue),
    )
  },
  updated(el, binding) {
    el.__permValue = binding.value
    apply(el, binding.value)
  },
  unmounted(el) {
    el.__permStop?.()
    delete el.__permStop
    delete el.__permValue
  },
}

function apply(el: HTMLElement, value: string | string[] | undefined) {
  if (value == null || value === '') {
    show(el)
    return
  }
  const userStore = useUserStore()
  const need = Array.isArray(value) ? value : [value]
  const ok = need.some((p) => !!p && userStore.hasPermission(p))
  if (ok) show(el)
  else hide(el)
}

function show(el: HTMLElement) {
  el.style.removeProperty('display')
  el.removeAttribute('aria-hidden')
  el.classList.remove('is-permission-hidden')
  el.removeAttribute('tabindex')
}

function hide(el: HTMLElement) {
  el.style.display = 'none'
  el.setAttribute('aria-hidden', 'true')
  el.classList.add('is-permission-hidden')
  el.setAttribute('tabindex', '-1')
}

export function setupPermissionDirective(app: App) {
  app.directive('permission', permission)
}
