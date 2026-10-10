<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { bizApi } from '@/api/biz'
import { sysApi } from '@/api/system'
import TaskDetailDrawer from '@/components/task/TaskDetailDrawer.vue'

const loading = ref(false)
const dashboard = ref<any>({})
const projects = ref<any[]>([])
const users = ref<any[]>([])
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
type DetailCategory = 'DONE' | 'DOING' | 'OVERDUE'
const detailCategory = ref<DetailCategory>('DOING')
const detailLoading = ref(false)
const detailRows = ref<any[]>([])
const detailTotal = ref(0)
const detailPage = ref(1)
const filters = reactive({ participantId: undefined as number | undefined, projectId: undefined as number | undefined, priority: undefined as number | undefined, title: '' })
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

const statusMap: Record<number, string> = { 0: '待办', 1: '进行中', 2: '已完成', 3: '已关闭', 4: '待确认完成' }
const filteredMemberTaskStats = computed(() => {
  const rows = dashboard.value.memberTaskStats || []
  if (filters.participantId == null) return rows
  return rows.filter((item: any) => Number(item.memberId) === Number(filters.participantId))
})
const memberStatsSummary = computed(() => {
  const rows = filteredMemberTaskStats.value
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
const memberChartRows = computed(() => filteredMemberTaskStats.value.map((item: any) => ({
  ...item,
  total: Number(item.total || 0),
  done: Number(item.done || 0),
  doing: Number(item.doing || 0),
  overdue: Number(item.overdue || 0),
  completionRate: Number(item.total || 0) ? Math.round(Number(item.done || 0) / Number(item.total) * 100) : 0,
})))
function memberInitial(name: string) {
  return (name || '?').trim().slice(0, 1).toUpperCase()
}

function detailStatus(item: any) {
  return detailCategory.value === 'OVERDUE' ? '已逾期' : (statusMap[item.status] || '未知状态')
}

function detailStatusType(item: any) {
  if (detailCategory.value === 'OVERDUE') return 'danger'
  if (Number(item.status) === 2) return 'success'
  if (Number(item.status) === 4) return 'warning'
  return 'primary'
}
const selectedUserName = computed(() => {
  if (filters.participantId == null) return '全部成员'
  const user = users.value.find(item => Number(item.id) === Number(filters.participantId))
  return user?.nickname || user?.username || '当前成员'
})
function taskParticipantText(item: any) {
  const names = item.participantNames
  return Array.isArray(names) && names.length ? names.join('、') : '暂无参与者'
}
const detailTabs = computed(() => [
  { label: '已完成', value: 'DONE' as const, count: Number(dashboard.value.done || 0) },
  { label: '进行中', value: 'DOING' as const, count: Number(dashboard.value.doing || 0) },
  { label: '已逾期', value: 'OVERDUE' as const, count: Number(dashboard.value.overdue || 0) },
])
const openTasks = computed(() => Number(dashboard.value.todo || 0) + Number(dashboard.value.doing || 0) + Number(dashboard.value.pending || 0))
const selectedPeriodLabel = computed(() => periodOptions.find(item => item.value === period.value)?.label || '当前周期')
const activeFilterCount = computed(() => [filters.participantId, filters.projectId, filters.priority, filters.title.trim()].filter(value => value !== undefined && value !== '').length)

async function load() {
  loading.value = true
  try {
    const params: Record<string, unknown> = {}
    if (filters.participantId != null) params.participantId = filters.participantId
    if (filters.projectId != null) params.projectId = filters.projectId
    if (filters.priority != null) params.priority = filters.priority
    if (filters.title.trim()) params.title = filters.title.trim()
    if (periodBounds.value) {
      params.periodFrom = periodBounds.value.from
      params.periodTo = periodBounds.value.to
    }
    dashboard.value = await bizApi.managementTaskDashboard(params)
    await loadDetailTasks()
  } finally {
    loading.value = false
  }
}

async function loadDetailTasks() {
  detailLoading.value = true
  try {
    const params: Record<string, unknown> = {
      page: detailPage.value,
      pageSize: 10,
      dashboardCategory: detailCategory.value,
    }
    if (filters.participantId != null) params.participantId = filters.participantId
    if (filters.projectId != null) params.projectId = filters.projectId
    if (filters.priority != null) params.priority = filters.priority
    if (filters.title.trim()) params.title = filters.title.trim()
    if (periodBounds.value) {
      params.periodFrom = periodBounds.value.from
      params.periodTo = periodBounds.value.to
    }
    const result = await bizApi.managementTaskPage(params)
    detailRows.value = result.list || []
    detailTotal.value = Number(result.total || 0)
  } finally {
    detailLoading.value = false
  }
}

function changeDetailCategory(value: string | number | boolean | undefined) {
  detailCategory.value = value as DetailCategory
  detailPage.value = 1
  loadDetailTasks()
}

function scheduleFilterLoad(delay = 300) {
  if (filterTimer) clearTimeout(filterTimer)
  filterTimer = setTimeout(() => {
    filterTimer = undefined
    load()
  }, delay)
}

watch(
  () => [filters.participantId, filters.projectId, filters.priority, filters.title] as const,
  ([participantId, projectId, priority], [previousParticipantId, previousProjectId, previousPriority]) => {
    const selectChanged = participantId !== previousParticipantId || projectId !== previousProjectId || priority !== previousPriority
    scheduleFilterLoad(selectChanged ? 0 : 300)
  },
)

function reset() {
  filters.participantId = undefined
  filters.projectId = undefined
  filters.priority = undefined
  filters.title = ''
  period.value = 'THIS_MONTH'
  customPeriod.value = undefined
  detailPage.value = 1
  scheduleFilterLoad(0)
}

function changePeriod(value: string | number | boolean | undefined) {
  period.value = value as PeriodKey
  if (period.value !== 'CUSTOM') load()
}

function changeCustomPeriod() {
  if (customPeriod.value?.length === 2) load()
}

function openTask(id: number) {
  activeTaskId.value = id
  drawerOpen.value = true
}

const categoryTitles: Record<string, string> = {
  TOTAL: '全部任务', OPEN: '在办任务', OVERDUE: '逾期任务', DONE_30: '周期内完成任务',
  DUE_SOON: '7天内到期任务', STALE: '长期未推进任务',
  NO_DUE_DATE: '未设截止日任务', PENDING: '待确认完成任务',
}

async function loadTaskList() {
  listLoading.value = true
  try {
    const params: Record<string, unknown> = { page: listPage.value, pageSize: 10, dashboardCategory: listCategory.value }
    const projectId = listProjectId.value ?? filters.projectId
    if (filters.participantId != null) params.participantId = filters.participantId
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
  const [projectOptions, userOptions] = await Promise.all([
    bizApi.taskManagementProjects(),
    sysApi.userList(),
  ])
  projects.value = projectOptions
  users.value = userOptions
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
        <el-form-item label="人员">
          <el-select v-model="filters.participantId" clearable filterable placeholder="全部人员">
            <el-option v-for="item in users" :key="item.id" :label="item.nickname || item.username" :value="Number(item.id)" />
          </el-select>
        </el-form-item>
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
      <button type="button" @click="showTasks('DONE_30')"><span>周期完成量</span><strong>{{ dashboard.done30 ?? 0 }}</strong><small>所选周期内创建且已完成</small><em>查看明细</em></button>
    </section>

    <section class="decision-strip" aria-label="待处理事项">
      <button type="button" @click="showTasks('PENDING')"><i class="signal signal-blue" aria-hidden="true" /><span><b>{{ dashboard.pending ?? 0 }}</b> 项完成申请待确认</span></button>
      <button type="button" @click="showTasks('DUE_SOON')"><i class="signal signal-orange" aria-hidden="true" /><span><b>{{ dashboard.dueSoon ?? 0 }}</b> 项任务 7 天内到期</span></button>
      <button type="button" @click="showTasks('STALE')"><i class="signal signal-red" aria-hidden="true" /><span><b>{{ dashboard.stale ?? 0 }}</b> 项任务长期未推进</span></button>
      <button type="button" @click="showTasks('NO_DUE_DATE')"><i class="signal" aria-hidden="true" /><span><b>{{ dashboard.noDueDate ?? 0 }}</b> 项任务未设截止日</span></button>
    </section>

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
      <div v-if="memberChartRows.length" class="member-card-grid" aria-label="成员任务统计">
        <button v-for="item in memberChartRows" :key="item.memberId" type="button" class="member-card"
          :aria-label="`${item.memberName}：共${item.total}项，已完成${item.done}项，进行中${item.doing}项，逾期${item.overdue}项`" @click="showMemberTasks(item)">
          <span class="member-card-head">
            <strong :title="item.memberName">{{ item.memberName }}</strong>
            <span><b>{{ item.completionRate }}%</b><small>完成率</small></span>
          </span>
          <span class="member-card-progress" aria-hidden="true"><i :style="{ width: `${item.completionRate}%` }" /></span>
          <span class="member-card-stats">
            <span><b>{{ item.total }}</b><small>总任务</small></span>
            <span><b>{{ item.doing }}</b><small>进行中</small></span>
            <span :class="{ danger: item.overdue > 0 }"><b>{{ item.overdue }}</b><small>逾期</small></span>
          </span>
        </button>
      </div>
      <el-empty v-else description="暂无成员任务数据" :image-size="64" />
    </section>

    <section class="page-card task-detail-panel" aria-labelledby="task-detail-title">
      <div class="section-head task-detail-head">
        <div>
          <h3 id="task-detail-title">任务明细</h3>
          <p>{{ selectedUserName }} · 自动继承上方全部筛选条件</p>
        </div>
        <el-radio-group :model-value="detailCategory" size="small" aria-label="任务状态" @change="changeDetailCategory">
          <el-radio-button v-for="tab in detailTabs" :key="tab.value" :value="tab.value">
            {{ tab.label }} <b>{{ tab.count }}</b>
          </el-radio-button>
        </el-radio-group>
      </div>
      <div v-loading="detailLoading" class="task-detail-list" aria-live="polite">
        <div v-if="detailRows.length" class="task-table-head" aria-hidden="true">
          <span>任务名称</span><span>所属项目</span><span>任务人员</span><span>状态</span><span>截止日期</span><span>操作</span>
        </div>
        <button v-for="item in detailRows" :key="item.id" type="button" class="task-table-row" :aria-label="`查看任务：${item.title}`" @click="openTask(item.id)">
          <span class="task-title-cell" data-label="任务名称"><strong>{{ item.title }}</strong><small>点击查看任务详情</small></span>
          <span class="task-project-cell" data-label="所属项目">{{ item.projectName || '未关联项目' }}</span>
          <span class="task-owner-cell" data-label="任务人员">
            <i aria-hidden="true">{{ memberInitial(item.assigneeName || '未') }}</i>
            <span class="task-people-copy">
              <strong>负责人：{{ item.assigneeName || '未指定' }}</strong>
              <small :title="taskParticipantText(item)">参与人：{{ taskParticipantText(item) }}</small>
            </span>
          </span>
          <span class="task-status-cell" data-label="状态"><el-tag :type="detailStatusType(item)" size="small" effect="light">{{ detailStatus(item) }}</el-tag></span>
          <span class="task-due-cell" :class="{ empty: !item.dueDate, overdue: detailCategory === 'OVERDUE' }" data-label="截止日期">{{ item.dueDate || '未设置' }}</span>
          <span class="task-action-cell" aria-hidden="true">查看 <b>›</b></span>
        </button>
        <el-empty v-if="!detailRows.length && !detailLoading" :description="`${selectedUserName}暂无${detailTabs.find(item => item.value === detailCategory)?.label || ''}任务`" :image-size="64" />
        <el-pagination v-if="detailTotal > 10" v-model:current-page="detailPage" :page-size="10" :total="detailTotal" layout="prev, pager, next, total" @current-change="loadDetailTasks" />
      </div>
    </section>

    <el-dialog v-model="listOpen" :title="listTitle" width="min(820px, calc(100vw - 24px))">
      <div v-loading="listLoading" class="drill-list">
        <button v-for="item in listRows" :key="item.id" type="button" @click="openListedTask(item.id)">
          <div><strong>{{ item.title }}</strong><span>{{ item.projectName || '未关联项目' }} · 负责人 {{ item.assigneeName || '未指定' }}</span></div>
          <aside>{{ item.dueDate || '未设截止日' }} · {{ statusMap[item.status] }}</aside>
        </button>
        <el-pagination v-if="listTotal > 10" v-model:current-page="listPage" :page-size="10" :total="listTotal" layout="prev, pager, next, total" @current-change="loadTaskList" />
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
.metric-grid { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); overflow: hidden; border: 1px solid var(--kk-card-border); border-radius: 16px; background: var(--kk-card-bg); box-shadow: 0 1px 2px rgba(0,0,0,.035); }
.metric-grid button { min-width: 0; padding: 18px 20px; border: 0; border-right: 1px solid var(--kk-card-border); background: transparent; text-align: left; cursor: pointer; transition: background-color 160ms ease-out, transform 160ms ease-out; }
.metric-grid button:last-child { border-right: 0; }
.metric-grid button:hover { background: rgba(0,0,0,.025); transform: translateY(-1px); }
.metric-grid button:focus-visible,.decision-strip button:focus-visible { position: relative; outline: 2px solid var(--el-color-primary); outline-offset: -2px; }
.metric-grid span, .metric-grid small { display: block; color: var(--kk-text-muted); }
.metric-grid span { font-size: 12px; font-weight: 500; }
.metric-grid strong { display: block; margin: 8px 0 6px; color: var(--kk-text); font-size: 28px; line-height: 1; font-variant-numeric: tabular-nums; }
.metric-grid small { overflow: hidden; font-size: 11px; line-height: 1.45; text-overflow: ellipsis; white-space: nowrap; }
.metric-danger strong { color: var(--kk-danger); }
.decision-strip { display: grid; grid-template-columns: repeat(4,1fr); overflow: hidden; border: 1px solid var(--kk-card-border); border-radius: 14px; background: rgba(255,255,255,.38); }
.decision-strip button { display: flex; align-items: baseline; justify-content: center; gap: 7px; padding: 13px 16px; border: 0; border-right: 1px solid var(--kk-card-border); background: transparent; cursor: pointer; transition: background-color 160ms ease-out; }
.decision-strip button:hover { background: rgba(0,0,0,.025); }
.decision-strip button:last-child { border: 0; }.decision-strip b { color: var(--kk-text); font-size: 19px; font-variant-numeric: tabular-nums; }.decision-strip span { color: var(--kk-text-secondary); font-size: 12px; }
.drill-list { min-height: 120px; }.drill-list>button { width: 100%; display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 14px 10px; border: 0; border-bottom: 1px solid var(--kk-card-border); background: transparent; text-align: left; cursor: pointer; }.drill-list>button:hover { background: rgba(0,0,0,.025); }.drill-list div strong,.drill-list div span { display: block; }.drill-list div span { margin-top: 5px; color: var(--kk-text-muted); font-size: 12px; }.drill-list aside { flex: none; color: var(--kk-text-secondary); font-size: 12px; }.drill-list em { color: var(--kk-danger); font-style: normal; }.drill-list .el-pagination { justify-content: flex-end; margin-top: 18px; }
.insight-grid { display: grid; grid-template-columns: 1fr; gap: 20px; }
.page-card { padding: 20px; }
.section-head { display: flex; justify-content: space-between; margin-bottom: 14px; }.section-head h3 { margin: 0 0 5px; color: var(--kk-text); font-size: 16px; line-height: 1.3; text-wrap: balance; }.section-head p { margin: 0; color: var(--kk-text-muted); font-size: 12px; line-height: 1.5; text-wrap: pretty; }
.load-row { width: 100%; display: block; margin: 4px 0; padding: 9px 6px; border: 0; border-radius: 8px; background: transparent; text-align: left; cursor: pointer; transition: background-color 140ms ease-out; }.load-row:hover { background: rgba(0,0,0,.025); }.load-row:focus-visible { outline: 2px solid var(--el-color-primary); }.load-row>div { display: flex; justify-content: space-between; gap: 16px; font-size: 12px; }.load-row strong { color: var(--kk-text); }.load-row span { color: var(--kk-text-muted); font-variant-numeric: tabular-nums; }.load-row p { height: 6px; margin: 8px 0 0; overflow: hidden; border-radius: 6px; background: var(--kk-fill); }.load-row p i { display: block; height: 100%; border-radius: 6px; background: var(--el-color-primary); }.load-row p i.danger { background: var(--kk-danger); }
.member-stats-panel { overflow: hidden; padding: 22px 24px 18px; }
.member-stats-panel>.section-head { align-items: flex-start; gap: 20px; }
.member-summary { display: flex; overflow: hidden; border: 1px solid var(--kk-card-border); border-radius: 10px; background: var(--kk-fill); }
.member-summary span { padding: 8px 13px; border-right: 1px solid var(--kk-card-border); color: var(--kk-text-muted); font-size: 11px; white-space: nowrap; }
.member-summary span:last-child { border-right: 0; }.member-summary b { margin-right: 3px; color: var(--kk-text); font-size: 13px; font-variant-numeric: tabular-nums; }.member-summary .danger b { color: var(--kk-danger); }
.member-card-grid { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 10px; }
.member-card { min-width: 0; padding: 15px 16px 14px; border: 1px solid var(--kk-card-border); border-radius: 10px; background: color-mix(in srgb, var(--kk-card-bg) 86%, var(--kk-fill)); color: inherit; text-align: left; cursor: pointer; transition: border-color 150ms ease-out, background-color 150ms ease-out, transform 150ms ease-out; }
.member-card:hover { border-color: color-mix(in srgb, var(--cockpit-blue) 25%, var(--kk-card-border)); background: var(--kk-card-bg); transform: translateY(-1px); }.member-card:focus-visible { outline: 2px solid var(--cockpit-blue); outline-offset: 2px; }
.member-card-head { display: flex; align-items: center; justify-content: space-between; gap: 14px; }.member-card-head>strong { min-width: 0; overflow: hidden; color: var(--kk-text); font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }.member-card-head>span { display: flex; flex: none; align-items: baseline; gap: 4px; }.member-card-head b { color: var(--kk-text); font-size: 15px; font-variant-numeric: tabular-nums; }.member-card-head small { color: var(--kk-text-muted); font-size: 9px; }
.member-card-progress { height: 5px; display: block; overflow: hidden; margin: 13px 0 12px; border-radius: 6px; background: var(--kk-fill); }.member-card-progress i { height: 100%; display: block; min-width: 2px; border-radius: inherit; background: var(--cockpit-blue); }
.member-card-stats { display: grid; grid-template-columns: repeat(3,1fr); }.member-card-stats>span { display: flex; align-items: baseline; gap: 5px; border-right: 1px solid var(--kk-card-border); }.member-card-stats>span:nth-child(2) { justify-content: center; }.member-card-stats>span:last-child { justify-content: flex-end; border-right: 0; }.member-card-stats b { color: var(--kk-text-secondary); font-size: 12px; font-variant-numeric: tabular-nums; }.member-card-stats small { color: var(--kk-text-muted); font-size: 9px; white-space: nowrap; }.member-card-stats .danger b,.member-card-stats .danger small { color: var(--cockpit-danger); }
@media(max-width:1100px){.member-card-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:640px){.member-card-grid{grid-template-columns:1fr}.member-card{padding:14px}}
@media(max-width:900px){.period-filter{width:100%;margin-left:0}.period-filter :deep(.el-form-item__content){flex-wrap:wrap}.insight-grid{display:block}.metric-grid{grid-template-columns:repeat(2,1fr)}.metric-grid button:nth-child(3){border-right:1px solid var(--kk-card-border)}.metric-grid button:nth-child(even){border-right:0}.metric-grid button:nth-child(-n+2){border-bottom:1px solid var(--kk-card-border)}.decision-strip{grid-template-columns:1fr 1fr}.decision-strip button:nth-child(2){border-right:0}.decision-strip button:nth-child(-n+2){border-bottom:1px solid var(--kk-card-border)}.load-panel{margin-top:20px}}
@media(max-width:560px){.cockpit{gap:16px}.metric-grid{grid-template-columns:1fr 1fr}.metric-grid button{padding:15px}.metric-grid strong{font-size:24px}.metric-grid small{white-space:normal}.decision-strip button{align-items:center;justify-content:flex-start;padding:12px}.decision-strip span{line-height:1.35}.page-card{padding:16px}.drill-list>button{display:block}.drill-list aside{margin-top:8px}}
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
.cockpit-filter { display: grid; grid-template-columns: repeat(2, minmax(150px, 1fr)) minmax(130px, .7fr) minmax(180px, 1fr) auto; align-items: end; gap: 14px; padding: 14px 16px; }
.cockpit-filter :deep(.el-form-item) { min-width: 0; display: block; margin: 0; }
.cockpit-filter :deep(.el-form-item__label) { height: auto; margin-bottom: 6px; color: var(--kk-text-muted); font-size: 11px; line-height: 1.2; }
.cockpit-filter :deep(.el-form-item__content),.cockpit-filter :deep(.el-select),.cockpit-filter :deep(.el-input) { width: 100%; }
.filter-actions :deep(.el-form-item__content) { display: flex; flex-wrap: nowrap; gap: 8px; }
.filter-actions :deep(.el-button + .el-button) { margin-left: 0; }
.metric-grid { gap: 10px; overflow: visible; border: 0; border-radius: 0; background: transparent; box-shadow: none; }
.metric-grid button { position: relative; min-height: 142px; overflow: hidden; padding: 18px; border: 1px solid var(--kk-card-border) !important; border-radius: 14px; background: var(--kk-card-bg); box-shadow: 0 1px 2px rgba(15, 23, 42, .03); }
.metric-grid button::before { content: ''; position: absolute; inset: 0 auto 0 0; width: 3px; background: var(--cockpit-blue); opacity: .72; }
.metric-grid button.metric-danger::before { background: var(--cockpit-danger); }
.metric-grid button:hover { border-color: color-mix(in srgb, var(--cockpit-blue) 28%, var(--kk-card-border)) !important; background: var(--kk-card-bg); box-shadow: 0 8px 22px rgba(15, 23, 42, .075); transform: translateY(-2px); }
.metric-grid span { color: var(--kk-text-secondary); font-size: 12px; font-weight: 650; }
.metric-grid strong { margin: 12px 0 7px; font-size: 30px; letter-spacing: -.04em; }
.metric-grid small { min-height: 31px; white-space: normal; }
.metric-grid em { display: block; margin-top: 10px; color: var(--cockpit-blue); font-size: 10px; font-style: normal; font-weight: 650; opacity: 0; transform: translateX(-4px); transition: opacity 160ms ease-out, transform 160ms ease-out; }
.metric-grid button:hover em,.metric-grid button:focus-visible em { opacity: 1; transform: translateX(0); }
.metric-danger em { color: var(--cockpit-danger); }
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
.load-row,.drill-list>button { border-radius: 8px; }
.load-row:hover,.drill-list>button:hover { background: var(--kk-fill); }
.load-row:focus-visible,.drill-list>button:focus-visible { outline: 2px solid var(--cockpit-blue); outline-offset: -2px; }
@media(max-width:1200px){
  .cockpit-filter{grid-template-columns:1fr 150px 1fr auto}.decision-strip{grid-template-columns:130px repeat(2,1fr)}.decision-title{grid-row:span 2}.decision-strip button:nth-of-type(2){border-right:0}.decision-strip button:nth-of-type(-n+2){border-bottom:1px solid var(--kk-card-border)}
}
@media(max-width:900px){
  .cockpit-hero{align-items:flex-start}.cockpit-filter{grid-template-columns:1fr 1fr}.filter-actions{grid-column:2}.period-switcher{align-items:flex-start;flex-wrap:wrap}.period-switcher :deep(.el-date-editor){width:100%;margin-left:0}.decision-strip{grid-template-columns:1fr 1fr}.decision-title{grid-column:1 / -1;grid-row:auto;border-right:0;border-bottom:1px solid var(--kk-card-border)}
}
@media(max-width:560px){
  .cockpit-hero{display:block}.scope-summary{margin-top:16px}.period-switcher{padding:12px}.control-label{width:100%}.period-switcher :deep(.el-radio-group){display:grid;grid-template-columns:repeat(3,1fr);width:100%}.cockpit-filter{grid-template-columns:1fr;padding:12px}.filter-actions{grid-column:auto}.metric-grid button{min-height:132px;padding:15px}.metric-grid em{display:none}.decision-strip{grid-template-columns:1fr}.decision-title{grid-column:auto}.decision-strip button{border-right:0;border-bottom:1px solid var(--kk-card-border)}
}
@media(prefers-reduced-motion:reduce){.cockpit *{scroll-behavior:auto !important}.metric-grid button,.metric-grid em,.load-row{transition:none !important}.metric-grid button:hover{transform:none}}

/* Compact scope toolbar: filters support the dashboard instead of becoming a section. */
.control-panel { display: grid; grid-template-columns: auto minmax(0, 1fr); align-items: center; min-height: 58px; overflow: visible; padding: 8px 10px; }
.period-switcher { min-height: 40px; padding: 0 14px 0 6px; border-right: 1px solid var(--kk-card-border); border-bottom: 0; background: transparent; }
.control-label { color: var(--kk-text-muted); font-size: 11px; }
.period-switcher :deep(.el-radio-button__inner) { min-height: 30px; padding: 5px 12px; }
.cockpit-filter { grid-template-columns: repeat(4, minmax(0, 1fr)) auto; gap: 10px; padding: 0 0 0 14px; }
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
.metric-grid button.metric-danger::before { background: var(--cockpit-danger); }
.metric-grid button:hover { border-color: var(--kk-card-border) !important; background: rgba(24,24,27,.025); box-shadow: none; transform: none; }
.metric-grid span { font-size: 11px; font-weight: 600; }.metric-grid strong { margin: 9px 0 5px; font-size: 32px; }.metric-grid small { min-height: auto; color: var(--kk-text-muted); font-size: 10px; }.metric-grid em { display: none; }
.decision-strip { grid-template-columns: repeat(4,1fr); border-radius: 10px; background: rgba(255,255,255,.62); }
.decision-title { padding: 10px 14px; background: rgba(24,24,27,.025); }.decision-strip button { min-height: 46px; padding: 8px 14px; }.decision-strip button b { font-size: 15px; }.signal { width: 6px; height: 6px; box-shadow: none; }
.cockpit .page-card { padding: 18px 20px; border: 1px solid var(--kk-card-border); border-radius: 12px; background: rgba(255,255,255,.72); box-shadow: none; backdrop-filter: none; -webkit-backdrop-filter: none; }
.cockpit .page-card::after { display: none; }.cockpit .page-card:hover { border-color: var(--kk-card-border); box-shadow: none; }
.insight-grid { gap: 14px; }
.section-head { margin-bottom: 12px; }.section-head h3 { font-size: 15px; font-weight: 700; }.section-head p { font-size: 11px; }
.load-row:hover,.drill-list>button:hover { background: rgba(24,24,27,.028); }
@media(max-width:1200px){
  .decision-strip{grid-template-columns:repeat(2,1fr)}
}
@media(max-width:900px){
  .cockpit-hero{min-height:0}.metric-grid{grid-template-columns:repeat(2,1fr)}.metric-grid button:nth-child(odd){border-right:1px solid var(--kk-card-border) !important}.metric-grid button:nth-child(even){border-right:0 !important}.metric-grid button:nth-child(-n+2){border-bottom:1px solid var(--kk-card-border) !important}.metric-grid button:last-child{border-right:0 !important}.decision-strip{grid-template-columns:1fr 1fr}
}
@media(max-width:560px){
  .cockpit{gap:12px}.cockpit-hero{padding-inline:2px}.hero-copy h1{font-size:25px}.metric-grid button{min-height:106px;padding:14px}.metric-grid strong{font-size:28px}.decision-strip{grid-template-columns:1fr}
}

.member-summary { gap: 6px; overflow: visible; border: 0; background: transparent; }.member-summary span { padding: 7px 10px; border: 0; border-radius: 8px; background: var(--kk-fill); }
@media(max-width:720px){.member-summary{display:grid;grid-template-columns:1fr 1fr}.member-stats-panel{padding-inline:12px}}

.task-detail-head { align-items: center; }
.task-detail-head :deep(.el-radio-button__inner) { min-width: 92px; }
.task-detail-head :deep(.el-radio-button b) { margin-left: 4px; font-variant-numeric: tabular-nums; }
.task-detail-list { min-height: 128px; overflow-x: auto; scrollbar-width: thin; }
.task-table-head,.task-table-row { min-width: 1040px; display: grid; grid-template-columns: minmax(240px,1.8fr) minmax(140px,.9fr) minmax(230px,1.3fr) 100px 126px 58px; align-items: center; column-gap: 18px; }
.task-table-head { height: 38px; padding: 0 12px; border-block: 1px solid var(--kk-card-border); background: color-mix(in srgb, var(--kk-fill) 62%, transparent); color: var(--kk-text-muted); font-size: 10px; font-weight: 650; }
.task-table-row { width: 100%; min-height: 62px; padding: 8px 12px; border: 0; border-bottom: 1px solid var(--kk-card-border); background: transparent; color: var(--kk-text-secondary); font-size: 11px; text-align: left; cursor: pointer; transition: background-color 140ms ease-out; }
.task-table-row:hover { background: rgba(24,24,27,.028); }
.task-table-row:focus-visible { outline: 2px solid var(--cockpit-blue); outline-offset: -2px; }
.task-title-cell,.task-title-cell strong,.task-title-cell small { min-width: 0; display: block; }.task-title-cell strong { overflow: hidden; color: var(--kk-text); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }.task-title-cell small { margin-top: 4px; color: var(--kk-text-muted); font-size: 9px; opacity: 0; transform: translateX(-3px); transition: opacity 140ms ease-out, transform 140ms ease-out; }.task-table-row:hover .task-title-cell small,.task-table-row:focus-visible .task-title-cell small { opacity: 1; transform: none; }
.task-project-cell { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.task-owner-cell { min-width: 0; display: flex; align-items: center; gap: 8px; overflow: hidden; }.task-owner-cell i { width: 28px; height: 28px; display: grid; flex: none; place-items: center; border-radius: 50%; background: var(--kk-fill); color: var(--kk-text-secondary); font-size: 10px; font-style: normal; font-weight: 700; }.task-people-copy { min-width: 0; display: block; }.task-people-copy strong,.task-people-copy small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.task-people-copy strong { color: var(--kk-text-secondary); font-size: 11px; font-weight: 600; }.task-people-copy small { margin-top: 3px; color: var(--kk-text-muted); font-size: 10px; }
.task-status-cell { display: flex; }.task-due-cell { font-variant-numeric: tabular-nums; white-space: nowrap; }.task-due-cell.empty { color: var(--cockpit-warning); }.task-due-cell.overdue { color: var(--cockpit-danger); font-weight: 650; }.task-action-cell { color: var(--cockpit-blue); font-weight: 650; white-space: nowrap; opacity: .66; }.task-action-cell b { margin-left: 2px; font-size: 16px; font-weight: 400; vertical-align: -1px; }.task-table-row:hover .task-action-cell { opacity: 1; }
.task-detail-list .el-pagination { justify-content: flex-end; margin-top: 16px; }
@media(max-width:720px){
  .task-detail-head{display:block}.task-detail-head :deep(.el-radio-group){width:100%;display:grid;grid-template-columns:repeat(3,1fr);margin-top:12px}.task-detail-head :deep(.el-radio-button__inner){width:100%;min-width:0}.task-detail-list{overflow:visible}.task-table-head{display:none}.task-table-row{min-width:0;grid-template-columns:1fr auto;gap:9px 16px;margin-bottom:10px;padding:13px;border:1px solid var(--kk-card-border);border-radius:10px}.task-title-cell{grid-column:1 / -1}.task-title-cell small{display:none}.task-project-cell{grid-column:1}.task-owner-cell{grid-column:1}.task-status-cell{grid-column:2;grid-row:2}.task-due-cell{grid-column:2;grid-row:3;align-self:center}.task-due-cell::before{content:'截止 ';color:var(--kk-text-muted);font-weight:400}.task-action-cell{display:none}
}
</style>
