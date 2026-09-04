<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { bizApi } from '@/api/biz'
import { workflowApi } from '@/api/workflow'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const ledgerLoading = ref(false)
const wallet = ref<any>({})
const ledgers = ref<any[]>([])
const ledgerTotal = ref(0)
const ledgerQuery = reactive({
  page: 1,
  pageSize: 10,
  keyword: '',
  dateRange: [] as string[],
  minAmount: undefined as number | undefined,
  maxAmount: undefined as number | undefined,
})

const todoApprovals = ref<any[]>([])
const mineApprovals = ref<any[]>([])
const myTasks = ref<any[]>([])
const calendarTasks = ref<any[]>([])
const myProjects = ref<any[]>([])
const calendarDate = ref(new Date())

function fmt(n?: number) {
  return Number(n || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function fmtTime(t?: string) {
  if (!t) return '—'
  return t.replace('T', ' ').slice(0, 16)
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
  void loadLedger()
}

async function loadBalance() {
  try {
    wallet.value = await bizApi.myWallet()
  } catch {
    wallet.value = {}
  }
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

async function loadApprovals() {
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
  if (!uid) {
    myTasks.value = []
    calendarTasks.value = []
    return
  }
  try {
    const seeAll = seeAllProjects.value
    const res = await bizApi.taskPage({
      page: 1,
      pageSize: 200,
      ...(seeAll ? {} : { assigneeId: uid }),
    })
    calendarTasks.value = res.list || []
    myTasks.value = calendarTasks.value.slice(0, 10)
  } catch {
    myTasks.value = []
    calendarTasks.value = []
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
  loading.value = true
  try {
    await Promise.all([loadBalance(), loadLedger(), loadApprovals(), loadTasks(), loadProjects()])
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

    <div class="stat-grid">
      <div class="stat-card stat-card--indigo">
        <div class="stat-body">
          <div class="stat-label">钱包余额</div>
          <div class="stat-value">¥ {{ fmt(wallet.balance) }}</div>
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

    <section class="page-card">
      <div class="sec-head">
        <div>
          <h3>钱包流水</h3>
          <p class="sec-tip">按时间、金额或摘要核对到账与扣款</p>
        </div>
        <el-button type="primary" @click="router.push('/workflow/center')">去发起报销</el-button>
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
        <el-table-column prop="bizNo" label="编号" width="170" show-overflow-tooltip />
        <el-table-column prop="title" label="摘要" min-width="200" show-overflow-tooltip />
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
          <p class="sec-tip">点日期查看当天到期任务</p>
        </div>
        <el-button
          v-if="userStore.hasPermission('project:task:list')"
          plain
          type="primary"
          @click="router.push('/project/task')"
        >
          去任务管理
        </el-button>
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
              <span>{{ t.projectName || '—' }} · {{ t.assigneeName || '未分配' }}</span>
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
          <h3>{{ seeAllProjects ? '全部任务' : '我的任务' }}</h3>
          <el-button
            v-if="userStore.hasPermission('project:task:list')"
            plain
            type="primary"
            @click="router.push('/project/task')"
          >
            去任务管理
          </el-button>
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
        <div v-if="!myTasks.length" class="empty">{{ seeAllProjects ? '暂无任务' : '暂无指派给我的任务' }}</div>
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
  </div>
</template>

<style scoped>
.account {
  display: flex;
  flex-direction: column;
  gap: 16px;
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

.sec-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 14px;
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
