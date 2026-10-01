<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { bizApi } from '@/api/biz'
import TaskDetailDrawer from '@/components/task/TaskDetailDrawer.vue'

const loading = ref(false)
const dashboard = ref<any>({})
const projects = ref<any[]>([])
const drawerOpen = ref(false)
const activeTaskId = ref<number | null>(null)
const listOpen = ref(false)
const listLoading = ref(false)
const listTitle = ref('')
const listCategory = ref('')
const listRows = ref<any[]>([])
const listTotal = ref(0)
const listPage = ref(1)
const listProjectId = ref<number | undefined>()
const listOwnerId = ref<number | undefined>()
const listFrom = ref<string | undefined>()
const listTo = ref<string | undefined>()
const filters = reactive({ projectId: undefined as number | undefined, priority: undefined as number | undefined, title: '' })
type PeriodKey = 'THIS_MONTH' | 'LAST_MONTH' | 'THIS_QUARTER' | 'LAST_QUARTER' | 'THIS_YEAR' | 'CUSTOM'
const period = ref<PeriodKey>('THIS_MONTH')
const customPeriod = ref<[string, string] | undefined>()
const periodOptions: Array<{ label: string; value: PeriodKey }> = [
  { label: '本月', value: 'THIS_MONTH' }, { label: '上月', value: 'LAST_MONTH' },
  { label: '本季度', value: 'THIS_QUARTER' }, { label: '上季度', value: 'LAST_QUARTER' },
  { label: '本年', value: 'THIS_YEAR' }, { label: '自定义', value: 'CUSTOM' },
]
let filterTimer: ReturnType<typeof setTimeout> | undefined

function formatDate(date: Date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function addDays(value: string, days: number) {
  const [year, month, day] = value.split('-').map(Number)
  const date = new Date(year, month - 1, day)
  date.setDate(date.getDate() + days)
  return formatDate(date)
}

const periodBounds = computed(() => {
  const now = new Date()
  let from: Date
  let to: Date
  if (period.value === 'CUSTOM') {
    return customPeriod.value?.length === 2
      ? { from: customPeriod.value[0], to: addDays(customPeriod.value[1], 1) }
      : undefined
  }
  if (period.value === 'THIS_YEAR') {
    from = new Date(now.getFullYear(), 0, 1)
    to = new Date(now.getFullYear() + 1, 0, 1)
  } else {
    const quarterMonth = Math.floor(now.getMonth() / 3) * 3
    const monthOffset = period.value === 'LAST_MONTH' ? -1 : 0
    const quarterOffset = period.value === 'LAST_QUARTER' ? -3 : 0
    if (period.value === 'THIS_QUARTER' || period.value === 'LAST_QUARTER') {
      from = new Date(now.getFullYear(), quarterMonth + quarterOffset, 1)
      to = new Date(from.getFullYear(), from.getMonth() + 3, 1)
    } else {
      from = new Date(now.getFullYear(), now.getMonth() + monthOffset, 1)
      to = new Date(from.getFullYear(), from.getMonth() + 1, 1)
    }
  }
  return { from: formatDate(from), to: formatDate(to) }
})

const riskTypeMap: Record<string, { label: string; type: 'danger' | 'warning' | 'info' | 'success' }> = {
  OVERDUE: { label: '已逾期', type: 'danger' },
  DUE_SOON: { label: '即将到期', type: 'warning' },
  STALE: { label: '七天未推进', type: 'warning' },
  NO_DUE_DATE: { label: '未设截止日', type: 'info' },
  PENDING: { label: '待确认', type: 'success' },
}
const statusMap: Record<number, string> = { 0: '待办', 1: '进行中', 2: '已完成', 3: '已关闭', 4: '待确认完成' }
const healthMap: Record<string, { label: string; type: 'danger' | 'warning' | 'success' }> = {
  DANGER: { label: '高风险', type: 'danger' }, WARNING: { label: '需关注', type: 'warning' }, HEALTHY: { label: '健康', type: 'success' },
}

const maxTrend = computed(() => Math.max(1, ...(dashboard.value.trend || []).flatMap((item: any) => [item.created || 0, item.completed || 0])))
const memberStatsSummary = computed(() => {
  const rows = dashboard.value.memberTaskStats || []
  const total = rows.reduce((sum: number, item: any) => sum + Number(item.total || 0), 0)
  const done = rows.reduce((sum: number, item: any) => sum + Number(item.done || 0), 0)
  const overdue = rows.reduce((sum: number, item: any) => sum + Number(item.overdue || 0), 0)
  return {
    members: rows.length,
    total,
    doneRate: total ? Math.round(done / total * 100) : 0,
    overdue,
  }
})
const memberChartRows = computed(() => (dashboard.value.memberTaskStats || []).map((item: any) => ({
  ...item,
  total: Number(item.total || 0),
  done: Number(item.done || 0),
  doing: Number(item.doing || 0),
  overdue: Number(item.overdue || 0),
  completionRate: Number(item.total || 0) ? Math.round(Number(item.done || 0) / Number(item.total) * 100) : 0,
})))
const memberChartMax = computed(() => Math.max(1, ...memberChartRows.value.flatMap((item: any) => [item.done, item.doing, item.overdue])))
const memberChartMinWidth = computed(() => Math.max(720, memberChartRows.value.length * 128))
const openTasks = computed(() => Number(dashboard.value.todo || 0) + Number(dashboard.value.doing || 0) + Number(dashboard.value.pending || 0))
const selectedPeriodLabel = computed(() => periodOptions.find(item => item.value === period.value)?.label || '当前周期')
const activeFilterCount = computed(() => [filters.projectId, filters.priority, filters.title.trim()].filter(value => value !== undefined && value !== '').length)

async function load() {
  loading.value = true
  try {
    const params: Record<string, unknown> = {}
    if (filters.projectId != null) params.projectId = filters.projectId
    if (filters.priority != null) params.priority = filters.priority
    if (filters.title.trim()) params.title = filters.title.trim()
    if (periodBounds.value) {
      params.periodFrom = periodBounds.value.from
      params.periodTo = periodBounds.value.to
    }
    dashboard.value = await bizApi.managementTaskDashboard(params)
  } finally {
    loading.value = false
  }
}

function scheduleFilterLoad(delay = 300) {
  if (filterTimer) clearTimeout(filterTimer)
  filterTimer = setTimeout(() => {
    filterTimer = undefined
    load()
  }, delay)
}

watch(
  () => [filters.projectId, filters.priority, filters.title] as const,
  ([projectId, priority], [previousProjectId, previousPriority]) => {
    const selectChanged = projectId !== previousProjectId || priority !== previousPriority
    scheduleFilterLoad(selectChanged ? 0 : 300)
  },
)

function reset() {
  filters.projectId = undefined
  filters.priority = undefined
  filters.title = ''
  period.value = 'THIS_MONTH'
  customPeriod.value = undefined
  scheduleFilterLoad(0)
}

function changePeriod(value: string | number | boolean | undefined) {
  period.value = value as PeriodKey
  if (period.value !== 'CUSTOM') load()
}

function changeCustomPeriod() {
  if (customPeriod.value?.length === 2) load()
}

function drillProject(projectId: number) {
  filters.projectId = projectId
  load()
}

function openTask(id: number) {
  activeTaskId.value = id
  drawerOpen.value = true
}

const categoryTitles: Record<string, string> = {
  TOTAL: '全部任务', OPEN: '在办任务', OVERDUE: '逾期任务', DONE_30: '周期内完成任务', ON_TIME: '按期完成率统计任务',
  CYCLE: '平均交付周期统计任务', DUE_SOON: '7天内到期任务', STALE: '长期未推进任务',
  NO_DUE_DATE: '未设截止日任务', PENDING: '待确认完成任务',
}

async function loadTaskList() {
  listLoading.value = true
  try {
    const params: Record<string, unknown> = { page: listPage.value, pageSize: 10, dashboardCategory: listCategory.value }
    const projectId = listProjectId.value ?? filters.projectId
    if (projectId != null) params.projectId = projectId
    if (filters.priority != null) params.priority = filters.priority
    if (filters.title.trim()) params.title = filters.title.trim()
    if (listOwnerId.value != null) params.dashboardOwnerId = listOwnerId.value
    if (listFrom.value) params.dashboardFrom = listFrom.value
    if (listTo.value) params.dashboardTo = listTo.value
    if (periodBounds.value) {
      params.periodFrom = periodBounds.value.from
      params.periodTo = periodBounds.value.to
    }
    const result = await bizApi.managementTaskPage(params)
    listRows.value = result.list || []
    listTotal.value = Number(result.total || 0)
  } finally {
    listLoading.value = false
  }
}

function showTasks(category: string, projectId?: number, projectName?: string, extra?: { ownerId?: number; from?: string; to?: string; title?: string }) {
  listCategory.value = category
  listProjectId.value = projectId
  listOwnerId.value = extra?.ownerId
  listFrom.value = extra?.from
  listTo.value = extra?.to
  listTitle.value = extra?.title || (projectName ? `${projectName} · 在办任务` : categoryTitles[category])
  listPage.value = 1
  listOpen.value = true
  loadTaskList()
}

function showRiskProjects() {
  listCategory.value = 'PROJECTS'
  listTitle.value = '风险项目'
  listRows.value = dashboard.value.projectHealth?.filter((item: any) => item.level !== 'HEALTHY') || []
  listTotal.value = listRows.value.length
  listOpen.value = true
}

function showTrendTasks(item: any, type: 'created' | 'completed') {
  const category = type === 'created' ? 'CREATED_RANGE' : 'COMPLETED_RANGE'
  const label = type === 'created' ? '新增任务' : '完成任务'
  showTasks(category, undefined, undefined, { from: item.from, to: item.to, title: `${item.label} 当周${label}` })
}

function showMemberTasks(item: any) {
  showTasks('OWNER_OPEN', undefined, undefined, { ownerId: Number(item.memberId), title: `${item.memberName} · 未结任务` })
}

function openListedTask(id: number) {
  listOpen.value = false
  openTask(id)
}

async function refreshAfterTaskSaved() {
  await load()
  if (listOpen.value) await loadTaskList()
}

onMounted(async () => {
  projects.value = await bizApi.taskManagementProjects()
  await load()
})

onBeforeUnmount(() => {
  if (filterTimer) clearTimeout(filterTimer)
})
</script>

<template>
  <div v-loading="loading" class="cockpit page-stack">
    <header class="cockpit-hero">
      <div class="hero-copy">
        <span class="eyebrow"><i aria-hidden="true" />项目管理中心</span>
        <h1>任务驾驶舱</h1>
        <p>聚焦交付风险、团队负载与推进效率，快速定位需要管理介入的事项。</p>
      </div>
      <div class="scope-summary" aria-label="当前数据范围">
        <span>数据范围</span>
        <strong>{{ selectedPeriodLabel }}</strong>
        <small>{{ filters.projectId ? '已聚焦单个项目' : '全部项目' }}<template v-if="activeFilterCount"> · {{ activeFilterCount }} 项筛选</template></small>
      </div>
    </header>

    <section class="control-panel" aria-label="驾驶舱筛选条件">
      <div class="period-switcher">
        <span class="control-label">周期</span>
        <el-radio-group :model-value="period" size="small" @change="changePeriod">
          <el-radio-button v-for="item in periodOptions" :key="item.value" :label="item.value" :value="item.value">{{ item.label }}</el-radio-button>
        </el-radio-group>
        <el-date-picker v-if="period === 'CUSTOM'" v-model="customPeriod" type="daterange" value-format="YYYY-MM-DD"
          start-placeholder="开始日期" end-placeholder="结束日期" range-separator="至" :clearable="false" @change="changeCustomPeriod" />
      </div>
      <el-form class="cockpit-filter">
        <el-form-item label="项目">
          <el-select v-model="filters.projectId" clearable filterable placeholder="全部项目">
            <el-option v-for="item in projects" :key="item.id" :label="item.name" :value="Number(item.id)" />
          </el-select>
        </el-form-item>
        <el-form-item label="优先级">
          <el-select v-model="filters.priority" clearable placeholder="全部">
            <el-option label="高" :value="1" /><el-option label="中" :value="2" /><el-option label="低" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item label="任务"><el-input v-model="filters.title" clearable placeholder="输入任务名称" @keyup.enter="scheduleFilterLoad(0)" /></el-form-item>
        <el-form-item class="filter-actions"><el-button @click="reset">重置</el-button></el-form-item>
      </el-form>
    </section>

    <section class="metric-grid" aria-label="核心交付指标">
      <button type="button" @click="showTasks('TOTAL')"><span>总任务数量</span><strong>{{ dashboard.total ?? 0 }}</strong><small>所选范围内的全部任务</small><em>查看明细</em></button>
      <button type="button" @click="showTasks('OPEN')"><span>在办任务</span><strong>{{ openTasks }}</strong><small>待办、进行中与待确认</small><em>查看明细</em></button>
      <button type="button" class="metric-danger" @click="showTasks('OVERDUE')"><span>逾期任务</span><strong>{{ dashboard.overdue ?? 0 }}</strong><small>已对交付承诺造成影响</small><em>优先处理</em></button>
      <button type="button" class="metric-warning" @click="showRiskProjects"><span>风险项目</span><strong>{{ dashboard.riskProjects ?? 0 }}</strong><small>存在逾期、临期或高风险任务</small><em>查看项目</em></button>
      <button type="button" @click="showTasks('DONE_30')"><span>周期完成量</span><strong>{{ dashboard.done30 ?? 0 }}</strong><small>所选周期内创建且已完成</small><em>查看明细</em></button>
      <button type="button" @click="showTasks('ON_TIME')"><span>按期完成率</span><strong>{{ dashboard.onTimeRate == null ? '—' : `${dashboard.onTimeRate}%` }}</strong><small>衡量交付承诺的可靠性</small><em>查看明细</em></button>
      <button type="button" @click="showTasks('CYCLE')"><span>平均交付周期</span><strong>{{ dashboard.avgCycleDays == null ? '—' : `${dashboard.avgCycleDays}天` }}</strong><small>从实际开始到完成</small><em>查看明细</em></button>
    </section>

    <section class="decision-strip" aria-label="待处理事项">
      <button type="button" @click="showTasks('PENDING')"><i class="signal signal-blue" aria-hidden="true" /><span><b>{{ dashboard.pending ?? 0 }}</b> 项完成申请待确认</span></button>
      <button type="button" @click="showTasks('DUE_SOON')"><i class="signal signal-orange" aria-hidden="true" /><span><b>{{ dashboard.dueSoon ?? 0 }}</b> 项任务 7 天内到期</span></button>
      <button type="button" @click="showTasks('STALE')"><i class="signal signal-red" aria-hidden="true" /><span><b>{{ dashboard.stale ?? 0 }}</b> 项任务长期未推进</span></button>
      <button type="button" @click="showTasks('NO_DUE_DATE')"><i class="signal" aria-hidden="true" /><span><b>{{ dashboard.noDueDate ?? 0 }}</b> 项任务未设截止日</span></button>
    </section>

    <div class="main-grid">
      <section class="page-card health-panel">
        <div class="section-head"><div><h3>项目健康排名</h3><p>从最需管理介入的项目开始查看</p></div></div>
        <button v-for="item in dashboard.projectHealth || []" :key="item.projectId" type="button" class="health-row" @click="drillProject(item.projectId)">
          <div><strong>{{ item.projectName }}</strong><span>项目负责人 {{ item.ownerName }}</span></div>
          <p><b>{{ item.open }}</b>未结 <em>{{ item.overdue }}逾期</em> {{ item.dueSoon }}临期</p>
          <el-tag :type="healthMap[item.level]?.type || 'info'" size="small">{{ healthMap[item.level]?.label }}</el-tag>
        </button>
        <el-empty v-if="!dashboard.projectHealth?.length" description="暂无项目风险数据" :image-size="56" />
      </section>

      <section class="page-card risk-panel">
        <div class="section-head"><div><h3>需关注清单</h3><p>按交付影响排序，建议优先协调资源或明确决策</p></div></div>
        <div v-if="dashboard.riskTasks?.length" class="risk-list">
          <button v-for="item in dashboard.riskTasks" :key="item.id" type="button" class="risk-row" @click="openTask(item.id)">
            <el-tag :type="riskTypeMap[item.riskType]?.type || 'info'" size="small">{{ riskTypeMap[item.riskType]?.label || '关注' }}</el-tag>
            <div><strong>{{ item.title }}</strong><span>{{ item.projectName }} · 负责人 {{ item.ownerName }}</span></div>
            <aside><strong>{{ item.dueDate || '未设日期' }}</strong><span>{{ statusMap[item.status] }}</span></aside>
          </button>
        </div>
        <el-empty v-else description="当前没有高优先级风险" :image-size="64" />
      </section>

      <section class="page-card trend-panel">
        <div class="section-head"><div><h3>六周交付趋势</h3><p>对比新增任务与完成任务，判断存量是否持续积压</p></div></div>
        <div class="legend"><i class="created" />新增 <i class="completed" />完成</div>
        <div class="trend-chart">
          <div v-for="item in dashboard.trend || []" :key="item.label" class="trend-column">
            <div>
              <el-tooltip :content="`新增：${item.created ?? 0}`" placement="top">
                <button type="button" class="created" :aria-label="`${item.label} 新增任务`" :style="{ height: `${Math.max(2, item.created / maxTrend * 100)}%` }" @click="showTrendTasks(item, 'created')" />
              </el-tooltip>
              <el-tooltip :content="`完成：${item.completed ?? 0}`" placement="top">
                <button type="button" class="completed" :aria-label="`${item.label} 完成任务`" :style="{ height: `${Math.max(2, item.completed / maxTrend * 100)}%` }" @click="showTrendTasks(item, 'completed')" />
              </el-tooltip>
            </div>
            <span>{{ item.label }}</span>
          </div>
        </div>
      </section>
    </div>

    <section class="page-card member-stats-panel">
      <div class="section-head">
        <div><h3>成员任务统计</h3><p>合并展示任务状态与当前负载；同一成员在同一任务中只计一次</p></div>
        <div class="member-summary" aria-label="成员任务概览">
          <span><b>{{ memberStatsSummary.members }}</b> 位成员</span>
          <span><b>{{ memberStatsSummary.total }}</b> 项任务</span>
          <span><b>{{ memberStatsSummary.doneRate }}%</b> 完成率</span>
          <span :class="{ danger: memberStatsSummary.overdue > 0 }"><b>{{ memberStatsSummary.overdue }}</b> 项延期</span>
        </div>
      </div>
      <div v-if="memberChartRows.length" class="member-chart-shell">
        <div class="member-chart-legend" aria-label="图例">
          <span><i class="done" />已完成</span><span><i class="doing" />进行中</span><span><i class="overdue" />已逾期</span>
        </div>
        <div class="member-chart-scroll">
          <div class="member-chart" :style="{ minWidth: `${memberChartMinWidth}px` }" role="img" aria-label="成员任务数量柱状图">
            <div class="member-grid-lines" aria-hidden="true"><i /><i /><i /><i /><i /></div>
            <div class="member-chart-groups" :style="{ gridTemplateColumns: `repeat(${memberChartRows.length}, minmax(128px, 1fr))` }">
              <button v-for="item in memberChartRows" :key="item.memberId" type="button" class="member-chart-group" :aria-label="`${item.memberName}：已完成${item.done}项，进行中${item.doing}项，逾期${item.overdue}项，完成率${item.completionRate}%`" @click="showMemberTasks(item)">
                <span class="member-bars">
                  <el-tooltip :content="`已完成 ${item.done} 项`" placement="top"><i class="done" :style="{ height: `${Math.max(3, item.done / memberChartMax * 100)}%` }"><b>{{ item.done }}</b></i></el-tooltip>
                  <el-tooltip :content="`进行中 ${item.doing} 项`" placement="top"><i class="doing" :style="{ height: `${Math.max(3, item.doing / memberChartMax * 100)}%` }"><b>{{ item.doing }}</b></i></el-tooltip>
                  <el-tooltip :content="`已逾期 ${item.overdue} 项`" placement="top"><i class="overdue" :style="{ height: `${Math.max(3, item.overdue / memberChartMax * 100)}%` }"><b>{{ item.overdue }}</b></i></el-tooltip>
                </span>
                <strong :title="item.memberName">{{ item.memberName }}</strong>
                <small>共 {{ item.total }} 项 · {{ item.completionRate }}%</small>
              </button>
            </div>
          </div>
        </div>
      </div>
      <el-empty v-else description="暂无成员任务数据" :image-size="64" />
    </section>

    <el-dialog v-model="listOpen" :title="listTitle" width="min(820px, calc(100vw - 24px))">
      <div v-loading="listLoading" class="drill-list">
        <template v-if="listCategory === 'PROJECTS'">
          <button v-for="item in listRows" :key="item.projectId" type="button" @click="showTasks('OPEN', item.projectId, item.projectName)">
            <div><strong>{{ item.projectName }}</strong><span>负责人 {{ item.ownerName }}</span></div>
            <aside>{{ item.open }} 未结 · <em>{{ item.overdue }} 逾期</em> · {{ item.dueSoon }} 临期</aside>
          </button>
        </template>
        <template v-else>
          <button v-for="item in listRows" :key="item.id" type="button" @click="openListedTask(item.id)">
            <div><strong>{{ item.title }}</strong><span>{{ item.projectName || '未关联项目' }} · 负责人 {{ item.assigneeName || '未指定' }}</span></div>
            <aside>{{ item.dueDate || '未设截止日' }} · {{ statusMap[item.status] }}</aside>
          </button>
          <el-pagination v-if="listTotal > 10" v-model:current-page="listPage" :page-size="10" :total="listTotal" layout="prev, pager, next, total" @current-change="loadTaskList" />
        </template>
        <el-empty v-if="!listRows.length && !listLoading" description="暂无符合条件的数据" :image-size="64" />
      </div>
    </el-dialog>
    <TaskDetailDrawer v-model="drawerOpen" :task-id="activeTaskId" @saved="refreshAfterTaskSaved" @deleted="refreshAfterTaskSaved" />
  </div>
</template>

<style scoped>
.cockpit { gap: 20px; }
.cockpit-filter { margin: 0; }
.period-filter { margin-left: auto; }
.period-filter :deep(.el-form-item__content) { flex-wrap: nowrap; gap: 10px; }
.period-filter :deep(.el-date-editor) { width: 250px; }
.metric-grid { display: grid; grid-template-columns: repeat(7,minmax(0,1fr)); overflow: hidden; border: 1px solid var(--kk-card-border); border-radius: 16px; background: var(--kk-card-bg); box-shadow: 0 1px 2px rgba(0,0,0,.035); }
.metric-grid button { min-width: 0; padding: 18px 20px; border: 0; border-right: 1px solid var(--kk-card-border); background: transparent; text-align: left; cursor: pointer; transition: background-color 160ms ease-out, transform 160ms ease-out; }
.metric-grid button:last-child { border-right: 0; }
.metric-grid button:hover { background: rgba(0,0,0,.025); transform: translateY(-1px); }
.metric-grid button:focus-visible,.decision-strip button:focus-visible { position: relative; outline: 2px solid var(--el-color-primary); outline-offset: -2px; }
.metric-grid span, .metric-grid small { display: block; color: var(--kk-text-muted); }
.metric-grid span { font-size: 12px; font-weight: 500; }
.metric-grid strong { display: block; margin: 8px 0 6px; color: var(--kk-text); font-size: 28px; line-height: 1; font-variant-numeric: tabular-nums; }
.metric-grid small { overflow: hidden; font-size: 11px; line-height: 1.45; text-overflow: ellipsis; white-space: nowrap; }
.metric-danger strong { color: var(--kk-danger); }.metric-warning strong { color: #b86b08; }
.decision-strip { display: grid; grid-template-columns: repeat(4,1fr); overflow: hidden; border: 1px solid var(--kk-card-border); border-radius: 14px; background: rgba(255,255,255,.38); }
.decision-strip button { display: flex; align-items: baseline; justify-content: center; gap: 7px; padding: 13px 16px; border: 0; border-right: 1px solid var(--kk-card-border); background: transparent; cursor: pointer; transition: background-color 160ms ease-out; }
.decision-strip button:hover { background: rgba(0,0,0,.025); }
.decision-strip button:last-child { border: 0; }.decision-strip b { color: var(--kk-text); font-size: 19px; font-variant-numeric: tabular-nums; }.decision-strip span { color: var(--kk-text-secondary); font-size: 12px; }
.drill-list { min-height: 120px; }.drill-list>button { width: 100%; display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 14px 10px; border: 0; border-bottom: 1px solid var(--kk-card-border); background: transparent; text-align: left; cursor: pointer; }.drill-list>button:hover { background: rgba(0,0,0,.025); }.drill-list div strong,.drill-list div span { display: block; }.drill-list div span { margin-top: 5px; color: var(--kk-text-muted); font-size: 12px; }.drill-list aside { flex: none; color: var(--kk-text-secondary); font-size: 12px; }.drill-list em { color: var(--kk-danger); font-style: normal; }.drill-list .el-pagination { justify-content: flex-end; margin-top: 18px; }
.main-grid { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 20px; }.insight-grid { display: grid; grid-template-columns: 1fr; gap: 20px; }
.page-card { padding: 20px; }.risk-panel { position: relative; border-left: 3px solid var(--el-color-primary); }
.section-head { display: flex; justify-content: space-between; margin-bottom: 14px; }.section-head h3 { margin: 0 0 5px; color: var(--kk-text); font-size: 16px; line-height: 1.3; text-wrap: balance; }.section-head p { margin: 0; color: var(--kk-text-muted); font-size: 12px; line-height: 1.5; text-wrap: pretty; }
.risk-list { display: flex; flex-direction: column; }.risk-row { display: grid; grid-template-columns: 82px minmax(0,1fr) 108px; align-items: center; gap: 14px; min-height: 62px; padding: 10px 8px; border: 0; border-top: 1px solid var(--kk-card-border); background: transparent; text-align: left; cursor: pointer; transition: background-color 160ms ease-out; }.risk-row:last-child { border-bottom: 0; }.risk-row div strong,.risk-row div span,.risk-row aside strong,.risk-row aside span { display: block; }.risk-row div strong,.risk-row aside strong { color: var(--kk-text); font-variant-numeric: tabular-nums; }.risk-row div span,.risk-row aside span { margin-top: 4px; color: var(--kk-text-muted); font-size: 12px; }.risk-row aside { text-align: right; }.risk-row:hover { background: rgba(0,0,0,.025); }.risk-row:focus-visible,.health-row:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: -2px; }
.health-row { width: 100%; display: grid; grid-template-columns: minmax(0,1fr) auto 72px; align-items: center; gap: 12px; min-height: 62px; padding: 10px 6px; border: 0; border-top: 1px solid var(--kk-card-border); background: transparent; text-align: left; cursor: pointer; transition: background-color 160ms ease-out; }.health-row:hover { background: rgba(0,0,0,.025); }.health-row div strong,.health-row div span { display: block; }.health-row div span { margin-top: 4px; color: var(--kk-text-muted); font-size: 11px; }.health-row p { margin: 0; color: var(--kk-text-secondary); font-size: 12px; }.health-row p b { margin-right: 2px; color: var(--kk-text); font-size: 16px; font-variant-numeric: tabular-nums; }.health-row em { margin: 0 7px; color: var(--kk-danger); font-style: normal; }
.trend-panel { display: flex; min-height: 0; flex-direction: column; }.legend { text-align: right; color: var(--kk-text-muted); font-size: 11px; }.legend i { display: inline-block; width: 8px; height: 8px; margin: 0 4px 0 12px; border-radius: 2px; }.created { background: #9aa6b2; }.completed { background: var(--el-color-primary); }.trend-chart { min-height: 142px; display: flex; flex: 1; align-items: stretch; justify-content: space-around; padding-top: 14px; }.trend-column { display: flex; flex-direction: column; }.trend-column>div { min-height: 106px; display: flex; flex: 1; align-items: flex-end; gap: 5px; }.trend-column button { display: block; width: 14px; padding: 0; border: 0; border-radius: 3px 3px 0 0; cursor: pointer; transition: filter 140ms ease-out, transform 140ms ease-out; }.trend-column button:hover { filter: brightness(.82); transform: scaleX(1.15); }.trend-column button:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }.trend-column>span { display: block; margin-top: 7px; color: var(--kk-text-muted); font-size: 11px; text-align: center; }
.load-row { width: 100%; display: block; margin: 4px 0; padding: 9px 6px; border: 0; border-radius: 8px; background: transparent; text-align: left; cursor: pointer; transition: background-color 140ms ease-out; }.load-row:hover { background: rgba(0,0,0,.025); }.load-row:focus-visible { outline: 2px solid var(--el-color-primary); }.load-row>div { display: flex; justify-content: space-between; gap: 16px; font-size: 12px; }.load-row strong { color: var(--kk-text); }.load-row span { color: var(--kk-text-muted); font-variant-numeric: tabular-nums; }.load-row p { height: 6px; margin: 8px 0 0; overflow: hidden; border-radius: 6px; background: var(--kk-fill); }.load-row p i { display: block; height: 100%; border-radius: 6px; background: var(--el-color-primary); }.load-row p i.danger { background: var(--kk-danger); }
.member-stats-panel { overflow: hidden; padding: 22px 24px 18px; }
.member-stats-panel>.section-head { align-items: flex-start; gap: 20px; }
.member-summary { display: flex; overflow: hidden; border: 1px solid var(--kk-card-border); border-radius: 10px; background: var(--kk-fill); }
.member-summary span { padding: 8px 13px; border-right: 1px solid var(--kk-card-border); color: var(--kk-text-muted); font-size: 11px; white-space: nowrap; }
.member-summary span:last-child { border-right: 0; }.member-summary b { margin-right: 3px; color: var(--kk-text); font-size: 13px; font-variant-numeric: tabular-nums; }.member-summary .danger b { color: var(--kk-danger); }
.member-table-scroll { overflow-x: auto; scrollbar-width: thin; }
.member-table { min-width: 920px; }
.member-table-head,.member-row { display: grid; grid-template-columns: minmax(190px,1.25fr) 74px repeat(3,minmax(150px,1fr)); align-items: center; column-gap: 24px; }
.member-table-head { height: 34px; padding: 0 14px; border-bottom: 1px solid var(--kk-card-border); color: var(--kk-text-muted); font-size: 11px; }
.member-table-head span:nth-child(2) { text-align: center; }
.member-row { min-height: 54px; padding: 0 14px; border-bottom: 1px solid var(--kk-card-border); transition: background-color 140ms ease-out; }
.member-row:last-child { border-bottom: 0; }.member-row:hover { background: rgba(0,0,0,.018); }
.member-identity { min-width: 0; display: flex; align-items: center; gap: 10px; }.member-rank { width: 20px; color: var(--kk-text-muted); font-size: 10px; font-variant-numeric: tabular-nums; }.member-avatar { width: 28px; height: 28px; display: grid; flex: none; place-items: center; border: 1px solid rgba(79,124,255,.12); border-radius: 9px; background: rgba(79,124,255,.08); color: #4267d5; font-size: 12px; font-weight: 650; }.member-identity strong { overflow: hidden; color: var(--kk-text); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.member-total { justify-self: center; min-width: 34px; padding: 5px 8px; border-radius: 8px; background: var(--kk-fill); color: var(--kk-text); font-size: 13px; font-variant-numeric: tabular-nums; text-align: center; }
.member-measure { display: grid; grid-template-columns: minmax(70px,1fr) 24px; align-items: center; gap: 9px; }.member-measure>span { height: 6px; overflow: hidden; border-radius: 8px; background: var(--kk-fill); }.member-measure i { display: block; min-width: 2px; height: 100%; border-radius: inherit; }.member-measure b { color: var(--kk-text-secondary); font-size: 11px; font-variant-numeric: tabular-nums; font-weight: 600; text-align: right; }.member-measure.done i { background: #42b883; }.member-measure.doing i { background: #4f7cff; }.member-measure.overdue i { background: #f2555a; }.member-measure.overdue.active b { color: var(--kk-danger); }
@media(max-width:1300px){.metric-grid{grid-template-columns:repeat(3,1fr)}.metric-grid button:nth-child(3){border-right:0}.metric-grid button:nth-child(-n+3){border-bottom:1px solid var(--kk-card-border)}}
@media(max-width:900px){.period-filter{width:100%;margin-left:0}.period-filter :deep(.el-form-item__content){flex-wrap:wrap}.main-grid,.insight-grid{display:block}.metric-grid{grid-template-columns:repeat(2,1fr)}.metric-grid button:nth-child(3){border-right:1px solid var(--kk-card-border)}.metric-grid button:nth-child(even){border-right:0}.metric-grid button:nth-child(-n+4){border-bottom:1px solid var(--kk-card-border)}.decision-strip{grid-template-columns:1fr 1fr}.decision-strip button:nth-child(2){border-right:0}.decision-strip button:nth-child(-n+2){border-bottom:1px solid var(--kk-card-border)}.risk-panel,.load-panel{margin-top:20px}}
@media(max-width:560px){.cockpit{gap:16px}.metric-grid{grid-template-columns:1fr 1fr}.metric-grid button{padding:15px}.metric-grid strong{font-size:24px}.metric-grid small{white-space:normal}.decision-strip button{align-items:center;justify-content:flex-start;padding:12px}.decision-strip span{line-height:1.35}.risk-row{grid-template-columns:1fr 90px}.risk-row>.el-tag{grid-column:1 / -1;justify-self:start}.health-row{grid-template-columns:1fr auto}.health-row>p{display:none}.page-card{padding:16px}.drill-list>button{display:block}.drill-list aside{margin-top:8px}}
@media(max-width:900px){.member-stats-panel>.section-head{display:block}.member-summary{width:max-content;margin-top:12px}}
@media(max-width:560px){.member-stats-panel{padding:16px}.member-summary{display:grid;grid-template-columns:1fr 1fr;width:100%}.member-summary span:nth-child(2){border-right:0}.member-summary span:nth-child(-n+2){border-bottom:1px solid var(--kk-card-border)}}

/* Cockpit visual system: clear hierarchy, dense data, restrained motion. */
.cockpit {
  --cockpit-blue: #2563eb;
  --cockpit-blue-soft: rgba(37, 99, 235, .09);
  --cockpit-danger: #dc2626;
  --cockpit-warning: #d97706;
  gap: 16px;
}
.cockpit-hero { display: flex; align-items: flex-end; justify-content: space-between; gap: 32px; padding: 8px 2px 4px; }
.hero-copy { min-width: 0; }
.eyebrow { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; color: var(--cockpit-blue); font-size: 12px; font-weight: 700; letter-spacing: .08em; }
.eyebrow i { width: 7px; height: 7px; border-radius: 50%; background: currentColor; box-shadow: 0 0 0 4px var(--cockpit-blue-soft); }
.hero-copy h1 { margin: 0; color: var(--kk-text); font-size: clamp(24px, 2vw, 32px); line-height: 1.15; letter-spacing: -.035em; }
.hero-copy p { max-width: 680px; margin: 8px 0 0; color: var(--kk-text-secondary); font-size: 13px; line-height: 1.6; }
.scope-summary { min-width: 172px; padding: 12px 16px; border-left: 2px solid var(--cockpit-blue); background: linear-gradient(90deg, var(--cockpit-blue-soft), transparent); }
.scope-summary span,.scope-summary strong,.scope-summary small { display: block; }
.scope-summary span { color: var(--kk-text-muted); font-size: 10px; font-weight: 700; letter-spacing: .08em; text-transform: uppercase; }
.scope-summary strong { margin-top: 4px; color: var(--kk-text); font-size: 15px; }
.scope-summary small { margin-top: 3px; color: var(--kk-text-muted); font-size: 11px; }
.control-panel { overflow: hidden; border: 1px solid var(--kk-card-border); border-radius: 14px; background: var(--kk-card-bg); box-shadow: 0 1px 2px rgba(15, 23, 42, .03); }
.period-switcher { min-height: 50px; display: flex; align-items: center; gap: 12px; padding: 8px 16px; border-bottom: 1px solid var(--kk-card-border); background: color-mix(in srgb, var(--kk-fill) 48%, transparent); }
.control-label { flex: none; color: var(--kk-text-secondary); font-size: 12px; font-weight: 650; }
.period-switcher :deep(.el-radio-group) { display: flex; flex-wrap: wrap; }
.period-switcher :deep(.el-radio-button__inner) { min-height: 32px; display: grid; place-items: center; padding: 6px 14px; border: 0; border-radius: 7px !important; background: transparent; box-shadow: none !important; color: var(--kk-text-secondary); }
.period-switcher :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) { background: var(--kk-card-bg); color: var(--cockpit-blue); box-shadow: 0 1px 4px rgba(15, 23, 42, .09) !important; }
.period-switcher :deep(.el-date-editor) { width: 250px; margin-left: auto; }
.cockpit-filter { display: grid; grid-template-columns: minmax(180px, 1.4fr) minmax(130px, .7fr) minmax(180px, 1fr) auto; align-items: end; gap: 14px; padding: 14px 16px; }
.cockpit-filter :deep(.el-form-item) { min-width: 0; display: block; margin: 0; }
.cockpit-filter :deep(.el-form-item__label) { height: auto; margin-bottom: 6px; color: var(--kk-text-muted); font-size: 11px; line-height: 1.2; }
.cockpit-filter :deep(.el-form-item__content),.cockpit-filter :deep(.el-select),.cockpit-filter :deep(.el-input) { width: 100%; }
.filter-actions :deep(.el-form-item__content) { display: flex; flex-wrap: nowrap; gap: 8px; }
.filter-actions :deep(.el-button + .el-button) { margin-left: 0; }
.metric-grid { gap: 10px; overflow: visible; border: 0; border-radius: 0; background: transparent; box-shadow: none; }
.metric-grid button { position: relative; min-height: 142px; overflow: hidden; padding: 18px; border: 1px solid var(--kk-card-border) !important; border-radius: 14px; background: var(--kk-card-bg); box-shadow: 0 1px 2px rgba(15, 23, 42, .03); }
.metric-grid button::before { content: ''; position: absolute; inset: 0 auto 0 0; width: 3px; background: var(--cockpit-blue); opacity: .72; }
.metric-grid button.metric-danger::before { background: var(--cockpit-danger); }
.metric-grid button.metric-warning::before { background: var(--cockpit-warning); }
.metric-grid button:hover { border-color: color-mix(in srgb, var(--cockpit-blue) 28%, var(--kk-card-border)) !important; background: var(--kk-card-bg); box-shadow: 0 8px 22px rgba(15, 23, 42, .075); transform: translateY(-2px); }
.metric-grid span { color: var(--kk-text-secondary); font-size: 12px; font-weight: 650; }
.metric-grid strong { margin: 12px 0 7px; font-size: 30px; letter-spacing: -.04em; }
.metric-grid small { min-height: 31px; white-space: normal; }
.metric-grid em { display: block; margin-top: 10px; color: var(--cockpit-blue); font-size: 10px; font-style: normal; font-weight: 650; opacity: 0; transform: translateX(-4px); transition: opacity 160ms ease-out, transform 160ms ease-out; }
.metric-grid button:hover em,.metric-grid button:focus-visible em { opacity: 1; transform: translateX(0); }
.metric-danger em { color: var(--cockpit-danger); }.metric-warning em { color: var(--cockpit-warning); }
.decision-strip { grid-template-columns: 150px repeat(4, 1fr); align-items: stretch; overflow: hidden; border-radius: 12px; background: var(--kk-card-bg); }
.decision-title { display: flex; flex-direction: column; justify-content: center; padding: 12px 16px; border-right: 1px solid var(--kk-card-border); background: var(--kk-fill); }
.decision-title span { color: var(--kk-text); font-size: 12px; font-weight: 700; }.decision-title small { margin-top: 3px; color: var(--kk-text-muted); font-size: 10px; }
.decision-strip button { min-height: 54px; justify-content: flex-start; gap: 10px; padding: 10px 14px; }
.decision-strip button:hover { background: var(--kk-fill); }
.decision-strip button span { color: var(--kk-text-secondary); line-height: 1.4; }
.decision-strip button b { margin-right: 2px; font-size: 17px; }
.signal { width: 7px; height: 7px; flex: none; border-radius: 50%; background: #94a3b8; box-shadow: 0 0 0 4px rgba(148, 163, 184, .12); }
.signal-blue { background: var(--cockpit-blue); box-shadow: 0 0 0 4px var(--cockpit-blue-soft); }.signal-orange { background: var(--cockpit-warning); box-shadow: 0 0 0 4px rgba(217, 119, 6, .1); }.signal-red { background: var(--cockpit-danger); box-shadow: 0 0 0 4px rgba(220, 38, 38, .09); }
.page-card { border-radius: 14px; box-shadow: 0 1px 2px rgba(15, 23, 42, .03); }
.risk-panel { border-left-width: 1px; }.risk-panel::before { content: ''; position: absolute; inset: 0 auto 0 0; width: 3px; border-radius: 14px 0 0 14px; background: var(--cockpit-danger); }
.risk-row,.health-row,.load-row,.drill-list>button { border-radius: 8px; }
.risk-row:hover,.health-row:hover,.load-row:hover,.drill-list>button:hover { background: var(--kk-fill); }
.risk-row:focus-visible,.health-row:focus-visible,.load-row:focus-visible,.drill-list>button:focus-visible { outline: 2px solid var(--cockpit-blue); outline-offset: -2px; }
@media(max-width:1200px){
  .cockpit-filter{grid-template-columns:1fr 150px 1fr auto}.decision-strip{grid-template-columns:130px repeat(2,1fr)}.decision-title{grid-row:span 2}.decision-strip button:nth-of-type(2){border-right:0}.decision-strip button:nth-of-type(-n+2){border-bottom:1px solid var(--kk-card-border)}
}
@media(max-width:900px){
  .cockpit-hero{align-items:flex-start}.cockpit-filter{grid-template-columns:1fr 1fr}.filter-actions{grid-column:2}.period-switcher{align-items:flex-start;flex-wrap:wrap}.period-switcher :deep(.el-date-editor){width:100%;margin-left:0}.decision-strip{grid-template-columns:1fr 1fr}.decision-title{grid-column:1 / -1;grid-row:auto;border-right:0;border-bottom:1px solid var(--kk-card-border)}
}
@media(max-width:560px){
  .cockpit-hero{display:block}.scope-summary{margin-top:16px}.period-switcher{padding:12px}.control-label{width:100%}.period-switcher :deep(.el-radio-group){display:grid;grid-template-columns:repeat(3,1fr);width:100%}.cockpit-filter{grid-template-columns:1fr;padding:12px}.filter-actions{grid-column:auto}.metric-grid button{min-height:132px;padding:15px}.metric-grid em{display:none}.decision-strip{grid-template-columns:1fr}.decision-title{grid-column:auto}.decision-strip button{border-right:0;border-bottom:1px solid var(--kk-card-border)}
}
@media(prefers-reduced-motion:reduce){.cockpit *{scroll-behavior:auto !important}.metric-grid button,.metric-grid em,.risk-row,.health-row,.load-row{transition:none !important}.metric-grid button:hover{transform:none}}

/* Compact scope toolbar: filters support the dashboard instead of becoming a section. */
.control-panel { display: grid; grid-template-columns: auto minmax(0, 1fr); align-items: center; min-height: 58px; overflow: visible; padding: 8px 10px; }
.period-switcher { min-height: 40px; padding: 0 14px 0 6px; border-right: 1px solid var(--kk-card-border); border-bottom: 0; background: transparent; }
.control-label { color: var(--kk-text-muted); font-size: 11px; }
.period-switcher :deep(.el-radio-button__inner) { min-height: 30px; padding: 5px 12px; }
.cockpit-filter { grid-template-columns: repeat(3, minmax(0, 1fr)) auto; gap: 10px; padding: 0 0 0 14px; }
.cockpit-filter :deep(.el-form-item) { display: flex; align-items: center; gap: 7px; }
.cockpit-filter :deep(.el-form-item__label) { flex: none; margin: 0; padding: 0; color: var(--kk-text-muted); white-space: nowrap; }
.cockpit-filter :deep(.el-form-item__content) { min-width: 0; }
.cockpit-filter :deep(.el-input__wrapper),.cockpit-filter :deep(.el-select__wrapper) { min-height: 34px; }
.filter-actions { gap: 0 !important; }
.filter-actions :deep(.el-button) { min-height: 34px; padding-inline: 15px; }
@media(max-width:1480px){
  .control-panel{grid-template-columns:1fr;padding:0}.period-switcher{padding:8px 14px;border-right:0;border-bottom:1px solid var(--kk-card-border)}.cockpit-filter{padding:10px 14px}
}
@media(max-width:900px){
  .control-panel{display:block}.cockpit-filter{grid-template-columns:1fr 1fr}.filter-actions{grid-column:2}.cockpit-filter :deep(.el-form-item){display:block}.cockpit-filter :deep(.el-form-item__label){margin-bottom:6px}
}
@media(max-width:560px){
  .control-panel{padding:0}.period-switcher{padding:10px 12px}.cockpit-filter{grid-template-columns:1fr;padding:12px}.filter-actions{grid-column:auto}.cockpit-filter :deep(.el-form-item){display:block}
}

/* Final visual reconciliation: one neutral workspace language across the page. */
.cockpit { width: 100%; max-width: none; margin: 0; gap: 14px; --cockpit-blue: #18181b; --cockpit-blue-soft: rgba(24,24,27,.07); }
.cockpit-hero { min-height: 76px; align-items: center; padding: 2px 4px 6px; }
.eyebrow { margin-bottom: 5px; color: var(--kk-text-muted); font-size: 11px; letter-spacing: .04em; }
.eyebrow i { width: 5px; height: 5px; box-shadow: none; }
.hero-copy h1 { font-size: 28px; font-weight: 720; letter-spacing: -.04em; }
.hero-copy p { margin-top: 6px; color: var(--kk-text-muted); font-size: 12px; }
.scope-summary { min-width: 150px; padding: 9px 12px; border: 1px solid var(--kk-card-border); border-left: 1px solid var(--kk-card-border); border-radius: 10px; background: rgba(255,255,255,.55); }
.scope-summary span { font-size: 9px; letter-spacing: .06em; }.scope-summary strong { font-size: 14px; }.scope-summary small { font-size: 10px; }
.control-panel { border-color: var(--kk-card-border); border-radius: 12px; background: rgba(255,255,255,.72); box-shadow: none; }
.period-switcher :deep(.el-radio-button__inner) { color: var(--kk-text-muted); }
.period-switcher :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) { background: #18181b; color: #fff; box-shadow: none !important; }
.metric-grid { gap: 0; overflow: hidden; border: 1px solid var(--kk-card-border); border-radius: 12px; background: rgba(255,255,255,.78); }
.metric-grid button { min-height: 112px; padding: 16px 17px 14px; border: 0 !important; border-right: 1px solid var(--kk-card-border) !important; border-radius: 0; background: transparent; box-shadow: none; }
.metric-grid button:last-child { border-right: 0 !important; }
.metric-grid button::before { inset: 14px auto 14px 0; width: 2px; border-radius: 2px; background: transparent; }
.metric-grid button.metric-danger::before { background: var(--cockpit-danger); }.metric-grid button.metric-warning::before { background: var(--cockpit-warning); }
.metric-grid button:hover { border-color: var(--kk-card-border) !important; background: rgba(24,24,27,.025); box-shadow: none; transform: none; }
.metric-grid span { font-size: 11px; font-weight: 600; }.metric-grid strong { margin: 9px 0 5px; font-size: 32px; }.metric-grid small { min-height: auto; color: var(--kk-text-muted); font-size: 10px; }.metric-grid em { display: none; }
.decision-strip { grid-template-columns: repeat(4,1fr); border-radius: 10px; background: rgba(255,255,255,.62); }
.decision-title { padding: 10px 14px; background: rgba(24,24,27,.025); }.decision-strip button { min-height: 46px; padding: 8px 14px; }.decision-strip button b { font-size: 15px; }.signal { width: 6px; height: 6px; box-shadow: none; }
.cockpit .page-card { padding: 18px 20px; border: 1px solid var(--kk-card-border); border-radius: 12px; background: rgba(255,255,255,.72); box-shadow: none; backdrop-filter: none; -webkit-backdrop-filter: none; }
.cockpit .page-card::after { display: none; }.cockpit .page-card:hover { border-color: var(--kk-card-border); box-shadow: none; }
.risk-panel::before { display: none; }.risk-panel { border-left: 1px solid var(--kk-card-border); }
.main-grid,.insight-grid { gap: 14px; }.main-grid { grid-template-columns: repeat(3,minmax(0,1fr)); }
.section-head { margin-bottom: 12px; }.section-head h3 { font-size: 15px; font-weight: 700; }.section-head p { font-size: 11px; }
.main-grid .risk-panel,.main-grid .health-panel { padding: 15px 16px; }
.main-grid .risk-panel .section-head,.main-grid .health-panel .section-head { margin-bottom: 8px; }
.risk-row { grid-template-columns: 66px minmax(0,1fr) 86px; gap: 9px; min-height: 50px; padding: 7px 5px; }.health-row { grid-template-columns: minmax(0,1fr) auto 62px; gap: 8px; min-height: 50px; padding: 7px 4px; }
.risk-row div strong,.risk-row aside strong,.health-row div strong { font-size: 12px; }.risk-row div span,.risk-row aside span { font-size: 10px; }.health-row p { font-size: 10px; }.health-row p b { font-size: 14px; }.health-row em { margin: 0 4px; }
.main-grid .trend-chart { min-height: 118px; }.main-grid .trend-column>div { min-height: 84px; }.main-grid .trend-column button { width: 11px; }
.risk-row:hover,.health-row:hover,.load-row:hover,.drill-list>button:hover { background: rgba(24,24,27,.028); }
@media(max-width:1300px){
  .metric-grid{grid-template-columns:repeat(4,1fr)}.metric-grid button:nth-child(4){border-right:0 !important}.metric-grid button:nth-child(-n+4){border-bottom:1px solid var(--kk-card-border) !important}
}
@media(max-width:1200px){
  .decision-strip{grid-template-columns:repeat(2,1fr)}
}
@media(max-width:900px){
  .cockpit-hero{min-height:0}.metric-grid{grid-template-columns:repeat(2,1fr)}.metric-grid button:nth-child(4){border-right:0 !important}.metric-grid button:nth-child(odd){border-right:1px solid var(--kk-card-border) !important}.metric-grid button:nth-child(even){border-right:0 !important}.metric-grid button:nth-child(-n+6){border-bottom:1px solid var(--kk-card-border) !important}.metric-grid button:last-child{border-right:0 !important}.decision-strip{grid-template-columns:1fr 1fr}
}
@media(max-width:560px){
  .cockpit{gap:12px}.cockpit-hero{padding-inline:2px}.hero-copy h1{font-size:25px}.metric-grid button{min-height:106px;padding:14px}.metric-grid strong{font-size:28px}.decision-strip{grid-template-columns:1fr}
}

.member-summary { gap: 6px; overflow: visible; border: 0; background: transparent; }.member-summary span { padding: 7px 10px; border: 0; border-radius: 8px; background: var(--kk-fill); }
.member-chart-shell { position: relative; padding-top: 2px; }
.member-chart-legend { display: flex; align-items: center; justify-content: flex-end; gap: 16px; margin: 0 0 10px; color: var(--kk-text-muted); font-size: 10px; }
.member-chart-legend span { display: inline-flex; align-items: center; gap: 5px; white-space: nowrap; }.member-chart-legend i { width: 9px; height: 9px; display: inline-block; border-radius: 2px; }.member-chart-legend i.done { background: #34c98f; }.member-chart-legend i.doing { background: #5b7cfa; }.member-chart-legend i.overdue { background: #ff6b6b; }
.member-chart-scroll { overflow-x: auto; padding-bottom: 5px; scrollbar-width: thin; }
.member-chart { position: relative; width: 100%; height: 304px; }
.member-grid-lines { position: absolute; inset: 18px 0 66px; display: flex; flex-direction: column; justify-content: space-between; pointer-events: none; }.member-grid-lines i { display: block; border-top: 1px dashed rgba(100,116,139,.16); }
.member-chart-groups { position: absolute; inset: 0; display: grid; }
.member-chart-group { min-width: 0; display: grid; grid-template-rows: 238px auto auto; padding: 0 16px; border: 0; background: transparent; color: inherit; cursor: pointer; text-align: center; }.member-chart-group:hover { background: linear-gradient(to bottom, rgba(24,24,27,.025), transparent); }.member-chart-group:focus-visible { outline: 2px solid var(--cockpit-blue); outline-offset: -2px; }
.member-bars { height: 220px; display: flex; align-self: end; align-items: flex-end; justify-content: center; gap: 6px; }.member-bars i { position: relative; width: 20px; min-height: 3px; display: block; border-radius: 4px 4px 1px 1px; transition: filter 140ms ease-out; }.member-chart-group:hover .member-bars i { filter: saturate(1.14) brightness(.96); }.member-bars i.done { background: #34c98f; }.member-bars i.doing { background: #5b7cfa; }.member-bars i.overdue { background: #ff6b6b; }.member-bars b { position: absolute; bottom: calc(100% + 3px); left: 50%; color: var(--kk-text-secondary); font-size: 9px; font-style: normal; font-weight: 600; font-variant-numeric: tabular-nums; transform: translateX(-50%); }
.member-chart-group>strong { min-width: 0; overflow: hidden; margin-top: 9px; color: var(--kk-text); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }.member-chart-group>small { margin-top: 3px; color: var(--kk-text-muted); font-size: 9px; font-variant-numeric: tabular-nums; }
@media(max-width:720px){.member-summary{display:grid;grid-template-columns:1fr 1fr}.member-stats-panel{padding-inline:12px}}
</style>
