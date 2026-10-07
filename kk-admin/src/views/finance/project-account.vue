<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { bizApi } from '@/api/biz'
import { sysApi } from '@/api/system'
import { workflowApi } from '@/api/workflow'
import { approvalFlowTip } from '@/utils/approvalTip'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const route = useRoute()
const router = useRouter()
const list = ref<any[]>([])
const companies = ref<any[]>([])
const filter = reactive({
  companyId: undefined as number | undefined,
  scale: '' as string,
})
const activeId = ref<number | null>(null)
/** 从重大外壳点进小项目后，返回时回到外壳 */
const shellParentId = ref<number | null>(null)
const childAccounts = ref<any[]>([])
const account = ref<any>(null)
const shareDetail = ref<any>(null)
const users = ref<any[]>([])
const ledgers = ref<any[]>([])
const analyticsLedgers = ref<any[]>([])
const analyticsTimeRange = ref<string[]>([])
const ledgerTotal = ref(0)
const ledgerQuery = reactive({ page: 1, pageSize: 20 })
const ledgerFilter = reactive({
  keyword: '',
  bizType: '',
  direction: '',
  accountType: '',
})
const tab = ref('overview')
const ledgerSectionRef = ref<HTMLElement | null>(null)
type ChartDrilldown = {
  label: string
  month?: string
  direction?: 'IN' | 'OUT'
  bizTypes?: string[]
  otherExpense?: boolean
  otherFlow?: boolean
}
const chartDrilldown = ref<ChartDrilldown | null>(null)

function routeId(value: unknown) {
  const parsed = Number(Array.isArray(value) ? value[0] : value)
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null
}

function syncDetailRoute(projectId: number | null, shellId: number | null = null) {
  const query = { ...route.query }
  if (projectId) query.projectId = String(projectId)
  else delete query.projectId
  if (shellId) query.shellId = String(shellId)
  else delete query.shellId
  delete query.tab
  void router.replace({ query })
}

const balanceBreakdown = computed(() =>
  Number(account.value?.sharePendingBalance || 0) + Number(account.value?.nonShareBalance || 0),
)
const balanceVariance = computed(() =>
  Number((Number(account.value?.balance || 0) - balanceBreakdown.value).toFixed(2)),
)
const balanceMatched = computed(() => Math.abs(balanceVariance.value) <= 0.01)

const visibleLedgers = computed(() => {
  const keyword = ledgerFilter.keyword.trim().toLowerCase()
  const source = chartDrilldown.value ? analyticsLedgers.value : ledgers.value
  return source.filter((row) => {
    const drilldown = chartDrilldown.value
    if (drilldown) {
      if (row.accountType !== 'PROJECT') return false
      if (drilldown.month && String(row.occurTime || '').slice(0, 7) !== drilldown.month) return false
      if (!drilldown.month && analyticsTimeRange.value?.length === 2) {
        const day = String(row.occurTime || '').slice(0, 10)
        if (day < analyticsTimeRange.value[0] || day > analyticsTimeRange.value[1]) return false
      }
      if (drilldown.direction === 'IN' && Number(row.amount) < 0) return false
      if (drilldown.direction === 'OUT' && Number(row.amount) >= 0) return false
      if (drilldown.bizTypes && !drilldown.bizTypes.includes(row.bizType)) return false
      if (drilldown.otherExpense && (Number(row.amount) >= 0 || ['SALARY', 'REIMBURSE', 'EXPENSE', 'PAYOUT', 'SETTLE', 'RESERVE'].includes(row.bizType))) return false
      if (drilldown.otherFlow && (Number(row.amount) >= 0 || ['SALARY', 'REIMBURSE', 'SETTLE', 'PAYOUT'].includes(row.bizType))) return false
    }
    if (ledgerFilter.bizType && row.bizType !== ledgerFilter.bizType) return false
    if (ledgerFilter.accountType && row.accountType !== ledgerFilter.accountType) return false
    if (ledgerFilter.direction === 'IN' && Number(row.amount) < 0) return false
    if (ledgerFilter.direction === 'OUT' && Number(row.amount) >= 0) return false
    if (keyword) {
      const haystack = [row.bizNo, row.title, row.userName, bizLabel(row.bizType), accountLabel(row)]
        .filter(Boolean)
        .join(' ')
        .toLowerCase()
      if (!haystack.includes(keyword)) return false
    }
    return true
  })
})

async function applyChartDrilldown(filter: ChartDrilldown) {
  chartDrilldown.value = filter
  ledgerFilter.keyword = ''
  ledgerFilter.bizType = ''
  ledgerFilter.direction = ''
  ledgerFilter.accountType = ''
  tab.value = 'overview'
  await nextTick()
  ledgerSectionRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function clearChartDrilldown() {
  chartDrilldown.value = null
}

function drillMonthly(month: string, direction: 'IN' | 'OUT') {
  applyChartDrilldown({ month, direction, label: `${month} ${direction === 'IN' ? '流入' : '流出'}` })
}

function drillExpense(item: { key: string; label: string }) {
  applyChartDrilldown({
    label: `支出构成 · ${item.label}`,
    direction: 'OUT',
    ...(item.key === 'OTHER' ? { otherExpense: true } : { bizTypes: [item.key] }),
  })
}

function drillFundFlow(item: { key: string; label: string }) {
  const filter: ChartDrilldown = { label: `资金流向 · ${item.label}` }
  if (item.key === 'in') filter.direction = 'IN'
  else {
    filter.direction = 'OUT'
    if (item.key === 'salary') filter.bizTypes = ['SALARY', 'REIMBURSE']
    else if (item.key === 'distribution') filter.bizTypes = ['SETTLE', 'PAYOUT']
    else filter.otherFlow = true
  }
  applyChartDrilldown(filter)
}

const currentPageProjectFlow = computed(() => {
  const rows = visibleLedgers.value.filter((row) => row.accountType === 'PROJECT')
  const inflow = rows.reduce((sum, row) => sum + Math.max(0, Number(row.amount || 0)), 0)
  const outflow = rows.reduce((sum, row) => sum + Math.abs(Math.min(0, Number(row.amount || 0))), 0)
  return { count: rows.length, inflow, outflow, net: inflow - outflow }
})

const analyticsDateShortcuts = [
  {
    text: '本月',
    value: () => {
      const now = new Date()
      return [new Date(now.getFullYear(), now.getMonth(), 1), now]
    },
  },
  {
    text: '近 30 天',
    value: () => {
      const end = new Date()
      const start = new Date()
      start.setDate(start.getDate() - 29)
      return [start, end]
    },
  },
  {
    text: '本年度',
    value: () => {
      const now = new Date()
      return [new Date(now.getFullYear(), 0, 1), now]
    },
  },
]

const filteredAnalyticsLedgers = computed(() => {
  const [start, end] = analyticsTimeRange.value || []
  if (!start || !end) return analyticsLedgers.value
  return analyticsLedgers.value.filter((row) => {
    const day = String(row.occurTime || '').slice(0, 10)
    return day >= start && day <= end
  })
})

const analyticsProjectRows = computed(() =>
  filteredAnalyticsLedgers.value.filter((row) => row.accountType === 'PROJECT'),
)

const monthlyCashFlow = computed(() => {
  const buckets = new Map<string, { month: string; inflow: number; outflow: number }>()
  analyticsProjectRows.value.forEach((row) => {
    const month = String(row.occurTime || '').slice(0, 7)
    if (!month) return
    const item = buckets.get(month) || { month, inflow: 0, outflow: 0 }
    const amount = Number(row.amount || 0)
    if (amount >= 0) item.inflow += amount
    else item.outflow += Math.abs(amount)
    buckets.set(month, item)
  })
  return [...buckets.values()].sort((a, b) => a.month.localeCompare(b.month)).slice(-6)
})

const monthlyCashMax = computed(() => Math.max(
  1,
  ...monthlyCashFlow.value.flatMap((item) => [item.inflow, item.outflow]),
))

const expenseBreakdown = computed(() => {
  const meta: Record<string, { label: string; color: string }> = {
    SALARY: { label: '工资', color: '#f59e0b' },
    REIMBURSE: { label: '报销', color: '#ef4444' },
    EXPENSE: { label: '项目支出', color: '#8b5cf6' },
    PAYOUT: { label: '余额发放', color: '#0ea5e9' },
    SETTLE: { label: '项目分成', color: '#14b8a6' },
    RESERVE: { label: '结余/预留', color: '#64748b' },
  }
  const amounts = new Map<string, number>()
  analyticsProjectRows.value.forEach((row) => {
    const amount = Number(row.amount || 0)
    if (amount >= 0) return
    const key = meta[row.bizType] ? row.bizType : 'OTHER'
    amounts.set(key, (amounts.get(key) || 0) + Math.abs(amount))
  })
  const other = { label: '其他', color: '#94a3b8' }
  return [...amounts.entries()]
    .map(([key, value]) => ({ ...(meta[key] || other), key, value }))
    .sort((a, b) => b.value - a.value)
})

const expenseTotal = computed(() => expenseBreakdown.value.reduce((sum, item) => sum + item.value, 0))
const expenseDonutStyle = computed(() => {
  if (!expenseTotal.value) return { background: '#e5e7eb' }
  let cursor = 0
  const stops = expenseBreakdown.value.map((item) => {
    const start = cursor
    cursor += (item.value / expenseTotal.value) * 100
    return `${item.color} ${start.toFixed(2)}% ${cursor.toFixed(2)}%`
  })
  return { background: `conic-gradient(${stops.join(',')})` }
})

const fundFlowRanking = computed(() => {
  const groups = [
    { key: 'in', label: '公司转入项目', color: '#10b981', value: 0 },
    { key: 'salary', label: '工资与报销', color: '#f59e0b', value: 0 },
    { key: 'distribution', label: '分成与余额发放', color: '#6366f1', value: 0 },
    { key: 'other', label: '其他支出/退回', color: '#64748b', value: 0 },
  ]
  analyticsProjectRows.value.forEach((row) => {
    const amount = Number(row.amount || 0)
    if (amount > 0) groups[0].value += amount
    else if (['SALARY', 'REIMBURSE'].includes(row.bizType)) groups[1].value += Math.abs(amount)
    else if (['SETTLE', 'PAYOUT'].includes(row.bizType)) groups[2].value += Math.abs(amount)
    else groups[3].value += Math.abs(amount)
  })
  return groups.filter((item) => item.value > 0)
})

const fundFlowMax = computed(() => Math.max(1, ...fundFlowRanking.value.map((item) => item.value)))

const advanceDialog = ref(false)
const reimburseDialog = ref(false)
const salaryDialog = ref(false)
const ledgerDetailVisible = ref(false)
const ledgerDetail = ref<any>(null)
const ledgerRelated = ref<any[]>([])
const ledgerDetailLoading = ref(false)

const form = reactive({
  amount: 0,
  remark: '',
  payMethodId: undefined as number | undefined,
  fundType: 'SHARE_PENDING' as string,
})
const voucherFiles = ref<any[]>([])
const uploadingVoucher = ref(false)
const myPayMethods = ref<any[]>([])
const shareForm = reactive({
  reservePercent: 0,
  settlePercent: 100,
  members: [] as Array<{ userId?: number; layer: string; percent: number; remark: string }>,
})
const periodSharing = ref(false)
const periodShareDialog = ref(false)
const selectedPeriodMonth = ref('')
const reverseAdvanceDialog = ref(false)
const reverseForm = reactive({
  sharePendingAmount: 0,
  nonShareAmount: 0,
  remark: '',
})
const remainderDialog = ref(false)
const remainderForm = reactive({
  sharePendingAmount: 0,
  nonShareAmount: 0,
  reserveHeldAmount: 0,
  remark: '',
})
const remainderSubmitting = ref(false)

const percentSum = computed(() =>
  shareForm.members.reduce((s, m) => s + Number(m.percent || 0), 0),
)

/** 分成% + 预留% 须为 100%；支出不再配置，直接从项目结余扣 */
const fundSplitOk = computed(() => {
  const settle = Number(shareForm.settlePercent || 0)
  const reserve = Number(shareForm.reservePercent || 0)
  return settle >= 0 && reserve >= 0 && Math.abs(settle + reserve - 100) <= 0.01
})

/** 分成% + 预留% 联动，合计恒为 100% */
function clampPercent(v: unknown) {
  const n = Number(v)
  if (!Number.isFinite(n)) return 0
  return Math.min(100, Math.max(0, Number(n.toFixed(2))))
}

function setSettlePercent(v: unknown) {
  const settle = clampPercent(v)
  const reserve = Number((100 - settle).toFixed(2))
  if (shareForm.settlePercent !== settle) shareForm.settlePercent = settle
  if (shareForm.reservePercent !== reserve) shareForm.reservePercent = reserve
}

function setReservePercent(v: unknown) {
  const reserve = clampPercent(v)
  const settle = Number((100 - reserve).toFixed(2))
  if (shareForm.reservePercent !== reserve) shareForm.reservePercent = reserve
  if (shareForm.settlePercent !== settle) shareForm.settlePercent = settle
}

watch(
  () => shareForm.settlePercent,
  (v) => setSettlePercent(v),
)

watch(
  () => shareForm.reservePercent,
  (v) => setReservePercent(v),
)

/** 加载配置时：以分成%为准，预留%补到 100 */
function applyFundSplitPercents(settle?: unknown, reserve?: unknown) {
  const settleRaw = settle != null ? Number(settle) : NaN
  const reserveRaw = reserve != null ? Number(reserve) : NaN
  if (Number.isFinite(settleRaw)) {
    setSettlePercent(settleRaw)
  } else if (Number.isFinite(reserveRaw)) {
    setReservePercent(reserveRaw)
  } else {
    setSettlePercent(100)
  }
}

function formatPeriodMonth(d = new Date()) {
  const m = `${d.getMonth() + 1}`.padStart(2, '0')
  return `${d.getFullYear()}-${m}`
}

const periodSettlePreview = computed(() => {
  const pending = Number(account.value?.sharePendingBalance || 0)
  const settlePct = Number(shareForm.settlePercent || 0)
  const settleAmt = Number(((pending * settlePct) / 100).toFixed(2))
  const reserveAmt = Number((pending - settleAmt).toFixed(2))
  return { pending, settleAmt, reserveAmt }
})

const periodMemberPreview = computed(() => buildMemberShares(periodSettlePreview.value.settleAmt))

/** 从公司转入可用余额：打开弹窗后优先用资金池列表（与总账同口径） */
const companyPoolBalance = computed(() => {
  if (advancePoolSnapshot.value?.balance != null) {
    return Number(advancePoolSnapshot.value.balance)
  }
  if (account.value?.companyPoolBalance != null && account.value?.companyPoolBalance !== '') {
    return Number(account.value.companyPoolBalance)
  }
  if (shareDetail.value?.poolBalance != null && shareDetail.value?.poolBalance !== '') {
    return Number(shareDetail.value.poolBalance)
  }
  return 0
})
const companyPoolId = computed(() =>
  advancePoolSnapshot.value?.id
    ?? account.value?.companyPoolId
    ?? shareDetail.value?.poolId
    ?? undefined,
)
const companyPoolLabel = computed(() => {
  const poolName = advancePoolSnapshot.value?.name
    || account.value?.companyPoolName
    || shareDetail.value?.poolName
  const company = account.value?.companyName || shareDetail.value?.companyName
  if (poolName && company) return `${company} · ${poolName}`
  return poolName || company || '公司总账'
})

/** 退回公司：最多 min(预支未退回, 待分成+非分成) */
const returnableAdvance = computed(() => {
  const advance = Number(account.value?.advanceAmount || 0)
  const pending = Number(account.value?.sharePendingBalance || 0)
  const nonShare = Number(account.value?.nonShareBalance || 0)
  return Math.max(0, Number(Math.min(advance, pending + nonShare).toFixed(2)))
})

const reverseReturnTotal = computed(() =>
  Number((Number(reverseForm.sharePendingAmount || 0) + Number(reverseForm.nonShareAmount || 0)).toFixed(2)),
)

const remainderReturnTotal = computed(() =>
  Number((
    Number(remainderForm.sharePendingAmount || 0)
    + Number(remainderForm.nonShareAmount || 0)
    + Number(remainderForm.reserveHeldAmount || 0)
  ).toFixed(2)),
)

/** 打开转入弹窗时从资金池列表核对到的快照 */
const advancePoolSnapshot = ref<{ id?: number; name?: string; balance?: number } | null>(null)

function fmt(n?: number) {
  return Number(n || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function buildMemberShares(amount: number) {
  if (!shareForm.members.length || amount <= 0) return []
  let allocated = 0
  return shareForm.members.map((m, i) => {
    let share = 0
    if (i === shareForm.members.length - 1) share = Number((amount - allocated).toFixed(2))
    else {
      share = Number(((amount * Number(m.percent || 0)) / 100).toFixed(2))
      allocated += share
    }
    const user = users.value.find((u) => u.id === m.userId)
    return {
      name: user?.nickname || user?.username || (m.userId ? `用户${m.userId}` : '未选人员'),
      layer: m.layer,
      percent: Number(m.percent || 0),
      share,
    }
  })
}

function fmtTime(t?: string) {
  if (!t) return '—'
  return t.replace('T', ' ').slice(0, 16)
}

function bizLabel(v?: string) {
  return ({
    ADVANCE: '预支入账',
    EXPENSE: '项目支出',
    SETTLE: '项目分钱',
    RESERVE: '预留',
    ROLLBACK: '回退',
    REIMBURSE: '报销',
    SALARY: '工资',
    PAYOUT: '余额发放',
  } as any)[v || ''] || v || '—'
}

function bizTagType(v?: string) {
  return ({
    ADVANCE: 'success',
    EXPENSE: 'danger',
    SETTLE: 'primary',
    RESERVE: 'info',
    ROLLBACK: 'danger',
    REIMBURSE: 'warning',
    SALARY: 'warning',
    PAYOUT: 'primary',
  } as Record<string, string>)[v || ''] || 'info'
}

function scaleLabel(v?: string) {
  return ({ NORMAL: '常规', KEY: '重点', MAJOR: '重大' } as Record<string, string>)[v || ''] || v || '—'
}

function scaleTone(v?: string) {
  return ({ KEY: 'primary', MAJOR: 'warning' } as Record<string, string>)[v || ''] || 'info'
}

async function loadList() {
  const params: { companyId?: number; scale?: string } = {}
  if (filter.companyId != null) params.companyId = filter.companyId
  if (filter.scale) params.scale = filter.scale
  list.value = await bizApi.projectAccountList(params)
}

async function loadCompanies() {
  if (companies.value.length) return
  companies.value = await sysApi.myCompanies()
}

function resetFilter() {
  filter.companyId = undefined
  filter.scale = ''
  loadList()
}

async function ensureUsers() {
  if (!users.value.length) users.value = await sysApi.userList()
}

async function enter(row: any) {
  activeId.value = row.projectId
  shellParentId.value = null
  tab.value = 'overview'
  childAccounts.value = []
  syncDetailRoute(activeId.value)
  await loadDetail()
}

function enterFundConfig(row: any) {
  void router.push({ path: '/finance/project-share', query: { projectId: String(row.projectId) } })
}

async function enterChild(row: any) {
  shellParentId.value = activeId.value
  activeId.value = row.projectId
  tab.value = 'overview'
  childAccounts.value = []
  syncDetailRoute(activeId.value, shellParentId.value)
  await loadDetail()
}

function back() {
  if (shellParentId.value) {
    activeId.value = shellParentId.value
    shellParentId.value = null
    childAccounts.value = []
    syncDetailRoute(activeId.value)
    void loadDetail()
    return
  }
  activeId.value = null
  account.value = null
  shareDetail.value = null
  childAccounts.value = []
  syncDetailRoute(null)
}

async function loadDetail() {
  if (!activeId.value) return
  clearChartDrilldown()
  await ensureUsers()
  account.value = await bizApi.projectAccountDetail(activeId.value)
  if (account.value?.majorShell) {
    try {
      childAccounts.value = await bizApi.projectAccountChildren(activeId.value)
    } catch {
      childAccounts.value = []
    }
    shareDetail.value = null
    ledgers.value = []
    analyticsLedgers.value = []
    ledgerTotal.value = 0
    return
  }
  childAccounts.value = []
  try {
    shareDetail.value = await bizApi.projectShareDetail(activeId.value)
    applyFundSplitPercents(shareDetail.value?.settlePercent, shareDetail.value?.reservePercent)
    const members = shareDetail.value?.members || []
    shareForm.members = members.length
      ? members.map((m: any) => ({
          userId: m.userId,
          layer: m.layer || '',
          percent: Number(m.percent || 0),
          remark: m.remark || '',
        }))
      : [{ userId: undefined, layer: '执行', percent: 100, remark: '' }]
  } catch {
    shareDetail.value = null
  }
  const res = await bizApi.projectAccountLedger(activeId.value, ledgerQuery)
  ledgers.value = res.list
  ledgerTotal.value = res.total
  if (res.total > res.list.length) {
    const analytics = await bizApi.projectAccountLedger(activeId.value, {
      page: 1,
      pageSize: Math.min(500, Math.max(res.total, ledgerQuery.pageSize)),
    })
    analyticsLedgers.value = analytics.list || []
  } else {
    analyticsLedgers.value = res.list || []
  }
}

async function openRemainderDialog() {
  if (!activeId.value) return
  await loadDetail()
  const pending = Number(account.value?.sharePendingBalance || 0)
  const nonShare = Number(account.value?.nonShareBalance || 0)
  const held = Number(account.value?.reserveHeld || 0)
  if (pending + nonShare + held <= 0) {
    ElMessage.warning('当前没有可回公司的结余/预留')
    return
  }
  // 默认全 0，避免误把全部金额带上；需要整笔时点「全部填入」
  remainderForm.sharePendingAmount = 0
  remainderForm.nonShareAmount = 0
  remainderForm.reserveHeldAmount = 0
  remainderForm.remark = ''
  remainderDialog.value = true
}

function fillRemainderAll() {
  remainderForm.sharePendingAmount = Number(Number(account.value?.sharePendingBalance || 0).toFixed(2))
  remainderForm.nonShareAmount = Number(Number(account.value?.nonShareBalance || 0).toFixed(2))
  remainderForm.reserveHeldAmount = Number(Number(account.value?.reserveHeld || 0).toFixed(2))
}

async function submitRemainderReturn() {
  if (!activeId.value) return
  // 先失焦，避免 InputNumber 编辑中未提交就点确认，仍带着旧值
  ;(document.activeElement as HTMLElement | null)?.blur?.()
  await new Promise<void>((resolve) => setTimeout(resolve, 0))

  const shareAmt = Number(Number(remainderForm.sharePendingAmount || 0).toFixed(2))
  const nonShareAmt = Number(Number(remainderForm.nonShareAmount || 0).toFixed(2))
  const heldAmt = Number(Number(remainderForm.reserveHeldAmount || 0).toFixed(2))
  const total = Number((shareAmt + nonShareAmt + heldAmt).toFixed(2))
  if (total <= 0) {
    ElMessage.warning('请至少填写一笔回公司金额')
    return
  }
  const pending = Number(Number(account.value?.sharePendingBalance || 0).toFixed(2))
  const nonShare = Number(Number(account.value?.nonShareBalance || 0).toFixed(2))
  const held = Number(Number(account.value?.reserveHeld || 0).toFixed(2))
  if (shareAmt < 0 || nonShareAmt < 0 || heldAmt < 0) {
    ElMessage.warning('回公司金额不能为负')
    return
  }
  if (shareAmt > pending) {
    ElMessage.warning(`待分成最多可回 ¥${fmt(pending)}`)
    return
  }
  if (nonShareAmt > nonShare) {
    ElMessage.warning(`非分成最多可回 ¥${fmt(nonShare)}`)
    return
  }
  if (heldAmt > held) {
    ElMessage.warning(`预留占用最多可回 ¥${fmt(held)}`)
    return
  }
  const poolId = companyPoolId.value || shareDetail.value?.poolId || account.value?.companyPoolId
  if (!poolId) {
    ElMessage.warning('未找到该公司资金池')
    return
  }
  try {
    await ElMessageBox.confirm(
      `<div style="line-height:1.7">即将提交回公司：<br/>待分成 <b>¥${fmt(shareAmt)}</b><br/>非分成 <b>¥${fmt(nonShareAmt)}</b><br/>预留占用 <b>¥${fmt(heldAmt)}</b><br/>合计 <b style="color:#b45309">¥${fmt(total)}</b><br/><br/>请核对合计，确认无误后再提交。</div>`,
      '确认结余回公司',
      {
        type: 'warning',
        dangerouslyUseHTMLString: true,
        confirmButtonText: '确认提交',
        cancelButtonText: '返回修改',
      },
    )
  } catch {
    return
  }
  remainderSubmitting.value = true
  try {
    const approval = await workflowApi.submit({
      type: 'RESERVE_RETURN',
      title: `项目结余回公司 ¥${fmt(total)} · ${account.value?.projectName || ''}`,
      projectId: activeId.value,
      poolId,
      amount: total,
      remark: remainderForm.remark || '项目结余退回公司总账',
      payload: {
        sharePendingAmount: shareAmt,
        nonShareAmount: nonShareAmt,
        reserveHeldAmount: heldAmt,
      },
    })
    ElMessage.success(approvalFlowTip(approval, '已提交结余回公司审批'))
    remainderDialog.value = false
    remainderForm.sharePendingAmount = 0
    remainderForm.nonShareAmount = 0
    remainderForm.reserveHeldAmount = 0
    remainderForm.remark = ''
    await loadDetail()
  } catch {
    // 错误提示由 request 拦截器统一弹出
  } finally {
    remainderSubmitting.value = false
  }
}

async function openPeriodShareDialog() {
  if (!activeId.value) return
  if (periodSettlePreview.value.pending <= 0) {
    ElMessage.warning('待分成余额为 0，无法分成')
    return
  }
  if (!fundSplitOk.value) {
    ElMessage.warning('请先配置分成% + 预留% = 100%')
    void router.push({ path: '/finance/project-share', query: { projectId: String(activeId.value) } })
    return
  }
  if (!shareForm.members.length || shareForm.members.some((m) => !m.userId)) {
    ElMessage.warning('请先配置分成人员及比例')
    void router.push({ path: '/finance/project-share', query: { projectId: String(activeId.value) } })
    return
  }
  selectedPeriodMonth.value = formatPeriodMonth()
  periodShareDialog.value = true
}

async function confirmPeriodShare() {
  if (!activeId.value) return
  const month = String(selectedPeriodMonth.value || '').trim()
  if (!/^\d{4}-\d{2}$/.test(month)) {
    ElMessage.warning('请选择自然月')
    return
  }
  const preview = periodSettlePreview.value
  periodSharing.value = true
  try {
    const approval = await workflowApi.submit({
      type: 'PROJECT_SHARE_PERIOD',
      title: `自然月分成 · ${account.value?.projectName || ''} · ${month}`,
      projectId: activeId.value,
      poolId: shareDetail.value?.poolId || account.value?.companyPoolId,
      amount: preview.pending,
      remark: `自然月 ${month}：待分成按配置分成/预留执行分成`,
      payload: {
        periodMonth: month,
        settlePercent: shareForm.settlePercent,
        reservePercent: shareForm.reservePercent,
      },
    })
    ElMessage.success(approvalFlowTip(approval, '已提交自然月分成审批'))
    periodShareDialog.value = false
    await loadDetail()
  } finally {
    periodSharing.value = false
  }
}

function fundBucketBalance(fundType?: string) {
  if (fundType === 'NON_SHARE') return Number(account.value?.nonShareBalance || 0)
  return Number(account.value?.sharePendingBalance || 0)
}

function fundTypeLabel(v?: string) {
  return v === 'NON_SHARE' ? '非分成资金' : '待分成资金'
}

function accountLabel(row: any) {
  if (row.accountType === 'PROJECT') return '项目'
  if (row.accountType === 'POOL') return '公司'
  const who = row.userName || (row.userId ? `用户${row.userId}` : '')
  return who ? `个人 · ${who}` : '个人'
}

async function loadRelatedLedgers(row: any) {
  if (!activeId.value) return []
  const all = ledgers.value.length >= ledgerTotal.value
    ? ledgers.value
    : ((await bizApi.projectAccountLedger(activeId.value, { page: 1, pageSize: 200 })).list || [])

  // 项目分成 / 工资 / 报销：项目扣款 → 个人入账；个人入账 → 同批项目扣款
  if (['SETTLE', 'SALARY', 'REIMBURSE'].includes(row.bizType) && row.accountType === 'PROJECT') {
    return all.filter((x: any) => x.relatedId === row.id && x.accountType === 'WALLET')
  }
  if (['SETTLE', 'SALARY', 'REIMBURSE'].includes(row.bizType) && row.accountType === 'WALLET' && row.relatedId) {
    return all.filter((x: any) =>
      (x.id === row.relatedId && x.accountType === 'PROJECT')
      || (x.relatedId === row.relatedId && x.accountType === 'WALLET' && x.id !== row.id),
    )
  }
  if (row.relatedId) {
    return all.filter((x: any) => x.id === row.relatedId || (x.relatedId === row.relatedId && x.id !== row.id))
  }
  return all.filter((x: any) => x.relatedId === row.id)
}

async function openLedgerDetail(row: any) {
  ledgerDetail.value = row
  ledgerRelated.value = []
  ledgerDetailVisible.value = true
  ledgerDetailLoading.value = true
  try {
    ledgerRelated.value = await loadRelatedLedgers(row)
  } finally {
    ledgerDetailLoading.value = false
  }
}

async function openAdvanceDialog() {
  form.amount = 0
  form.remark = ''
  advancePoolSnapshot.value = null
  if (activeId.value) {
    // 1) 刷新账款详情（含公司资金池余额）
    try {
      account.value = await bizApi.projectAccountDetail(activeId.value)
    } catch {
      // ignore
    }
    // 2) 刷新资金配置（兼容旧字段 poolBalance）
    try {
      const detail = await bizApi.projectShareDetail(activeId.value)
      shareDetail.value = detail
      applyFundSplitPercents(detail?.settlePercent, detail?.reservePercent)
      const members = detail?.members || []
      shareForm.members = members.length
        ? members.map((m: any) => ({
            userId: m.userId,
            layer: m.layer || '',
            percent: Number(m.percent || 0),
            remark: m.remark || '',
          }))
        : shareForm.members
    } catch {
      // 无资金配置权限时仍可打开
    }
    // 3) 有总账/资金池权限时再用列表核对（与总账同口径）；无权限则用账款详情里的公司池余额，避免误报 403
    const canReadPool = userStore.hasPermission('finance:pool:list')
      || userStore.hasPermission('finance:ledger:list')
      || userStore.hasPermission('finance:ledger:add')
    if (canReadPool) {
      try {
        const companyId = Number(account.value?.companyId || shareDetail.value?.companyId)
        if (Number.isFinite(companyId) && companyId > 0) {
          const pools = (await bizApi.poolList()) || []
          const matched = pools.filter((p: any) => {
            if (Number(p.companyId) !== companyId) return false
            // status 空/未返回视为启用；仅显式 0 为禁用（与后端一致）
            return p.status == null || p.status === '' || Number(p.status) !== 0
          })
          const preferredId = Number(account.value?.companyPoolId || shareDetail.value?.poolId || 0)
          let pool = preferredId > 0 ? matched.find((p: any) => Number(p.id) === preferredId) : undefined
          if (!pool) pool = matched.find((p: any) => Number(p.isDefault) === 1)
          if (!pool) pool = matched[0]
          if (pool) {
            advancePoolSnapshot.value = {
              id: Number(pool.id),
              name: pool.name,
              balance: Number(pool.balance || 0),
            }
          }
        }
      } catch {
        // 列表失败时仍依赖账款详情
      }
    }
  }
  advanceDialog.value = true
}

async function submitAdvance() {
  if (!activeId.value || form.amount <= 0) {
    ElMessage.warning('请填写金额')
    return
  }
  const amount = Number(Number(form.amount).toFixed(2))
  const available = Number(Number(companyPoolBalance.value).toFixed(2))
  if (!(available > 0)) {
    ElMessage.warning('公司余额为 0，无法转入')
    return
  }
  if (amount > available) {
    ElMessage.warning(`不能超过公司余额 ¥${fmt(available)}`)
    return
  }
  if (!companyPoolId.value) {
    ElMessage.warning('未找到该公司资金池，请先在总账确认资金池配置')
    return
  }
  try {
    const approval = await workflowApi.submit({
      type: 'PROJECT_ADVANCE',
      title: `项目预支 · ${account.value?.projectName || ''}`,
      projectId: activeId.value,
      poolId: companyPoolId.value,
      amount,
      remark: form.remark,
    })
    ElMessage.success(approvalFlowTip(approval, '已提交：等审批通过后，钱从公司转到本项目'))
    advanceDialog.value = false
    form.amount = 0
    form.remark = ''
    await loadDetail()
  } catch {
    // 错误提示由 request 拦截器统一弹出
  }
}

async function openReverseAdvanceDialog() {
  if (activeId.value) {
    await loadDetail()
  }
  const available = returnableAdvance.value
  if (available <= 0) {
    ElMessage.warning('当前没有可退回公司的预支余额（受公司预支未退回与项目可用余额限制）')
    return
  }
  const advance = Number(account.value?.advanceAmount || 0)
  const pending = Number(account.value?.sharePendingBalance || 0)
  const nonShare = Number(account.value?.nonShareBalance || 0)
  // 默认优先扣非分成，剩余额度再扣待分成
  let remain = Number(Math.min(advance, pending + nonShare).toFixed(2))
  const fromNonShare = Number(Math.min(nonShare, remain).toFixed(2))
  remain = Number((remain - fromNonShare).toFixed(2))
  const fromPending = Number(Math.min(pending, remain).toFixed(2))
  reverseForm.nonShareAmount = fromNonShare
  reverseForm.sharePendingAmount = fromPending
  reverseForm.remark = ''
  reverseAdvanceDialog.value = true
}

async function submitReverseAdvance() {
  if (!activeId.value) return
  const shareAmt = Number(Number(reverseForm.sharePendingAmount || 0).toFixed(2))
  const nonShareAmt = Number(Number(reverseForm.nonShareAmount || 0).toFixed(2))
  const total = Number((shareAmt + nonShareAmt).toFixed(2))
  if (total <= 0) {
    ElMessage.warning('请至少填写一笔退回金额')
    return
  }
  const pending = Number(Number(account.value?.sharePendingBalance || 0).toFixed(2))
  const nonShare = Number(Number(account.value?.nonShareBalance || 0).toFixed(2))
  const advance = Number(Number(account.value?.advanceAmount || 0).toFixed(2))
  if (shareAmt < 0 || nonShareAmt < 0) {
    ElMessage.warning('退回金额不能为负')
    return
  }
  if (shareAmt > pending) {
    ElMessage.warning(`待分成最多可退 ¥${fmt(pending)}`)
    return
  }
  if (nonShareAmt > nonShare) {
    ElMessage.warning(`非分成最多可退 ¥${fmt(nonShare)}`)
    return
  }
  if (total > advance) {
    ElMessage.warning(`合计不能超过公司预支未退回 ¥${fmt(advance)}`)
    return
  }
  const poolId = companyPoolId.value || shareDetail.value?.poolId || account.value?.companyPoolId
  if (!poolId) {
    ElMessage.warning('未找到该公司资金池')
    return
  }
  try {
    const approval = await workflowApi.submit({
      type: 'PROJECT_ADVANCE_RETURN',
      title: `退回公司 · ${account.value?.projectName || ''}`,
      projectId: activeId.value,
      poolId,
      amount: total,
      remark: reverseForm.remark || '公司预支资金退回公司总账',
      payload: {
        sharePendingAmount: shareAmt,
        nonShareAmount: nonShareAmt,
      },
    })
    ElMessage.success(approvalFlowTip(approval, '已提交：等审批通过后，资金退回公司总账'))
    reverseAdvanceDialog.value = false
    reverseForm.sharePendingAmount = 0
    reverseForm.nonShareAmount = 0
    reverseForm.remark = ''
    await loadDetail()
  } catch {
    // 错误提示由 request 拦截器统一弹出
  }
}

function payMethodLabel(m: any) {
  const type = m.methodTypeLabel || ({ BANK: '银行卡', ALIPAY: '支付宝', WECHAT: '微信' } as any)[m.methodType] || m.methodType
  const name = m.accountName ? `${m.accountName} · ` : ''
  const bank = m.methodType === 'BANK' && m.bankName ? `（${m.bankName}）` : ''
  return `${type} · ${name}${m.accountNo || ''}${bank}`
}

async function loadMyPayMethods() {
  try {
    myPayMethods.value = (await bizApi.myPayMethods()) || []
  } catch {
    myPayMethods.value = []
  }
}

async function openPayDialog(kind: 'reimburse' | 'salary') {
  form.amount = 0
  form.remark = ''
  // 默认待分成；若待分成为空且非分成有余额，自动切到非分成，避免预支后无法报销
  const pending = Number(account.value?.sharePendingBalance || 0)
  const nonShare = Number(account.value?.nonShareBalance || 0)
  form.fundType = pending > 0 || nonShare <= 0 ? 'SHARE_PENDING' : 'NON_SHARE'
  form.payMethodId = undefined
  voucherFiles.value = []
  await loadMyPayMethods()
  const def = myPayMethods.value.find((m) => Number(m.isDefault) === 1) || myPayMethods.value[0]
  form.payMethodId = def?.id != null ? Number(def.id) : undefined
  if (!myPayMethods.value.length) {
    ElMessage.warning('请先在员工档案中配置个人收款方式，否则无法提交')
  }
  if (kind === 'reimburse') reimburseDialog.value = true
  else salaryDialog.value = true
}

function fileUrl(file: any) {
  if (file?.id != null) return `/api/file/preview/${file.id}`
  const url = String(file?.url || '')
  if (url.includes('/api/file/download/')) {
    const id = url.split('/').pop()
    if (id) return `/api/file/preview/${id}`
  }
  return url || ''
}

async function onUploadPayVoucher(options: any) {
  uploadingVoucher.value = true
  try {
    const file = await workflowApi.uploadVoucher(options.file)
    voucherFiles.value.push(file)
    ElMessage.success('凭证已上传')
    options.onSuccess?.(file)
  } catch (e: any) {
    ElMessage.error(e.message || '上传失败')
    options.onError?.(e)
  } finally {
    uploadingVoucher.value = false
  }
}

function removePayVoucher(index: number) {
  voucherFiles.value.splice(index, 1)
}

async function submitPay(type: 'REIMBURSE_PROJECT' | 'SALARY_APPLY') {
  if (!activeId.value || form.amount <= 0) {
    ElMessage.warning('请填写金额')
    return
  }
  if (!form.payMethodId) {
    ElMessage.warning('请选择收款方式，便于财务线下打款')
    return
  }
  if (type === 'REIMBURSE_PROJECT' && !voucherFiles.value.length) {
    ElMessage.warning('请上传发票/凭证')
    return
  }
  const bucket = fundBucketBalance(form.fundType)
  if (bucket <= 0) {
    ElMessage.warning(`${fundTypeLabel(form.fundType)}余额为 0，请换资金池或先入账`)
    return
  }
  if (form.amount > bucket) {
    ElMessage.warning(`不能超过${fundTypeLabel(form.fundType)} ¥${fmt(bucket)}（不可跨池拆扣）`)
    return
  }
  const approval = await workflowApi.submit({
    type,
    title: `${type === 'SALARY_APPLY' ? '发工资' : '项目报销'} · ${account.value?.projectName || ''}`,
    projectId: activeId.value,
    amount: form.amount,
    remark: form.remark,
    voucherFileIds: voucherFiles.value.length ? voucherFiles.value.map((f) => f.id) : undefined,
    payload: { payMethodId: form.payMethodId, fundType: form.fundType || 'SHARE_PENDING' },
  })
  const moneyHint = '审批通过并确认到账后：从所选资金池转入你的个人钱包，公司总账不变'
  ElMessage.success(`${approvalFlowTip(approval)}。${moneyHint}`)
  reimburseDialog.value = false
  salaryDialog.value = false
  form.amount = 0
  form.remark = ''
  form.payMethodId = undefined
  form.fundType = 'SHARE_PENDING'
  voucherFiles.value = []
}

onMounted(async () => {
  await loadCompanies()
  await loadList()
  const restoredId = routeId(route.query.projectId)
  if (!restoredId) return
  activeId.value = restoredId
  shellParentId.value = routeId(route.query.shellId)
  tab.value = 'overview'
  try {
    await loadDetail()
  } catch {
    activeId.value = null
    shellParentId.value = null
    syncDetailRoute(null)
    ElMessage.warning('原项目账款已无法访问，已返回项目列表')
  }
})
</script>

<template>
  <div class="page-stack">
    <template v-if="!activeId">
      <div class="page-top">
        <div class="page-top__main">
          <p class="page-desc">待分成 / 非分成双池；支出默认扣待分成；自然月走「自然月分成」审批。常规项目不进入本页。</p>
        </div>
      </div>
      <div class="page-card filter-card">
        <el-form :inline="true" class="filter-form" @submit.prevent>
          <el-form-item label="公司">
            <el-select v-model="filter.companyId" clearable filterable placeholder="全部公司" style="width: 200px" @change="loadList">
              <el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="重要度">
            <el-select v-model="filter.scale" clearable placeholder="重点+重大" style="width: 140px" @change="loadList">
              <el-option label="重点" value="KEY" />
              <el-option label="重大" value="MAJOR" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button @click="resetFilter">重置</el-button>
          </el-form-item>
        </el-form>
      </div>
      <div v-if="list.length" class="acc-grid">
        <article v-for="row in list" :key="row.projectId" class="acc-card">
          <div class="acc-card__head">
            <div class="acc-card__title-row">
              <h4>{{ row.projectName || `项目#${row.projectId}` }}</h4>
              <el-tag v-if="row.scale" :type="scaleTone(row.scale)" size="small" effect="plain">{{ scaleLabel(row.scale) }}</el-tag>
            </div>
            <p>{{ row.companyName || '—' }} · 负责人 {{ row.ownerName || '—' }}</p>
          </div>
          <div class="acc-card__balance">
            <div>
              <span>项目结余</span>
              <b>¥ {{ fmt(row.balance) }}</b>
            </div>
            <el-icon class="acc-card__icon" :size="44"><Wallet /></el-icon>
          </div>
          <div class="acc-card__meta">
            <div><span>待分成</span><b>¥ {{ fmt(row.sharePendingBalance) }}</b></div>
            <div><span>非分成</span><b>¥ {{ fmt(row.nonShareBalance) }}</b></div>
            <div><span>预留占用</span><b>¥ {{ fmt(row.reserveHeld) }}</b></div>
          </div>
          <div class="acc-card__actions">
            <el-button @click="enter(row)">查看详情</el-button>
            <el-button type="primary" @click="enterFundConfig(row)">资金配置</el-button>
          </div>
        </article>
      </div>
      <el-empty v-else description="暂无项目账款" />
    </template>

    <template v-else>
      <template v-if="account">
        <div class="page-top">
          <div class="page-top__main">
            <el-button @click="back">返回列表</el-button>
            <div class="project-heading">
              <div class="project-heading__title">
                <h2 class="detail-name">{{ account.projectName }}</h2>
                <el-tag v-if="account.scale" :type="scaleTone(account.scale)" effect="plain">{{ scaleLabel(account.scale) }}</el-tag>
              </div>
              <p class="page-desc">
                {{ account.companyName || '所属公司未设置' }}
                <span class="meta-separator">·</span>
                负责人 {{ account.ownerName || shareDetail?.ownerName || '—' }}
              </p>
            </div>
          </div>
          <div class="page-actions">
            <template v-if="!account.majorShell">
              <div class="action-group">
                <span class="action-group__label">资金调拨</span>
                <el-button type="primary" @click="openAdvanceDialog">从公司转入</el-button>
                <el-button
                  :disabled="returnableAdvance <= 0"
                  @click="openReverseAdvanceDialog"
                >退回公司</el-button>
              </div>
              <div class="action-group">
                <span class="action-group__label">业务支出</span>
                <el-button @click="openPayDialog('reimburse')">申请报销</el-button>
                <el-button @click="openPayDialog('salary')">申请发工资</el-button>
              </div>
              <div class="action-group">
                <span class="action-group__label">结算</span>
                <el-button
                  type="success"
                  :loading="periodSharing"
                  :disabled="Number(account.sharePendingBalance || 0) <= 0"
                  @click="openPeriodShareDialog"
                >自然月分成</el-button>
                <el-button
                  v-if="Number(account.balance) > 0 || Number(account.reserveHeld || 0) > 0"
                  @click="openRemainderDialog"
                >结余回公司</el-button>
              </div>
            </template>
            <el-tag v-else type="warning" effect="plain">重大项目汇总（请进入小项目动账）</el-tag>
          </div>
        </div>

        <section class="finance-overview" aria-label="项目资金概览">
          <header class="section-head">
            <div>
              <h3>资金概览</h3>
              <p>余额、资金分桶与累计支出</p>
            </div>
            <el-tag :type="balanceMatched ? 'success' : 'danger'" effect="light">
              {{ balanceMatched ? '余额已勾稽' : `余额差额 ¥${fmt(Math.abs(balanceVariance))}` }}
            </el-tag>
          </header>
        <div class="metric-grid">
          <div class="metric-card metric-card--indigo metric-card--primary">
            <div class="metric-body">
              <div class="metric-label">当前可用余额</div>
              <div class="metric-value">¥ {{ fmt(account.balance) }}</div>
              <div class="metric-hint">项目当前可动用资金</div>
            </div>
            <el-icon class="metric-glyph" :size="48"><Wallet /></el-icon>
          </div>
          <div class="metric-card metric-card--violet">
            <div class="metric-body">
              <div class="metric-label">待分成</div>
              <div class="metric-value sm">¥ {{ fmt(account.sharePendingBalance) }}</div>
              <div class="metric-hint">月末分成基数</div>
            </div>
            <el-icon class="metric-glyph" :size="48"><Coin /></el-icon>
          </div>
          <div class="metric-card metric-card--cyan">
            <div class="metric-body">
              <div class="metric-label">非分成</div>
              <div class="metric-value sm">¥ {{ fmt(account.nonShareBalance) }}</div>
              <div class="metric-hint">不参与月度分成</div>
            </div>
            <el-icon class="metric-glyph" :size="48"><OfficeBuilding /></el-icon>
          </div>
          <div class="metric-card metric-card--amber">
            <div class="metric-body">
              <div class="metric-label">累计业务支出</div>
              <div class="metric-value sm">¥ {{ fmt(account.expenseAmount) }}</div>
              <div class="metric-hint">工资、报销及项目支出</div>
            </div>
            <el-icon class="metric-glyph" :size="48"><Ticket /></el-icon>
          </div>
          <div class="metric-card metric-card--slate">
            <div class="metric-body">
              <div class="metric-label">{{ account.majorShell ? '预留占用（汇总）' : '预留占用' }}</div>
              <div class="metric-value sm">¥ {{ fmt(account.reserveHeld) }}</div>
              <div v-if="!account.majorShell" class="metric-hint">结束时随结余回公司</div>
            </div>
            <el-icon class="metric-glyph" :size="48"><Box /></el-icon>
          </div>
        </div>
        <div class="balance-equation" :class="{ 'balance-equation--warning': !balanceMatched }">
          <template v-if="account.majorShell">
            <div class="balance-equation__copy">重大项目仅展示小项目汇总结余；请进入小项目进行资金操作。</div>
          </template>
          <template v-else>
            <div class="equation-item"><span>待分成</span><b>¥{{ fmt(account.sharePendingBalance) }}</b></div>
            <span class="equation-sign">+</span>
            <div class="equation-item"><span>非分成</span><b>¥{{ fmt(account.nonShareBalance) }}</b></div>
            <span class="equation-sign">=</span>
            <div class="equation-item"><span>分桶合计</span><b>¥{{ fmt(balanceBreakdown) }}</b></div>
            <span class="equation-sign">对比</span>
            <div class="equation-item equation-item--strong"><span>当前可用</span><b>¥{{ fmt(account.balance) }}</b></div>
            <div v-if="!balanceMatched" class="balance-alert">分桶合计与当前可用不一致，请核查待分成/非分成扣减是否同步。</div>
          </template>
        </div>
        <p v-if="!account.majorShell" class="rule-tip">支出默认扣待分成；待分成按配置走自然月分成审批，预留占用在项目结束时随结余退回公司。</p>
        </section>

        <section v-if="!account.majorShell" class="analytics-section" aria-label="项目财务统计">
          <header class="section-head analytics-head">
            <div>
              <h3>财务统计</h3>
              <p>
                当前范围 {{ filteredAnalyticsLedgers.length }} 条流水
                <template v-if="filteredAnalyticsLedgers.length !== analyticsLedgers.length">（全部 {{ analyticsLedgers.length }} 条）</template>
                ，最多加载最近 500 条
              </p>
            </div>
            <div class="analytics-controls">
              <el-date-picker
                v-model="analyticsTimeRange"
                type="daterange"
                value-format="YYYY-MM-DD"
                format="YYYY-MM-DD"
                range-separator="至"
                start-placeholder="开始日期"
                end-placeholder="结束日期"
                unlink-panels
                clearable
                :shortcuts="analyticsDateShortcuts"
                style="width: 260px"
              />
              <el-tag effect="plain">项目账户口径</el-tag>
            </div>
          </header>
          <div class="analytics-grid">
            <article class="chart-card chart-card--trend">
              <header class="chart-card__head">
                <div class="chart-title">
                  <span class="chart-icon chart-icon--blue"><el-icon><TrendCharts /></el-icon></span>
                  <div><h4>月度收支趋势</h4><p>判断资金流入与消耗节奏</p></div>
                </div>
                <div class="chart-legend"><span class="legend-in">流入</span><span class="legend-out">流出</span></div>
              </header>
              <div v-if="monthlyCashFlow.length" class="cash-chart" role="img" aria-label="最近六个月项目账户流入流出柱状图">
                <div v-for="item in monthlyCashFlow" :key="item.month" class="cash-column">
                  <div class="cash-bars">
                    <button class="cash-bar cash-bar--in" type="button" :style="{ height: `${Math.max(3, item.inflow / monthlyCashMax * 100)}%` }" :title="`筛选 ${item.month} 流入 ¥${fmt(item.inflow)}`" :aria-label="`筛选 ${item.month} 流入流水，金额 ${fmt(item.inflow)} 元`" @click="drillMonthly(item.month, 'IN')"></button>
                    <button class="cash-bar cash-bar--out" type="button" :style="{ height: `${Math.max(3, item.outflow / monthlyCashMax * 100)}%` }" :title="`筛选 ${item.month} 流出 ¥${fmt(item.outflow)}`" :aria-label="`筛选 ${item.month} 流出流水，金额 ${fmt(item.outflow)} 元`" @click="drillMonthly(item.month, 'OUT')"></button>
                  </div>
                  <span>{{ item.month.slice(5) }}月</span>
                  <small>净 {{ item.inflow - item.outflow >= 0 ? '+' : '-' }}¥{{ fmt(Math.abs(item.inflow - item.outflow)) }}</small>
                </div>
              </div>
              <el-empty v-else :image-size="54" description="暂无趋势数据" />
            </article>

            <article class="chart-card">
              <header class="chart-card__head">
                <div class="chart-title">
                  <span class="chart-icon chart-icon--amber"><el-icon><PieChart /></el-icon></span>
                  <div><h4>支出构成</h4><p>识别主要资金消耗类型</p></div>
                </div>
              </header>
              <div v-if="expenseBreakdown.length" class="donut-layout">
                <div class="donut-chart" :style="expenseDonutStyle" role="img" :aria-label="`支出合计 ${fmt(expenseTotal)} 元`">
                  <div class="donut-center"><span>支出合计</span><b>¥{{ fmt(expenseTotal) }}</b></div>
                </div>
                <div class="donut-legend">
                  <button v-for="item in expenseBreakdown" :key="item.key" class="donut-legend__item" type="button" :aria-label="`筛选${item.label}流水`" @click="drillExpense(item)">
                    <i :style="{ background: item.color }"></i>
                    <span>{{ item.label }}</span>
                    <b>{{ expenseTotal ? (item.value / expenseTotal * 100).toFixed(1) : '0.0' }}%</b>
                    <small>¥{{ fmt(item.value) }}</small>
                  </button>
                </div>
              </div>
              <el-empty v-else :image-size="54" description="暂无支出数据" />
            </article>

            <article class="chart-card">
              <header class="chart-card__head">
                <div class="chart-title">
                  <span class="chart-icon chart-icon--violet"><el-icon><DataAnalysis /></el-icon></span>
                  <div><h4>资金流向</h4><p>比较转入与各类资金去向</p></div>
                </div>
              </header>
              <div v-if="fundFlowRanking.length" class="flow-ranking" role="img" aria-label="项目资金流向金额排行">
                <button v-for="item in fundFlowRanking" :key="item.key" class="flow-row" type="button" :aria-label="`筛选${item.label}流水`" @click="drillFundFlow(item)">
                  <div class="flow-row__label"><span>{{ item.label }}</span><b>¥{{ fmt(item.value) }}</b></div>
                  <div class="flow-track"><i :style="{ width: `${Math.max(2, item.value / fundFlowMax * 100)}%`, background: item.color }"></i></div>
                </button>
              </div>
              <el-empty v-else :image-size="54" description="暂无流向数据" />
            </article>
          </div>
        </section>

        <div v-if="account.majorShell" class="page-card" style="margin-bottom: 16px">
          <h3 style="margin: 0 0 12px; font-size: 16px">小项目账款</h3>
          <div v-if="childAccounts.length" class="acc-grid">
            <article
              v-for="row in childAccounts"
              :key="row.projectId"
              class="acc-card acc-card--clickable"
              role="button"
              tabindex="0"
              @click="enterChild(row)"
              @keyup.enter="enterChild(row)"
            >
              <div class="acc-card__head">
                <h4>{{ row.projectName || `项目#${row.projectId}` }}</h4>
                <p>负责人 {{ row.ownerName || '—' }}</p>
              </div>
              <div class="acc-card__balance">
                <div>
                  <span>项目结余</span>
                  <b>¥ {{ fmt(row.balance) }}</b>
                </div>
              </div>
            </article>
          </div>
          <el-empty v-else description="暂无小项目，请先在项目管理中创建" />
        </div>

        <div v-if="!account.majorShell" ref="ledgerSectionRef" class="page-card ledger-section">
        <el-tabs v-model="tab" class="tabs">
          <el-tab-pane label="项目流水" name="overview">
            <div class="ledger-toolbar">
              <div class="ledger-toolbar__filters">
                <el-input v-model="ledgerFilter.keyword" clearable placeholder="搜索摘要、单号或人员" style="width: 240px" />
                <el-select v-model="ledgerFilter.bizType" clearable placeholder="业务类型" style="width: 140px">
                  <el-option label="预支入账" value="ADVANCE" />
                  <el-option label="项目支出" value="EXPENSE" />
                  <el-option label="报销" value="REIMBURSE" />
                  <el-option label="工资" value="SALARY" />
                  <el-option label="余额发放" value="PAYOUT" />
                  <el-option label="项目分钱" value="SETTLE" />
                  <el-option label="预留" value="RESERVE" />
                </el-select>
                <el-select v-model="ledgerFilter.direction" clearable placeholder="收支方向" style="width: 120px">
                  <el-option label="流入" value="IN" />
                  <el-option label="流出" value="OUT" />
                </el-select>
                <el-select v-model="ledgerFilter.accountType" clearable placeholder="账户范围" style="width: 130px">
                  <el-option label="项目账户" value="PROJECT" />
                  <el-option label="公司账户" value="POOL" />
                  <el-option label="个人钱包" value="WALLET" />
                </el-select>
              </div>
              <div class="ledger-summary">
                <span>当前页项目账户 {{ currentPageProjectFlow.count }} 笔</span>
                <span class="in">流入 ¥{{ fmt(currentPageProjectFlow.inflow) }}</span>
                <span class="out">流出 ¥{{ fmt(currentPageProjectFlow.outflow) }}</span>
                <b>净额 {{ currentPageProjectFlow.net >= 0 ? '+' : '' }}¥{{ fmt(currentPageProjectFlow.net) }}</b>
              </div>
            </div>
            <div v-if="chartDrilldown" class="chart-filter-notice" role="status">
              <span>图表筛选：<b>{{ chartDrilldown.label }}</b></span>
              <span>以下仅显示匹配的项目账户流水</span>
              <el-button link type="primary" @click="clearChartDrilldown">清除筛选</el-button>
            </div>
            <el-table :data="visibleLedgers" stripe empty-text="暂无符合条件的流水">
              <el-table-column label="时间" width="150">
                <template #default="{ row }">{{ fmtTime(row.occurTime) }}</template>
              </el-table-column>
              <el-table-column label="业务类型" width="110">
                <template #default="{ row }">
                  <el-tag :type="bizTagType(row.bizType)" size="small">{{ bizLabel(row.bizType) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="title" label="业务摘要" min-width="210" show-overflow-tooltip />
              <el-table-column label="金额" width="120" align="right">
                <template #default="{ row }">
                  <span :class="Number(row.amount) >= 0 ? 'in' : 'out'">
                    {{ Number(row.amount) >= 0 ? '+' : '' }}{{ fmt(row.amount) }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="记账账户" width="140" show-overflow-tooltip>
                <template #default="{ row }">
                  {{ accountLabel(row) }}
                </template>
              </el-table-column>
              <el-table-column label="业务单号" prop="bizNo" width="150">
                <template #default="{ row }">
                  <el-tooltip
                    :content="row.bizNo || '—'"
                    placement="top-end"
                    :show-after="250"
                    :disabled="!row.bizNo"
                    :popper-options="{ strategy: 'fixed' }"
                    popper-class="biz-no-tooltip"
                  >
                    <span class="biz-no-cell">{{ row.bizNo || '—' }}</span>
                  </el-tooltip>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="80" align="center" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" @click="openLedgerDetail(row)">详情</el-button>
                </template>
              </el-table-column>
            </el-table>
            <div v-if="!chartDrilldown && ledgerTotal > ledgerQuery.pageSize" class="page-footer">
              <el-pagination
                v-model:current-page="ledgerQuery.page"
                :page-size="ledgerQuery.pageSize"
                :total="ledgerTotal"
                layout="total, prev, pager, next"
                @current-change="loadDetail"
              />
            </div>
          </el-tab-pane>

        </el-tabs>
      </div>
      </template>
    </template>

    <el-drawer v-model="ledgerDetailVisible" title="流水详情" size="480px" append-to-body>
      <template v-if="ledgerDetail">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="时间">{{ fmtTime(ledgerDetail.occurTime) }}</el-descriptions-item>
          <el-descriptions-item label="编号">{{ ledgerDetail.bizNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="类型">{{ bizLabel(ledgerDetail.bizType) }}</el-descriptions-item>
          <el-descriptions-item label="摘要">{{ ledgerDetail.title || '—' }}</el-descriptions-item>
          <el-descriptions-item label="账户">{{ accountLabel(ledgerDetail) }}</el-descriptions-item>
          <el-descriptions-item label="金额">
            <span :class="Number(ledgerDetail.amount) >= 0 ? 'in' : 'out'">
              {{ Number(ledgerDetail.amount) >= 0 ? '+' : '' }}{{ fmt(ledgerDetail.amount) }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="变动前">
            {{ ledgerDetail.beforeBalance != null ? `¥ ${fmt(ledgerDetail.beforeBalance)}` : '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="变动后">
            {{ ledgerDetail.afterBalance != null ? `¥ ${fmt(ledgerDetail.afterBalance)}` : '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="备注">{{ ledgerDetail.remark || '—' }}</el-descriptions-item>
          <el-descriptions-item v-if="ledgerDetail.approvalId" label="审批单">
            #{{ ledgerDetail.approvalId }}
          </el-descriptions-item>
        </el-descriptions>

        <div v-loading="ledgerDetailLoading" class="related-block">
          <h4 class="related-title">关联流水</h4>
          <p class="related-tip">
            <template v-if="['SETTLE', 'SALARY', 'REIMBURSE'].includes(ledgerDetail.bizType) && ledgerDetail.accountType === 'PROJECT'">
              本笔从项目扣出后，进入以下个人钱包：
            </template>
            <template v-else-if="['SETTLE', 'SALARY', 'REIMBURSE'].includes(ledgerDetail.bizType) && ledgerDetail.accountType === 'WALLET'">
              同一次动账的其它流水：
            </template>
            <template v-else>
              与本笔成对的进出账：
            </template>
          </p>
          <div v-if="ledgerRelated.length" class="related-list">
            <div v-for="r in ledgerRelated" :key="r.id" class="related-row">
              <div class="related-main">
                <span class="related-who">{{ accountLabel(r) }}</span>
                <span class="related-sub">{{ bizLabel(r.bizType) }} · {{ r.bizNo || r.title || '' }}</span>
              </div>
              <b :class="Number(r.amount) >= 0 ? 'in' : 'out'">
                {{ Number(r.amount) >= 0 ? '+' : '' }}{{ fmt(r.amount) }}
              </b>
            </div>
          </div>
          <div v-else class="related-empty">没有关联流水</div>
        </div>
      </template>
    </el-drawer>

    <el-dialog v-model="advanceDialog" title="从公司转入本项目" width="480px" :close-on-click-modal="false">
      <div class="dialog-box">
        <p>把公司总账的钱拨到这个项目的<strong>非分成资金</strong>，不参与自然月分成；之后可报销、发工资。</p>
        <ul>
          <li>公司余额：<b>¥{{ fmt(companyPoolBalance) }}</b>
            <span v-if="companyPoolLabel" class="hint">（{{ companyPoolLabel }}）</span>
          </li>
          <li>现在非分成：<b>¥{{ fmt(account?.nonShareBalance) }}</b> · 总可用：<b>¥{{ fmt(account?.balance) }}</b></li>
          <li>审批通过后：公司总账减少，本项目非分成增加。</li>
        </ul>
      </div>
      <el-form label-width="90px">
        <el-form-item label="转入金额" required>
          <el-input-number
            v-model="form.amount"
            :min="0.01"
            :precision="2"
            :max="companyPoolBalance > 0 ? companyPoolBalance : undefined"
            style="width: 200px"
          />
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="form.remark" placeholder="例如：项目启动拨款" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="advanceDialog = false">取消</el-button>
        <el-button type="primary" @click="submitAdvance">提交审批</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="remainderDialog" title="结余回公司" width="520px" :close-on-click-modal="false">
      <div class="dialog-box">
        <p>把项目结余退回公司总账；请分别填写金额（默认不填，避免误退全部）。也可点「全部填入」后改数。</p>
        <ul>
          <li>待分成：<b>¥{{ fmt(account?.sharePendingBalance) }}</b> · 非分成：<b>¥{{ fmt(account?.nonShareBalance) }}</b></li>
          <li>预留占用：<b>¥{{ fmt(account?.reserveHeld) }}</b></li>
          <li>审批通过后：按下方填写金额扣减，公司总账增加。</li>
        </ul>
      </div>
      <div style="margin-bottom: 12px">
        <el-button size="small" @click="fillRemainderAll">全部填入</el-button>
        <el-button size="small" @click="remainderForm.sharePendingAmount = 0; remainderForm.nonShareAmount = 0; remainderForm.reserveHeldAmount = 0">全部清零</el-button>
      </div>
      <el-form label-width="110px">
        <el-form-item label="待分成回公司">
          <el-input-number
            v-model="remainderForm.sharePendingAmount"
            :min="0"
            :precision="2"
            :value-on-clear="0"
            :controls="false"
            :max="Number(account?.sharePendingBalance || 0)"
            style="width: 200px"
          />
          <div class="hint">可用 ¥{{ fmt(account?.sharePendingBalance) }}</div>
        </el-form-item>
        <el-form-item label="非分成回公司">
          <el-input-number
            v-model="remainderForm.nonShareAmount"
            :min="0"
            :precision="2"
            :value-on-clear="0"
            :controls="false"
            :max="Number(account?.nonShareBalance || 0)"
            style="width: 200px"
          />
          <div class="hint">可用 ¥{{ fmt(account?.nonShareBalance) }}</div>
        </el-form-item>
        <el-form-item label="预留占用回公司">
          <el-input-number
            v-model="remainderForm.reserveHeldAmount"
            :min="0"
            :precision="2"
            :value-on-clear="0"
            :controls="false"
            :max="Number(account?.reserveHeld || 0)"
            style="width: 200px"
          />
          <div class="hint">可用 ¥{{ fmt(account?.reserveHeld) }}</div>
        </el-form-item>
        <el-form-item label="合计">
          <b>¥{{ fmt(remainderReturnTotal) }}</b>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="remainderForm.remark" placeholder="例如：项目结束结余回笼" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="remainderDialog = false">取消</el-button>
        <el-button type="primary" :loading="remainderSubmitting" @click="submitRemainderReturn">确认提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="periodShareDialog" title="自然月分成" width="560px" :close-on-click-modal="false">
      <div class="dialog-box">
        <p>按当前资金配置，把待分成余额拆成「分成进个人钱包」和「预留占用」。同一项目同一自然月只能提交一次。</p>
        <ul>
          <li>待分成总额：<b>¥{{ fmt(periodSettlePreview.pending) }}</b></li>
          <li>
            分成 {{ Number(shareForm.settlePercent || 0).toFixed(2) }}% →
            <b>¥{{ fmt(periodSettlePreview.settleAmt) }}</b>
            · 预留 {{ Number(shareForm.reservePercent || 0).toFixed(2) }}% →
            <b>¥{{ fmt(periodSettlePreview.reserveAmt) }}</b>
          </li>
        </ul>
      </div>
      <el-form label-width="90px">
        <el-form-item label="自然月" required>
          <el-date-picker
            v-model="selectedPeriodMonth"
            type="month"
            value-format="YYYY-MM"
            placeholder="选择月份"
            style="width: 200px"
          />
        </el-form-item>
      </el-form>
      <div v-if="periodMemberPreview.length" class="period-preview">
        <div class="field-label">分成明细预览</div>
        <div
          v-for="item in periodMemberPreview"
          :key="String(item.name) + item.layer"
          class="period-preview-row"
        >
          <span>{{ item.name }}<em>{{ item.layer || '成员' }} · {{ Number(item.percent).toFixed(2) }}%</em></span>
          <b>+¥ {{ fmt(item.share) }}</b>
        </div>
      </div>
      <template #footer>
        <el-button @click="periodShareDialog = false">取消</el-button>
        <el-button type="primary" :loading="periodSharing" @click="confirmPeriodShare">确认提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reverseAdvanceDialog" title="退回公司" width="520px" :close-on-click-modal="false">
      <div class="dialog-box">
        <p>把本项目<strong>公司预支未退回</strong>的资金退回公司总账；可从待分成、非分成分别填写，也可组合。</p>
        <ul>
          <li>公司预支未退回：<b>¥{{ fmt(account?.advanceAmount) }}</b></li>
          <li>待分成：<b>¥{{ fmt(account?.sharePendingBalance) }}</b> · 非分成：<b>¥{{ fmt(account?.nonShareBalance) }}</b></li>
          <li>本次最多可退：<b>¥{{ fmt(returnableAdvance) }}</b>（预支未退回与项目可用取较小值）</li>
          <li>审批通过后：所选资金池减少，公司总账增加。</li>
        </ul>
      </div>
      <el-form label-width="110px">
        <el-form-item label="待分成退回">
          <el-input-number
            v-model="reverseForm.sharePendingAmount"
            :min="0"
            :precision="2"
            :max="Number(account?.sharePendingBalance || 0)"
            style="width: 200px"
          />
          <div class="hint">可用 ¥{{ fmt(account?.sharePendingBalance) }}</div>
        </el-form-item>
        <el-form-item label="非分成退回">
          <el-input-number
            v-model="reverseForm.nonShareAmount"
            :min="0"
            :precision="2"
            :max="Number(account?.nonShareBalance || 0)"
            style="width: 200px"
          />
          <div class="hint">可用 ¥{{ fmt(account?.nonShareBalance) }}</div>
        </el-form-item>
        <el-form-item label="合计">
          <b>¥{{ fmt(reverseReturnTotal) }}</b>
          <span class="hint" style="margin-left: 8px">须 ≤ 预支未退回 ¥{{ fmt(account?.advanceAmount) }}</span>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="reverseForm.remark" placeholder="例如：多余预支退回" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reverseAdvanceDialog = false">取消</el-button>
        <el-button type="primary" @click="submitReverseAdvance">提交审批</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reimburseDialog" title="申请项目报销" width="480px" :close-on-click-modal="false">
      <div class="dialog-box">
        <p>用本项目的钱报销项目开支（如采购、差旅）。</p>
        <ul>
          <li>待分成：<b>¥{{ fmt(account?.sharePendingBalance) }}</b> · 非分成：<b>¥{{ fmt(account?.nonShareBalance) }}</b></li>
          <li>流程：上传发票提交 → 审批 → 财务回执 → 你确认到账 → <b>从所选资金池转入个人钱包</b>（公司总账不变）</li>
        </ul>
      </div>
      <el-form label-width="90px">
        <el-form-item label="扣款资金" required>
          <el-radio-group v-model="form.fundType">
            <el-radio value="SHARE_PENDING">待分成（默认）</el-radio>
            <el-radio value="NON_SHARE">非分成</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="报销金额" required>
          <el-input-number
            v-model="form.amount"
            :min="0.01"
            :precision="2"
            :max="fundBucketBalance(form.fundType) || undefined"
            style="width: 200px"
          />
          <div class="hint">当前可选余额 ¥{{ fmt(fundBucketBalance(form.fundType)) }}</div>
        </el-form-item>
        <el-form-item label="收款方式" required>
          <el-select
            v-model="form.payMethodId"
            filterable
            :placeholder="myPayMethods.length ? '选择收款方式' : '请先在员工档案配置'"
            style="width: 100%"
          >
            <el-option v-for="m in myPayMethods" :key="m.id" :label="payMethodLabel(m)" :value="Number(m.id)" />
          </el-select>
        </el-form-item>
        <el-form-item label="发票" required>
          <div class="voucher-box">
            <el-upload :show-file-list="false" :http-request="onUploadPayVoucher" accept="image/*,.pdf">
              <el-button :loading="uploadingVoucher" size="small">上传发票/凭证</el-button>
            </el-upload>
            <div v-for="(file, index) in voucherFiles" :key="file.id" class="voucher-item">
              <a :href="fileUrl(file)" target="_blank" rel="noopener">{{ file.originalName || `文件#${file.id}` }}</a>
              <el-button link type="danger" @click="removePayVoucher(index)">移除</el-button>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="form.remark" placeholder="例如：买服务器" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reimburseDialog = false">取消</el-button>
        <el-button type="primary" @click="submitPay('REIMBURSE_PROJECT')">提交报销审批</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="salaryDialog" title="申请发工资" width="480px" :close-on-click-modal="false">
      <div class="dialog-box">
        <p>用本项目的钱发项目相关工资/劳务。</p>
        <ul>
          <li>待分成：<b>¥{{ fmt(account?.sharePendingBalance) }}</b> · 非分成：<b>¥{{ fmt(account?.nonShareBalance) }}</b></li>
          <li>流程：提交审批 → 财务回执 → 确认到账 → <b>从所选资金池转入个人钱包</b>（公司总账不变）</li>
        </ul>
      </div>
      <el-form label-width="90px">
        <el-form-item label="扣款资金" required>
          <el-radio-group v-model="form.fundType">
            <el-radio value="SHARE_PENDING">待分成（默认）</el-radio>
            <el-radio value="NON_SHARE">非分成</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="工资金额" required>
          <el-input-number
            v-model="form.amount"
            :min="0.01"
            :precision="2"
            :max="fundBucketBalance(form.fundType) || undefined"
            style="width: 200px"
          />
          <div class="hint">当前可选余额 ¥{{ fmt(fundBucketBalance(form.fundType)) }}</div>
        </el-form-item>
        <el-form-item label="收款方式" required>
          <el-select
            v-model="form.payMethodId"
            filterable
            :placeholder="myPayMethods.length ? '选择收款方式' : '请先在员工档案配置'"
            style="width: 100%"
          >
            <el-option v-for="m in myPayMethods" :key="m.id" :label="payMethodLabel(m)" :value="Number(m.id)" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="form.remark" placeholder="例如：3 月外包劳务" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="salaryDialog = false">取消</el-button>
        <el-button type="primary" @click="submitPay('SALARY_APPLY')">提交发工资审批</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.detail-name {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
  letter-spacing: -0.03em;
  color: var(--kk-text);
}
.project-heading { margin-top: 12px; }
.project-heading__title {
  display: flex;
  align-items: center;
  gap: 10px;
}
.meta-separator { margin: 0 6px; color: var(--kk-text-muted); }
.page-actions {
  display: flex;
  align-items: flex-end;
  justify-content: flex-end;
  gap: 10px;
  flex-wrap: wrap;
}
.action-group {
  display: flex;
  align-items: center;
  gap: 0;
  padding: 18px 4px 4px;
  position: relative;
}
.action-group__label {
  position: absolute;
  top: 0;
  left: 4px;
  font-size: 11px;
  line-height: 14px;
  color: var(--kk-text-muted);
}
.action-group :deep(.el-button) { margin-left: 0; border-radius: 0; }
.action-group :deep(.el-button:first-of-type) { border-radius: 8px 0 0 8px; }
.action-group :deep(.el-button:last-of-type) { border-radius: 0 8px 8px 0; }
.action-group :deep(.el-button:only-of-type) { border-radius: 8px; }
.action-group :deep(.el-button + .el-button) { margin-left: -1px; }
.finance-overview {
  margin-bottom: 16px;
  padding: 18px;
  background: #fff;
  border: 1px solid #e8edf3;
  border-radius: var(--kk-radius);
  box-shadow: 0 8px 28px rgba(15, 23, 42, 0.04);
}
.section-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}
.section-head h3 { margin: 0; font-size: 16px; color: var(--kk-text); }
.section-head p { margin: 4px 0 0; font-size: 12px; color: var(--kk-text-muted); }
.fund-swap {
  display: inline-flex;
  align-items: stretch;
  overflow: hidden;
  border-radius: var(--kk-radius-sm);
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.55) inset;
}
.fund-swap :deep(.el-button) {
  margin: 0;
  border: 0;
  border-radius: 0;
  height: 32px;
}
.fund-swap :deep(.el-button + .el-button) {
  margin-left: 0;
  box-shadow: inset 1px 0 0 rgba(15, 23, 42, 0.1);
}
.fund-swap :deep(.el-button.is-disabled) {
  opacity: 0.42;
}
.acc-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
}
.acc-card {
  position: relative;
  overflow: hidden;
  padding: 20px;
  background: var(--kk-glass-bg);
  border: 1px solid var(--kk-glass-border);
  border-radius: var(--kk-radius);
  box-shadow: var(--kk-glass-shadow);
  backdrop-filter: var(--kk-glass-blur);
  -webkit-backdrop-filter: var(--kk-glass-blur);
}
.acc-card--clickable { cursor: pointer; }
.acc-card:hover { box-shadow: 0 8px 28px rgba(0, 0, 0, 0.08); }
.acc-card:focus-visible {
  outline: 2px solid var(--kk-primary);
  outline-offset: 2px;
}
.acc-card__head h4 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--kk-text);
}
.acc-card__title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.filter-card {
  margin-bottom: 16px;
  padding: 14px 18px;
}
.filter-form {
  margin: 0;
}
.filter-form :deep(.el-form-item) {
  margin-bottom: 0;
}
.acc-card__head p {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--kk-text-muted);
}
.acc-card__balance {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 16px 0 14px;
}
.acc-card__balance span {
  display: block;
  font-size: 13px;
  color: var(--kk-text-secondary);
}
.acc-card__balance b {
  display: block;
  margin-top: 4px;
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
  color: var(--kk-text);
}
.acc-card__icon { color: var(--kk-primary); flex-shrink: 0; }
.acc-card__meta {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 10px;
  padding-top: 12px;
  border-top: 1px solid rgba(0, 0, 0, 0.06);
  font-size: 12px;
  color: var(--kk-text-secondary);
}
.acc-card__meta b {
  display: block;
  margin-top: 4px;
  font-size: 13px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--kk-text);
}
.acc-card__actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid rgba(0, 0, 0, 0.06);
}
.acc-card__actions :deep(.el-button + .el-button) { margin-left: 0; }
.metric-grid {
  display: grid;
  grid-template-columns: 1.25fr repeat(4, minmax(0, 1fr));
  gap: 14px;
}
.metric-card {
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-height: 96px;
  padding: 16px 14px 16px 18px;
  background: #f8fafc;
  border: 1px solid #edf1f5;
  border-radius: var(--kk-radius);
  box-shadow: var(--kk-glass-shadow);
  backdrop-filter: var(--kk-glass-blur);
  -webkit-backdrop-filter: var(--kk-glass-blur);
}
.metric-card--primary {
  background: #171717;
  border-color: #171717;
}
.metric-card--primary .metric-label,
.metric-card--primary .metric-hint { color: rgba(255, 255, 255, 0.66); }
.metric-card--primary .metric-value,
.metric-card--primary .metric-glyph { color: #fff; }
.metric-card::before {
  content: "";
  position: absolute;
  right: -24px;
  top: 50%;
  width: 100px;
  height: 100px;
  border-radius: 50%;
  transform: translateY(-50%);
  filter: blur(32px);
  opacity: 0.22;
  pointer-events: none;
}
.metric-card--indigo::before { background: #d4d4d8; }
.metric-card--cyan::before { background: #a5f3fc; }
.metric-card--violet::before { background: #ddd6fe; }
.metric-card--amber::before { background: #fde68a; }
.metric-card--slate::before { background: #e2e8f0; }
.metric-card--indigo .metric-glyph { color: var(--kk-primary); }
.metric-card--cyan .metric-glyph { color: #0891b2; }
.metric-card--violet .metric-glyph { color: #7c3aed; }
.metric-card--amber .metric-glyph { color: #d97706; }
.metric-card--slate .metric-glyph { color: #64748b; }
.metric-body { position: relative; z-index: 1; min-width: 0; }
.metric-glyph { position: relative; z-index: 1; flex-shrink: 0; opacity: 1; }
.metric-label { font-size: 12px; font-weight: 500; color: var(--kk-text-secondary); }
.metric-value {
  margin-top: 6px;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
  color: var(--kk-text);
}
.metric-value.sm { font-size: 18px; }
.metric-hint { margin-top: 4px; font-size: 12px; color: var(--kk-text-muted); }
.rule-tip {
  margin: 10px 2px 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--kk-text-secondary);
}
.balance-equation {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 14px;
  padding: 12px 14px;
  background: #f8fafc;
  border: 1px solid #e8edf3;
  border-radius: 10px;
}
.balance-equation--warning { background: #fff8ed; border-color: #fed7aa; }
.equation-item { display: flex; flex-direction: column; gap: 2px; }
.equation-item span { font-size: 11px; color: var(--kk-text-muted); }
.equation-item b { font-size: 14px; color: var(--kk-text); font-variant-numeric: tabular-nums; }
.equation-item--strong b { color: var(--kk-primary); }
.equation-sign { color: var(--kk-text-muted); font-size: 12px; }
.balance-alert { margin-left: auto; font-size: 12px; font-weight: 600; color: #b45309; }
.balance-equation__copy { font-size: 13px; color: var(--kk-text-secondary); }
.ledger-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 14px;
  padding: 12px;
  background: #f8fafc;
  border: 1px solid #edf1f5;
  border-radius: 10px;
}
.ledger-toolbar__filters { display: flex; gap: 8px; flex-wrap: wrap; }
.ledger-summary { display: flex; gap: 12px; flex-wrap: wrap; font-size: 12px; color: var(--kk-text-secondary); }
.ledger-summary b { color: var(--kk-text); font-variant-numeric: tabular-nums; }
.ledger-section { scroll-margin-top: 16px; }
.biz-no-cell {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
:global(.biz-no-tooltip) {
  max-width: calc(100vw - 24px);
  white-space: nowrap;
}
.chart-filter-notice {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin: -2px 0 12px;
  padding: 9px 12px;
  color: #475569;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  border-radius: 9px;
  font-size: 12px;
}
.chart-filter-notice b { color: #1d4ed8; }
.chart-filter-notice .el-button { margin-left: auto; }
.analytics-section {
  margin-bottom: 16px;
  padding: 18px;
  background: #fff;
  border: 1px solid #e8edf3;
  border-radius: var(--kk-radius);
  box-shadow: 0 8px 28px rgba(15, 23, 42, 0.04);
}
.analytics-head { margin-bottom: 14px; }
.analytics-controls { display: flex; align-items: center; justify-content: flex-end; gap: 10px; flex-wrap: wrap; }
.analytics-grid {
  display: grid;
  grid-template-columns: 1.25fr 1fr 1fr;
  gap: 14px;
}
.chart-card {
  min-width: 0;
  min-height: 286px;
  padding: 16px;
  background: #fbfcfe;
  border: 1px solid #edf1f5;
  border-radius: 12px;
}
.chart-card__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 18px;
}
.chart-title { display: flex; align-items: center; gap: 10px; min-width: 0; }
.chart-title h4 { margin: 0; font-size: 14px; color: var(--kk-text); }
.chart-title p { margin: 3px 0 0; font-size: 11px; color: var(--kk-text-muted); }
.chart-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  border-radius: 9px;
  font-size: 18px;
}
.chart-icon--blue { color: #2563eb; background: #dbeafe; }
.chart-icon--amber { color: #d97706; background: #fef3c7; }
.chart-icon--violet { color: #7c3aed; background: #ede9fe; }
.chart-legend { display: flex; gap: 10px; font-size: 11px; white-space: nowrap; color: var(--kk-text-secondary); }
.chart-legend span::before { content: ''; display: inline-block; width: 7px; height: 7px; margin-right: 5px; border-radius: 2px; }
.legend-in::before { background: #10b981; }
.legend-out::before { background: #f97316; }
.cash-chart {
  display: flex;
  align-items: stretch;
  justify-content: space-around;
  gap: 10px;
  height: 205px;
  padding: 10px 4px 0;
  border-bottom: 1px solid #dfe5ec;
  background-image: linear-gradient(to bottom, #eef2f7 1px, transparent 1px);
  background-size: 100% 25%;
}
.cash-column { flex: 1; min-width: 42px; text-align: center; display: flex; flex-direction: column; }
.cash-bars { flex: 1; display: flex; align-items: flex-end; justify-content: center; gap: 5px; min-height: 130px; }
.cash-bar { width: min(18px, 34%); min-height: 3px; padding: 0; border: 0; border-radius: 4px 4px 1px 1px; cursor: pointer; transition: opacity 0.2s ease, transform 0.2s ease; }
.cash-bar:hover { opacity: 0.78; transform: translateY(-2px); }
.cash-bar:focus-visible, .donut-legend__item:focus-visible, .flow-row:focus-visible { outline: 2px solid #2563eb; outline-offset: 3px; }
.cash-bar--in { background: #10b981; }
.cash-bar--out { background: #f97316; }
.cash-column > span { margin-top: 7px; font-size: 11px; color: var(--kk-text-secondary); }
.cash-column > small { margin-top: 2px; font-size: 9px; color: var(--kk-text-muted); white-space: nowrap; }
.donut-layout { display: grid; grid-template-columns: 132px 1fr; align-items: center; gap: 18px; min-height: 200px; }
.donut-chart {
  position: relative;
  width: 132px;
  height: 132px;
  border-radius: 50%;
}
.donut-chart::after {
  content: '';
  position: absolute;
  inset: 22px;
  background: #fbfcfe;
  border-radius: 50%;
}
.donut-center { position: absolute; inset: 0; z-index: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; }
.donut-center span { font-size: 10px; color: var(--kk-text-muted); }
.donut-center b { margin-top: 3px; font-size: 12px; color: var(--kk-text); font-variant-numeric: tabular-nums; }
.donut-legend { min-width: 0; }
.donut-legend__item { display: grid; width: 100%; grid-template-columns: 8px minmax(48px, 1fr) auto; gap: 7px; align-items: center; padding: 5px; border: 0; border-radius: 6px; background: transparent; text-align: left; font: inherit; font-size: 11px; cursor: pointer; }
.donut-legend__item:hover { background: #f1f5f9; }
.donut-legend__item i { width: 8px; height: 8px; border-radius: 2px; }
.donut-legend__item span { color: var(--kk-text-secondary); }
.donut-legend__item b { color: var(--kk-text); font-variant-numeric: tabular-nums; }
.donut-legend__item small { grid-column: 2 / 4; margin-top: -5px; color: var(--kk-text-muted); font-variant-numeric: tabular-nums; }
.flow-ranking { display: flex; flex-direction: column; gap: 19px; padding-top: 5px; }
.flow-row { width: 100%; padding: 4px; border: 0; border-radius: 7px; background: transparent; text-align: left; font: inherit; cursor: pointer; }
.flow-row:hover { background: #f1f5f9; }
.flow-row__label { display: flex; justify-content: space-between; gap: 12px; margin-bottom: 7px; font-size: 11px; }
.flow-row__label span { color: var(--kk-text-secondary); }
.flow-row__label b { color: var(--kk-text); font-variant-numeric: tabular-nums; }
.flow-track { height: 9px; overflow: hidden; background: #e9eef4; border-radius: 99px; }
.flow-track i { display: block; height: 100%; border-radius: inherit; }
.dialog-box {
  margin: 0 0 14px;
  padding: 12px 14px;
  background: rgba(255, 255, 255, 0.45);
  border-radius: var(--kk-radius-sm);
  color: var(--kk-text-secondary);
  font-size: 13px;
  line-height: 1.6;
}
.dialog-box p { margin: 0 0 8px; }
.dialog-box ul { margin: 0; padding-left: 18px; }
.dialog-box li { margin: 4px 0; }
.dialog-box b { color: var(--kk-text); }
.voucher-box { width: 100%; }
.voucher-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 6px;
  padding: 6px 8px;
  background: rgba(255, 255, 255, 0.45);
  border-radius: 8px;
  font-size: 13px;
}
.voucher-item a { color: var(--kk-primary); word-break: break-all; }
.tabs { margin-top: 0; }
.hint { color: var(--kk-text-muted); font-size: 12px; }
.period-preview {
  margin-top: 8px;
  border: 1px solid #eef2f7;
  border-radius: 10px;
  overflow: hidden;
}
.period-preview .field-label {
  padding: 10px 12px 0;
}
.period-preview-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  font-size: 13px;
  color: #334155;
  border-top: 1px solid #f1f5f9;
}
.period-preview-row em {
  margin-left: 6px;
  font-style: normal;
  color: #94a3b8;
  font-size: 12px;
}
.period-preview-row b {
  color: #16a34a;
  font-weight: 600;
  white-space: nowrap;
}
.field-label {
  display: block;
  font-size: 13px;
  color: var(--kk-text);
  font-weight: 500;
  margin-bottom: 6px;
}
.share-layout {
  display: block;
}
.share-panel {
  background: var(--kk-glass-bg);
  border: 1px solid var(--kk-glass-border);
  border-radius: var(--kk-radius);
  box-shadow: var(--kk-glass-shadow);
  backdrop-filter: var(--kk-glass-blur);
  -webkit-backdrop-filter: var(--kk-glass-blur);
  padding: 16px 18px 14px;
}
.share-panel-head {
  margin-bottom: 14px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f1f5f9;
}
.share-panel-head h4 {
  margin: 0 0 4px;
  font-size: 15px;
  font-weight: 600;
  color: #0f172a;
}
.share-panel-head p {
  margin: 0;
  font-size: 12px;
  color: #94a3b8;
  line-height: 1.5;
}
.percent-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.percent-row.two {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.percent-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px;
  background: rgba(255, 255, 255, 0.35);
  border-radius: var(--kk-radius-sm);
}
.percent-item .field-label { margin-bottom: 0; }
.percent-item :deep(.el-input-number) { width: 100%; }
.percent-item.readonly {
  background: #f1f5f9;
  border: 1px dashed #cbd5e1;
}
.readonly-value {
  height: 32px;
  display: flex;
  align-items: center;
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
}
.sub {
  font-size: 12px;
  color: #94a3b8;
  line-height: 1.4;
}
.sum-line {
  margin: 10px 0 14px;
  font-size: 12px;
  color: #64748b;
}
.sum-line.inline { margin: 0; }
.sum-line.bad { color: #dc2626; font-weight: 600; }
.members-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 8px;
}
.members-head .field-label { margin: 0; }
.members-table {
  width: 100%;
  --el-table-header-bg-color: #f8fafc;
}
.panel-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid #f1f5f9;
}
.foot-left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.in { color: var(--kk-success); font-weight: 600; font-variant-numeric: tabular-nums; }
.out { color: var(--kk-danger); font-weight: 600; font-variant-numeric: tabular-nums; }
.related-block { margin-top: 20px; }
.related-title { margin: 0 0 6px; font-size: 15px; color: #0f172a; }
.related-tip { margin: 0 0 10px; font-size: 12px; color: #94a3b8; line-height: 1.5; }
.related-list {
  border: 1px solid #eef2f7;
  border-radius: 10px;
  overflow: hidden;
}
.related-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-bottom: 1px solid #f1f5f9;
}
.related-row:last-child { border-bottom: 0; }
.related-main { min-width: 0; }
.related-who { display: block; font-size: 13px; color: #0f172a; font-weight: 500; }
.related-sub { display: block; margin-top: 2px; font-size: 12px; color: #94a3b8; }
.related-empty {
  padding: 16px;
  text-align: center;
  color: #94a3b8;
  font-size: 13px;
  background: #f8fafc;
  border-radius: 8px;
}
@media (max-width: 1280px) {
  .metric-grid { grid-template-columns: 1fr 1fr; }
  .acc-card__meta { grid-template-columns: 1fr 1fr; }
  .page-top { align-items: flex-start; }
  .analytics-grid { grid-template-columns: 1fr 1fr; }
  .chart-card--trend { grid-column: 1 / -1; }
}
@media (max-width: 1100px) {
  .percent-row { grid-template-columns: 1fr; }
}
@media (max-width: 720px) {
  .metric-grid,
  .acc-grid { grid-template-columns: 1fr; }
  .finance-overview { padding: 14px; }
  .analytics-section { padding: 14px; }
  .analytics-head { flex-direction: column; }
  .analytics-controls { width: 100%; justify-content: flex-start; }
  .analytics-controls :deep(.el-date-editor) { width: 100% !important; }
  .analytics-grid { grid-template-columns: 1fr; }
  .chart-card--trend { grid-column: auto; }
  .donut-layout { grid-template-columns: 118px 1fr; }
  .donut-chart { width: 118px; height: 118px; }
  .ledger-toolbar__filters :deep(.el-input),
  .ledger-toolbar__filters :deep(.el-select) { width: 100% !important; }
}
@media (prefers-reduced-transparency: reduce) {
  .acc-card,
  .metric-card,
  .share-panel {
    background: #fff;
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}
</style>
