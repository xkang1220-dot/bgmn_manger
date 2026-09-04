<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import KkLogoMark from '@/components/KkLogoMark.vue'
import { notificationApi, type NotificationItem } from '@/api/notification'
import type { MenuInfo } from '@/api/types'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const visibleMenus = computed(() =>
  (userStore.menus || []).filter((m) => m.visible !== 0 && m.type !== 3),
)

const pageTitle = computed(() => String(route.meta.title || '工作台'))

const userInitial = computed(() => {
  const name = userStore.nickname || userStore.user?.username || 'U'
  return name.charAt(0).toUpperCase()
})

const unreadCount = ref(0)
const noticeList = ref<NotificationItem[]>([])
const noticeLoading = ref(false)
const noticeVisible = ref(false)
let pollTimer: ReturnType<typeof setInterval> | null = null

function iconOf(name?: string) {
  return name || 'Menu'
}

function go(path?: string) {
  if (path) router.push(path)
}

function fmtTime(t?: string) {
  if (!t) return ''
  return t.replace('T', ' ').slice(0, 16)
}

async function loadUnread() {
  try {
    const res = await notificationApi.unreadCount()
    unreadCount.value = Number(res?.count || 0)
  } catch {
    /* ignore poll errors */
  }
}

async function loadNotices() {
  noticeLoading.value = true
  try {
    const res = await notificationApi.page({ page: 1, pageSize: 15 })
    noticeList.value = res.list || []
    await loadUnread()
  } finally {
    noticeLoading.value = false
  }
}

async function onNoticeShow(visible: boolean) {
  noticeVisible.value = visible
  if (visible) await loadNotices()
}

async function openNotice(item: NotificationItem) {
  if (!item.readFlag) {
    try {
      await notificationApi.markRead(item.id)
      item.readFlag = 1
      unreadCount.value = Math.max(0, unreadCount.value - 1)
    } catch { /* ignore */ }
  }
  noticeVisible.value = false
  router.push(item.link || '/workflow/center')
}

async function markAllRead() {
  await notificationApi.markAllRead()
  noticeList.value.forEach((n) => { n.readFlag = 1 })
  unreadCount.value = 0
}

async function logout() {
  await userStore.logout()
}

onMounted(() => {
  loadUnread()
  pollTimer = setInterval(loadUnread, 30000)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<template>
  <el-container class="layout">
    <el-aside width="240px" class="aside">
      <div class="logo" @click="router.push('/dashboard')">
        <div class="logo-mark"><KkLogoMark /></div>
        <span class="logo-title">BGMN</span>
      </div>

      <el-scrollbar class="menu-scroll">
        <el-menu
          :default-active="route.path"
          class="side-menu"
          router
        >
          <template v-for="menu in visibleMenus" :key="menu.id">
            <el-sub-menu v-if="menu.children?.length" :index="menu.path || String(menu.id)">
              <template #title>
                <el-icon><component :is="iconOf(menu.icon)" /></el-icon>
                <span>{{ menu.name }}</span>
              </template>
              <el-menu-item
                v-for="child in menu.children.filter((c: MenuInfo) => c.type !== 3 && c.visible !== 0)"
                :key="child.id"
                :index="child.path"
              >
                {{ child.name }}
              </el-menu-item>
            </el-sub-menu>
            <el-menu-item v-else :index="menu.path">
              <el-icon><component :is="iconOf(menu.icon)" /></el-icon>
              <span>{{ menu.name }}</span>
            </el-menu-item>
          </template>
        </el-menu>
      </el-scrollbar>
    </el-aside>

    <el-container class="main-wrap">
      <el-header class="header">
        <div class="header-left">
          <h1 class="page-heading">{{ pageTitle }}</h1>
        </div>
        <div class="header-right">
          <button type="button" class="user-chip" title="账号资料" @click="go('/account/profile')">
            <span class="avatar">{{ userInitial }}</span>
            <span class="user-name">{{ userStore.nickname }}</span>
          </button>
          <span class="header-split" aria-hidden="true" />
          <div class="header-actions">
            <el-popover
              :visible="noticeVisible"
              placement="bottom-end"
              :width="360"
              trigger="click"
              @update:visible="onNoticeShow"
            >
              <template #reference>
                <button type="button" class="icon-btn" title="通知" aria-label="通知">
                  <el-badge :value="unreadCount" :hidden="!unreadCount" :max="99">
                    <el-icon :size="18"><Bell /></el-icon>
                  </el-badge>
                </button>
              </template>
              <div class="notice-panel">
                <div class="notice-head">
                  <span>通知</span>
                  <el-button v-if="unreadCount" link type="primary" @click="markAllRead">全部已读</el-button>
                </div>
                <div v-loading="noticeLoading" class="notice-body">
                  <div
                    v-for="item in noticeList"
                    :key="item.id"
                    class="notice-item"
                    :class="{ unread: !item.readFlag }"
                    @click="openNotice(item)"
                  >
                    <div class="notice-title">{{ item.title }}</div>
                    <div class="notice-content">{{ item.content }}</div>
                    <div class="notice-time">{{ fmtTime(item.createTime) }}</div>
                  </div>
                  <el-empty v-if="!noticeLoading && !noticeList.length" description="暂无通知" :image-size="64" />
                </div>
                <div class="notice-foot">
                  <el-button link type="primary" @click="noticeVisible = false; go('/workflow/center')">去审批中心</el-button>
                </div>
              </div>
            </el-popover>
            <button type="button" class="icon-btn is-logout" title="退出" aria-label="退出" @click="logout">
              <el-icon :size="18"><SwitchButton /></el-icon>
            </button>
          </div>
        </div>
      </el-header>

      <el-main class="main">
        <router-view v-slot="{ Component }">
          <transition name="page-fade" mode="out-in">
            <component :is="Component" :key="route.path" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
  overflow: hidden;
  background: var(--kk-bg);
  font-family: var(--el-font-family);
}

.aside {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--kk-side);
  overflow: hidden;
}

.logo {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 48px;
  margin: 20px 20px 0;
  cursor: pointer;
  flex-shrink: 0;
}

.logo-mark {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  overflow: hidden;
  flex-shrink: 0;
  color: #fff;
  background: var(--kk-text);
}

.logo-title {
  font-size: 24px;
  font-weight: 500;
  line-height: 1.2;
  color: var(--kk-text);
}

.menu-scroll {
  flex: 1;
  padding: 20px;
}

.side-menu {
  --el-menu-bg-color: transparent;
  --el-menu-hover-bg-color: transparent;
  --el-menu-text-color: var(--kk-text-secondary);
  --el-menu-active-color: var(--kk-text);
  --el-menu-hover-text-color: var(--kk-text);
  width: 200px;
  border-right: none;
  background: transparent;
}

.aside :deep(.el-menu) {
  background: transparent;
  width: 200px;
}

.aside :deep(.el-sub-menu__title),
.aside :deep(.el-menu > .el-menu-item) {
  height: 48px;
  line-height: 20px;
  margin: 0;
  padding: 12px !important;
  border: 1px solid transparent;
  border-radius: 12px;
  color: var(--kk-text-secondary);
  font-size: 14px;
  font-weight: 500;
  display: flex;
  align-items: center;
  transition: background 0.18s var(--kk-ease), color 0.18s var(--kk-ease), box-shadow 0.18s var(--kk-ease), border-color 0.18s var(--kk-ease);
}

.aside :deep(.el-sub-menu__title:hover),
.aside :deep(.el-menu > .el-menu-item:hover) {
  background: var(--kk-side-hover) !important;
  color: var(--kk-text) !important;
}

.aside :deep(.el-menu > .el-menu-item.is-active) {
  background: var(--kk-side-active) !important;
  border-color: var(--kk-side-active-border);
  color: var(--kk-side-active-color) !important;
  box-shadow: var(--kk-side-active-shadow);
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
}

.aside :deep(.el-sub-menu.is-opened > .el-sub-menu__title),
.aside :deep(.el-sub-menu.is-active > .el-sub-menu__title) {
  color: var(--kk-text) !important;
}

.aside :deep(.el-menu--inline) {
  position: relative;
  background: transparent !important;
}

.aside :deep(.el-menu--inline)::before {
  content: "";
  position: absolute;
  left: 23px;
  top: -4px;
  bottom: 32px;
  width: 1.5px;
  height: auto;
  background: #dedee0;
  border-radius: 1px 1px 0 0;
}

.aside :deep(.el-menu--inline .el-menu-item) {
  position: relative;
  height: 44px;
  line-height: 20px;
  margin: 0 0 0 36px;
  width: calc(100% - 36px);
  padding: 0 12px !important;
  border: 1px solid transparent;
  border-radius: 12px;
  font-size: 14px;
  font-weight: 500;
  color: var(--kk-text-secondary);
  box-sizing: border-box;
}

.aside :deep(.el-menu--inline .el-menu-item::before) {
  content: "";
  position: absolute;
  left: -13px;
  top: 50%;
  width: 13px;
  height: 12px;
  margin-top: -11px;
  background: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='13' height='12' fill='none'%3E%3Cpath d='M1 1C1 6.5 5.5 11 11 11H13' stroke='%23DEDEE0' stroke-width='1.5' stroke-linecap='round'/%3E%3C/svg%3E") no-repeat center;
  background-size: 13px 12px;
  pointer-events: none;
}

.aside :deep(.el-menu--inline .el-menu-item:hover) {
  background: var(--kk-side-hover) !important;
  color: var(--kk-text) !important;
}

.aside :deep(.el-menu--inline .el-menu-item.is-active) {
  background: var(--kk-side-active) !important;
  border-color: var(--kk-side-active-border);
  color: var(--kk-side-active-color) !important;
  box-shadow: var(--kk-side-active-shadow);
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
}

.aside :deep(.el-sub-menu__title:focus),
.aside :deep(.el-menu-item:focus) {
  outline: none;
}

.aside :deep(.el-icon:not(.el-sub-menu__icon-arrow)) {
  width: 24px;
  height: 24px;
  font-size: 18px;
  margin-right: 12px;
  color: inherit;
}

.aside :deep(.el-sub-menu__icon-arrow) {
  top: 50%;
  right: 12px;
  width: 12px;
  height: 12px;
  margin-top: -6px;
  margin-right: 0;
  font-size: 12px;
  color: var(--kk-text-muted);
}

.main-wrap {
  flex: 1;
  min-width: 0;
  min-height: 0;
  height: 100%;
  overflow: hidden;
  background: var(--kk-bg);
}

.header {
  --el-header-height: 88px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 88px;
  padding: 0 20px 0 0;
  background: var(--kk-header-bg);
}

.header-left {
  min-width: 0;
}

.page-heading {
  margin: 0;
  font-size: 24px;
  font-weight: 500;
  line-height: 1.2;
  color: var(--kk-text);
}

.header-right {
  display: flex;
  align-items: center;
  height: 36px;
  gap: 12px;
  flex-shrink: 0;
}

.user-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 36px;
  padding: 0 8px 0 4px;
  border: none;
  border-radius: 12px;
  background: transparent;
  cursor: pointer;
  font: inherit;
  line-height: 0;
  transition: background 0.18s var(--kk-ease);
}

.user-chip:hover {
  background: var(--kk-side-hover);
}

.avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 600;
  line-height: 1;
  color: #fff;
  background: var(--kk-text);
}

.user-name {
  font-size: 14px;
  font-weight: 500;
  line-height: 1;
  color: var(--kk-text);
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.header-split {
  width: 1px;
  height: 14px;
  flex-shrink: 0;
  background: var(--kk-hairline);
}

.header-actions {
  display: flex;
  align-items: center;
  height: 36px;
  gap: 0;
}

.header-actions > * {
  display: inline-flex;
  align-items: center;
  height: 36px;
}

.icon-btn {
  width: 36px;
  height: 36px;
  padding: 0;
  border: none;
  border-radius: 12px;
  background: transparent;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: var(--kk-text-secondary);
  font: inherit;
  line-height: 0;
  transition: background 0.18s var(--kk-ease), color 0.18s var(--kk-ease);
}

.icon-btn:hover {
  background: var(--kk-side-hover);
  color: var(--kk-text);
}

.icon-btn.is-logout:hover {
  background: #fce8ee;
  color: #e11d48;
}

.icon-btn :deep(.el-badge) {
  display: flex;
  align-items: center;
  justify-content: center;
  line-height: 0;
  height: 18px;
  vertical-align: top;
}

.icon-btn :deep(.el-icon) {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  font-size: 18px;
}

.notice-panel {
  margin: -12px;
}

.notice-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px 10px;
  font-size: 14px;
  font-weight: 500;
  color: var(--kk-text);
  border-bottom: 1px solid var(--kk-hairline);
}

.notice-body {
  max-height: 360px;
  overflow: auto;
  min-height: 80px;
}

.notice-item {
  padding: 12px 16px;
  cursor: pointer;
}

.notice-item + .notice-item {
  border-top: 1px solid var(--kk-hairline);
}

.notice-item:hover {
  background: var(--kk-side-hover);
}

.notice-item.unread .notice-title {
  font-weight: 600;
}

.notice-title {
  font-size: 14px;
  color: var(--kk-text);
  line-height: 20px;
}

.notice-content {
  margin-top: 4px;
  font-size: 12px;
  color: var(--kk-text-secondary);
  line-height: 16px;
}

.notice-time {
  margin-top: 4px;
  font-size: 12px;
  color: var(--kk-text-muted);
}

.notice-foot {
  padding: 10px 16px;
  border-top: 1px solid var(--kk-hairline);
  text-align: center;
}

.main {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 0 20px 20px;
  overflow: auto;
  background: var(--kk-bg);
}

.main > * {
  flex: 1 0 auto;
  width: 100%;
  box-sizing: border-box;
}

.page-fade-enter-active,
.page-fade-leave-active {
  transition: opacity 0.18s var(--kk-ease), transform 0.18s var(--kk-ease);
}

.page-fade-enter-from {
  opacity: 0;
  transform: translateY(6px);
}

.page-fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

@media (prefers-reduced-motion: reduce) {
  .aside :deep(.el-sub-menu__title),
  .aside :deep(.el-menu-item),
  .user-chip,
  .icon-btn,
  .page-fade-enter-active,
  .page-fade-leave-active {
    transition: none;
  }
}
</style>
