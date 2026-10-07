<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bizApi } from '@/api/biz'
import { useUserStore } from '@/stores/user'
import TaskDetailDrawer from '@/components/task/TaskDetailDrawer.vue'
import TaskTimeline from '@/components/task/TaskTimeline.vue'
import CompanyTaskShareDialog from '@/components/task/CompanyTaskShareDialog.vue'
import { companyTaskShareApi, type CompanyTaskShareOption } from '@/api/companyTaskShare'

const route = useRoute()
const userStore = useUserStore()
// 任务管理始终是个人工作台；管理视角已拆分到独立的“任务驾驶舱”。
const isTaskManager = false

/** “待办与进行中”快捷筛选包含待确认完成 */
const OPEN_STATUSES = [0, 1, 4]

const query = reactive({
  page: 1,
  pageSize: 10,
  title: '',
  projectId: undefined as number | undefined,
  status: undefined as number | undefined,
  statuses: undefined as number[] | undefined,
  priority: undefined as number | undefined,
  overdue: undefined as boolean | undefined,
})

type PeriodKey = 'all' | 'thisMonth' | 'lastMonth' | 'last7Days' | 'last30Days' | 'custom'
const periodKey = ref<PeriodKey>('all')
const customPeriod = ref<[string, string] | null>(null)
const periodOptions: Array<{ value: PeriodKey; label: string }> = [
  { value: 'all', label: '全部时间' },
  { value: 'thisMonth', label: '本月' },
  { value: 'lastMonth', label: '上个月' },
  { value: 'last7Days', label: '近 7 天' },
  { value: 'last30Days', label: '近 30 天' },
  { value: 'custom', label: '自定义' },
]

function formatLocalDate(date: Date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

/** 后端使用左闭右开区间，因此结束日期统一转换为次日。 */
function resolvePeriod(): { periodFrom?: string; periodTo?: string } {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  let from: Date | undefined
  let to: Date | undefined
  if (periodKey.value === 'thisMonth') {
    from = new Date(today.getFullYear(), today.getMonth(), 1)
    to = new Date(today.getFullYear(), today.getMonth() + 1, 1)
  } else if (periodKey.value === 'lastMonth') {
    from = new Date(today.getFullYear(), today.getMonth() - 1, 1)
    to = new Date(today.getFullYear(), today.getMonth(), 1)
  } else if (periodKey.value === 'last7Days' || periodKey.value === 'last30Days') {
    const days = periodKey.value === 'last7Days' ? 7 : 30
    from = new Date(today)
    from.setDate(from.getDate() - days + 1)
    to = new Date(today)
    to.setDate(to.getDate() + 1)
  } else if (periodKey.value === 'custom' && customPeriod.value?.length === 2) {
    from = new Date(`${customPeriod.value[0]}T00:00:00`)
    to = new Date(`${customPeriod.value[1]}T00:00:00`)
    to.setDate(to.getDate() + 1)
  }
  return from && to ? { periodFrom: formatLocalDate(from), periodTo: formatLocalDate(to) } : {}
}

const list = ref<any[]>([])
const total = ref(0)
const summary = ref<any>({})
const projects = ref<any[]>([])
const taskDrawer = ref(false)
const activeTaskId = ref<number | null>(null)
type TaskDrawerAction = 'view' | 'edit' | 'transfer' | 'close'
const taskDrawerAction = ref<TaskDrawerAction>('view')
const listLoading = ref(false)
const shareOpen = ref(false)
const shareCompanies = ref<CompanyTaskShareOption[]>([])
type TaskViewMode = 'timeline' | 'details'
const taskViewMode = ref<TaskViewMode>('details')

function taskContentPreview(content?: string) {
  if (!content) return ''
  const document = new DOMParser().parseFromString(content, 'text/html')
  return document.body.textContent?.trim() || ''
}

function selectTaskView(mode: TaskViewMode, moveFocus = false) {
  taskViewMode.value = mode
  if (moveFocus) document.getElementById(`task-view-tab-${mode}`)?.focus()
}

function onTaskViewKeydown(event: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const mode = event.key === 'ArrowLeft' || event.key === 'Home' ? 'details' : 'timeline'
  selectTaskView(mode, true)
}

type ProjectTreeNode = {
  id: number | string
  label: string
  scale?: string
  children?: ProjectTreeNode[]
}

const projectTree = computed<ProjectTreeNode[]>(() => {
  const visible = projects.value.filter((project) => ![2, 3].includes(Number(project.status)))
  const childrenByParent = new Map<number, any[]>()
  visible.forEach((project) => {
    if (!project.parentId) return
    const parentId = Number(project.parentId)
    childrenByParent.set(parentId, [...(childrenByParent.get(parentId) || []), project])
  })

  const topProjects = visible
    .filter((project) => !project.parentId)
    .map((project) => {
      const children = (childrenByParent.get(Number(project.id)) || []).map((child) => ({
        id: Number(child.id),
        label: child.name,
        scale: child.scale,
      }))
      return {
        id: Number(project.id),
        label: project.name,
        scale: project.scale,
        ...(children.length ? { children } : {}),
      }
    })

  return [{ id: 'all', label: '全部项目', children: topProjects }]
})

const selectedProjectName = computed(() => {
  if (query.projectId == null) return '全部项目'
  return projects.value.find((project) => Number(project.id) === Number(query.projectId))?.name || '当前项目'
})

const statusMap: Record<number, string> = { 0: '待办', 1: '进行中', 2: '已完成', 3: '已关闭', 4: '待确认完成' }
const priorityMap: Record<number, string> = { 1: '高', 2: '中', 3: '低' }
const statusType: Record<number, '' | 'success' | 'warning' | 'info' | 'danger'> = {
  0: 'info',
  1: 'warning',
  2: 'success',
  3: 'info',
  4: 'warning',
}
const priorityType: Record<number, '' | 'success' | 'warning' | 'info' | 'danger'> = {
  1: 'danger',
  2: 'warning',
  3: 'info',
}

const statusFilterOptions = [
  { value: 'open', label: '待办与进行中' },
  { value: '0', label: '待办' },
  { value: '1', label: '进行中' },
  { value: '2', label: '已完成' },
  { value: '4', label: '待确认完成' },
  { value: '3', label: '已关闭' },
]

const statusFilterKey = computed({
  get: (): string | undefined => {
    if (query.overdue) return undefined
    if (query.statuses?.length === OPEN_STATUSES.length && OPEN_STATUSES.every((status) => query.statuses?.includes(status)) && query.status == null) {
      return 'open'
    }
    if (query.status !== undefined && query.status !== null) return String(query.status)
    return undefined
  },
  set: (v: string | undefined | null) => {
    query.overdue = undefined
    if (v === 'open') {
      query.status = undefined
      query.statuses = [...OPEN_STATUSES]
    } else if (v === '' || v == null) {
      query.status = undefined
      query.statuses = undefined
    } else {
      const n = Number(v)
      if (Number.isNaN(n)) {
        query.status = undefined
        query.statuses = [...OPEN_STATUSES]
      } else {
        query.status = n
        query.statuses = undefined
      }
    }
    query.page = 1
    load()
  },
})

const managerStatCards = [
  { key: 'riskProjects', label: '风险项目', hint: '需要负责人介入', icon: 'DataAnalysis', tone: 'rose' },
  { key: 'overdue', label: '已逾期', hint: '必须立即处理', icon: 'Warning', tone: 'rose' },
  { key: 'dueSoon', label: '7天内到期', hint: '提前确认交付', icon: 'Timer', tone: 'amber' },
  { key: 'pending', label: '待确认完成', hint: '等待管理确认', icon: 'CircleCheck', tone: 'cyan' },
]

const employeeStatCards = [
  { key: 'todo', label: '我的待办', hint: '尚未开始处理', icon: 'List', tone: 'slate' },
  { key: 'doing', label: '进行中', hint: '当前正在推进', icon: 'Loading', tone: 'cyan' },
  { key: 'overdue', label: '已逾期', hint: '需要优先处理', icon: 'Warning', tone: 'rose' },
  { key: 'pending', label: '待确认完成', hint: '已提交，等待确认', icon: 'CircleCheck', tone: 'amber' },
  { key: 'done', label: '完成任务量', hint: '已确认完成', icon: 'Finished', tone: 'emerald' },
]

const statCards = computed(() => employeeStatCards)

function isStatActive(key: string) {
  if (key === 'overdue') return !!query.overdue
  if (key === 'pending') return query.status === 4 && !query.overdue
  if (key === 'todo') return query.status === 0 && !query.overdue
  if (key === 'doing') return query.status === 1 && !query.overdue
  if (key === 'done') return query.status === 2 && !query.overdue
  return false
}

function onStatClick(key: string) {
  if (key === 'overdue') {
    filterOverdue()
    return
  }
  if (key === 'todo') return filterByStatus(0)
  if (key === 'doing') return filterByStatus(1)
  if (key === 'pending') filterByStatus(4)
  if (key === 'done') filterByStatus(2)
}

const maxTrend = computed(() => Math.max(1, ...(summary.value.trend || []).flatMap((item: any) => [item.created || 0, item.completed || 0])))
const maxOwnerLoad = computed(() => Math.max(1, ...(summary.value.ownerLoad || []).map((item: any) => item.open || 0)))

const healthMap: Record<string, { label: string; type: 'danger' | 'warning' | 'success' }> = {
  DANGER: { label: '危险', type: 'danger' },
  WARNING: { label: '预警', type: 'warning' },
  HEALTHY: { label: '健康', type: 'success' },
}

function selectProject(projectId?: number) {
  query.projectId = projectId
  query.page = 1
  load()
}

function onProjectNodeClick(node: ProjectTreeNode) {
  selectProject(node.id === 'all' ? undefined : Number(node.id))
}

async function loadSummary() {
  // 统计随筛选变：项目/标题/优先级；状态与逾期由卡片本身表达，不传入
  const params: {
    projectId?: number
    priority?: number
    title?: string
    periodFrom?: string
    periodTo?: string
  } = {}
  if (query.projectId != null) params.projectId = query.projectId
  if (query.priority != null) params.priority = query.priority
  if (query.title.trim()) params.title = query.title.trim()
  Object.assign(params, resolvePeriod())
  summary.value = await bizApi.managementTaskSummary(params)
}

async function load() {
  listLoading.value = true
  try {
    const params: Record<string, unknown> = {
      page: query.page,
      pageSize: query.pageSize,
      ...resolvePeriod(),
    }
    if (query.title.trim()) params.title = query.title.trim()
    if (query.projectId != null) params.projectId = query.projectId
    if (query.priority != null) params.priority = query.priority
    if (query.overdue) {
      params.overdue = true
    } else if (query.statuses?.length) {
      params.statuses = query.statuses.join(',')
    } else if (query.status !== undefined && query.status !== null) {
      params.status = query.status
    }
    const res = await bizApi.managementTaskPage(params)
    list.value = res.list
    total.value = res.total
    await loadSummary()
  } finally {
    listLoading.value = false
  }
}

function onFilter() {
  query.page = 1
  load()
}

function onPeriodChange() {
  query.page = 1
  if (periodKey.value !== 'custom' || customPeriod.value) load()
}

function resetQuery() {
  Object.assign(query, {
    page: 1,
    title: '',
    projectId: undefined,
    status: undefined,
    statuses: undefined,
    priority: undefined,
    overdue: undefined,
  })
  periodKey.value = 'all'
  customPeriod.value = null
  load()
}

function filterByStatus(status?: number) {
  query.status = status
  query.statuses = undefined
  query.overdue = undefined
  query.page = 1
  load()
}

function filterOverdue() {
  query.status = undefined
  query.statuses = undefined
  query.overdue = true
  query.page = 1
  load()
}

function open(row?: any, action: TaskDrawerAction = 'view') {
  activeTaskId.value = row?.id ?? null
  taskDrawerAction.value = action
  taskDrawer.value = true
}

async function review(row: any, approved: boolean) {
  let remark = ''
  if (approved) {
    try {
      await ElMessageBox.confirm(`确认任务「${row.title}」已经完成？`, '确认完成', {
        confirmButtonText: '确认完成', cancelButtonText: '取消', type: 'success',
      })
    } catch { return }
  } else {
    try {
      const result = await ElMessageBox.prompt(`请输入驳回「${row.title}」完成申请的原因`, '驳回完成申请', {
        confirmButtonText: '确认驳回', cancelButtonText: '取消', inputType: 'textarea',
        inputValidator: (value) => !!String(value || '').trim() || '请填写驳回原因',
      })
      remark = result.value.trim()
    } catch { return }
  }
  await bizApi.reviewTaskCompletion(row.id, approved, remark)
  ElMessage.success(approved ? '任务已确认完成' : '完成申请已驳回')
  await load()
}

onMounted(async () => {
  projects.value = []
  shareCompanies.value = userStore.hasPermission('project:task:share')
    ? await companyTaskShareApi.options().catch(() => [])
    : []
  const pid = route.query.projectId
  if (pid) {
    const num = Number(pid)
    if (!Number.isNaN(num)) query.projectId = num
  }
  await load()
  const tid = route.query.taskId
  if (tid) {
    const num = Number(tid)
    if (!Number.isNaN(num)) open({ id: num })
  }
})
</script>

<template>
  <div class="page-stack">
    <div class="page-top">
      <div class="page-top__main">
        <h2 class="page-title">{{ isTaskManager ? '交付管理驾驶舱' : '我的任务工作台' }}</h2>
        <p class="page-desc">
          {{ isTaskManager ? '先处理风险和待确认事项，再下钻项目与具体任务。' : '只展示我负责或参与的任务，优先处理逾期和临期事项。' }}
        </p>
      </div>
      <div class="page-actions">
        <el-button v-if="shareCompanies.length" v-permission="'project:task:share'" @click="shareOpen = true">今日工作外链</el-button>
        <el-button type="primary" @click="open()">新建任务</el-button>
      </div>
    </div>

    <el-form class="filter-bar task-filter-bar" @submit.prevent="onFilter">
      <el-form-item label="标题">
        <el-input v-model="query.title" clearable placeholder="任务标题" class="filter-keyword--wide" @keyup.enter="onFilter" @change="onFilter" />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="statusFilterKey" clearable placeholder="全部" class="filter-select--wide">
          <el-option v-for="item in statusFilterOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="优先级">
        <el-select v-model="query.priority" clearable placeholder="全部" class="filter-select" @change="onFilter">
          <el-option v-for="(label, value) in priorityMap" :key="value" :label="label" :value="Number(value)" />
        </el-select>
      </el-form-item>
      <el-form-item label="时间周期">
        <el-select v-model="periodKey" class="period-select" aria-label="按任务创建时间筛选" @change="onPeriodChange">
          <el-option v-for="item in periodOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="periodKey === 'custom'" label="自定义日期">
        <el-date-picker v-model="customPeriod" type="daterange" value-format="YYYY-MM-DD" range-separator="至"
          start-placeholder="开始日期" end-placeholder="结束日期" unlink-panels @change="onPeriodChange" />
      </el-form-item>
      <el-form-item class="filter-actions">
        <el-button @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="stat-grid" :class="{ 'stat-grid--personal': !isTaskManager }">
      <button
        v-for="card in statCards"
        :key="card.key"
        type="button"
        class="stat-card"
        :class="[`stat-card--${card.tone}`, { 'is-active': isStatActive(card.key) }]"
        @click="onStatClick(card.key)"
      >
        <div class="stat-body">
          <div class="stat-label">{{ card.label }}</div>
          <div class="stat-value">{{ summary[card.key] ?? 0 }}</div>
          <div class="stat-hint">{{ card.hint }}</div>
        </div>
        <el-icon class="stat-glyph" :size="44"><component :is="card.icon" /></el-icon>
      </button>
    </div>

    <div v-if="isTaskManager" class="insight-grid">
      <section class="page-card performance-panel">
        <div class="section-head">
          <div><h3>交付效率</h3><p>按实际开始和完成时间计算</p></div>
        </div>
        <div class="performance-metrics">
          <div><span>按期完成率</span><strong>{{ summary.onTimeRate == null ? '—' : `${summary.onTimeRate}%` }}</strong></div>
          <div><span>平均交付周期</span><strong>{{ summary.avgCycleDays == null ? '—' : `${summary.avgCycleDays}天` }}</strong></div>
        </div>
        <div class="trend-legend"><span class="created-dot" />新增任务 <span class="completed-dot" />完成任务</div>
        <div class="trend-chart">
          <div v-for="item in summary.trend || []" :key="item.label" class="trend-column">
            <div class="trend-bars">
              <i class="created" :style="{ height: `${Math.max(3, item.created / maxTrend * 72)}px` }" :title="`新增 ${item.created}`" />
              <i class="completed" :style="{ height: `${Math.max(3, item.completed / maxTrend * 72)}px` }" :title="`完成 ${item.completed}`" />
            </div>
            <span>{{ item.label }}</span>
          </div>
        </div>
      </section>

      <section class="page-card owner-panel">
        <div class="section-head"><div><h3>任务负载</h3><p>按任务负责人和参与人统计，单个任务不重复计算</p></div></div>
        <div v-if="summary.ownerLoad?.length" class="owner-list">
          <div v-for="item in summary.ownerLoad" :key="item.ownerId" class="owner-row">
            <div class="owner-line"><strong>{{ item.ownerName }}</strong><span>{{ item.open }}项 · 逾期{{ item.overdue }}</span></div>
            <div class="owner-bar"><i :style="{ width: `${item.open / maxOwnerLoad * 100}%` }" /></div>
          </div>
        </div>
        <el-empty v-else description="暂无任务负载数据" :image-size="56" />
      </section>
    </div>

    <div class="task-workspace" :class="{ 'task-workspace--personal': !isTaskManager }">
      <aside v-if="isTaskManager" class="page-card project-tree-panel">
        <div class="project-tree-head">
          <div>
            <h3>项目导航</h3>
            <p>选择项目查看对应任务</p>
          </div>
          <span>{{ projects.filter((project) => !project.parentId && ![2, 3].includes(Number(project.status))).length }}</span>
        </div>
        <el-tree
          :data="projectTree"
          node-key="id"
          :current-node-key="query.projectId ?? 'all'"
          default-expand-all
          :expand-on-click-node="false"
          highlight-current
          class="project-tree"
          @node-click="onProjectNodeClick"
        >
          <template #default="{ data }">
            <div class="project-tree-node">
              <el-icon><FolderOpened v-if="data.children?.length" /><Document v-else /></el-icon>
              <span :title="data.label">{{ data.label }}</span>
              <i v-if="data.scale && data.id !== 'all'">{{ data.scale === 'MAJOR' ? '重大' : data.scale === 'KEY' ? '重点' : '常规' }}</i>
            </div>
          </template>
        </el-tree>
      </aside>

      <div class="task-workspace-main">
    <div class="page-card task-view-card">
      <div class="task-view-switcher" role="tablist" aria-label="任务展示方式">
        <button
          id="task-view-tab-details"
          type="button"
          role="tab"
          :aria-selected="taskViewMode === 'details'"
          aria-controls="task-view-panel-details"
          :tabindex="taskViewMode === 'details' ? 0 : -1"
          :class="{ 'is-active': taskViewMode === 'details' }"
          @click="selectTaskView('details')"
          @keydown="onTaskViewKeydown"
        >
          我的任务明细
        </button>
        <button
          id="task-view-tab-timeline"
          type="button"
          role="tab"
          :aria-selected="taskViewMode === 'timeline'"
          aria-controls="task-view-panel-timeline"
          :tabindex="taskViewMode === 'timeline' ? 0 : -1"
          :class="{ 'is-active': taskViewMode === 'timeline' }"
          @click="selectTaskView('timeline')"
          @keydown="onTaskViewKeydown"
        >
          任务时间轴
        </button>
      </div>

      <div
        v-show="taskViewMode === 'timeline'"
        id="task-view-panel-timeline"
        role="tabpanel"
        aria-labelledby="task-view-tab-timeline"
        class="task-view-panel"
      >
        <TaskTimeline :tasks="list" :loading="listLoading" @open="open" />
      </div>

      <div
        v-show="taskViewMode === 'details'"
        id="task-view-panel-details"
        role="tabpanel"
        aria-labelledby="task-view-tab-details"
        class="task-view-panel"
      >
        <div class="section-head task-section-head">
          <div><h3>{{ isTaskManager ? `${selectedProjectName} · 任务明细` : '我的任务明细' }}</h3><p>{{ isTaskManager ? '用于筛选、下钻和日常执行' : '仅包含我负责或直接参与的任务' }}</p></div>
        </div>
        <el-table v-loading="listLoading" :data="list" row-key="id" stripe empty-text="暂无任务" class="task-table">
        <el-table-column label="任务" min-width="320">
          <template #default="{ row }">
            <div class="task-title-cell">
              <div class="task-title-line">
                <el-link type="primary" :underline="false" @click="open(row)">{{ row.title }}</el-link>
                <el-tag v-if="row.overdue" type="danger" size="small" effect="light">逾期</el-tag>
                <el-tag v-else-if="!row.dueDate && [0, 1].includes(row.status)" type="info" size="small" effect="plain">无日期</el-tag>
                <el-tag :type="priorityType[row.priority] || 'info'" size="small" effect="plain">
                  {{ priorityMap[row.priority] || '中' }}优先级
                </el-tag>
              </div>
              <div v-if="taskContentPreview(row.content)" class="task-content-preview">{{ taskContentPreview(row.content) }}</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="projectName" label="所属项目" min-width="150" show-overflow-tooltip />
        <el-table-column label="负责人" min-width="190">
          <template #default="{ row }">
            <div class="task-owner-cell">
              <strong>{{ row.assigneeName || '未指定' }}</strong>
              <span v-if="row.participantNames?.length">
                协作：{{ row.participantNames.filter((name: string) => name !== row.assigneeName).join('、') || '—' }}
              </span>
              <span v-else>暂无协作人员</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="交付状态" min-width="120">
          <template #default="{ row }">
            <el-tag :type="statusType[row.status]" size="small">{{ statusMap[row.status] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="list.some((row: any) => row.taskReward != null)" label="任务报酬" min-width="120">
          <template #default="{ row }">
            <strong v-if="row.taskReward != null">¥{{ Number(row.taskReward).toFixed(2) }}</strong>
          </template>
        </el-table-column>
        <el-table-column label="计划时间" min-width="190">
          <template #default="{ row }">
            <div class="task-date-range">
              <span><i>开始</i>{{ row.startDate || '未设置' }}</span>
              <span :class="{ overdue: row.overdue }"><i>截止</i>{{ row.dueDate || '未设置' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right" align="center">
          <template #default="{ row }">
            <div class="task-row-actions">
              <el-button link type="primary" @click="open(row)">详情</el-button>
              <el-button v-if="userStore.hasPermission('project:task:edit') && row.canEdit && row.status !== 3" link type="primary" @click="open(row, 'edit')">编辑</el-button>
              <el-button v-if="userStore.hasPermission('project:task:add') && row.canTransfer && row.status !== 3" link type="primary" @click="open(row, 'transfer')">移交</el-button>
              <el-button v-if="userStore.hasPermission('project:task:edit') && row.canEdit && row.status !== 3" link type="danger" @click="open(row, 'close')">关闭</el-button>
              <template v-if="isTaskManager && row.status === 4">
                <el-button link type="success" @click="review(row, true)">确认完成</el-button>
                <el-button link type="danger" @click="review(row, false)">驳回</el-button>
              </template>
            </div>
          </template>
        </el-table-column>
        </el-table>
        <div class="page-footer">
          <el-pagination
            v-model:current-page="query.page"
            v-model:page-size="query.pageSize"
            :total="total"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            @current-change="load"
            @size-change="load"
          />
        </div>
      </div>
    </div>
      </div>
    </div>

    <CompanyTaskShareDialog v-model="shareOpen" :companies="shareCompanies" />
    <TaskDetailDrawer
      v-model="taskDrawer"
      :task-id="activeTaskId"
      :default-project-id="query.projectId"
      :initial-action="taskDrawerAction"
      @saved="load"
      @deleted="load"
    />
  </div>
</template>

<style scoped>
.page-title { margin: 0 0 5px; font-size: 20px; color: var(--kk-text); }
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}
.stat-card {
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-height: 92px;
  padding: 16px 14px 16px 18px;
  cursor: pointer;
  text-align: left;
  background: var(--kk-glass-bg);
  border: 1px solid var(--kk-glass-border);
  border-radius: var(--kk-radius);
  box-shadow: var(--kk-glass-shadow);
  backdrop-filter: var(--kk-glass-blur);
  -webkit-backdrop-filter: var(--kk-glass-blur);
  transition: box-shadow 0.15s var(--kk-ease);
}
.stat-card::before {
  content: "";
  position: absolute;
  right: -24px;
  top: 50%;
  width: 96px;
  height: 96px;
  border-radius: 50%;
  transform: translateY(-50%);
  filter: blur(28px);
  opacity: 0.22;
  pointer-events: none;
}
.stat-card--slate::before { background: #e2e8f0; }
.stat-card--amber::before { background: #fde68a; }
.stat-card--cyan::before { background: #a5f3fc; }
.stat-card--rose::before { background: #fecaca; }
.stat-card--emerald::before { background: #a7f3d0; }
.stat-card--indigo::before { background: #d4d4d8; }
.stat-card--slate .stat-glyph { color: #64748b; }
.stat-card--amber .stat-glyph { color: #d97706; }
.stat-card--cyan .stat-glyph { color: #0891b2; }
.stat-card--rose .stat-glyph { color: #dc2626; }
.stat-card--emerald .stat-glyph { color: #059669; }
.stat-card--indigo .stat-glyph { color: var(--kk-primary); }
.stat-card:hover { box-shadow: 0 8px 28px rgba(0, 0, 0, 0.08); }
.stat-card.is-active {
  box-shadow: 0 0 0 2px var(--kk-primary);
}
.stat-card:focus-visible {
  outline: 2px solid var(--kk-primary);
  outline-offset: 2px;
}
.stat-body { position: relative; z-index: 1; min-width: 0; }
.stat-glyph { position: relative; z-index: 1; flex-shrink: 0; }
.stat-label { font-size: 13px; font-weight: 500; color: var(--kk-text-secondary); }
.stat-value {
  margin-top: 6px;
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
  color: var(--kk-text);
}

.task-table :deep(.el-table__cell) { padding: 13px 0; }
.task-table :deep(.el-table__header .el-table__cell) { padding: 11px 0; }
.task-table :deep(.el-table__row) { height: 66px; }
.task-row-actions { display: flex; align-items: center; justify-content: center; flex-wrap: wrap; gap: 4px 10px; }
.task-row-actions :deep(.el-button + .el-button) { margin-left: 0; }
.task-title-cell { min-width: 0; line-height: 1.4; }
.task-title-line { display: flex; align-items: center; gap: 6px; min-width: 0; }
.task-title-line .el-link { min-width: 0; max-width: 250px; font-weight: 600; }
.task-title-line :deep(.el-link__inner) { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.task-title-line .el-tag { flex-shrink: 0; }
.task-content-preview {
  margin-top: 4px;
  font-size: 12px;
  color: var(--kk-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 440px;
}
.task-owner-cell { display: flex; flex-direction: column; gap: 5px; min-width: 0; }
.task-owner-cell strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; color: var(--kk-text); }
.task-owner-cell span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; color: var(--kk-text-muted); }
.delivery-cell { max-width: 170px; }
.delivery-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 7px; }
.delivery-head > span { font-size: 12px; font-variant-numeric: tabular-nums; color: var(--kk-text-secondary); }
.stat-hint { margin-top: 3px; font-size: 12px; color: var(--kk-text-muted); }

.task-workspace {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 14px;
  align-items: stretch;
}
.stat-grid--personal { grid-template-columns: repeat(5, minmax(0, 1fr)); }
.task-workspace--personal { grid-template-columns: minmax(0, 1fr); }
.task-workspace-main {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}
.task-filter-bar { margin-bottom: 0; }
.period-select { width: 136px; }
.task-view-card { min-width: 0; overflow: hidden; }
.task-view-switcher {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 16px;
  padding: 4px;
  border: 1px solid var(--kk-border, #dcdfe6);
  border-radius: 10px;
  background: var(--kk-bg-muted, #f5f7fa);
}
.task-view-switcher button {
  min-height: 36px;
  padding: 7px 16px;
  border: 0;
  border-radius: 7px;
  font: inherit;
  font-size: 13px;
  font-weight: 500;
  color: var(--kk-text-secondary);
  background: transparent;
  cursor: pointer;
  transition: color .15s var(--kk-ease), background-color .15s var(--kk-ease), box-shadow .15s var(--kk-ease);
}
.task-view-switcher button:hover { color: var(--kk-text); }
.task-view-switcher button.is-active {
  color: var(--kk-primary);
  background: var(--kk-bg, #fff);
  box-shadow: 0 1px 4px rgba(15, 23, 42, .08);
}
.task-view-switcher button:focus-visible {
  outline: 2px solid var(--kk-primary);
  outline-offset: 2px;
}
.task-view-panel { min-width: 0; }
.project-tree-panel {
  min-width: 0;
  padding: 18px 14px;
  overflow: hidden;
}
.project-tree-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  padding: 0 6px 14px;
  border-bottom: 1px solid var(--kk-border, #ebeef5);
}
.project-tree-head h3 { margin: 0; font-size: 16px; color: var(--kk-text); }
.project-tree-head p { margin: 4px 0 0; font-size: 12px; color: var(--kk-text-muted); }
.project-tree-head > span {
  min-width: 24px;
  padding: 3px 7px;
  border-radius: 999px;
  text-align: center;
  font-size: 11px;
  color: var(--kk-text-secondary);
  background: var(--kk-bg-muted, #f5f7fa);
}
.project-tree {
  margin-top: 10px;
  background: transparent;
  color: var(--kk-text-secondary);
}
.project-tree :deep(.el-tree-node__content) {
  height: 40px;
  margin: 2px 0;
  border-radius: 8px;
  padding-right: 7px;
}
.project-tree :deep(.el-tree-node__content:hover) { background: var(--kk-bg-muted, #f5f7fa); }
.project-tree :deep(.el-tree-node.is-current > .el-tree-node__content) {
  color: var(--kk-text);
  background: color-mix(in srgb, var(--kk-primary) 10%, transparent);
}
.project-tree-node {
  display: flex;
  align-items: center;
  gap: 7px;
  width: 100%;
  min-width: 0;
  font-size: 13px;
}
.project-tree-node .el-icon { flex: 0 0 auto; color: var(--kk-text-muted); }
.project-tree-node > span { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.project-tree-node > i {
  flex: 0 0 auto;
  font-size: 10px;
  font-style: normal;
  color: var(--kk-text-muted);
}

.management-grid,
.insight-grid {
  display: grid;
  grid-template-columns: minmax(0, 3fr) minmax(360px, 2fr);
  gap: 14px;
  align-items: stretch;
}
.management-grid--personal { grid-template-columns: minmax(0, 1fr); }
.management-grid > .page-card,
.insight-grid > .page-card {
  min-width: 0;
  height: 100%;
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
.risk-badges { display: flex; gap: 8px; font-size: 12px; color: var(--kk-text-secondary); }
.risk-badges span { padding: 4px 8px; border-radius: 999px; background: var(--kk-bg-muted, #f5f7fa); }
.risk-list, .health-list { display: flex; flex-direction: column; }
.risk-row, .health-row {
  width: 100%;
  border: 0;
  border-top: 1px solid var(--kk-border, #ebeef5);
  background: transparent;
  cursor: pointer;
  color: inherit;
}
.risk-row:first-child, .health-row:first-child { border-top: 0; }
.risk-row {
  display: grid;
  grid-template-columns: 82px minmax(0, 1fr) 100px;
  gap: 10px;
  align-items: center;
  padding: 11px 2px;
  text-align: left;
}
.risk-row:hover, .health-row:hover { background: var(--kk-bg-muted, #f5f7fa); }
.risk-main, .risk-meta, .health-project div { min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.risk-main strong, .health-project strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; }
.risk-main span, .risk-meta span, .health-project span { font-size: 12px; color: var(--kk-text-muted); }
.risk-meta { text-align: right; }
.risk-meta strong { font-size: 12px; color: var(--kk-text-secondary); }
.health-row { padding: 11px 2px; text-align: left; }
.health-project { display: flex; justify-content: space-between; align-items: center; gap: 10px; }
.health-metrics { display: grid; grid-template-columns: repeat(4, 1fr); gap: 6px; margin-top: 9px; }
.health-metrics span { font-size: 11px; color: var(--kk-text-muted); }
.health-metrics b { margin-right: 3px; font-size: 14px; color: var(--kk-text); }
.health-metrics .danger b { color: var(--kk-danger); }
.task-section-head { padding: 2px 2px 12px; margin-bottom: 0; }
.performance-metrics { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.performance-metrics div { padding: 10px 12px; border-radius: 8px; background: var(--kk-bg-muted, #f5f7fa); }
.performance-metrics span { display: block; font-size: 12px; color: var(--kk-text-muted); }
.performance-metrics strong { display: block; margin-top: 5px; font-size: 21px; color: var(--kk-text); }
.performance-metrics strong.danger { color: var(--kk-danger); }
.trend-legend { display: flex; align-items: center; gap: 6px; margin-top: 16px; font-size: 11px; color: var(--kk-text-muted); }
.trend-legend span { width: 8px; height: 8px; border-radius: 2px; }
.created-dot, .trend-bars .created { background: #94a3b8; }
.completed-dot, .trend-bars .completed { background: var(--kk-primary); }
.trend-chart { display: grid; grid-template-columns: repeat(6, 1fr); align-items: end; gap: 10px; height: 112px; margin-top: 6px; }
.trend-column { display: flex; flex-direction: column; align-items: center; gap: 5px; }
.trend-column > span { font-size: 11px; color: var(--kk-text-muted); }
.trend-bars { height: 76px; display: flex; align-items: end; gap: 3px; }
.trend-bars i { display: block; width: 10px; min-height: 3px; border-radius: 3px 3px 0 0; }
.owner-list { display: flex; flex-direction: column; gap: 13px; }
.owner-line { display: flex; justify-content: space-between; gap: 10px; font-size: 12px; }
.owner-line strong { font-size: 13px; }
.owner-line span { color: var(--kk-text-muted); }
.owner-bar { height: 7px; margin-top: 5px; overflow: hidden; border-radius: 999px; background: var(--kk-bg-muted, #ebeef5); }
.owner-bar i { display: block; height: 100%; border-radius: inherit; background: var(--kk-primary); }
.task-date-range { display: flex; flex-direction: column; gap: 5px; font-size: 12px; color: var(--kk-text-secondary); }
.task-date-range span { display: flex; align-items: center; gap: 7px; white-space: nowrap; }
.task-date-range i { width: 28px; flex-shrink: 0; font-style: normal; color: var(--kk-text-muted); }
.overdue { color: var(--kk-danger); font-weight: 500; }

@media (max-width: 1100px) {
  .stat-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .management-grid,
  .insight-grid { grid-template-columns: minmax(0, 1fr); }
}
@media (max-width: 900px) {
  .task-workspace { grid-template-columns: minmax(0, 1fr); }
  .project-tree-panel { max-height: 300px; }
  .project-tree { max-height: 225px; overflow-y: auto; }
}
@media (max-width: 640px) {
  .stat-grid { grid-template-columns: minmax(0, 1fr); }
  .task-view-switcher { display: flex; width: 100%; }
  .task-view-switcher button { flex: 1; min-width: 0; padding-inline: 8px; }
  .section-head { gap: 10px; }
  .risk-badges { flex-direction: column; align-items: flex-end; gap: 4px; }
  .risk-row { grid-template-columns: 72px minmax(0, 1fr); }
  .risk-meta { grid-column: 2; flex-direction: row; justify-content: space-between; text-align: left; }
  .performance-metrics { grid-template-columns: minmax(0, 1fr); }
}
@media (prefers-reduced-transparency: reduce) {
  .stat-card {
    background: #fff;
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}
</style>
