import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/stores/user'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/login/LoginPage.vue'),
    meta: { title: '登录', requiresAuth: false },
  },
  {
    path: '/',
    component: () => import('@/layouts/AdminLayout.vue'),
    meta: { requiresAuth: true },
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: 'dashboard', component: () => import('@/views/dashboard/index.vue'), meta: { title: '首页' } },
      { path: 'finance/pool', redirect: '/finance/ledger' },
      { path: 'finance/ledger', component: () => import('@/views/finance/ledger.vue'), meta: { title: '公司总账' } },
      { path: 'finance/project-account', component: () => import('@/views/finance/project-account.vue'), meta: { title: '项目账款' } },
      { path: 'finance/project-share', redirect: '/finance/project-account' },
      { path: 'finance/distribute', redirect: '/finance/project-account' },
      { path: 'finance/wallet-board', component: () => import('@/views/finance/wallet-board.vue'), meta: { title: '全员钱包' } },
      { path: 'finance/wallet', redirect: '/account' },
      { path: 'finance/pay-channel', component: () => import('@/views/finance/pay-channel.vue'), meta: { title: '收款渠道' } },
      { path: 'finance/month-verify', component: () => import('@/views/finance/month-verify.vue'), meta: { title: '月度核验' } },
      { path: 'workflow/center', component: () => import('@/views/workflow/center.vue'), meta: { title: '审批中心' } },
      { path: 'workflow/flow', component: () => import('@/views/workflow/flow.vue'), meta: { title: '审批配置' } },
      { path: 'project/list', component: () => import('@/views/project/list.vue'), meta: { title: '项目管理' } },
      { path: 'project/task', component: () => import('@/views/project/task.vue'), meta: { title: '任务管理' } },
      { path: 'hr/archive', component: () => import('@/views/hr/archive.vue'), meta: { title: '人员档案' } },
      { path: 'file/list', component: () => import('@/views/file/list.vue'), meta: { title: '文件管理' } },
      { path: 'system/user', component: () => import('@/views/system/user/index.vue'), meta: { title: '账号管理' } },
      { path: 'system/role', component: () => import('@/views/system/role/index.vue'), meta: { title: '角色权限' } },
      { path: 'system/dept', component: () => import('@/views/system/dept/index.vue'), meta: { title: '部门管理' } },
      { path: 'system/menu', component: () => import('@/views/system/menu/index.vue'), meta: { title: '菜单管理' } },
      { path: 'account', component: () => import('@/views/account/index.vue'), meta: { title: '个人中心' } },
      { path: 'account/profile', component: () => import('@/views/account/profile.vue'), meta: { title: '账号资料' } },
      { path: 'account/overview', redirect: '/account' },
      { path: 'account/wallet', redirect: '/account' },
      { path: 'account/approval', redirect: '/account' },
      { path: 'account/projects', redirect: '/account' },
      { path: 'account/calendar', redirect: '/account' },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach(async (to) => {
  document.title = `${to.meta.title || 'KK'} - 公司管理系统`
  const userStore = useUserStore()
  if (to.meta.requiresAuth === false) {
    if (userStore.token && to.name === 'login') return '/dashboard'
    return true
  }
  if (!userStore.token) return '/login'
  if (!userStore.user) {
    try {
      await userStore.getInfo()
    } catch {
      await userStore.logout()
      return '/login'
    }
  }
  return true
})

export default router
