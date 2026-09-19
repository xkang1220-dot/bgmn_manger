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
    path: '/public/company-tasks/:token',
    name: 'public-company-tasks',
    component: () => import('@/views/public/company-tasks.vue'),
    meta: { title: '今日工作', requiresAuth: false },
  },
  {
    path: '/',
    component: () => import('@/layouts/AdminLayout.vue'),
    meta: { requiresAuth: true },
    redirect: '/account',
    children: [
      { path: 'dashboard', redirect: '/account' },
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
      { path: 'ticket/manage', component: () => import('@/views/ticket/manage.vue'), meta: { title: '工单管理' } },
      { path: 'ticket/submissions', component: () => import('@/views/ticket/submissions.vue'), meta: { title: '我的工单' } },
      { path: 'ticket/dashboard', component: () => import('@/views/ticket/dashboard.vue'), meta: { title: '任务看板' } },
      { path: 'hr/archive', component: () => import('@/views/hr/archive.vue'), meta: { title: '人员档案' } },
      { path: 'hr/depr-category', component: () => import('@/views/hr/depr-category.vue'), meta: { title: '折旧类别' } },
      { path: 'hr/asset', component: () => import('@/views/hr/asset.vue'), meta: { title: '资产台账' } },
      { path: 'hr/salary', component: () => import('@/views/hr/salary.vue'), meta: { title: '工资配置' } },
      { path: 'hr/salary-run', component: () => import('@/views/hr/salary-run.vue'), meta: { title: '发薪记录' } },
      { path: 'file/list', component: () => import('@/views/file/list.vue'), meta: { title: '文件管理' } },
      { path: 'system/user', component: () => import('@/views/system/user/index.vue'), meta: { title: '账号管理' } },
      { path: 'system/role', component: () => import('@/views/system/role/index.vue'), meta: { title: '角色权限' } },
      { path: 'system/dept', component: () => import('@/views/system/dept/index.vue'), meta: { title: '部门管理' } },
      { path: 'system/menu', component: () => import('@/views/system/menu/index.vue'), meta: { title: '菜单管理' } },
      { path: 'account', component: () => import('@/views/account/index.vue'), meta: { title: '个人中心' } },
      { path: 'account/profile', component: () => import('@/views/account/profile.vue'), meta: { title: '账号资料' } },
      { path: 'account/salary-confirm', component: () => import('@/views/account/salary-confirm.vue'), meta: { title: '工资确认' } },
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
  document.title = `${to.meta.title || 'BGMN'} - BGMN`
  const userStore = useUserStore()
  if (to.meta.requiresAuth === false) {
    if (userStore.token && to.name === 'login') return '/account'
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
