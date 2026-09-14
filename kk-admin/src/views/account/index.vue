<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
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
const withdrawDialog = ref(false)
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
})
const voucherFiles = ref<any[]>([])
const uploading = ref(false)
const myPayMethods = ref<any[]>([])
const withdrawTaxRate = ref(0.2)
const withdrawTaxMode = ref<'FLAT' | 'TIER'>('FLAT')
const withdrawTaxBreakdown = ref<any[]>([])
const withdrawCalcTax = ref(0)
const withdrawCalcNet = ref(0)
let withdrawTaxSeq = 0

const todoApprovals = ref<any[]>([])
const mineApprovals = ref<any[]>([])
const myTasks = ref<any[]>([])
const calendarTasks = ref<any[]>([])
const myProjects = ref<any[]>([])
const calendarDate = ref(new Date())
/** admin/shareholder 可切全部；默认我的（创建或参与） */
const taskScope = ref<'mine' | 'all'>('mine')
let taskLoadSeq = 0

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
    ROLLBACK: '回退',
  } as Record<string, string>)[v || ''] || v || '—'
}

function payMethodLabel(m: any) {
  const type = m.methodTypeLabel || ({ BANK: '银行卡', ALIPAY: '支付宝', WECHAT: '微信' } as any)[m.methodType] || m.methodType
  const name = m.accountName ? `${m.accountName} · ` : ''
  const bank = m.methodType === 'BANK' && m.bankName ? `（${m.bankName}）` : ''
  return `${type} · ${name}${m.accountNo || ''}${bank}`
}

const withdrawTax = computed(() => Number(withdrawCalcTax.value || 0))
const withdrawNet = computed(() => Number(withdrawCalcNet.value || 0))

async function refreshWithdrawTax() {
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

const dayTasks = computed(() =>
  calendarTasks.value.filter((t) => String(t.dueDate || '').startsWith(selectedDay.value)),
)

const todayDueCount = computed(
  () => calendarTasks.value.filter((t) => String(t.dueDate || '').startsWith(todayKey.value)).length,
)

const tasksByDay = computed(() => {
  const map: Record<string, number> = {}
  for (const t of calendarTasks.value) {
    const key = String(t.dueDate || '').slice(0, 10)
    if (!key) continue
    map[key] = (map[key] || 0) + 1
  }
  return map
})

function cellClass(day: string) {
  const n = tasksByDay.value[day] || 0
  if (!n) return ''
  const overdue = calendarTasks.value.some(
    (t) => String(t.dueDate || '').startsWith(day) && (t.overdue || Number(t.status) === 0 || Number(t.status) === 1) && day < todayKey.value,
  )
  return overdue ? 'has-task overdue' : 'has-task'
}

const seeAllProjects = computed(() => {
  const roles = userStore.roles || []
  return roles.includes('admin') || roles.includes('shareholder')
})

const showTaskScopeToggle = computed(() => seeAllProjects.value)

const taskSectionTitle = computed(() =>
  showTaskScopeToggle.value && taskScope.value === 'all' ? '全部任务' : '我的任务',
)

const taskEmptyText = computed(() =>
  showTaskScopeToggle.value && taskScope.value === 'all' ? '暂无任务' : '暂无相关任务',
)

const canSeeWallet = computed(() => userStore.hasPermission('finance:wallet:list'))

function projectRole(p: any) {
  const uid = userStore.user?.id
  if (uid && p.ownerId === uid) return '负责人'
  if (seeAllProjects.value) return '全部可见'
  return '成员'
}

function openTask(t: any) {
  if (!userStore.hasPermission('project:task:list')) return
  router.push({ path: '/project/task', query: { projectId: String(t.projectId || '') } })
}

function searchLedger() {
  ledgerQuery.page = 1
  void loadLedger()
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

async function preparePayForm(form: { companyId?: number; payMethodId?: number; amount: number; remark: string }) {
  form.amount = 0
  form.remark = ''
  form.companyId = companies.value.length === 1 ? companies.value[0].id : undefined
  form.payMethodId = undefined
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
  await preparePayForm(reimburseForm)
  if (!myPayMethods.value.length) {
    ElMessage.warning('请先在员工档案中配置个人收款方式，否则无法提交提现/报销')
  }
  reimburseDialog.value = true
}

async function openWithdraw() {
  await preparePayForm(withdrawForm)
  if (!myPayMethods.value.length) {
    ElMessage.warning('请先在员工档案中配置个人收款方式，否则无法提交提现/报销')
  }
  await refreshWithdrawTax()
  withdrawDialog.value = true
}

watch(
  () => [withdrawForm.companyId, withdrawForm.amount] as const,
  () => {
    if (withdrawDialog.value) void refreshWithdrawTax()
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
  ElMessage.success(`${approvalFlowTip(approval)}。后续：上传回执 → 确认到账`)
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
  const available = Number(wallet.value?.available ?? wallet.value?.balance ?? 0)
  if (withdrawForm.amount > available) {
    ElMessage.warning(`可用余额不足，当前可用 ¥${fmt(available)}`)
    return
  }
  const approval = await workflowApi.submit({
    type: 'WALLET_WITHDRAW',
    title: '钱包提现',
    amount: withdrawForm.amount,
    companyId: withdrawForm.companyId,
    remark: withdrawForm.remark,
    payload: {
      taxMode: withdrawTaxMode.value,
      taxRate: withdrawTaxMode.value === 'FLAT' ? withdrawTaxRate.value : undefined,
      tax: withdrawTax.value,
      net: withdrawNet.value,
      taxBreakdown: withdrawTaxBreakdown.value,
      payMethodId: withdrawForm.payMethodId,
    },
  })
  ElMessage.success(`${approvalFlowTip(approval)}。后续：财务回执 → 确认到账`)
  withdrawDialog.value = false
  // 提交即冻结，立刻刷新可用余额，避免界面仍显示旧可用额
  await Promise.all([loadBalance(), loadBoard(), loadApprovals()])
}

async function loadApprovals() {
  if (!userStore.hasPermission('workflow:list')) {
    todoApprovals.value = []
    mineApprovals.value = []
    return
  }
  try {
    const [todo, mine] = await Promise.all([
      workflowApi.page({ page: 1, pageSize: 8, scope: 'todo' }),
      workflowApi.page({ page: 1, pageSize: 8, scope: 'mine' }),
    ])
    todoApprovals.value = todo.list || []
    mineApprovals.value = mine.list || []
  } catch {
    todoApprovals.value = []
    mineApprovals.value = []
  }
}

async function loadTasks() {
  const uid = userStore.user?.id
  const seq = ++taskLoadSeq
  if (!uid) {
    myTasks.value = []
    calendarTasks.value = []
    return
  }
  try {
    // 非 admin/shareholder 即使残留 all 也强制走 related
    const useAll = seeAllProjects.value && taskScope.value === 'all'
    let list: any[] = []
    if (useAll) {
      const res = await bizApi.taskPage({ page: 1, pageSize: 200 })
      list = res.list || []
    } else {
      list = (await bizApi.taskRelated()) || []
    }
    if (seq !== taskLoadSeq) return
    calendarTasks.value = list
    myTasks.value = list.slice(0, 10)
  } catch {
    if (seq !== taskLoadSeq) return
    myTasks.value = []
    calendarTasks.value = []
  }
}

function onTaskScopeChange() {
  void loadTasks()
}

async function loadProjects() {
  try {
    myProjects.value = (await bizApi.myProjects()) || []
  } catch {
    myProjects.value = []
  }
}

onMounted(async () => {
  loading.value = true
  try {
    const jobs: Promise<unknown>[] = [loadApprovals(), loadTasks(), loadProjects()]
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
})
</script>

<template>
  <div v-loading="loading" class="account">
    <div class="welcome">
      <h2 class="welcome-title">你好，{{ userStore.nickname || userStore.user?.username || '同事' }}</h2>
      <p class="welcome-desc">先看待办和余额，再按需查流水或进对应模块处理</p>
    </div>

    <div class="stat-grid" :class="{ 'stat-grid--no-wallet': !canSeeWallet }">
      <div v-if="canSeeWallet" class="stat-card stat-card--indigo">
        <div class="stat-body">
          <div class="stat-label">钱包余额</div>
          <div class="stat-value">¥ {{ fmt(wallet.balance) }}</div>
          <div v-if="Number(wallet.frozen) > 0" class="stat-sub">
            冻结 ¥{{ fmt(wallet.frozen) }} · 可用 ¥{{ fmt(wallet.available ?? (Number(wallet.balance || 0) - Number(wallet.frozen || 0))) }}
          </div>
        </div>
        <el-icon class="stat-glyph" :size="52"><Wallet /></el-icon>
      </div>
      <div
        class="stat-card stat-card--violet"
        role="button"
        tabindex="0"
        @click="router.push({ path: '/workflow/center', query: { scope: 'todo' } })"
        @keyup.enter="router.push({ path: '/workflow/center', query: { scope: 'todo' } })"
      >
        <div class="stat-body">
          <div class="stat-label">待我审批</div>
          <div class="stat-value">{{ todoApprovals.length }}</div>
        </div>
        <el-icon class="stat-glyph" :size="52"><Stamp /></el-icon>
      </div>
      <div class="stat-card stat-card--amber">
        <div class="stat-body">
          <div class="stat-label">今日到期</div>
          <div class="stat-value">{{ todayDueCount }}</div>
        </div>
        <el-icon class="stat-glyph" :size="52"><Calendar /></el-icon>
      </div>
      <div
        class="stat-card stat-card--cyan"
        role="button"
        tabindex="0"
        @click="userStore.hasPermission('project:list') && router.push('/project/list')"
        @keyup.enter="userStore.hasPermission('project:list') && router.push('/project/list')"
      >
        <div class="stat-body">
          <div class="stat-label">{{ seeAllProjects ? '全部项目' : '参与项目' }}</div>
          <div class="stat-value">{{ myProjects.length }}</div>
        </div>
        <el-icon class="stat-glyph" :size="52"><FolderOpened /></el-icon>
      </div>
    </div>

    <section v-if="canSeeWallet" class="page-card">
      <div class="sec-head">
        <div>
          <h3>钱包流水</h3>
          <p class="sec-tip">按时间、金额或摘要核对到账与扣款</p>
        </div>
        <div class="page-actions">
          <el-button type="primary" @click="openReimburse">去发起报销</el-button>
          <el-button @click="openWithdraw">申请提现</el-button>
        </div>
      </div>
      <div v-if="walletBoard" class="wallet-board">
        <div class="wallet-board__metrics">
          <div><span>余额</span><b>¥ {{ fmt(walletBoard.balance) }}</b></div>
          <div><span>冻结</span><b>¥ {{ fmt(walletBoard.frozen) }}</b></div>
          <div><span>可用</span><b>¥ {{ fmt(walletBoard.available) }}</b></div>
          <div><span>本月入账</span><b class="in">¥ {{ fmt(walletBoard.monthIn) }}</b></div>
          <div><span>本月出账</span><b class="out">¥ {{ fmt(walletBoard.monthOut) }}</b></div>
          <div v-if="Number(walletBoard.pendingConfirmCount) > 0">
            <span>待确认</span><b>{{ walletBoard.pendingConfirmCount }}</b>
          </div>
        </div>
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
      <el-form class="filter-bar" @submit.prevent="searchLedger">
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
          />
        </el-form-item>
        <el-form-item label="公司">
          <el-select
            v-model="ledgerQuery.companyId"
            clearable
            filterable
            placeholder="全部"
            style="width: 160px"
            @change="ledgerQuery.projectId = undefined"
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
          />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="ledgerQuery.bizType" clearable placeholder="全部" style="width: 120px">
            <el-option label="工资" value="SALARY" />
            <el-option label="报销" value="REIMBURSE" />
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
            />
            <span class="amount-sep">至</span>
            <el-input-number
              v-model="ledgerQuery.maxAmount"
              :controls="false"
              :precision="2"
              placeholder="最大"
              class="amount-input"
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
          />
        </el-form-item>
        <el-form-item class="filter-actions">
          <el-button type="primary" native-type="submit" :loading="ledgerLoading">查询</el-button>
          <el-button @click="resetLedger">重置</el-button>
        </el-form-item>
      </el-form>
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
    </section>

    <section class="page-card cal-section">
      <div class="sec-head">
        <div>
          <h3>任务日历</h3>
          <p class="sec-tip">点日期查看当天到期任务（与下方任务列表同一筛选）</p>
        </div>
        <div class="sec-head-actions">
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
            </div>
          </template>
        </el-calendar>
        <div class="day-panel">
          <h4>{{ selectedDay === todayKey ? '今天' : selectedDay }}的任务</h4>
          <div
            v-for="t in dayTasks"
            :key="t.id"
            class="row"
            @click="openTask(t)"
          >
            <div>
              <b>{{ t.title }}</b>
              <span>{{ t.projectName || '—' }} · {{ t.participantNames?.length ? `参与人 ${t.participantNames.join('、')}` : '无参与人' }}</span>
            </div>
            <em :class="{ overdue: t.overdue }">{{ t.statusLabel || t.status || '—' }}</em>
          </div>
          <div v-if="!dayTasks.length" class="empty">这一天没有到期任务</div>
        </div>
      </div>
    </section>

    <div class="three-col">
      <section class="page-card">
        <div class="sec-head">
          <h3>我的审批</h3>
          <el-button plain type="primary" @click="router.push({ path: '/workflow/center', query: { scope: 'todo' } })">
            去审批中心
          </el-button>
        </div>
        <h4 class="sub">待我处理</h4>
        <div
          v-for="r in todoApprovals"
          :key="'t' + r.id"
          class="row"
          @click="router.push({ path: '/workflow/center', query: { scope: 'todo' } })"
        >
          <div>
            <b>{{ r.title }}</b>
            <span>{{ r.typeLabel }} · {{ r.bizNo }}</span>
          </div>
          <em>{{ r.statusLabel }}</em>
        </div>
        <div v-if="!todoApprovals.length" class="empty">暂无待办</div>

        <h4 class="sub">我发起的</h4>
        <div
          v-for="r in mineApprovals"
          :key="'m' + r.id"
          class="row"
          @click="router.push({ path: '/workflow/center', query: { scope: 'mine' } })"
        >
          <div>
            <b>{{ r.title }}</b>
            <span>{{ r.typeLabel }} · {{ r.bizNo }}</span>
          </div>
          <em>{{ r.statusLabel }}</em>
        </div>
        <div v-if="!mineApprovals.length" class="empty">暂无申请</div>
      </section>

      <section class="page-card">
        <div class="sec-head">
          <h3>{{ taskSectionTitle }}</h3>
          <div class="sec-head-actions">
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
        <div
          v-for="t in myTasks"
          :key="t.id"
          class="row"
          @click="openTask(t)"
        >
          <div>
            <b>{{ t.title }}</b>
            <span>{{ t.projectName || '—' }} · 截止 {{ t.dueDate || '未设' }}</span>
          </div>
          <em>{{ t.statusLabel || t.status || '—' }}</em>
        </div>
        <div v-if="!myTasks.length" class="empty">{{ taskEmptyText }}</div>
      </section>

      <section class="page-card">
        <div class="sec-head">
          <h3>{{ seeAllProjects ? '全部项目' : '参与的项目' }}</h3>
          <el-button
            v-if="userStore.hasPermission('project:list')"
            plain
            type="primary"
            @click="router.push('/project/list')"
          >
            去项目管理
          </el-button>
        </div>
        <div
          v-for="p in myProjects"
          :key="p.id"
          class="row"
          @click="router.push({ path: '/project/list', query: { keyword: p.name || '' } })"
        >
          <div>
            <b>{{ p.name }}</b>
            <span>{{ p.code || '—' }} · {{ projectRole(p) }}</span>
          </div>
          <em>{{ p.statusLabel || p.status || '—' }}</em>
        </div>
        <div v-if="!myProjects.length" class="empty">{{ seeAllProjects ? '暂无项目' : '暂无参与项目' }}</div>
      </section>
    </div>

    <el-dialog v-model="reimburseDialog" title="个人报销" width="480px" @closed="voucherFiles = []">
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

    <el-dialog v-model="withdrawDialog" title="申请提现" width="480px">
      <el-form label-width="88px">
        <el-form-item label="所属公司" required>
          <el-select v-model="withdrawForm.companyId" filterable placeholder="选择公司" style="width: 100%">
            <el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="提现全额" required>
          <el-input-number v-model="withdrawForm.amount" :min="0.01" :precision="2" style="width: 100%" />
        </el-form-item>
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
        <p class="sec-tip">确认到账后：钱包扣提现全额，税额进入公司资金池，到手金额线下打款。</p>
      </el-form>
      <template #footer>
        <el-button @click="withdrawDialog = false">取消</el-button>
        <el-button type="primary" @click="submitWithdraw">提交审批</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.account {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.wallet-board {
  margin-bottom: 14px;
  padding: 14px 16px;
  border: 1px solid rgba(0, 0, 0, 0.06);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.35);
}
.wallet-board__metrics {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: 10px;
  margin-bottom: 12px;
}
.wallet-board__metrics span {
  display: block;
  font-size: 12px;
  color: var(--kk-text-muted);
}
.wallet-board__metrics b {
  display: block;
  margin-top: 4px;
  font-size: 16px;
}
.voucher-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 6px;
  font-size: 12px;
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

.welcome-title {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
  letter-spacing: -0.03em;
  color: var(--kk-text);
}

.welcome-desc {
  margin: 6px 0 0;
  color: var(--kk-text-secondary);
  font-size: 14px;
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.stat-grid--no-wallet {
  grid-template-columns: repeat(3, 1fr);
}

.stat-card {
  border-radius: var(--kk-radius);
  background: var(--kk-glass-bg);
  border: 1px solid var(--kk-glass-border);
  box-shadow: var(--kk-glass-shadow);
  backdrop-filter: var(--kk-glass-blur);
  -webkit-backdrop-filter: var(--kk-glass-blur);
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 96px;
  padding: 18px 16px 18px 20px;
}

.stat-card[role="button"] {
  cursor: pointer;
}

.stat-card[role="button"]:hover {
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.08);
}

.stat-card[role="button"]:focus-visible {
  outline: 2px solid var(--kk-primary);
  outline-offset: 2px;
}

.stat-card::before {
  content: "";
  position: absolute;
  right: -24px;
  top: 50%;
  width: 120px;
  height: 120px;
  border-radius: 50%;
  transform: translateY(-50%);
  filter: blur(32px);
  opacity: 0.22;
  pointer-events: none;
}

.stat-card--indigo::before { background: #d4d4d8; }
.stat-card--cyan::before { background: #a5f3fc; }
.stat-card--violet::before { background: #ddd6fe; }
.stat-card--amber::before { background: #fde68a; }

.stat-card--indigo .stat-glyph { color: var(--kk-primary); }
.stat-card--cyan .stat-glyph { color: #0891b2; }
.stat-card--violet .stat-glyph { color: #7c3aed; }
.stat-card--amber .stat-glyph { color: #d97706; }

.stat-body {
  position: relative;
  z-index: 1;
  min-width: 0;
}

.stat-glyph {
  position: relative;
  z-index: 1;
  flex-shrink: 0;
  opacity: 1;
}

.stat-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--kk-text-secondary);
}

.stat-value {
  margin-top: 8px;
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
  color: var(--kk-text);
}

.stat-sub {
  margin-top: 6px;
  font-size: 12px;
  color: var(--kk-text-secondary);
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

.day-panel {
  background: rgba(255, 255, 255, 0.28);
  border: 1px solid rgba(255, 255, 255, 0.5);
  border-radius: var(--kk-radius-sm);
  padding: 16px;
  min-height: 280px;
  max-height: 420px;
  overflow: auto;
}

.day-panel h4 {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
  color: var(--kk-text);
}

.row em.overdue { color: var(--kk-danger); }

.three-col {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.sub {
  margin: 8px 0;
  font-size: 13px;
  color: var(--kk-text-secondary);
  font-weight: 600;
}

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

@media (max-width: 1280px) {
  .stat-grid { grid-template-columns: repeat(2, 1fr); }
}

@media (max-width: 1100px) {
  .three-col { grid-template-columns: 1fr; }
  .cal-wrap { grid-template-columns: 1fr; }
}

@media (prefers-reduced-transparency: reduce) {
  .stat-card,
  .filter-bar,
  .day-panel {
    background: #fff;
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}
</style>
