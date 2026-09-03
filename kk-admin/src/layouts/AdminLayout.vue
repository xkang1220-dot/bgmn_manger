<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
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
    <el-aside width="248px" class="aside">
      <div class="logo" @click="router.push('/dashboard')">
        <div class="logo-mark">K</div>
        <div class="logo-text">
          <span class="logo-title">KK Manager</span>
          <span class="logo-sub">公司管理系统</span>
        </div>
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

      <div class="aside-footer">
        <span>KK · 财务 · 项目 · 人事</span>
      </div>
    </el-aside>

    <el-container class="main-wrap">
      <el-header class="header">
        <div class="header-left">
          <div class="page-kicker">当前页面</div>
          <h1 class="page-heading">{{ pageTitle }}</h1>
        </div>
        <div class="header-right">
          <el-popover
            :visible="noticeVisible"
            placement="bottom-end"
            :width="360"
            trigger="click"
            @update:visible="onNoticeShow"
          >
            <template #reference>
              <button type="button" class="notice-btn" title="通知">
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

          <button type="button" class="user-pill" title="账号资料" @click="go('/account/profile')">
            <span class="avatar">{{ userInitial }}</span>
            <span class="user-meta">
              <span class="user-name">{{ userStore.nickname }}</span>
              <span class="user-sub">账号资料</span>
            </span>
          </button>
          <el-button class="logout-btn" round @click="logout">退出</el-button>
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
  background: var(--kk-bg-gradient);
}

.aside {
  display: flex;
  flex-direction: column;
  background: linear-gradient(180deg, var(--kk-side) 0%, var(--kk-side-2) 100%);
  border-right: 1px solid var(--kk-side-border);
  overflow: hidden;
}

.logo {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 68px;
  padding: 0 20px;
  cursor: pointer;
  border-bottom: 1px solid var(--kk-side-border);
  flex-shrink: 0;
}

.logo-mark {
  width: 38px;
  height: 38px;
  border-radius: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 800;
  font-size: 18px;
  color: #fff;
  background: linear-gradient(135deg, #6366f1 0%, #4f46e5 55%, #06b6d4 100%);
  box-shadow: 0 4px 14px rgba(99, 102, 241, 0.45);
}

.logo-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.logo-title {
  font-size: 15px;
  font-weight: 700;
  color: #f8fafc;
  letter-spacing: 0.02em;
}

.logo-sub {
  font-size: 11px;
  color: #64748b;
}

.menu-scroll {
  flex: 1;
  padding: 12px 10px;
}

.side-menu {
  border-right: none;
  background: transparent;
}

.aside :deep(.el-menu) {
  background: transparent;
}

.aside :deep(.el-sub-menu__title),
.aside :deep(.el-menu-item) {
  height: 44px;
  line-height: 44px;
  margin-bottom: 4px;
  border-radius: 10px;
  color: #94a3b8;
  transition: all 0.2s ease;
}

.aside :deep(.el-sub-menu__title:hover),
.aside :deep(.el-menu-item:hover) {
  background: var(--kk-side-hover) !important;
  color: #e2e8f0 !important;
}

.aside :deep(.el-menu-item.is-active) {
  background: var(--kk-side-active) !important;
  color: #fff !important;
  font-weight: 600;
  box-shadow: inset 3px 0 0 #6366f1;
}

.aside :deep(.el-sub-menu.is-active > .el-sub-menu__title) {
  color: #e2e8f0 !important;
}

.aside :deep(.el-menu--inline) {
  background: transparent !important;
}

.aside :deep(.el-menu--inline .el-menu-item) {
  padding-left: 48px !important;
  height: 40px;
  line-height: 40px;
  font-size: 13px;
}

.aside :deep(.el-icon) {
  font-size: 17px;
  margin-right: 4px;
}

.aside-footer {
  padding: 14px 18px;
  border-top: 1px solid var(--kk-side-border);
  font-size: 11px;
  color: #475569;
  flex-shrink: 0;
}

.main-wrap {
  min-width: 0;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 68px;
  padding: 0 28px;
  background: var(--kk-header-bg);
  backdrop-filter: blur(12px);
  border-bottom: 1px solid rgba(15, 23, 42, 0.06);
  box-shadow: 0 1px 0 rgba(255, 255, 255, 0.8) inset;
}

.header-left {
  min-width: 0;
}

.page-kicker {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--kk-primary-light);
}

.page-heading {
  margin: 2px 0 0;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--kk-text);
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}

.notice-btn {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  border: 1px solid #e2e8f0;
  background: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: #475569;
  transition: border-color 0.2s, box-shadow 0.2s, color 0.2s;
}

.notice-btn:hover {
  border-color: #c7d2fe;
  color: #4f46e5;
  box-shadow: 0 2px 12px rgba(79, 70, 229, 0.12);
}

.notice-panel {
  margin: -12px;
}

.notice-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px 8px;
  font-weight: 600;
  border-bottom: 1px solid #f1f5f9;
}

.notice-body {
  max-height: 360px;
  overflow: auto;
  min-height: 80px;
}

.notice-item {
  padding: 10px 14px;
  cursor: pointer;
  border-bottom: 1px solid #f8fafc;
}

.notice-item:hover {
  background: #f8fafc;
}

.notice-item.unread {
  background: #eef2ff;
}

.notice-item.unread .notice-title {
  font-weight: 700;
}

.notice-title {
  font-size: 13px;
  color: #0f172a;
  line-height: 1.4;
}

.notice-content {
  margin-top: 4px;
  font-size: 12px;
  color: #64748b;
  line-height: 1.4;
}

.notice-time {
  margin-top: 4px;
  font-size: 11px;
  color: #94a3b8;
}

.notice-foot {
  padding: 8px 14px;
  border-top: 1px solid #f1f5f9;
  text-align: center;
}

.user-pill {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 6px 14px 6px 6px;
  border: 1px solid #e2e8f0;
  border-radius: 999px;
  background: #fff;
  cursor: pointer;
  transition: border-color 0.2s, box-shadow 0.2s;
}

.user-pill:hover {
  border-color: #c7d2fe;
  box-shadow: 0 2px 12px rgba(79, 70, 229, 0.12);
}

.avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: 700;
  color: #fff;
  background: linear-gradient(135deg, #6366f1, #4f46e5);
}

.user-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.2;
}

.user-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--kk-text);
}

.user-sub {
  font-size: 11px;
  color: #64748b;
}

.logout-btn {
  border-color: #fecaca;
  color: #dc2626;
}

.logout-btn:hover {
  background: #fef2f2;
  border-color: #fca5a5;
  color: #b91c1c;
}

.main {
  padding: 22px 28px 28px;
  overflow: auto;
}

.page-fade-enter-active,
.page-fade-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.page-fade-enter-from {
  opacity: 0;
  transform: translateY(6px);
}

.page-fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
