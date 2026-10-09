<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { bizApi } from '@/api/biz'
import { sysApi } from '@/api/system'
import { workflowApi } from '@/api/workflow'
import { approvalFlowTip } from '@/utils/approvalTip'
import { useUserStore } from '@/stores/user'
import WalletBoardCharts from '@/components/wallet/WalletBoardCharts.vue'
import ProjectCascadeSelect from '@/components/project/ProjectCascadeSelect.vue'

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const ledgerLoading = ref(false)
const wallet = ref<any>({})
const ledgers = ref<any[]>([])
const ledgerTotal = ref(0)
const companies = ref<any[]>([])
const projects = ref<any[]>([])
const walletBoard = ref<any>(null)
const boardPeriod = ref<'daily' | 'monthly'>('daily')
const freezeDrawer = ref(false)
const ledgerQuery = reactive({
  page: 1,
  pageSize: 10,
  keyword: '',
  dateRange: [] as string[],
  minAmount: undefined as number | undefined,
  maxAmount: undefined as number | undefined,
  companyId: undefined as number | undefined,
  projectId: undefined as number | undefined,
  bizType: '' as string,
})

const reimburseDialog = ref(false)
const leaveDialog = ref(false)
const leaveSubmitting = ref(false)
const leaveForm = reactive({
  companyId: undefined as number | undefined,
  dateRange: [] as string[],
  reason: '',
})
const withdrawDialog = ref(false)
const balanceApplyDialog = ref(false)
const reimburseForm = reactive({
  amount: 0,
  remark: '',
  companyId: undefined as number | undefined,
  payMethodId: undefined as number | undefined,
})
const withdrawForm = reactive({
  amount: 0,
  remark: '',
  companyId: undefined as number | undefined,
  payMethodId: undefined as number | undefined,
  withVoucher: false,
})
const balanceApplyForm = reactive({
  projectId: undefined as number | undefined,
  amount: 0,
  remark: '',
  fundType: 'SHARE_PENDING' as string,
})
const balanceApplyProjects = ref<any[]>([])
const voucherFiles = ref<any[]>([])
const withdrawVoucherFiles = ref<any[]>([])
const balanceApplyFiles = ref<any[]>([])
const uploading = ref(false)
const withdrawUploading = ref(0)
const balanceUploading = ref(0)
const submittingBalance = ref(false)
const MAX_BALANCE_FILES = 9
const MAX_WITHDRAW_VOUCHERS = 10
const balanceBusy = computed(() => balanceUploading.value > 0 || submittingBalance.value)
let balanceUploadGen = 0
let withdrawUploadGen = 0
const myPayMethods = ref<any[]>([])
const withdrawTaxRate = ref(0.2)
const withdrawTaxMode = ref<'FLAT' | 'TIER'>('FLAT')
const withdrawTaxBreakdown = ref<any[]>([])
const withdrawCalcTax = ref(0)
const withdrawCalcNet = ref(0)
let withdrawTaxSeq = 0

const todoApprovals = ref<any[]>([])
const calendarTasks = ref<any[]>([])
const myLeaves = ref<any[]>([])
const myProjects = ref<any[]>([])
const calendarDate = ref(new Date())
/** admin/shareholder 可切全部；默认我的（创建或参与） */
const taskScope = ref<'mine' | 'all'>('mine')
/** 任务模块：左侧选中项目；右侧任务分栏 */
const taskPanelProjectId = ref<number | null>(null)
const taskPanelTab = ref<'all' | 'related' | 'priority'>('related')
const TASK_PAGE_SIZE = 10
const TASK_ROW_HEIGHT = 52
const TASK_ROW_GAP = 4
const TASK_ROW_STRIDE = TASK_ROW_HEIGHT + TASK_ROW_GAP
const TASK_OVERSCAN = 6
const panelTaskList = ref<any[]>([])
const panelTaskTotal = ref(0)
const panelTaskPage = ref(0)
const panelTaskLoading = ref(false)
const panelTaskLoadingMore = ref(false)
const panelTabTotals = reactive({ all: 0, related: 0, priority: 0 })
const taskPanelListRef = ref<HTMLElement | null>(null)
const panelScrollTop = ref(0)
const panelViewportH = ref(0)
let taskLoadSeq = 0
let leaveLoadSeq = 0
let panelLoadSeq = 0
let panelListObserver: ResizeObserver | undefined

/** 日历考勤标记：角色权限里勾选「查看考勤」 */
const canViewLeave = computed(() => userStore.hasPermission('hr:leave:mine'))

function fmt(n?: number) {
  return Number(n || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function fmtTime(t?: string) {
  if (!t) return '—'
  return t.replace('T', ' ').slice(0, 16)
}

function bizLabel(v?: string) {
  return ({
    INCOME: '入账',
    EXPENSE: '出账',
    SETTLE: '分成',
    ADVANCE: '预支',
    RESERVE: '预留',
    SALARY: '工资',
    REIMBURSE: '报销',
    WITHDRAW: '提现',
    PAYOUT: '项目余额',
    ROLLBACK: '回退',
  } as Record<string, string>)[v || ''] || v || '—'
}

function payMethodLabel(m: any) {
  const type = m.methodTypeLabel || ({ BANK: '银行卡', ALIPAY: '支付宝', WECHAT: '微信' } as any)[m.methodType] || m.methodType
  const name = m.accountName ? `${m.accountName} · ` : ''
  const bank = m.methodType === 'BANK' && m.bankName ? `（${m.bankName}）` : ''
  return `${type} · ${name}${m.accountNo || ''}${bank}`
}

const withdrawTax = computed(() => (withdrawForm.withVoucher ? 0 : Number(withdrawCalcTax.value || 0)))
const withdrawNet = computed(() => {
  const amount = Number(withdrawForm.amount || 0)
  if (withdrawForm.withVoucher) return amount
  return Number(withdrawCalcNet.value || 0)
})

async function refreshWithdrawTax() {
  if (withdrawForm.withVoucher) {
    const amount = Number(withdrawForm.amount || 0)
    withdrawCalcTax.value = 0
    withdrawCalcNet.value = amount
    withdrawTaxBreakdown.value = []
    withdrawTaxMode.value = 'FLAT'
    return
  }
  const seq = ++withdrawTaxSeq
  const companyId = withdrawForm.companyId
  const amount = Number(withdrawForm.amount || 0)
  try {
    const cfg = await bizApi.withdrawConfig({
      companyId: companyId || undefined,
      amount: amount > 0 ? amount : undefined,
    })
    if (seq !== withdrawTaxSeq) return
    withdrawTaxRate.value = Number(cfg?.taxRate ?? cfg?.defaultTaxRate ?? 0.2)
    withdrawTaxMode.value = (cfg?.taxMode === 'TIER' ? 'TIER' : 'FLAT')
    withdrawTaxBreakdown.value = cfg?.breakdown || []
    if (amount > 0 && cfg?.tax != null) {
      withdrawCalcTax.value = Number(cfg.tax)
      withdrawCalcNet.value = Number(cfg.net ?? Math.max(0, amount - Number(cfg.tax)))
    } else if (amount > 0) {
      const tax = Number((amount * withdrawTaxRate.value).toFixed(2))
      withdrawCalcTax.value = tax
      withdrawCalcNet.value = Number((amount - tax).toFixed(2))
      withdrawTaxBreakdown.value = []
    } else {
      withdrawCalcTax.value = 0
      withdrawCalcNet.value = 0
      withdrawTaxBreakdown.value = []
    }
  } catch {
    if (seq !== withdrawTaxSeq) return
    if (amount > 0) {
      const tax = Number((amount * Number(withdrawTaxRate.value || 0.2)).toFixed(2))
      withdrawCalcTax.value = tax
      withdrawCalcNet.value = Number((amount - tax).toFixed(2))
    }
  }
}

/** 摘要里带上项目名，避免只显示「项目分成入账」看不出是哪个项目 */
function ledgerTitle(row: any) {
  const title = String(row?.title || '').trim() || '—'
  const project = String(row?.projectName || '').trim()
  if (!project) return title
  if (title.includes(project)) return title
  return `${title} · ${project}`
}

function dayKey(d: Date | string) {
  if (typeof d === 'string') return d.slice(0, 10)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const selectedDay = computed(() => dayKey(calendarDate.value))
const todayKey = computed(() => dayKey(new Date()))

const dayStartingTasks = computed(() =>
  calendarTasks.value.filter((t) => String(t.startDate || '').startsWith(selectedDay.value)),
)

const dayDueTasks = computed(() =>
  calendarTasks.value.filter((t) => String(t.dueDate || '').startsWith(selectedDay.value)),
)

const dayTasks = computed(() => {
  const rows = new Map<number | string, any>()
  for (const task of [...dayStartingTasks.value, ...dayDueTasks.value]) rows.set(task.id, task)
  return [...rows.values()]
})

const dayLeaves = computed(() =>
  myLeaves.value.filter((r) => String(r.leaveDate || '').startsWith(selectedDay.value)),
)

const monthLeaveCount = computed(() => {
  const d = calendarDate.value
  const prefix = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
  const days = new Set(
    myLeaves.value
      .map((r) => String(r.leaveDate || '').slice(0, 10))
      .filter((k) => k.startsWith(prefix)),
  )
  return days.size
})

const tasksByDay = computed(() => {
  const map: Record<string, number> = {}
  for (const t of calendarTasks.value) {
    const keys = new Set([
      String(t.startDate || '').slice(0, 10),
      String(t.dueDate || '').slice(0, 10),
    ].filter(Boolean))
    for (const key of keys) map[key] = (map[key] || 0) + 1
  }
  return map
})

function taskDayLabel(task: any) {
  const starts = String(task.startDate || '').startsWith(selectedDay.value)
  const ends = String(task.dueDate || '').startsWith(selectedDay.value)
  if (starts && ends) return '当日开始并截止'
  return starts ? '当日开始' : '当日截止'
}

function openLeaveApply(day = selectedDay.value) {
  leaveForm.companyId = companies.value.length === 1 ? Number(companies.value[0].id) : undefined
  leaveForm.dateRange = [day, day]
  leaveForm.reason = ''
  leaveDialog.value = true
}

async function submitLeaveApply() {
  if (!leaveForm.companyId) return void ElMessage.warning('请选择所属公司')
  if (leaveForm.dateRange.length !== 2) return void ElMessage.warning('请选择请假日期')
  if (!leaveForm.reason.trim()) return void ElMessage.warning('请填写请假事由')
  leaveSubmitting.value = true
  try {
    await bizApi.submitLeave({
      companyId: leaveForm.companyId,
      startDate: leaveForm.dateRange[0],
      endDate: leaveForm.dateRange[1],
      reason: leaveForm.reason.trim(),
    })
    ElMessage.success('请假申请已提交')
    leaveDialog.value = false
    await loadLeaves()
  } catch (e: any) {
    ElMessage.error(e?.message || '请假申请提交失败')
  } finally {
    leaveSubmitting.value = false
  }
}

const leaveByDay = computed(() => {
  const map: Record<string, number> = {}
  for (const r of myLeaves.value) {
    const key = String(r.leaveDate || '').slice(0, 10)
    if (!key) continue
    map[key] = (map[key] || 0) + 1
  }
  return map
})

function cellClass(day: string) {
  const n = tasksByDay.value[day] || 0
  const leave = !!leaveByDay.value[day]
  if (!n && !leave) return ''
  const overdue = n > 0 && calendarTasks.value.some(
    (t) => String(t.dueDate || '').startsWith(day) && (t.overdue || Number(t.status) === 0 || Number(t.status) === 1) && day < todayKey.value,
  )
  const parts = []
  if (n) parts.push(overdue ? 'has-task overdue' : 'has-task')
  if (leave) parts.push('has-leave')
  return parts.join(' ')
}

const seeAllProjects = computed(() => {
  const roles = userStore.roles || []
  return roles.includes('admin') || roles.includes('shareholder')
})

const showTaskScopeToggle = computed(() => seeAllProjects.value)

const taskStatusMap: Record<number, string> = {
  0: '待办', 1: '进行中', 2: '已完成', 3: '已关闭', 4: '待确认完成',
}

const riskTypeMap: Record<string, { label: string; type: 'danger' | 'warning' | 'info' | 'success' }> = {
  OVERDUE: { label: '已逾期', type: 'danger' },
  DUE_SOON: { label: '即将到期', type: 'warning' },
  STALE: { label: '长期未更新', type: 'warning' },
  NO_DUE_DATE: { label: '未设截止时间', type: 'info' },
  PENDING: { label: '待确认', type: 'success' },
}

const canSeeWallet = computed(() => userStore.hasPermission('finance:wallet:list'))

const welcomeName = computed(() => userStore.nickname || userStore.user?.username || '同事')
const welcomeNow = ref(new Date())
const welcomeWeather = ref<{ temp: number; label: string } | null>(null)
let welcomeClockTimer: ReturnType<typeof setInterval> | undefined

function welcomeTimeGreeting(hour: number) {
  if (hour >= 5 && hour < 9) return '早上好'
  if (hour >= 9 && hour < 12) return '上午好'
  if (hour >= 12 && hour < 14) return '中午好'
  if (hour >= 14 && hour < 18) return '下午好'
  if (hour >= 18 && hour < 22) return '晚上好'
  return '夜深了'
}

function weatherCodeLabel(code?: number) {
  if (code == null) return '—'
  if (code === 0) return '晴'
  if (code <= 3) return '多云'
  if (code === 45 || code === 48) return '雾'
  if (code >= 51 && code <= 57) return '毛毛雨'
  if (code >= 61 && code <= 67) return '雨'
  if (code >= 71 && code <= 77) return '雪'
  if (code >= 80 && code <= 82) return '阵雨'
  if (code >= 95) return '雷雨'
  return '阴'
}

async function loadWelcomeWeather() {
  let lat = 31.23
  let lon = 121.47
  if (typeof navigator !== 'undefined' && navigator.geolocation) {
    await new Promise<void>((resolve) => {
      navigator.geolocation.getCurrentPosition(
        (pos) => {
          lat = pos.coords.latitude
          lon = pos.coords.longitude
          resolve()
        },
        () => resolve(),
        { timeout: 4000, maximumAge: 600_000 },
      )
    })
  }
  try {
    const q = new URLSearchParams({
      latitude: String(lat),
      longitude: String(lon),
      current: 'temperature_2m,weather_code',
      timezone: 'auto',
    })
    const res = await fetch(`https://api.open-meteo.com/v1/forecast?${q}`)
    if (!res.ok) return
    const data = await res.json()
    const temp = Number(data?.current?.temperature_2m)
    const code = Number(data?.current?.weather_code)
    if (!Number.isFinite(temp)) return
    welcomeWeather.value = { temp: Math.round(temp), label: weatherCodeLabel(code) }
  } catch {
    welcomeWeather.value = null
  }
}

const welcomeTimePhrase = computed(() => welcomeTimeGreeting(welcomeNow.value.getHours()))
const welcomeDate = computed(() => {
  const d = welcomeNow.value
  const week = ['日', '一', '二', '三', '四', '五', '六'][d.getDay()]
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日 周${week}`
})
const welcomeClock = computed(() => {
  const d = welcomeNow.value
  const h = String(d.getHours()).padStart(2, '0')
  const m = String(d.getMinutes()).padStart(2, '0')
  const s = String(d.getSeconds()).padStart(2, '0')
  return `${h}:${m}:${s}`
})

function projectRole(p: any) {
  const uid = userStore.user?.id
  if (uid && p.ownerId === uid) return '负责人'
  if (seeAllProjects.value) return '全部可见'
  return '成员'
}

function openTask(t: any) {
  if (!userStore.hasPermission('project:task:list')) return
  router.push({
    path: '/project/task',
    query: { projectId: String(t.projectId || ''), taskId: String(t.id || '') },
  })
}

function goApprovalCenter(scope: 'todo' | 'mine' = 'todo') {
  router.push({ path: '/workflow/center', query: { scope } })
}

function goTaskManage() {
  if (!userStore.hasPermission('project:task:list')) {
    ElMessage.warning('暂无任务管理权限')
    return
  }
  const query: Record<string, string> = {}
  if (taskPanelProjectId.value != null) {
    query.projectId = String(taskPanelProjectId.value)
  }
  router.push({ path: '/project/task', query })
}

function selectTaskProject(id: number | null) {
  taskPanelProjectId.value = id
}

const panelTaskEmpty = computed(() => {
  if (taskPanelTab.value === 'priority') return '当前没有需要优先处理的任务'
  if (taskPanelTab.value === 'all') return '暂无任务'
  return '暂无相关任务'
})

const panelHasMore = computed(() => panelTaskList.value.length < panelTaskTotal.value)
const panelVirtualStart = computed(() => {
  const start = Math.floor(panelScrollTop.value / TASK_ROW_STRIDE) - TASK_OVERSCAN
  return Math.max(0, start)
})
const panelVirtualEnd = computed(() => {
  const visible = Math.ceil((panelViewportH.value || 1) / TASK_ROW_STRIDE) + TASK_OVERSCAN * 2
  return Math.min(panelTaskList.value.length, panelVirtualStart.value + Math.max(visible, 1))
})
const panelVirtualItems = computed(() =>
  panelTaskList.value.slice(panelVirtualStart.value, panelVirtualEnd.value).map((item, offset) => ({
    item,
    idx: panelVirtualStart.value + offset,
  })),
)
const panelVirtualHeight = computed(() => {
  const n = panelTaskList.value.length
  if (!n) return 0
  return n * TASK_ROW_STRIDE - TASK_ROW_GAP
})
const panelVirtualOffset = computed(() => panelVirtualStart.value * TASK_ROW_STRIDE)

function panelTabTotal(tab: 'all' | 'related' | 'priority') {
  return tab === taskPanelTab.value ? panelTaskTotal.value : panelTabTotals[tab]
}

function searchLedger() {
  ledgerQuery.page = 1
  void loadLedger()
}

function onLedgerCompanyChange() {
  ledgerQuery.projectId = undefined
  searchLedger()
}

function resetLedger() {
  ledgerQuery.page = 1
  ledgerQuery.keyword = ''
  ledgerQuery.dateRange = []
  ledgerQuery.minAmount = undefined
  ledgerQuery.maxAmount = undefined
  ledgerQuery.companyId = undefined
  ledgerQuery.projectId = undefined
  ledgerQuery.bizType = ''
  void loadLedger()
}

async function loadBalance() {
  try {
    wallet.value = await bizApi.myWallet()
  } catch {
    wallet.value = {}
  }
}

let boardLoadSeq = 0

async function loadBoard() {
  const seq = ++boardLoadSeq
  try {
    const data = await bizApi.myWalletBoard({ period: boardPeriod.value })
    if (seq !== boardLoadSeq) return
    walletBoard.value = data
    if (data?.withdrawTaxRate != null) {
      withdrawTaxRate.value = Number(data.withdrawTaxRate)
    }
  } catch {
    if (seq !== boardLoadSeq) return
    walletBoard.value = null
  }
}

const freezeItems = computed(() => {
  const list = walletBoard.value?.freezeItems
  return Array.isArray(list) ? list : []
})

const freezeUnexplained = computed(() => Number(walletBoard.value?.freezeUnexplained || 0))

async function openFreezeDetail() {
  const frozen = Number(walletBoard.value?.frozen ?? wallet.value?.frozen ?? 0)
  if (frozen <= 0) return
  // 看板未带上明细时先刷新，避免抽屉空白
  if (!Array.isArray(walletBoard.value?.freezeItems)) {
    await loadBoard()
  }
  freezeDrawer.value = true
}

function openFreezeItem(row: any) {
  freezeDrawer.value = false
  if (row?.link) router.push(row.link)
}

async function onBoardPeriod(period: 'daily' | 'monthly') {
  boardPeriod.value = period
  await loadBoard()
}

async function loadLedger() {
  const range = ledgerQuery.dateRange || []
  ledgerLoading.value = true
  try {
    const res = await bizApi.myWalletLedger({
      page: ledgerQuery.page,
      pageSize: ledgerQuery.pageSize,
      keyword: ledgerQuery.keyword || undefined,
      minAmount: ledgerQuery.minAmount,
      maxAmount: ledgerQuery.maxAmount,
      companyId: ledgerQuery.companyId,
      projectId: ledgerQuery.projectId,
      bizType: ledgerQuery.bizType || undefined,
      startTime: range[0] ? `${range[0]} 00:00:00` : undefined,
      endTime: range[1] ? `${range[1]} 23:59:59` : undefined,
    })
    ledgers.value = res.list || []
    ledgerTotal.value = res.total || 0
  } catch {
    ledgers.value = []
    ledgerTotal.value = 0
  } finally {
    ledgerLoading.value = false
  }
}

async function preparePayForm(form: {
  companyId?: number
  payMethodId?: number
  amount: number
  remark: string
  withVoucher?: boolean
}) {
  form.amount = 0
  form.remark = ''
  form.companyId = companies.value.length === 1 ? companies.value[0].id : undefined
  form.payMethodId = undefined
  if ('withVoucher' in form) form.withVoucher = false
  try {
    myPayMethods.value = (await bizApi.myPayMethods()) || []
  } catch {
    myPayMethods.value = []
  }
  const def = myPayMethods.value.find((m) => Number(m.isDefault) === 1) || myPayMethods.value[0]
  form.payMethodId = def?.id != null ? Number(def.id) : undefined
}

async function openReimburse() {
  voucherFiles.value = []
  if (!companies.value.length) {
    try {
      companies.value = await sysApi.myCompanies()
    } catch {
      companies.value = []
    }
  }
  await preparePayForm(reimburseForm)
  if (!myPayMethods.value.length) {
    ElMessage.warning('请先在员工档案中配置个人收款方式，否则无法提交提现/报销')
  }
  reimburseDialog.value = true
}

async function openWithdraw() {
  withdrawVoucherFiles.value = []
  await preparePayForm(withdrawForm)
  if (!myPayMethods.value.length) {
    ElMessage.warning('请先在员工档案中配置个人收款方式，否则无法提交提现/报销')
  }
  await refreshWithdrawTax()
  withdrawDialog.value = true
}

const selectedBalanceProject = computed(() =>
  balanceApplyProjects.value.find((p) => Number(p.projectId) === Number(balanceApplyForm.projectId)),
)

function balanceApplyBucket(fundType?: string) {
  const row = selectedBalanceProject.value
  if (fundType === 'NON_SHARE') return Number(row?.nonShareBalance || 0)
  return Number(row?.sharePendingBalance || 0)
}

function syncBalanceApplyFundType() {
  const pending = Number(selectedBalanceProject.value?.sharePendingBalance || 0)
  const nonShare = Number(selectedBalanceProject.value?.nonShareBalance || 0)
  balanceApplyForm.fundType = pending > 0 || nonShare <= 0 ? 'SHARE_PENDING' : 'NON_SHARE'
}

async function openBalanceApply() {
  balanceUploadGen++
  balanceApplyForm.projectId = undefined
  balanceApplyForm.amount = 0
  balanceApplyForm.remark = ''
  balanceApplyForm.fundType = 'SHARE_PENDING'
  balanceApplyFiles.value = []
  try {
    balanceApplyProjects.value = (await bizApi.balanceApplyProjects()) || []
  } catch (e: any) {
    balanceApplyProjects.value = []
    ElMessage.error(e.message || '加载可申请项目失败')
  }
  if (!balanceApplyProjects.value.length) {
    ElMessage.warning('暂无可申请余额的项目（需为重点/重大且有结余）')
  }
  balanceApplyDialog.value = true
}

async function submitBalanceApply() {
  if (submittingBalance.value) return
  if (balanceUploading.value > 0) {
    ElMessage.warning('附件正在上传，请稍候')
    return
  }
  if (!balanceApplyForm.projectId) {
    ElMessage.warning('请选择项目')
    return
  }
  if (!balanceApplyForm.amount || balanceApplyForm.amount <= 0) {
    ElMessage.warning('请填写申请金额')
    return
  }
  const fundType = balanceApplyForm.fundType || 'SHARE_PENDING'
  const bal = balanceApplyBucket(fundType)
  if (bal <= 0) {
    ElMessage.warning(fundType === 'NON_SHARE' ? '该项目非分成余额为 0，无法申请' : '该项目待分成余额为 0，无法申请')
    return
  }
  if (balanceApplyForm.amount > bal) {
    ElMessage.warning(`不能超过${fundType === 'NON_SHARE' ? '非分成' : '待分成'}余额 ¥${fmt(bal)}`)
    return
  }
  const projectName = selectedBalanceProject.value?.projectName || ''
  const voucherFileIds = balanceApplyFiles.value.map((f) => f.id).filter((id) => id != null)
  submittingBalance.value = true
  try {
    const approval = await workflowApi.submit({
      type: 'PROJECT_BALANCE_APPLY',
      title: `项目余额申请 · ${projectName}`,
      projectId: balanceApplyForm.projectId,
      amount: balanceApplyForm.amount,
      remark: balanceApplyForm.remark,
      voucherFileIds,
      payload: { fundType },
    })
    ElMessage.success(`${approvalFlowTip(approval)}。审批通过后将从所选资金池转入你的个人钱包`)
    balanceApplyDialog.value = false
    balanceApplyFiles.value = []
    await Promise.all([loadBalance(), loadBoard(), loadApprovals()])
  } finally {
    submittingBalance.value = false
  }
}

watch(
  () => balanceApplyForm.projectId,
  () => {
    if (balanceApplyDialog.value) syncBalanceApplyFundType()
  },
)

watch(
  () => [withdrawForm.companyId, withdrawForm.amount, withdrawForm.withVoucher] as const,
  ([, , withVoucher], prev) => {
    if (!withdrawDialog.value) return
    // 切到无凭证时清掉已传凭证，并作废进行中的上传
    if (prev && withVoucher === false && prev[2] === true) {
      withdrawUploadGen++
      clearWithdrawVouchers()
      withdrawUploading.value = 0
    }
    void refreshWithdrawTax()
  },
)

async function onUploadVoucher(options: any) {
  uploading.value = true
  try {
    const file = await workflowApi.uploadVoucher(options.file)
    voucherFiles.value.push(file)
    ElMessage.success('发票已上传')
    options.onSuccess?.(file)
  } catch (e: any) {
    ElMessage.error(e.message || '上传失败')
    options.onError?.(e)
  } finally {
    uploading.value = false
  }
}

function removeVoucher(index: number) {
  voucherFiles.value.splice(index, 1)
}

async function onUploadWithdrawVoucher(options: any) {
  if (!withdrawForm.withVoucher) {
    options.onError?.(new Error('not voucher mode'))
    return
  }
  if (withdrawVoucherFiles.value.length + withdrawUploading.value >= MAX_WITHDRAW_VOUCHERS) {
    ElMessage.warning(`最多上传 ${MAX_WITHDRAW_VOUCHERS} 个凭证`)
    options.onError?.(new Error('too many'))
    return
  }
  const gen = withdrawUploadGen
  const localUrl = options.file instanceof File ? URL.createObjectURL(options.file) : ''
  withdrawUploading.value++
  try {
    const file = await workflowApi.uploadVoucher(options.file)
    if (gen !== withdrawUploadGen || !withdrawForm.withVoucher) {
      if (localUrl) URL.revokeObjectURL(localUrl)
      options.onError?.(new Error('cancelled'))
      return
    }
    if (file?.id == null) {
      throw new Error('上传结果无效')
    }
    if (withdrawVoucherFiles.value.some((f) => f.id === file.id)) {
      if (localUrl) URL.revokeObjectURL(localUrl)
      options.onSuccess?.(file)
      return
    }
    if (withdrawVoucherFiles.value.length >= MAX_WITHDRAW_VOUCHERS) {
      if (localUrl) URL.revokeObjectURL(localUrl)
      ElMessage.warning(`最多上传 ${MAX_WITHDRAW_VOUCHERS} 个凭证`)
      options.onError?.(new Error('too many'))
      return
    }
    withdrawVoucherFiles.value.push({
      ...file,
      localUrl,
      // 本地存储上传后 url 常是 download，缩略图改走 preview
      url: file.id != null ? `/api/file/preview/${file.id}` : file.url,
    })
    options.onSuccess?.(file)
  } catch (e: any) {
    if (localUrl) URL.revokeObjectURL(localUrl)
    if (gen === withdrawUploadGen) {
      ElMessage.error(e.message || '上传失败')
    }
    options.onError?.(e)
  } finally {
    if (gen === withdrawUploadGen) {
      withdrawUploading.value = Math.max(0, withdrawUploading.value - 1)
    }
  }
}

function removeWithdrawVoucher(index: number) {
  const file = withdrawVoucherFiles.value[index]
  if (file?.localUrl) {
    URL.revokeObjectURL(file.localUrl)
  }
  withdrawVoucherFiles.value.splice(index, 1)
}

function clearWithdrawVouchers() {
  for (const file of withdrawVoucherFiles.value) {
    if (file?.localUrl) URL.revokeObjectURL(file.localUrl)
  }
  withdrawVoucherFiles.value = []
}

function onWithdrawDialogClosed() {
  withdrawUploadGen++
  clearWithdrawVouchers()
  withdrawUploading.value = 0
}

function fileUrl(file: any) {
  if (file?.localUrl) return file.localUrl
  if (file?.id != null) return `/api/file/preview/${file.id}`
  const url = String(file?.url || '')
  // download 带 attachment，img 无法预览
  if (url.includes('/api/file/download/')) {
    const id = url.split('/').pop()
    if (id) return `/api/file/preview/${id}`
  }
  return url || ''
}

function isImage(file: any) {
  const name = String(file?.originalName || file?.name || file?.url || '').toLowerCase()
  return /\.(png|jpe?g|gif|webp|bmp)$/.test(name) || String(file?.contentType || '').startsWith('image/')
}

async function onUploadBalanceFile(options: any) {
  if (balanceApplyFiles.value.length + balanceUploading.value >= MAX_BALANCE_FILES) {
    ElMessage.warning(`最多上传 ${MAX_BALANCE_FILES} 个附件`)
    options.onError?.(new Error('too many'))
    return
  }
  balanceUploading.value++
  try {
    const file = await workflowApi.uploadVoucher(options.file)
    if (file?.id == null) {
      throw new Error('上传结果无效')
    }
    if (balanceApplyFiles.value.some((f) => f.id === file.id)) {
      options.onSuccess?.(file)
      return
    }
    if (balanceApplyFiles.value.length >= MAX_BALANCE_FILES) {
      ElMessage.warning(`最多上传 ${MAX_BALANCE_FILES} 个附件`)
      options.onError?.(new Error('too many'))
      return
    }
    balanceApplyFiles.value.push(file)
    ElMessage.success('附件已上传')
    options.onSuccess?.(file)
  } catch (e: any) {
    ElMessage.error(e.message || '上传失败')
    options.onError?.(e)
  } finally {
    balanceUploading.value = Math.max(0, balanceUploading.value - 1)
  }
}

function removeBalanceFile(index: number) {
  balanceApplyFiles.value.splice(index, 1)
}

async function submitReimburse() {
  if (!reimburseForm.companyId) {
    ElMessage.warning('请选择所属公司')
    return
  }
  if (!reimburseForm.amount || reimburseForm.amount <= 0) {
    ElMessage.warning('请填写报销金额')
    return
  }
  if (!reimburseForm.payMethodId) {
    ElMessage.warning('请选择收款方式，便于财务线下打款')
    return
  }
  if (!voucherFiles.value.length) {
    ElMessage.warning('请上传发票/凭证')
    return
  }
  const approval = await workflowApi.submit({
    type: 'REIMBURSE_PERSONAL',
    title: '个人报销',
    amount: reimburseForm.amount,
    companyId: reimburseForm.companyId,
    remark: reimburseForm.remark,
    voucherFileIds: voucherFiles.value.map((f) => f.id),
    payload: { payMethodId: reimburseForm.payMethodId },
  })
  ElMessage.success(`${approvalFlowTip(approval)}。后续：上传回执 → 确认到账后从公司总账扣款，不进个人钱包`)
  reimburseDialog.value = false
  voucherFiles.value = []
  await Promise.all([loadBalance(), loadBoard(), loadApprovals()])
}

async function submitWithdraw() {
  if (!withdrawForm.companyId) {
    ElMessage.warning('请选择所属公司')
    return
  }
  if (!withdrawForm.amount || withdrawForm.amount <= 0) {
    ElMessage.warning('请填写提现金额')
    return
  }
  if (!withdrawForm.payMethodId) {
    ElMessage.warning('请选择收款方式，便于财务线下打款')
    return
  }
  if (withdrawForm.withVoucher && !withdrawVoucherFiles.value.length) {
    ElMessage.warning('有凭证提现请上传凭证')
    return
  }
  if (withdrawUploading.value > 0) {
    ElMessage.warning('凭证正在上传，请稍候')
    return
  }
  const voucherIds = withdrawForm.withVoucher
    ? withdrawVoucherFiles.value.map((f) => f.id).filter((id) => id != null)
    : undefined
  if (withdrawForm.withVoucher && !voucherIds?.length) {
    ElMessage.warning('有凭证提现请上传凭证')
    return
  }
  const available = Number(wallet.value?.available ?? wallet.value?.balance ?? 0)
  if (withdrawForm.amount > available) {
    ElMessage.warning(`可用余额不足，当前可用 ¥${fmt(available)}`)
    return
  }
  const approval = await workflowApi.submit({
    type: 'WALLET_WITHDRAW',
    title: withdrawForm.withVoucher ? '钱包提现（有凭证）' : '钱包提现',
    amount: withdrawForm.amount,
    companyId: withdrawForm.companyId,
    remark: withdrawForm.remark,
    voucherFileIds: voucherIds,
    payload: {
      withVoucher: withdrawForm.withVoucher,
      taxMode: withdrawForm.withVoucher ? 'VOUCHER' : withdrawTaxMode.value,
      taxRate: withdrawForm.withVoucher
        ? 0
        : withdrawTaxMode.value === 'FLAT'
          ? withdrawTaxRate.value
          : undefined,
      tax: withdrawTax.value,
      net: withdrawNet.value,
      taxBreakdown: withdrawForm.withVoucher ? [] : withdrawTaxBreakdown.value,
      payMethodId: withdrawForm.payMethodId,
      voucherFileIds: voucherIds,
    },
  })
  ElMessage.success(`${approvalFlowTip(approval)}。后续：财务回执 → 确认到账`)
  withdrawDialog.value = false
  withdrawUploadGen++
  clearWithdrawVouchers()
  withdrawUploading.value = 0
  // 提交即冻结，立刻刷新可用余额，避免界面仍显示旧可用额
  await Promise.all([loadBalance(), loadBoard(), loadApprovals()])
}

async function loadApprovals() {
  if (!userStore.hasPermission('workflow:list')) {
    todoApprovals.value = []
    return
  }
  try {
    const todo = await workflowApi.page({ page: 1, pageSize: 8, scope: 'todo' })
    todoApprovals.value = todo.list || []
  } catch {
    todoApprovals.value = []
  }
}

async function loadTasks() {
  const uid = userStore.user?.id
  const seq = ++taskLoadSeq
  if (!uid) {
    calendarTasks.value = []
    return
  }
  try {
    const related = (await bizApi.taskRelated()) || []
    let list = related
    if (seeAllProjects.value && taskScope.value === 'all') {
      try {
        const res = await bizApi.taskPage({ page: 1, pageSize: 200 })
        list = res.list || []
      } catch {
        list = related
      }
    }
    if (seq !== taskLoadSeq) return
    calendarTasks.value = list
  } catch {
    if (seq !== taskLoadSeq) return
    calendarTasks.value = []
  }
}

function panelQueryParams(page: number) {
  const params: Record<string, unknown> = { page, pageSize: TASK_PAGE_SIZE }
  if (taskPanelProjectId.value != null) params.projectId = taskPanelProjectId.value
  return params
}

async function fetchPanelPage(page: number) {
  const params = panelQueryParams(page)
  if (taskPanelTab.value === 'priority') {
    return bizApi.taskPriorityPage(params)
  }
  if (taskPanelTab.value === 'related' || !seeAllProjects.value) {
    return bizApi.taskRelatedPage(params)
  }
  try {
    return await bizApi.taskPage(params)
  } catch {
    return bizApi.taskRelatedPage(params)
  }
}

function measurePanelViewport() {
  const el = taskPanelListRef.value
  if (!el) return
  panelViewportH.value = el.clientHeight
}

async function fillPanelIfNeeded(seq: number) {
  await nextTick()
  measurePanelViewport()
  const el = taskPanelListRef.value
  if (!el || seq !== panelLoadSeq) return
  if (panelHasMore.value && el.scrollHeight <= el.clientHeight + 8) {
    await loadMorePanelTasks()
  }
}

async function loadPanelTasks(reset = true) {
  const uid = userStore.user?.id
  if (!uid) {
    panelTaskList.value = []
    panelTaskTotal.value = 0
    panelTaskPage.value = 0
    return
  }
  if (!reset) {
    if (!panelHasMore.value || panelTaskLoadingMore.value || panelTaskLoading.value) return
    panelTaskLoadingMore.value = true
  } else {
    panelLoadSeq += 1
    panelTaskList.value = []
    panelTaskTotal.value = 0
    panelTaskPage.value = 0
    panelScrollTop.value = 0
    panelTaskLoading.value = true
    panelTaskLoadingMore.value = false
    await nextTick()
    if (taskPanelListRef.value) taskPanelListRef.value.scrollTop = 0
  }
  const seq = panelLoadSeq
  const nextPage = reset ? 1 : panelTaskPage.value + 1
  try {
    const res = await fetchPanelPage(nextPage)
    if (seq !== panelLoadSeq) return
    const list = Array.isArray(res?.list) ? res.list : []
    if (!list.length) {
      panelTaskTotal.value = reset ? 0 : panelTaskList.value.length
      panelTabTotals[taskPanelTab.value] = panelTaskTotal.value
      if (reset) panelTaskList.value = []
    } else {
      panelTaskTotal.value = Number(res?.total || 0)
      panelTabTotals[taskPanelTab.value] = panelTaskTotal.value
      panelTaskPage.value = nextPage
      panelTaskList.value = reset ? list : panelTaskList.value.concat(list)
    }
  } catch {
    if (seq !== panelLoadSeq) return
    if (reset) {
      panelTaskList.value = []
      panelTaskTotal.value = 0
      panelTabTotals[taskPanelTab.value] = 0
    }
  } finally {
    if (seq === panelLoadSeq) {
      panelTaskLoading.value = false
      panelTaskLoadingMore.value = false
    }
  }
  if (seq === panelLoadSeq) await fillPanelIfNeeded(seq)
}

async function loadMorePanelTasks() {
  if (panelTaskLoading.value || panelTaskLoadingMore.value || !panelHasMore.value) return
  await loadPanelTasks(false)
}

function onTaskPanelScroll(e: Event) {
  const el = e.target as HTMLElement
  panelScrollTop.value = el.scrollTop
  panelViewportH.value = el.clientHeight
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 48) {
    void loadMorePanelTasks()
  }
}

function bindPanelListObserver() {
  panelListObserver?.disconnect()
  const el = taskPanelListRef.value
  if (!el || typeof ResizeObserver === 'undefined') return
  panelListObserver = new ResizeObserver(() => {
    measurePanelViewport()
  })
  panelListObserver.observe(el)
}

function onTaskScopeChange() {
  void loadTasks()
}

async function loadLeaves() {
  if (!canViewLeave.value) {
    myLeaves.value = []
    return
  }
  const seq = ++leaveLoadSeq
  const d = calendarDate.value
  const start = new Date(d.getFullYear(), d.getMonth() - 1, 1)
  const end = new Date(d.getFullYear(), d.getMonth() + 2, 0)
  try {
    const list = await bizApi.myLeave({
      start: dayKey(start),
      end: dayKey(end),
    })
    if (seq !== leaveLoadSeq) return
    myLeaves.value = list || []
  } catch {
    if (seq !== leaveLoadSeq) return
    myLeaves.value = []
  }
}

async function loadProjects() {
  try {
    myProjects.value = (await bizApi.myProjects()) || []
  } catch {
    myProjects.value = []
  }
}

onMounted(async () => {
  welcomeNow.value = new Date()
  welcomeClockTimer = setInterval(() => {
    welcomeNow.value = new Date()
  }, 1000)
  void loadWelcomeWeather()

  loading.value = true
  try {
    const jobs: Promise<unknown>[] = [loadApprovals(), loadTasks(), loadPanelTasks(true), loadProjects(), loadLeaves()]
    if (canViewLeave.value && !canSeeWallet.value) {
      jobs.push(sysApi.myCompanies().then((rows) => { companies.value = rows || [] }).catch(() => { companies.value = [] }))
    }
    if (canSeeWallet.value) {
      jobs.push(
        (async () => {
          try {
            companies.value = await sysApi.myCompanies()
          } catch {
            companies.value = []
          }
          try {
            projects.value = (await bizApi.projectList()) || []
          } catch {
            projects.value = []
          }
          await Promise.all([loadBalance(), loadLedger(), loadBoard()])
        })(),
      )
    }
    await Promise.all(jobs)
  } finally {
    loading.value = false
  }
  bindPanelListObserver()
})

watch(taskPanelTab, () => {
  void loadPanelTasks(true)
})

watch(taskPanelProjectId, () => {
  panelTabTotals.all = 0
  panelTabTotals.related = 0
  panelTabTotals.priority = 0
  void loadPanelTasks(true)
})

watch(
  () => {
    const d = calendarDate.value
    return `${d.getFullYear()}-${d.getMonth()}`
  },
  () => {
    void loadLeaves()
  },
)

onUnmounted(() => {
  if (welcomeClockTimer) clearInterval(welcomeClockTimer)
  panelListObserver?.disconnect()
})
</script>

<template>
  <div v-loading="loading" class="account">
    <section class="page-card welcome">
      <div class="welcome__text">
        <h2 class="welcome-title">{{ welcomeTimePhrase }}，{{ welcomeName }}</h2>
        <p class="welcome__meta">
          <span>{{ welcomeDate }}</span>
          <span class="welcome__sep" aria-hidden="true">·</span>
          <time>{{ welcomeClock }}</time>
          <template v-if="welcomeWeather">
            <span class="welcome__sep" aria-hidden="true">·</span>
            <span>{{ welcomeWeather.label }} {{ welcomeWeather.temp }}°C</span>
          </template>
        </p>
      </div>
      <div v-if="!canSeeWallet" class="welcome__aside">
        <el-button type="primary" @click="openReimburse">去发起报销</el-button>
      </div>
    </section>

    <section class="page-card hub-panel">
      <div class="sec-head hub-panel__head">
        <div>
          <h3>工作台</h3>
        </div>
        <div class="hub-panel__shortcuts">
          <div class="hub-panel__group">
            <span class="hub-panel__group-label">业务</span>
            <div class="hub-panel__actions">
              <button type="button" class="hub-chip hub-chip--violet" @click="goApprovalCenter('todo')">
                <span class="hub-chip__icon" aria-hidden="true">
                  <el-icon :size="18"><Stamp /></el-icon>
                  <i v-if="todoApprovals.length" class="hub-chip__badge">{{ todoApprovals.length > 99 ? '99+' : todoApprovals.length }}</i>
                </span>
                <strong>待我审批</strong>
              </button>
            </div>
          </div>
          <div class="hub-panel__group-divider" aria-hidden="true"></div>
          <div class="hub-panel__group">
            <span class="hub-panel__group-label">财务</span>
            <div class="hub-panel__actions">
              <button type="button" class="hub-chip hub-chip--indigo" @click="openReimburse">
                <span class="hub-chip__icon" aria-hidden="true">
                  <el-icon :size="18"><Ticket /></el-icon>
                </span>
                <strong>发起报销</strong>
              </button>
              <button
                v-if="canSeeWallet"
                type="button"
                class="hub-chip hub-chip--cyan"
                @click="openBalanceApply"
              >
                <span class="hub-chip__icon" aria-hidden="true">
                  <el-icon :size="18"><Coin /></el-icon>
                </span>
                <strong>申请项目余额</strong>
              </button>
              <button
                v-if="canSeeWallet"
                type="button"
                class="hub-chip hub-chip--violet"
                @click="openWithdraw"
              >
                <span class="hub-chip__icon" aria-hidden="true">
                  <el-icon :size="18"><CreditCard /></el-icon>
                </span>
                <strong>申请提现</strong>
              </button>
            </div>
          </div>
        </div>
      </div>

      <template v-if="canSeeWallet">
        <div v-if="walletBoard" class="hub-panel__board">
          <div class="hub-panel__metrics">
            <div class="hub-metric hub-metric--indigo">
              <div class="hub-metric__body">
                <span>余额</span>
                <b>¥ {{ fmt(walletBoard.balance) }}</b>
              </div>
              <el-icon class="hub-metric__glyph" :size="40"><Wallet /></el-icon>
            </div>
            <div
              class="hub-metric hub-metric--amber"
              :class="{ 'is-clickable': Number(walletBoard.frozen) > 0 }"
              :role="Number(walletBoard.frozen) > 0 ? 'button' : undefined"
              :tabindex="Number(walletBoard.frozen) > 0 ? 0 : undefined"
              @click="Number(walletBoard.frozen) > 0 && openFreezeDetail()"
              @keyup.enter="Number(walletBoard.frozen) > 0 && openFreezeDetail()"
            >
              <div class="hub-metric__body">
                <span>冻结</span>
                <b>¥ {{ fmt(walletBoard.frozen) }}</b>
                <em v-if="Number(walletBoard.frozen) > 0" class="metric-hint">查看明细</em>
              </div>
              <el-icon class="hub-metric__glyph" :size="40"><Lock /></el-icon>
            </div>
            <div class="hub-metric hub-metric--cyan">
              <div class="hub-metric__body">
                <span>可用</span>
                <b>¥ {{ fmt(walletBoard.available) }}</b>
              </div>
              <el-icon class="hub-metric__glyph" :size="40"><Coin /></el-icon>
            </div>
            <div class="hub-metric hub-metric--violet">
              <div class="hub-metric__body">
                <span>本月入账</span>
                <b class="in">¥ {{ fmt(walletBoard.monthIn) }}</b>
              </div>
              <el-icon class="hub-metric__glyph" :size="40"><TrendCharts /></el-icon>
            </div>
            <div class="hub-metric hub-metric--rose">
              <div class="hub-metric__body">
                <span>本月出账</span>
                <b class="out">¥ {{ fmt(walletBoard.monthOut) }}</b>
              </div>
              <el-icon class="hub-metric__glyph" :size="40"><CreditCard /></el-icon>
            </div>
            <div v-if="Number(walletBoard.pendingConfirmCount) > 0" class="hub-metric hub-metric--amber">
              <div class="hub-metric__body">
                <span>待确认</span>
                <b>{{ walletBoard.pendingConfirmCount }}</b>
              </div>
              <el-icon class="hub-metric__glyph" :size="40"><Warning /></el-icon>
            </div>
          </div>
          <div class="hub-panel__charts">
            <WalletBoardCharts
              :period="boardPeriod"
              :balance="walletBoard.balance"
              :frozen="walletBoard.frozen"
              :available="walletBoard.available"
              :trend="walletBoard.trend"
              :source-breakdown="walletBoard.sourceBreakdown"
              :biz-label="bizLabel"
              @update:period="onBoardPeriod"
            />
          </div>
        </div>

        <div class="hub-panel__ledger-block">
          <div class="hub-panel__ledger-head">
            <h4>流水明细</h4>
            <p>按时间、金额或摘要核对到账与扣款</p>
          </div>
          <el-form class="filter-bar hub-panel__filters" @submit.prevent="searchLedger">
            <el-form-item label="发生时间">
              <el-date-picker
                v-model="ledgerQuery.dateRange"
                type="daterange"
                unlink-panels
                clearable
                value-format="YYYY-MM-DD"
                range-separator="至"
                start-placeholder="开始日期"
                end-placeholder="结束日期"
                style="width: 260px"
                @change="searchLedger"
              />
            </el-form-item>
            <el-form-item label="公司">
              <el-select
                v-model="ledgerQuery.companyId"
                clearable
                filterable
                placeholder="全部"
                style="width: 160px"
                @change="onLedgerCompanyChange"
              >
                <el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="项目">
              <ProjectCascadeSelect
                v-model="ledgerQuery.projectId"
                :projects="projects"
                :company-id="ledgerQuery.companyId"
                mode="filter"
                top-placeholder="全部"
                child-placeholder="全部小项目"
                top-width="160px"
                child-width="160px"
                @change="searchLedger"
              />
            </el-form-item>
            <el-form-item label="类型">
              <el-select v-model="ledgerQuery.bizType" clearable placeholder="全部" style="width: 120px" @change="searchLedger">
                <el-option label="工资" value="SALARY" />
                <el-option label="报销" value="REIMBURSE" />
                <el-option label="项目余额" value="PAYOUT" />
                <el-option label="分成" value="SETTLE" />
                <el-option label="提现" value="WITHDRAW" />
                <el-option label="回退" value="ROLLBACK" />
              </el-select>
            </el-form-item>
            <el-form-item label="金额">
              <div class="amount-range">
                <el-input-number
                  v-model="ledgerQuery.minAmount"
                  :controls="false"
                  :precision="2"
                  placeholder="最小"
                  class="amount-input"
                  @change="searchLedger"
                />
                <span class="amount-sep">至</span>
                <el-input-number
                  v-model="ledgerQuery.maxAmount"
                  :controls="false"
                  :precision="2"
                  placeholder="最大"
                  class="amount-input"
                  @change="searchLedger"
                />
              </div>
            </el-form-item>
            <el-form-item label="关键词">
              <el-input
                v-model="ledgerQuery.keyword"
                clearable
                placeholder="编号 / 摘要"
                class="filter-keyword"
                @keyup.enter="searchLedger"
                @change="searchLedger"
              />
            </el-form-item>
            <el-form-item class="filter-actions">
              <el-button @click="resetLedger">重置</el-button>
            </el-form-item>
          </el-form>

          <div class="hub-panel__ledger">
            <el-table v-loading="ledgerLoading" :data="ledgers" stripe empty-text="暂无流水">
              <el-table-column label="时间" width="150">
                <template #default="{ row }">{{ fmtTime(row.occurTime) }}</template>
              </el-table-column>
              <el-table-column prop="bizNo" label="编号" width="160" show-overflow-tooltip />
              <el-table-column label="类型" width="90">
                <template #default="{ row }">{{ bizLabel(row.bizType) }}</template>
              </el-table-column>
              <el-table-column label="公司" min-width="120" show-overflow-tooltip>
                <template #default="{ row }">{{ row.companyName || '—' }}</template>
              </el-table-column>
              <el-table-column label="项目" min-width="120" show-overflow-tooltip>
                <template #default="{ row }">{{ row.projectName || '—' }}</template>
              </el-table-column>
              <el-table-column label="摘要" min-width="180" show-overflow-tooltip>
                <template #default="{ row }">
                  {{ ledgerTitle(row) }}
                </template>
              </el-table-column>
              <el-table-column label="金额" width="130" align="right">
                <template #default="{ row }">
                  <span :class="Number(row.amount) >= 0 ? 'in' : 'out'">
                    {{ Number(row.amount) >= 0 ? '+' : '' }}{{ fmt(row.amount) }}
                  </span>
                </template>
              </el-table-column>
            </el-table>
            <div v-if="ledgerTotal > ledgerQuery.pageSize" class="page-footer">
              <el-pagination
                v-model:current-page="ledgerQuery.page"
                :page-size="ledgerQuery.pageSize"
                :total="ledgerTotal"
                layout="total, prev, pager, next"
                @current-change="loadLedger"
              />
            </div>
          </div>
        </div>
      </template>
    </section>

    <div class="task-cal-row">
      <div class="task-panel-slot">
      <section class="page-card task-panel">
        <div class="sec-head">
          <div>
            <h3>任务</h3>
          </div>
          <el-button
            v-if="userStore.hasPermission('project:task:list')"
            plain
            type="primary"
            @click="goTaskManage"
          >
            去任务管理
          </el-button>
        </div>
        <div class="task-panel__body">
          <aside class="task-panel__projects">
            <button
              type="button"
              class="task-panel__project task-panel__project--all"
              :class="{ 'is-active': taskPanelProjectId == null }"
              @click="selectTaskProject(null)"
            >
              <div>
                <b>全部项目</b>
                <span>{{ seeAllProjects ? '查看全部' : '我参与的' }}</span>
              </div>
              <em>{{ myProjects.length }}</em>
            </button>
            <div class="task-panel__project-list">
              <button
                v-for="p in myProjects"
                :key="p.id"
                type="button"
                class="task-panel__project"
                :class="{ 'is-active': Number(taskPanelProjectId) === Number(p.id) }"
                @click="selectTaskProject(Number(p.id))"
              >
                <div>
                  <b>{{ p.name }}</b>
                  <span>{{ p.code || '—' }} · {{ projectRole(p) }}</span>
                </div>
                <em>{{ p.statusLabel || p.status || '—' }}</em>
              </button>
              <div v-if="!myProjects.length" class="task-panel__empty">{{ seeAllProjects ? '暂无项目' : '暂无参与项目' }}</div>
            </div>
          </aside>

          <div class="task-panel__main">
            <div class="task-panel__tabs" role="tablist">
              <button
                type="button"
                role="tab"
                class="task-tab"
                :class="{ 'is-active': taskPanelTab === 'all' }"
                :aria-selected="taskPanelTab === 'all'"
                @click="taskPanelTab = 'all'"
              >
                <span>所有任务</span>
                <i v-if="panelTabTotal('all')" class="task-tab-badge">{{ panelTabTotal('all') > 99 ? '99+' : panelTabTotal('all') }}</i>
              </button>
              <button
                type="button"
                role="tab"
                class="task-tab"
                :class="{ 'is-active': taskPanelTab === 'related' }"
                :aria-selected="taskPanelTab === 'related'"
                @click="taskPanelTab = 'related'"
              >
                <span>与我相关</span>
                <i v-if="panelTabTotal('related')" class="task-tab-badge">{{ panelTabTotal('related') > 99 ? '99+' : panelTabTotal('related') }}</i>
              </button>
              <button
                type="button"
                role="tab"
                class="task-tab"
                :class="{ 'is-active': taskPanelTab === 'priority' }"
                :aria-selected="taskPanelTab === 'priority'"
                @click="taskPanelTab = 'priority'"
              >
                <span>优先事项</span>
                <i v-if="panelTabTotal('priority')" class="task-tab-badge">{{ panelTabTotal('priority') > 99 ? '99+' : panelTabTotal('priority') }}</i>
              </button>
            </div>

            <div
              ref="taskPanelListRef"
              class="task-panel__list"
              v-loading="panelTaskLoading"
              @scroll.passive="onTaskPanelScroll"
            >
              <div v-if="!panelTaskList.length && !panelTaskLoading" class="task-panel__empty">{{ panelTaskEmpty }}</div>
              <div
                v-else-if="panelTaskList.length"
                class="task-panel__virtual"
                :style="{ height: panelVirtualHeight + 'px' }"
              >
                <div class="task-panel__virtual-inner" :style="{ transform: `translateY(${panelVirtualOffset}px)` }">
                  <template v-if="taskPanelTab === 'priority'">
                    <button
                      v-for="{ item, idx } in panelVirtualItems"
                      :key="'p' + item.id"
                      type="button"
                      class="task-panel__item task-panel__item--priority"
                      @click="openTask(item)"
                    >
                      <i class="task-panel__index">{{ idx + 1 }}</i>
                      <el-tag :type="riskTypeMap[item.riskType]?.type || 'info'" size="small">
                        {{ riskTypeMap[item.riskType]?.label || '关注' }}
                      </el-tag>
                      <div class="task-panel__item-main">
                        <strong>{{ item.title }}</strong>
                        <span>{{ item.projectName || '—' }}</span>
                      </div>
                      <div class="task-panel__item-meta">
                        <strong>{{ item.dueDate || '未设日期' }}</strong>
                        <span>{{ taskStatusMap[item.status] || '未知状态' }}</span>
                      </div>
                    </button>
                  </template>
                  <template v-else>
                    <button
                      v-for="{ item: t, idx } in panelVirtualItems"
                      :key="t.id"
                      type="button"
                      class="task-panel__item"
                      @click="openTask(t)"
                    >
                      <i class="task-panel__index">{{ idx + 1 }}</i>
                      <div class="task-panel__item-main">
                        <strong>{{ t.title }}</strong>
                        <span>{{ t.projectName || '—' }} · 截止 {{ t.dueDate || '未设' }}</span>
                      </div>
                      <em :class="{ overdue: t.overdue }">{{ t.statusLabel || taskStatusMap[t.status] || t.status || '—' }}</em>
                    </button>
                  </template>
                </div>
              </div>
              <div v-if="panelTaskLoadingMore" class="task-panel__more">加载中...</div>
              <div v-else-if="panelTaskList.length && !panelHasMore && panelTaskTotal > TASK_PAGE_SIZE" class="task-panel__more">已加载全部</div>
            </div>
          </div>
        </div>
      </section>
      </div>

      <section class="page-card cal-section">
        <div class="sec-head">
          <div>
            <h3>任务与考勤日历</h3>
            <p class="sec-tip">
              点日期查看当天开始和截止的任务
              <template v-if="canViewLeave">；橙色标记为已通过请假。本月请假 {{ monthLeaveCount }} 天（无记录视为全勤）</template>
            </p>
          </div>
          <div class="sec-head-actions">
            <el-button v-if="canViewLeave" plain type="warning" @click="openLeaveApply()">申请请假</el-button>
            <el-radio-group
              v-if="showTaskScopeToggle"
              v-model="taskScope"
              size="small"
              @change="onTaskScopeChange"
            >
              <el-radio-button value="mine">我的</el-radio-button>
              <el-radio-button value="all">全部</el-radio-button>
            </el-radio-group>
            <el-button
              v-if="userStore.hasPermission('project:task:list')"
              plain
              type="primary"
              @click="router.push('/project/task')"
            >
              去任务管理
            </el-button>
          </div>
        </div>
        <div class="cal-wrap">
          <el-calendar v-model="calendarDate">
            <template #date-cell="{ data }">
              <div :class="['cell', cellClass(data.day)]">
                <span class="day-num">{{ data.day.split('-')[2] }}</span>
                <em v-if="tasksByDay[data.day]" class="dot">{{ tasksByDay[data.day] }}</em>
                <i v-if="leaveByDay[data.day]" class="leave-mark" title="请假" />
              </div>
            </template>
          </el-calendar>
          <div class="day-panel">
            <h4>{{ selectedDay === todayKey ? '今天' : selectedDay }}</h4>
            <template v-if="dayLeaves.length">
              <h5 class="day-sub">请假</h5>
              <div v-for="r in dayLeaves" :key="'leave-' + r.id" class="row leave-row">
                <div>
                  <b>请假</b>
                  <span>{{ r.companyName || '—' }}{{ r.reason ? ` · ${r.reason}` : '' }}</span>
                </div>
                <em>已记考勤</em>
              </div>
            </template>
            <div class="day-task-summary" aria-label="当日任务统计">
              <span><b>{{ dayStartingTasks.length }}</b> 项开始</span>
              <span><b>{{ dayDueTasks.length }}</b> 项截止</span>
              <span v-if="canViewLeave"><b>{{ dayLeaves.length ? '请假' : '正常' }}</b> 考勤</span>
            </div>
            <h5 class="day-sub">任务</h5>
            <div
              v-for="t in dayTasks"
              :key="t.id"
              class="row"
              @click="openTask(t)"
            >
              <div>
                <b>{{ t.title }}</b>
                <span>{{ taskDayLabel(t) }} · {{ t.projectName || '—' }} · {{ t.participantNames?.length ? `参与人 ${t.participantNames.join('、')}` : '无参与人' }}</span>
              </div>
              <em :class="{ overdue: t.overdue }">{{ t.statusLabel || t.status || '—' }}</em>
            </div>
            <div v-if="!dayTasks.length && !(canViewLeave && dayLeaves.length)" class="empty">这一天没有开始或截止任务</div>
          </div>
        </div>
      </section>
    </div>

    <el-dialog v-model="leaveDialog" title="请假申请" width="min(480px, calc(100vw - 24px))" :close-on-click-modal="false">
      <el-form label-width="88px">
        <el-form-item label="所属公司" required>
          <el-select v-model="leaveForm.companyId" filterable placeholder="选择公司" style="width: 100%">
            <el-option v-for="company in companies" :key="company.id" :label="company.name" :value="Number(company.id)" />
          </el-select>
        </el-form-item>
        <el-form-item label="请假日期" required>
          <el-date-picker v-model="leaveForm.dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至"
            start-placeholder="开始日期" end-placeholder="结束日期" unlink-panels style="width: 100%" />
        </el-form-item>
        <el-form-item label="请假事由" required>
          <el-input v-model="leaveForm.reason" type="textarea" :rows="4" maxlength="300" show-word-limit placeholder="请填写请假事由" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="leaveDialog = false">取消</el-button>
        <el-button type="primary" :loading="leaveSubmitting" @click="submitLeaveApply">提交审批</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reimburseDialog" title="个人报销" width="480px" @closed="voucherFiles = []">
      <p class="sec-tip" style="margin: 0 0 12px">财务回执并由你确认到账后，只从公司总账扣款；个人钱包余额不变。收款方式用于线下打款。</p>
      <el-form label-width="88px">
        <el-form-item label="所属公司" required>
          <el-select v-model="reimburseForm.companyId" filterable placeholder="选择公司" style="width: 100%">
            <el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="金额" required>
          <el-input-number v-model="reimburseForm.amount" :min="0.01" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="收款方式" required>
          <el-select
            v-model="reimburseForm.payMethodId"
            filterable
            :placeholder="myPayMethods.length ? '选择收款方式' : '请先在员工档案配置'"
            style="width: 100%"
          >
            <el-option v-for="m in myPayMethods" :key="m.id" :label="payMethodLabel(m)" :value="Number(m.id)" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="reimburseForm.remark" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="发票凭证" required>
          <el-upload :http-request="onUploadVoucher" :show-file-list="false" accept="image/*,.pdf">
            <el-button :loading="uploading">上传发票</el-button>
          </el-upload>
          <div v-for="(f, i) in voucherFiles" :key="f.id" class="voucher-row">
            <span>{{ f.originalName || f.name || f.id }}</span>
            <el-button link type="danger" @click="removeVoucher(i)">移除</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reimburseDialog = false">取消</el-button>
        <el-button type="primary" @click="submitReimburse">提交审批</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="withdrawDialog"
      title="申请提现"
      width="480px"
      @closed="onWithdrawDialogClosed"
    >
      <el-form label-width="88px">
        <el-form-item label="所属公司" required>
          <el-select v-model="withdrawForm.companyId" filterable placeholder="选择公司" style="width: 100%">
            <el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="提现类型" required>
          <el-radio-group v-model="withdrawForm.withVoucher">
            <el-radio :value="false">无凭证（扣税）</el-radio>
            <el-radio :value="true">有凭证（免税）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="提现全额" required>
          <el-input-number v-model="withdrawForm.amount" :min="0.01" :precision="2" style="width: 100%" />
        </el-form-item>
        <template v-if="withdrawForm.withVoucher">
          <el-form-item label="凭证" required>
            <div class="attach-field">
              <el-upload
                :http-request="onUploadWithdrawVoucher"
                :show-file-list="false"
                accept="image/*,.pdf"
                multiple
                :disabled="withdrawVoucherFiles.length >= MAX_WITHDRAW_VOUCHERS"
              >
                <el-button :loading="withdrawUploading > 0" size="small">上传凭证</el-button>
              </el-upload>
              <p class="sec-tip">
                有凭证提现须上传至少 1 个凭证，确认后不扣税；支持一次选择多张图片或 PDF，最多 {{ MAX_WITHDRAW_VOUCHERS }} 个
              </p>
              <div v-if="withdrawVoucherFiles.length" class="attach-gallery">
                <div v-for="(f, i) in withdrawVoucherFiles" :key="f.id ?? i" class="attach-item">
                  <a v-if="isImage(f)" :href="fileUrl(f)" target="_blank" rel="noopener" class="attach-thumb">
                    <img :src="fileUrl(f)" :alt="f.originalName || f.name" />
                  </a>
                  <a :href="fileUrl(f)" target="_blank" rel="noopener" class="attach-name">
                    {{ f.originalName || f.name || f.id }}
                  </a>
                  <el-button link type="danger" @click="removeWithdrawVoucher(i)">移除</el-button>
                </div>
              </div>
            </div>
          </el-form-item>
          <el-form-item label="税额">
            <span>¥ 0.00（有凭证免税）</span>
          </el-form-item>
          <el-form-item label="预计到手">
            <strong>¥ {{ fmt(withdrawNet) }}</strong>
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item label="计税方式">
            <span v-if="withdrawTaxMode === 'TIER'">阶梯累进</span>
            <span v-else>一口价 {{ (Number(withdrawTaxRate) * 100).toFixed(1) }}%</span>
          </el-form-item>
          <el-form-item label="税额">
            <span>¥ {{ fmt(withdrawTax) }}</span>
          </el-form-item>
          <el-form-item v-if="withdrawTaxBreakdown.length" label="分档明细">
            <ul class="tax-breakdown-mini">
              <li v-for="(b, i) in withdrawTaxBreakdown" :key="i">
                {{ b.minAmount }}~{{ b.maxAmount == null ? '∞' : b.maxAmount }}
                · {{ (Number(b.taxRate) * 100).toFixed(1) }}%
                · 税 ¥{{ fmt(b.tax) }}
              </li>
            </ul>
          </el-form-item>
          <el-form-item label="预计到手">
            <strong>¥ {{ fmt(withdrawNet) }}</strong>
          </el-form-item>
        </template>
        <el-form-item label="收款方式" required>
          <el-select
            v-model="withdrawForm.payMethodId"
            filterable
            :placeholder="myPayMethods.length ? '选择收款方式' : '请先在员工档案配置'"
            style="width: 100%"
          >
            <el-option v-for="m in myPayMethods" :key="m.id" :label="payMethodLabel(m)" :value="Number(m.id)" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="withdrawForm.remark" type="textarea" :rows="2" />
        </el-form-item>
        <p class="sec-tip">
          {{
            withdrawForm.withVoucher
              ? '确认后：钱包扣提现全额（有凭证不扣税），到手金额线下打款；公司余额不变，个人合计与系统内资金同步下降。'
              : '确认后：钱包扣提现全额，到手=全额−税额（线下打款）；公司余额不变，个人合计与系统内资金同步下降。'
          }}
        </p>
      </el-form>
      <template #footer>
        <el-button @click="withdrawDialog = false">取消</el-button>
        <el-button type="primary" :disabled="withdrawUploading > 0" @click="submitWithdraw">提交审批</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="balanceApplyDialog" title="申请项目余额" width="520px" @closed="balanceApplyFiles = []">
      <el-form label-width="88px">
        <el-form-item label="项目" required>
          <el-select
            v-model="balanceApplyForm.projectId"
            filterable
            placeholder="选择有结余的重点/重大项目"
            style="width: 100%"
          >
            <el-option
              v-for="p in balanceApplyProjects"
              :key="p.projectId"
              :label="`${p.projectName || p.projectId}（结余 ¥${fmt(p.balance)}）`"
              :value="Number(p.projectId)"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="selectedBalanceProject" label="项目结余">
          <span>¥ {{ fmt(selectedBalanceProject.balance) }}</span>
          <span class="sec-tip" style="margin-left: 8px">
            待分成 ¥{{ fmt(selectedBalanceProject.sharePendingBalance) }} · 非分成 ¥{{ fmt(selectedBalanceProject.nonShareBalance) }}
          </span>
          <span v-if="selectedBalanceProject.companyName" class="sec-tip" style="margin-left: 8px">
            {{ selectedBalanceProject.companyName }}
          </span>
        </el-form-item>
        <el-form-item v-if="selectedBalanceProject" label="扣自" required>
          <el-radio-group v-model="balanceApplyForm.fundType">
            <el-radio value="SHARE_PENDING">待分成（默认）</el-radio>
            <el-radio value="NON_SHARE">非分成</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="申请金额" required>
          <el-input-number
            v-model="balanceApplyForm.amount"
            :min="0.01"
            :precision="2"
            :max="balanceApplyBucket(balanceApplyForm.fundType) || undefined"
            style="width: 100%"
          />
          <p class="sec-tip">当前可选余额 ¥{{ fmt(balanceApplyBucket(balanceApplyForm.fundType)) }}</p>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="balanceApplyForm.remark" type="textarea" :rows="2" placeholder="说明用途即可，附件选填" />
        </el-form-item>
        <el-form-item label="附件">
          <div class="attach-field">
            <el-upload
              :http-request="onUploadBalanceFile"
              :show-file-list="false"
              accept="image/*,.pdf,.doc,.docx,.xls,.xlsx"
              multiple
              :disabled="balanceBusy"
            >
              <el-button :loading="balanceUploading > 0" :disabled="submittingBalance">上传图片/文件</el-button>
            </el-upload>
            <p class="sec-tip">选填，支持图片、PDF 和常见办公文件</p>
            <div v-if="balanceApplyFiles.length" class="attach-gallery">
              <div v-for="(f, i) in balanceApplyFiles" :key="f.id ?? i" class="attach-item">
                <a v-if="isImage(f)" :href="fileUrl(f)" target="_blank" rel="noopener" class="attach-thumb">
                  <img :src="fileUrl(f)" :alt="f.originalName || f.name" />
                </a>
                <a :href="fileUrl(f)" target="_blank" rel="noopener" class="attach-name">
                  {{ f.originalName || f.name || f.id }}
                </a>
                <el-button link type="danger" @click="removeBalanceFile(i)">移除</el-button>
              </div>
            </div>
          </div>
        </el-form-item>
        <p class="sec-tip">流程：提交 → 财务审批通过 → 项目结余直接转入个人钱包（公司总账不变，无需回执）。</p>
      </el-form>
      <template #footer>
        <el-button :disabled="submittingBalance" @click="balanceApplyDialog = false">取消</el-button>
        <el-button type="primary" :loading="submittingBalance" :disabled="balanceBusy" @click="submitBalanceApply">提交审批</el-button>
      </template>
    </el-dialog>

    <el-drawer
      v-model="freezeDrawer"
      title="冻结明细"
      size="460px"
      append-to-body
    >
      <div class="freeze-head">
        <span>当前冻结合计</span>
        <strong>¥ {{ fmt(walletBoard?.frozen ?? wallet.frozen) }}</strong>
      </div>
      <el-table :data="freezeItems" stripe empty-text="暂无冻结明细">
        <el-table-column label="类型" width="96">
          <template #default="{ row }">
            <el-tag size="small" :type="row.type === 'ASSET' ? 'warning' : 'primary'" effect="plain">
              {{ row.typeLabel || row.type }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="180">
          <template #default="{ row }">
            <div class="freeze-title">{{ row.title || '—' }}</div>
            <div class="freeze-remark">{{ row.remark || '' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="金额" width="110" align="right">
          <template #default="{ row }">
            <span class="freeze-amt">¥ {{ fmt(row.amount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="" width="72" align="center">
          <template #default="{ row }">
            <el-button v-if="row.link" link type="primary" @click="openFreezeItem(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
      <p v-if="freezeUnexplained > 0" class="freeze-warn">
        另有 ¥{{ fmt(freezeUnexplained) }} 未能匹配到资产或提现单，请核对历史数据。
      </p>
      <p class="freeze-tip">
        冻结常见来源：资产领用按原值占用、提现申请在审批/回执/确认前占用。
      </p>
    </el-drawer>
  </div>
</template>

<style scoped>
.account {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.hub-panel {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.hub-panel__head {
  align-items: center;
  gap: 16px;
  margin-bottom: 0;
  padding-bottom: 14px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.05);
}

.hub-panel__shortcuts {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 10px 14px;
  flex: 1;
  min-width: 0;
}

.hub-panel__group {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.hub-panel__group-label {
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 600;
  color: var(--kk-text-muted);
  letter-spacing: 0.04em;
}

.hub-panel__group-divider {
  width: 1px;
  height: 28px;
  background: rgba(0, 0, 0, 0.08);
  flex-shrink: 0;
}

.hub-panel__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.hub-chip {
  position: relative;
  overflow: hidden;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 40px;
  padding: 0 14px 0 8px;
  border: 1px solid var(--kk-glass-border);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.42);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
  color: inherit;
  cursor: pointer;
  transition: background 0.15s ease, box-shadow 0.15s ease, transform 0.15s ease;
}

.hub-chip::before {
  content: "";
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 36px;
  border-radius: 12px 0 0 12px;
  opacity: 0.22;
  pointer-events: none;
}

.hub-chip:hover {
  background: var(--kk-glass-table-hover);
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.05);
  transform: translateY(-1px);
}

.hub-chip:focus-visible {
  outline: 2px solid var(--kk-primary);
  outline-offset: 2px;
}

.hub-chip__icon {
  position: relative;
  z-index: 1;
  width: 28px;
  height: 28px;
  border-radius: 9px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  background: rgba(255, 255, 255, 0.7);
  border: 1px solid rgba(255, 255, 255, 0.8);
}

.hub-chip__badge {
  position: absolute;
  top: -5px;
  right: -6px;
  min-width: 15px;
  height: 15px;
  padding: 0 4px;
  border-radius: 999px;
  background: var(--kk-danger, #ef4444);
  color: #fff;
  font-size: 10px;
  font-style: normal;
  font-weight: 600;
  line-height: 15px;
  text-align: center;
}

.hub-chip strong {
  position: relative;
  z-index: 1;
  font-size: 13px;
  font-weight: 600;
  color: var(--kk-text);
  white-space: nowrap;
}

.hub-chip--violet::before { background: #ddd6fe; }
.hub-chip--amber::before { background: #fde68a; }
.hub-chip--cyan::before { background: #a5f3fc; }
.hub-chip--indigo::before { background: #d4d4d8; }

.hub-chip--violet .hub-chip__icon { color: #7c3aed; }
.hub-chip--amber .hub-chip__icon { color: #d97706; }
.hub-chip--cyan .hub-chip__icon { color: #0891b2; }
.hub-chip--indigo .hub-chip__icon { color: var(--kk-primary); }

.hub-panel__board {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.hub-panel__metrics {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
}

.hub-metric {
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-width: 0;
  min-height: 92px;
  padding: 16px 14px 16px 16px;
  border-radius: var(--kk-radius-sm, 14px);
  background: rgba(255, 255, 255, 0.42);
  border: 1px solid var(--kk-glass-border);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03), 0 8px 22px rgba(0, 0, 0, 0.03);
  transition: transform 0.2s ease, box-shadow 0.2s ease, background 0.15s ease;
}

.hub-metric::before {
  content: "";
  position: absolute;
  right: -20px;
  top: 50%;
  width: 88px;
  height: 88px;
  border-radius: 50%;
  transform: translateY(-50%);
  filter: blur(28px);
  opacity: 0.22;
  pointer-events: none;
}

.hub-metric:hover {
  transform: translateY(-1px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.06);
}

.hub-metric__body {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.hub-metric__body span {
  font-size: 13px;
  font-weight: 500;
  color: var(--kk-text-secondary);
}

.hub-metric__body b {
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
  color: var(--kk-text);
  line-height: 1.15;
  white-space: nowrap;
}

.hub-metric__glyph {
  position: relative;
  z-index: 1;
  flex-shrink: 0;
  margin-right: -2px;
}

.hub-metric--indigo::before { background: #d4d4d8; }
.hub-metric--cyan::before { background: #a5f3fc; }
.hub-metric--violet::before { background: #ddd6fe; }
.hub-metric--amber::before { background: #fde68a; }
.hub-metric--rose::before { background: #fecdd3; }

.hub-metric--indigo .hub-metric__glyph { color: var(--kk-primary); }
.hub-metric--cyan .hub-metric__glyph { color: #0891b2; }
.hub-metric--violet .hub-metric__glyph { color: #7c3aed; }
.hub-metric--amber .hub-metric__glyph { color: #d97706; }
.hub-metric--rose .hub-metric__glyph { color: #e11d48; }

.hub-metric.is-clickable {
  cursor: pointer;
}

.hub-metric.is-clickable:hover {
  background: rgba(255, 255, 255, 0.55);
}

.hub-metric .metric-hint {
  font-size: 11px;
  font-style: normal;
  color: var(--el-color-primary);
  font-weight: 400;
}

.hub-panel__charts {
  min-width: 0;
}

.hub-panel__ledger-block {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-top: 2px;
}

.hub-panel__ledger-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px 12px;
}

.hub-panel__ledger-head h4 {
  margin: 0;
  font-size: 15px;
  font-weight: 650;
  color: var(--kk-text);
}

.hub-panel__ledger-head p {
  margin: 0;
  font-size: 12px;
  color: var(--kk-text-muted);
}

.hub-panel__filters {
  margin-bottom: 0;
}

.hub-panel__ledger {
  min-height: 0;
  max-height: 420px;
  overflow: auto;
  scrollbar-gutter: stable;
  padding: 4px;
  border-radius: var(--kk-radius-sm, 14px);
  border: 1px solid rgba(255, 255, 255, 0.62);
  background: rgba(255, 255, 255, 0.22);
}

.hub-panel__ledger :deep(.el-table) {
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-header-bg-color: var(--kk-glass-table-header);
  --el-table-row-hover-bg-color: var(--kk-glass-table-hover);
  background: transparent;
}

.hub-panel__ledger :deep(.el-table th.el-table__cell) {
  background: var(--kk-glass-table-header);
}

.task-cal-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.15fr);
  gap: 16px;
  align-items: stretch;
}

/* 占位随右侧定高；任务卡绝对铺满，内容内部滚动 */
.task-panel-slot {
  position: relative;
  min-width: 0;
  min-height: 0;
}

.task-panel-slot > .task-panel {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-sizing: border-box;
}

.task-panel {
  --task-head-h: 56px;
  --task-radius: 10px;
  --task-surface: rgba(255, 255, 255, 0.32);
  --task-surface-hover: rgba(255, 255, 255, 0.48);
  --task-surface-active: rgba(255, 255, 255, 0.62);
  --task-border: rgba(255, 255, 255, 0.72);
  --task-gap: 8px;
}

.task-panel .sec-head {
  margin-bottom: 14px;
  flex-shrink: 0;
  padding-bottom: 12px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.05);
}

.task-panel__body {
  display: grid;
  grid-template-columns: minmax(0, 4fr) minmax(0, 6fr);
  gap: 16px;
  flex: 1 1 auto;
  min-height: 0;
  align-items: stretch;
  overflow: hidden;
}

.task-panel__projects {
  display: flex;
  flex-direction: column;
  gap: var(--task-gap);
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  padding-right: 14px;
  border-right: 1px solid rgba(0, 0, 0, 0.05);
}

.task-panel__project-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex: 1 1 auto;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  padding-right: 4px;
  scrollbar-gutter: stable;
}

.task-panel__project {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  box-sizing: border-box;
  padding: 10px 12px;
  border: 1px solid transparent;
  border-radius: var(--task-radius);
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease, box-shadow 0.15s ease;
}

.task-panel__project > div {
  min-width: 0;
  flex: 1;
}

.task-panel__project:hover {
  background: var(--task-surface);
  border-color: rgba(255, 255, 255, 0.45);
}

.task-panel__project.is-active {
  background: var(--task-surface-active);
  border-color: var(--task-border);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
}

.task-panel__project.is-active b {
  color: var(--kk-primary);
}

.task-panel__project--all {
  flex-shrink: 0;
  box-sizing: border-box;
  min-height: var(--task-head-h);
  margin-bottom: 0;
  border-color: var(--task-border);
  background: var(--task-surface);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
}

.task-panel__project--all b {
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 0.01em;
  color: var(--kk-text);
}

.task-panel__project--all span {
  font-weight: 500;
  color: var(--kk-text-secondary);
}

.task-panel__project--all em,
.task-tab-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  min-width: 22px;
  height: 22px;
  padding: 0 6px;
  border-radius: 999px;
  background: rgba(24, 24, 27, 0.08);
  color: var(--kk-text);
  font-size: 12px;
  font-style: normal;
  font-weight: 650;
  line-height: 22px;
  font-variant-numeric: tabular-nums;
  text-align: center;
}

.task-panel__project--all.is-active,
.task-tab.is-active {
  background: var(--task-surface-active);
  border-color: rgba(255, 255, 255, 0.85);
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.04);
}

.task-panel__project--all.is-active b,
.task-tab.is-active {
  color: var(--kk-primary);
}

.task-panel__project--all.is-active em,
.task-tab.is-active .task-tab-badge {
  background: color-mix(in srgb, var(--kk-primary) 14%, transparent);
  color: var(--kk-primary);
}

.task-panel__project b {
  display: block;
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-panel__project span {
  display: block;
  margin-top: 2px;
  font-size: 12px;
  color: var(--kk-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-panel__project em {
  flex-shrink: 0;
  max-width: 4.8em;
  font-style: normal;
  font-size: 12px;
  color: var(--kk-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-panel__main {
  display: flex;
  flex-direction: column;
  gap: var(--task-gap);
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}

.task-panel__tabs {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--task-gap);
  flex-shrink: 0;
  box-sizing: border-box;
  height: var(--task-head-h);
  min-height: var(--task-head-h);
  margin: 0;
}

.task-tab {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-width: 0;
  height: 100%;
  padding: 0 12px;
  border: 1px solid var(--task-border);
  border-radius: var(--task-radius);
  background: var(--task-surface);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
  color: var(--kk-text-secondary);
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease, box-shadow 0.15s ease, border-color 0.15s ease;
}

.task-tab > span {
  overflow: hidden;
  text-overflow: ellipsis;
}

.task-tab:hover {
  color: var(--kk-text);
  background: var(--task-surface-hover);
}

.task-tab:focus-visible,
.task-panel__project:focus-visible,
.task-panel__item:focus-visible {
  outline: 2px solid var(--kk-primary);
  outline-offset: 2px;
}

.task-panel__list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex: 1 1 auto;
  min-height: 0;
  max-height: 100%;
  overflow-x: hidden;
  overflow-y: auto;
  padding: 4px;
  border-radius: var(--task-radius);
  border: 1px solid rgba(255, 255, 255, 0.55);
  background: rgba(255, 255, 255, 0.18);
  scrollbar-gutter: stable;
}

.task-panel__virtual {
  position: relative;
  width: 100%;
  flex: 0 0 auto;
}

.task-panel__virtual-inner {
  display: flex;
  flex-direction: column;
  gap: 4px;
  will-change: transform;
}

.task-panel__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  box-sizing: border-box;
  height: 52px;
  flex-shrink: 0;
  overflow: hidden;
  padding: 8px 12px;
  border: 1px solid transparent;
  border-radius: 9px;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;
}

.task-panel__index {
  flex-shrink: 0;
  width: 22px;
  font-style: normal;
  font-size: 12px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
  color: var(--kk-text-muted);
  text-align: center;
  line-height: 1;
}

.task-panel__item:hover {
  background: var(--task-surface-hover);
  border-color: rgba(255, 255, 255, 0.5);
}

.task-panel__item:hover .task-panel__item-main strong {
  color: var(--kk-primary);
}

.task-panel__item-main {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.task-panel__item-main strong {
  font-size: 13px;
  font-weight: 600;
  color: var(--kk-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: color 0.15s ease;
}

.task-panel__item-main span,
.task-panel__item-meta span {
  font-size: 12px;
  color: var(--kk-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-panel__item > em {
  flex-shrink: 0;
  font-style: normal;
  font-size: 12px;
  font-weight: 500;
  color: var(--kk-text-secondary);
  white-space: nowrap;
}

.task-panel__item > em.overdue {
  color: var(--kk-danger);
}

.task-panel__item--priority {
  display: grid;
  grid-template-columns: 22px 82px minmax(0, 1fr) minmax(84px, 104px);
  align-items: center;
  gap: 10px;
}

.task-panel__item-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 3px;
  min-width: 0;
  text-align: right;
}

.task-panel__item-meta strong {
  font-size: 12px;
  font-weight: 600;
  color: var(--kk-text-secondary);
}

.task-panel__empty {
  margin: auto 0;
  padding: 28px 12px;
  text-align: center;
  color: var(--kk-text-muted);
  font-size: 13px;
}

.task-panel__more {
  flex-shrink: 0;
  padding: 8px 4px 4px;
  text-align: center;
  font-size: 12px;
  color: var(--kk-text-muted);
}

.freeze-link {
  border: 0;
  padding: 0;
  background: transparent;
  color: var(--el-color-primary);
  cursor: pointer;
  font: inherit;
}
.freeze-link:hover {
  text-decoration: underline;
}
.freeze-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
  padding: 12px 14px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.42);
  border: 1px solid rgba(255, 255, 255, 0.72);
}
.freeze-head span {
  color: var(--kk-text-secondary);
  font-size: 13px;
}
.freeze-head strong {
  font-size: 18px;
  color: var(--kk-text);
}
.freeze-title {
  font-size: 13px;
  color: var(--kk-text);
  line-height: 1.4;
}
.freeze-remark {
  margin-top: 2px;
  font-size: 12px;
  color: var(--kk-text-muted);
  line-height: 1.35;
}
.freeze-amt {
  font-weight: 600;
  color: #b45309;
}
.freeze-tip,
.freeze-warn {
  margin-top: 12px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--kk-text-muted);
}
.freeze-warn {
  color: #b45309;
}
.voucher-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 6px;
  font-size: 12px;
}
.attach-field {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  width: 100%;
}
.attach-gallery {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}
.attach-item {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
}
.attach-thumb {
  width: 64px;
  height: 48px;
  border-radius: 8px;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.55);
  border: 1px solid rgba(255, 255, 255, 0.72);
  flex-shrink: 0;
}
.attach-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.attach-name {
  flex: 1;
  min-width: 0;
  color: var(--kk-primary);
  word-break: break-all;
}
.tax-breakdown-mini {
  margin: 0;
  padding: 0;
  list-style: none;
  font-size: 12px;
  color: var(--kk-text-secondary);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.welcome {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}

.welcome__text {
  min-width: 0;
}

.welcome-title {
  margin: 0;
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.03em;
  line-height: 1.2;
  color: var(--kk-text);
}

.welcome__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin: 8px 0 0;
  font-size: 13px;
  font-weight: 500;
  color: var(--kk-text-secondary);
}

.welcome__meta time {
  font-variant-numeric: tabular-nums;
}

.welcome__sep {
  color: var(--kk-text-muted);
  user-select: none;
}

.welcome__aside {
  flex-shrink: 0;
}

.sec-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 14px;
}

.sec-head-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  flex-shrink: 0;
}

.sec-head h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--kk-text);
}

.sec-tip {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--kk-text-secondary);
}

.sec-head :deep(.el-button) {
  flex-shrink: 0;
}

.sec-head :deep(.el-button--primary.is-plain) {
  background: rgba(24, 24, 27, 0.08) !important;
  box-shadow: none !important;
  transform: none !important;
  color: var(--kk-primary);
  border: 1px solid rgba(24, 24, 27, 0.18);
}

.sec-head :deep(.el-button--primary.is-plain:hover),
.sec-head :deep(.el-button--primary.is-plain:focus) {
  background: rgba(24, 24, 27, 0.14) !important;
  color: var(--kk-primary-dark);
}

.cal-wrap {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(240px, 1fr);
  gap: 16px;
  align-items: stretch;
}

.cal-section :deep(.el-calendar) {
  background: transparent;
}

.cal-section :deep(.el-calendar__header) {
  padding: 0 0 12px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
}

.cal-section :deep(.el-calendar__body) {
  padding: 8px 0 0;
}

.cal-section :deep(.el-calendar-table .el-calendar-day) {
  height: 52px;
  padding: 2px;
}

.cal-section :deep(.el-calendar-table td) {
  border-color: rgba(0, 0, 0, 0.04);
}

.cell {
  height: 100%;
  min-height: 44px;
  padding: 4px 6px;
  position: relative;
}

.cell.has-task {
  background: rgba(24, 24, 27, 0.08);
  border-radius: 8px;
  font-weight: 600;
  color: var(--kk-primary);
}

.cell.has-task.overdue {
  background: rgba(239, 68, 68, 0.1);
  color: var(--kk-danger);
}

.cell.has-leave {
  border-radius: 8px;
  box-shadow: inset 0 0 0 1.5px rgba(180, 83, 9, 0.45);
}

.cell.has-leave:not(.has-task) {
  background: rgba(245, 158, 11, 0.12);
  color: #b45309;
  font-weight: 600;
}

.day-num { font-size: 13px; }

.dot {
  position: absolute;
  right: 4px;
  bottom: 4px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 999px;
  background: var(--kk-primary);
  color: #fff;
  font-size: 10px;
  font-style: normal;
  line-height: 16px;
  text-align: center;
}

.cell.overdue .dot { background: var(--kk-danger); }

.leave-mark {
  position: absolute;
  left: 6px;
  bottom: 6px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #d97706;
}

.day-panel {
  background: rgba(255, 255, 255, 0.28);
  border: 1px solid rgba(255, 255, 255, 0.5);
  border-radius: var(--kk-radius-sm);
  padding: 16px;
  min-height: 280px;
  max-height: 420px;
  overflow: auto;
}

.day-sub {
  margin: 12px 0 8px;
  font-size: 12px;
  font-weight: 600;
  color: #64748b;
}

.day-panel > .day-sub:first-of-type {
  margin-top: 0;
}

.leave-row em {
  color: #b45309;
}

.day-panel h4 {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
  color: var(--kk-text);
}

.day-task-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin: 12px 0 14px;
}

.day-task-summary span {
  padding: 9px 8px;
  border: 1px solid var(--kk-border, #e5e7eb);
  border-radius: 8px;
  text-align: center;
  font-size: 12px;
  color: var(--kk-text-muted);
  background: var(--kk-bg-muted, #f8fafc);
}

.day-task-summary b {
  display: block;
  margin-bottom: 2px;
  font-size: 15px;
  color: var(--kk-text);
}

.row em.overdue { color: var(--kk-danger); }

.row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  padding: 10px 0;
  border-bottom: 1px solid rgba(0, 0, 0, 0.04);
  cursor: pointer;
}

.row:hover b { color: var(--kk-primary); }
.row b { display: block; font-size: 14px; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.row span { display: block; font-size: 12px; color: var(--kk-text-muted); margin-top: 2px; }
.row em { font-style: normal; font-size: 12px; color: var(--kk-text-secondary); white-space: nowrap; flex-shrink: 0; }
.empty { color: var(--kk-text-muted); font-size: 13px; padding: 12px 0; }
.in { color: var(--kk-success); font-weight: 600; font-variant-numeric: tabular-nums; }
.out { color: var(--kk-danger); font-weight: 600; font-variant-numeric: tabular-nums; }

@media (max-width: 1100px) {
  .welcome {
    flex-direction: column;
    align-items: stretch;
  }

  .welcome__aside {
    align-items: flex-start;
  }

  .hub-panel__group-divider {
    display: none;
  }

  .hub-panel__shortcuts {
    flex-direction: column;
    align-items: stretch;
  }

  .hub-panel__metrics {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .task-cal-row { grid-template-columns: 1fr; }
  .task-panel-slot {
    min-height: 420px;
  }
  .task-panel-slot > .task-panel {
    position: relative;
    inset: auto;
    min-height: 420px;
    max-height: 520px;
  }
  .task-panel__body {
    grid-template-columns: 1fr;
  }
  .task-panel__projects {
    max-height: 220px;
    border-right: 0;
    border-bottom: 1px solid rgba(0, 0, 0, 0.06);
    padding-right: 0;
    padding-bottom: 8px;
  }
  .cal-wrap { grid-template-columns: 1fr; }
}

@media (max-width: 640px) {
  .task-panel__item--priority {
    grid-template-columns: 22px 72px minmax(0, 1fr);
  }
  .task-panel__item-meta {
    grid-column: 2 / -1;
    flex-direction: row;
    justify-content: space-between;
    align-items: center;
    text-align: left;
  }
  .hub-panel__head {
    align-items: flex-start;
  }
  .hub-panel__shortcuts {
    justify-content: flex-start;
    width: 100%;
  }
  .hub-panel__metrics {
    grid-template-columns: 1fr 1fr;
  }
  .hub-metric__body b {
    font-size: 17px;
  }
}

@media (prefers-reduced-transparency: reduce) {
  .filter-bar,
  .day-panel,
  .hub-panel__board,
  .hub-metric,
  .hub-chip,
  .hub-panel__ledger {
    background: #fff;
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }

  .hub-chip::before {
    display: none;
  }
}
</style>
