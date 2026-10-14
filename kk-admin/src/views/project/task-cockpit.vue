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
const filters = reactive({ participantId: undefined as number | undefined })
type PeriodKey = 'ALL' | 'TODAY' | 'THIS_WEEK' | 'LAST_WEEK' | 'THIS_MONTH' | 'LAST_MONTH' | 'CUSTOM'
const period = ref<PeriodKey>('TODAY')
const customPeriod = ref<[string, string] | undefined>()
const collapsedDurationMembers = ref<Set<string>>(new Set())
const periodOptions: Array<{ label: string; value: PeriodKey }> = [
  { label: '全部', value: 'ALL' }, { label: '当日', value: 'TODAY' }, { label: '本周', value: 'THIS_WEEK' },
  { label: '上周', value: 'LAST_WEEK' }, { label: '本月', value: 'THIS_MONTH' },
  { label: '上月', value: 'LAST_MONTH' }, { label: '自定义', value: 'CUSTOM' },
]
let filterTimer: ReturnType<typeof setTimeout> | undefined

/** 筛选项下方：左项目 / 右任务（交付状态 tab）；成员用顶部筛选 */
const browserProjectId = ref<number | undefined>()
const browserLoading = ref(false)
const browserRows = ref<any[]>([])
let browserRequestId = 0
type BrowserTabKey = 'all' | 0 | 1 | 2 | 3 | 4 | 'overdue'
const browserTab = ref<BrowserTabKey>('all')
const browserTabs: Array<{ key: BrowserTabKey; label: string }> = [
  { key: 'all', label: '全部任务' },
  { key: 0, label: '待办' },
  { key: 1, label: '进行中' },
  { key: 'overdue', label: '已逾期' },
  { key: 4, label: '待确认完成' },
  { key: 2, label: '已完成' },
  { key: 3, label: '已关闭' },
]
const priorityMap: Record<number, string> = { 1: '高', 2: '中', 3: '低' }

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
  if (period.value === 'ALL') return undefined
  const now = new Date()
  let from: Date
  let to: Date
  if (period.value === 'CUSTOM') {
    return customPeriod.value?.length === 2
      ? { from: customPeriod.value[0], to: addDays(customPeriod.value[1], 1) }
      : undefined
  }
  if (period.value === 'TODAY') {
    from = new Date(now.getFullYear(), now.getMonth(), now.getDate())
    to = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1)
  } else if (period.value === 'THIS_WEEK' || period.value === 'LAST_WEEK') {
    const mondayOffset = (now.getDay() + 6) % 7
    const weekOffset = period.value === 'LAST_WEEK' ? -7 : 0
    from = new Date(now.getFullYear(), now.getMonth(), now.getDate() - mondayOffset + weekOffset)
    to = new Date(from.getFullYear(), from.getMonth(), from.getDate() + 7)
  } else {
    const monthOffset = period.value === 'LAST_MONTH' ? -1 : 0
    from = new Date(now.getFullYear(), now.getMonth() + monthOffset, 1)
    to = new Date(from.getFullYear(), from.getMonth() + 1, 1)
  }
  return { from: formatDate(from), to: formatDate(to) }
})

const statusMap: Record<number, string> = { 0: '待办', 1: '进行中', 2: '已完成', 3: '已关闭', 4: '待确认完成' }

type BrowserProjectItem = { id: number; label: string; scale?: string; taskCount: number }
const browserProjectTaskCounts = computed(() => {
  const map = new Map<number, number>()
  for (const task of browserRows.value) {
    const id = Number(task.projectId)
    if (!id) continue
    map.set(id, (map.get(id) || 0) + 1)
  }
  return map
})
const browserProjectItems = computed<BrowserProjectItem[]>(() => {
  const visible = projects.value
  const childrenByParent = new Map<number, any[]>()
  visible.forEach((project) => {
    if (!project.parentId) return
    const parentId = Number(project.parentId)
    childrenByParent.set(parentId, [...(childrenByParent.get(parentId) || []), project])
  })
  const counts = browserProjectTaskCounts.value
  const items: BrowserProjectItem[] = []
  visible.filter((project) => !project.parentId).forEach((project) => {
    items.push({
      id: Number(project.id),
      label: project.name,
      scale: project.scale,
      taskCount: counts.get(Number(project.id)) || 0,
    })
    for (const child of childrenByParent.get(Number(project.id)) || []) {
      items.push({
        id: Number(child.id),
        label: child.name,
        scale: child.scale,
        taskCount: counts.get(Number(child.id)) || 0,
      })
    }
  })
  return items.filter((item) => item.taskCount > 0)
})
const browserSelectedLabel = computed(() => {
  if (browserProjectId.value == null) return '参与项目'
  return browserProjectItems.value.find((item) => item.id === browserProjectId.value)?.label || '当前项目'
})
const browserTabCounts = computed(() => {
  const rows = browserScopedRows.value
  return {
    all: rows.length,
    0: rows.filter((task) => Number(task.status) === 0).length,
    1: rows.filter((task) => Number(task.status) === 1).length,
    overdue: rows.filter((task) => !!task.overdue).length,
    4: rows.filter((task) => Number(task.status) === 4).length,
    2: rows.filter((task) => Number(task.status) === 2).length,
    3: rows.filter((task) => Number(task.status) === 3).length,
  } as Record<BrowserTabKey, number>
})
const browserScopedRows = computed(() => {
  if (browserProjectId.value == null) return browserRows.value
  return browserRows.value.filter((task) => Number(task.projectId) === Number(browserProjectId.value))
})
const browserActiveTasks = computed(() => {
  const rows = browserScopedRows.value
  if (browserTab.value === 'all') return rows
  if (browserTab.value === 'overdue') return rows.filter((task) => !!task.overdue)
  return rows.filter((task) => Number(task.status) === browserTab.value)
})
const browserActiveTabLabel = computed(() => browserTabs.find((tab) => tab.key === browserTab.value)?.label || '')

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
const memberChartMax = computed(() => Math.max(1, ...memberChartRows.value.flatMap((item: any) => [item.done, item.doing, item.overdue])))
const memberChartMinWidth = computed(() => Math.max(720, memberChartRows.value.length * 112))
const employeeDurationRows = computed(() => (dashboard.value.employeeDurationStats || [])
  .map((item: any) => {
    const tasks = [...(item.tasks || [])].sort((a: any, b: any) => Number(b.durationSeconds || 0) - Number(a.durationSeconds || 0))
    return {
      ...item,
      durationSeconds: Number(item.durationSeconds || 0),
      completedDurationSeconds: Number(item.completedDurationSeconds || 0),
      incompleteDurationSeconds: Number(item.incompleteDurationSeconds || 0),
      completedTasks: Number(item.completedTasks || 0),
      incompleteTasks: Number(item.incompleteTasks || 0),
      timedTasks: Number(item.timedTasks || 0),
      doingTasks: tasks.filter((task: any) => Number(task.status) === 1).length,
      overdueTasks: tasks.filter((task: any) => task.overdue).length,
      tasks,
    }
  })
  .sort((a: any, b: any) => b.timedTasks - a.timedTasks
    || b.completedTasks - a.completedTasks
    || b.durationSeconds - a.durationSeconds
    || String(a.memberName || '').localeCompare(String(b.memberName || ''), 'zh-CN')))
type TimelineRange = { start: number; end: number }

function buildTimelineRange(tasks: any[]): TimelineRange | undefined {
  if (!tasks.length) return undefined
  const starts = tasks.map((task: any) => parseDateTime(task.startedAt)).filter(Number.isFinite)
  const ends = tasks.map((task: any) => parseDateTime(task.endedAt)).filter(Number.isFinite)
  if (!starts.length || !ends.length) return undefined
  const startDate = new Date(Math.min(...starts))
  const endDate = new Date(Math.max(...ends))
  const start = new Date(startDate.getFullYear(), startDate.getMonth(), startDate.getDate()).getTime()
  const end = new Date(endDate.getFullYear(), endDate.getMonth(), endDate.getDate() + 1).getTime()
  return { start, end: Math.max(end, start + 86_400_000) }
}

function buildTimelineTicks(range: TimelineRange) {
  const dayMs = 86_400_000
  const days = Math.max(1, Math.round((range.end - range.start) / dayMs))
  if (days === 1) {
    return [0, 4, 8, 12, 16, 20, 24].map((hour) => ({ key: hour, label: `${String(hour).padStart(2, '0')}:00`, position: hour / 24 * 100 }))
  }
  if (days <= 14) {
    return Array.from({ length: days }, (_, index) => {
      const date = new Date(range.start + index * dayMs)
      const weekLabel = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][date.getDay()]
      return { key: date.getTime(), label: days === 7 ? weekLabel : `${date.getMonth() + 1}/${date.getDate()}`, position: (index + 0.5) / days * 100 }
    })
  }
  if (days <= 62) {
    const step = Math.max(1, Math.ceil(days / 8))
    const ticks = []
    for (let index = 0; index < days; index += step) {
      const date = new Date(range.start + index * dayMs)
      ticks.push({ key: date.getTime(), label: `${date.getMonth() + 1}/${date.getDate()}`, position: index / days * 100 })
    }
    const lastDate = new Date(range.end - dayMs)
    ticks.push({ key: range.end, label: `${lastDate.getMonth() + 1}/${lastDate.getDate()}`, position: 100 })
    return ticks
  }
  const ticks = []
  const cursor = new Date(range.start)
  cursor.setDate(1)
  if (cursor.getTime() < range.start) cursor.setMonth(cursor.getMonth() + 1)
  while (cursor.getTime() < range.end) {
    const value = cursor.getTime()
    ticks.push({
      key: value,
      label: `${cursor.getFullYear()}/${cursor.getMonth() + 1}`,
      position: (value - range.start) / (range.end - range.start) * 100,
    })
    cursor.setMonth(cursor.getMonth() + 1)
  }
  return ticks
}

const memberTimelines = computed(() => {
  const timelines = new Map<string, { range: TimelineRange; ticks: ReturnType<typeof buildTimelineTicks> }>()
  for (const item of employeeDurationRows.value) {
    const range = buildTimelineRange(item.tasks || [])
    if (range) timelines.set(durationMemberKey(item), { range, ticks: buildTimelineTicks(range) })
  }
  return timelines
})

function memberTimeline(item: any) {
  return memberTimelines.value.get(durationMemberKey(item))
}

function memberTimelineTicks(item: any) {
  return memberTimeline(item)?.ticks || []
}

function parseDateTime(value: string) {
  return new Date(value).getTime()
}
function timelineBarStyle(task: any, item: any) {
  const timeline = memberTimeline(item)?.range
  if (!timeline) return { left: '0%', width: '100%' }
  const rangeStart = timeline.start
  const rangeEnd = timeline.end
  const start = Math.max(rangeStart, parseDateTime(task.startedAt))
  const end = Math.min(rangeEnd, parseDateTime(task.endedAt))
  const range = Math.max(1, rangeEnd - rangeStart)
  const left = Math.max(0, Math.min(100, (start - rangeStart) / range * 100))
  const width = Math.max(0.7, Math.min(100 - left, (Math.max(start, end) - start) / range * 100))
  return { left: `${left}%`, width: `${width}%` }
}
function timelineTaskTime(task: any) {
  const start = new Date(task.startedAt)
  const end = new Date(task.endedAt)
  const time = (value: Date) => `${String(value.getHours()).padStart(2, '0')}:${String(value.getMinutes()).padStart(2, '0')}`
  if (period.value === 'TODAY') return `${time(start)}–${time(end)}`
  return `${start.getMonth() + 1}/${start.getDate()} ${time(start)}–${end.getMonth() + 1}/${end.getDate()} ${time(end)}`
}
function durationTaskState(task: any) {
  if (task.overdue) return 'overdue'
  return task.completed ? 'completed' : 'incomplete'
}
function durationTaskStateLabel(task: any) {
  if (task.overdue) return '已逾期'
  return task.completed ? '已完成' : '未完成'
}
function memberInitial(name: string) {
  return (name || '?').trim().slice(0, 1).toUpperCase()
}
function durationMemberKey(item: any) {
  return String(item.memberId ?? item.memberName)
}
function isDurationMemberCollapsed(item: any) {
  return collapsedDurationMembers.value.has(durationMemberKey(item))
}
function toggleDurationMember(item: any) {
  const key = durationMemberKey(item)
  const next = new Set(collapsedDurationMembers.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  collapsedDurationMembers.value = next
}

function formatDuration(seconds: number) {
  const totalMinutes = Math.max(0, Math.round(Number(seconds || 0) / 60))
  const hours = Math.floor(totalMinutes / 60)
  const minutes = totalMinutes % 60
  if (hours && minutes) return `${hours}小时${minutes}分钟`
  if (hours) return `${hours}小时`
  return `${minutes}分钟`
}

const selectedUserName = computed(() => {
  if (filters.participantId == null) return '全部成员'
  const user = users.value.find(item => Number(item.id) === Number(filters.participantId))
  return user?.nickname || user?.username || '当前成员'
})
const browserEmptyText = computed(() => {
  const taskLabel = browserTab.value === 'all' ? '任务' : `${browserActiveTabLabel.value}任务`
  if (filters.participantId != null && browserProjectId.value == null) {
    return `${selectedUserName.value}作为负责人或持有人暂无${taskLabel}`
  }
  return `${selectedUserName.value} · ${browserSelectedLabel.value}暂无${taskLabel}`
})
function buildFilterParams() {
  const params: Record<string, unknown> = {}
  if (filters.participantId != null) params.participantId = filters.participantId
  if (periodBounds.value) {
    params.periodFrom = periodBounds.value.from
    params.periodTo = periodBounds.value.to
  }
  return params
}

async function loadBrowserTasks() {
  const requestId = ++browserRequestId
  browserLoading.value = true
  try {
    const params: Record<string, unknown> = {
      ...buildFilterParams(),
      page: 1,
      pageSize: 200,
      // 驾驶舱列表与统计统一按唯一持有人筛选。
      dashboardCategory: 'TOTAL',
    }
    const firstPage = await bizApi.managementTaskPage(params)
    const rows = [...(firstPage.list || [])]
    const total = Number(firstPage.total || rows.length)
    const pageSize = Number(params.pageSize)
    const pageCount = Math.ceil(total / pageSize)
    if (pageCount > 1) {
      const remainingPages = await Promise.all(
        Array.from({ length: pageCount - 1 }, (_, index) =>
          bizApi.managementTaskPage({ ...params, page: index + 2 }),
        ),
      )
      remainingPages.forEach(result => rows.push(...(result.list || [])))
    }
    // 接口分页总数包含子任务；驾驶舱项目与状态统计只展示顶层任务，必须在拉取全量后统一过滤。
    if (requestId === browserRequestId) {
      browserRows.value = rows.filter((item: any) => !item.parentTaskId)
    }
  } finally {
    if (requestId === browserRequestId) browserLoading.value = false
  }
}

function selectBrowserProject(projectId?: number) {
  browserProjectId.value = projectId
  loadBrowserTasks()
}

function selectBrowserTab(key: BrowserTabKey) {
  browserTab.value = key
}

function browserScaleLabel(scale?: string) {
  if (scale === 'MAJOR') return '重大'
  if (scale === 'KEY') return '重点'
  if (scale === 'NORMAL') return '常规'
  return ''
}

async function load() {
  loading.value = true
  try {
    dashboard.value = await bizApi.managementTaskDashboard(buildFilterParams())
    await loadBrowserTasks()
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
  () => filters.participantId,
  () => scheduleFilterLoad(0),
)

function reset() {
  filters.participantId = undefined
  period.value = 'TODAY'
  customPeriod.value = undefined
  browserProjectId.value = undefined
  browserTab.value = 'all'
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
    if (filters.participantId != null) params.participantId = filters.participantId
    if (listProjectId.value != null) params.projectId = listProjectId.value
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

type MemberTaskCategory = 'DONE' | 'DOING' | 'OVERDUE'
const memberTaskCategoryLabels: Record<MemberTaskCategory, string> = {
  DONE: '已完成',
  DOING: '进行中',
  OVERDUE: '已逾期',
}

function showMemberTasks(item: any, category: MemberTaskCategory) {
  showTasks(category, undefined, undefined, {
    ownerId: Number(item.memberId),
    title: `${item.memberName} · ${memberTaskCategoryLabels[category]}`,
  })
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
    bizApi.taskDashboardProjects(),
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
    <section class="control-panel glass-panel" aria-label="驾驶舱筛选条件">
      <div class="period-switcher">
        <span class="control-label">周期</span>
        <nav class="period-tabs" aria-label="统计周期">
          <button
            v-for="item in periodOptions"
            :key="item.value"
            type="button"
            :class="{ 'is-active': period === item.value }"
            @click="changePeriod(item.value)"
          >
            {{ item.label }}
          </button>
        </nav>
        <el-date-picker v-if="period === 'CUSTOM'" v-model="customPeriod" type="daterange" value-format="YYYY-MM-DD"
          start-placeholder="开始日期" end-placeholder="结束日期" range-separator="至" :clearable="false" @change="changeCustomPeriod" />
      </div>
      <el-form class="cockpit-filter">
        <el-form-item label="人员">
          <el-select v-model="filters.participantId" clearable filterable placeholder="全部人员">
            <el-option v-for="item in users" :key="item.id" :label="item.nickname || item.username" :value="Number(item.id)" />
          </el-select>
        </el-form-item>
        <el-form-item class="filter-actions"><el-button @click="reset">重置</el-button></el-form-item>
      </el-form>
    </section>

    <section class="page-card project-task-browser glass-panel" aria-label="项目任务浏览">
      <div class="section-head">
        <h3>项目任务浏览</h3>
      </div>
      <div class="browser-layout">
        <aside class="browser-projects" aria-label="项目列表">
          <button
            type="button"
            class="browser-project-item"
            :class="{ 'is-active': browserProjectId == null }"
            @click="selectBrowserProject(undefined)"
          >
            <span>参与项目</span>
            <i>{{ browserProjectItems.length }}</i>
          </button>
          <button
            v-for="item in browserProjectItems"
            :key="item.id"
            type="button"
            class="browser-project-item"
            :class="{ 'is-active': Number(browserProjectId) === item.id }"
            :title="item.label"
            @click="selectBrowserProject(item.id)"
          >
            <span>{{ item.label }}</span>
            <em v-if="item.scale" class="browser-scale" :class="`is-${String(item.scale).toLowerCase()}`">{{ browserScaleLabel(item.scale) }}</em>
            <i>{{ item.taskCount }}</i>
          </button>
        </aside>
        <div v-loading="browserLoading" class="browser-tasks" aria-live="polite">
          <div class="browser-task-panel">
            <nav class="delivery-tabs" aria-label="任务交付状态">
              <button
                v-for="tab in browserTabs"
                :key="String(tab.key)"
                type="button"
                :class="{ 'is-active': browserTab === tab.key }"
                @click="selectBrowserTab(tab.key)"
              >
                {{ tab.label }} <b>{{ browserTabCounts[tab.key] }}</b>
              </button>
            </nav>
            <div class="browser-task-list">
              <button
                v-for="task in browserActiveTasks"
                :key="task.id"
                type="button"
                class="browser-task-card"
                :class="{ overdue: task.overdue }"
                :aria-label="`查看任务：${task.title}`"
                @click="openTask(task.id)"
              >
                <span class="browser-task-top">
                  <em class="browser-prio" :class="`is-p${task.priority || 2}`">{{ priorityMap[task.priority] || '中' }}</em>
                  <em v-if="task.overdue" class="browser-overdue">逾期</em>
                  <em v-if="![2, 3].includes(Number(task.status))" class="browser-incomplete">
                    未完成
                  </em>
                </span>
                <strong>{{ task.title }}</strong>
                <small>
                  <span>持有人 {{ task.holderName || '未指定' }}</span>
                  <span v-if="browserProjectId == null">{{ task.projectName || '未关联项目' }}</span>
                  <span v-if="task.dueDate" :class="{ overdue: task.overdue }">{{ task.dueDate }}</span>
                </small>
              </button>
              <p v-if="!browserActiveTasks.length && !browserLoading" class="browser-type-empty">
                {{ browserEmptyText }}
              </p>
            </div>
          </div>
        </div>
      </div>
    </section>

    <section class="page-card duration-stats-panel glass-panel" aria-labelledby="duration-stats-title">
      <div class="section-head">
        <div>
          <h3 id="duration-stats-title">员工任务耗时统计</h3>
          <p class="section-description">仅统计已开始且未关闭的顶层任务；任务执行区间与查询周期相交即纳入，耗时按任务自身完整执行区间计算</p>
        </div>
      </div>
      <div class="duration-legend" aria-label="耗时类型说明">
        <span><i class="completed" />已完成任务耗时</span>
        <span><i class="incomplete" />未完成任务耗时（从实际开始计算至当前时间）</span>
        <span><i class="overdue" />已逾期任务耗时（从实际开始计算至当前时间）</span>
      </div>
      <div v-if="employeeDurationRows.length" class="duration-timelines" aria-label="员工任务时间轴">
        <article v-for="item in employeeDurationRows" :key="item.memberId" class="duration-timeline-card">
          <button
            type="button"
            class="timeline-person"
            :aria-expanded="!isDurationMemberCollapsed(item)"
            :aria-controls="`duration-tasks-${durationMemberKey(item)}`"
            @click="toggleDurationMember(item)"
          >
            <span class="duration-person">
              <i aria-hidden="true">{{ memberInitial(item.memberName) }}</i>
              <span><strong>{{ item.memberName }}</strong><small>总计任务 {{ item.timedTasks }} 条 · 进行中 {{ item.doingTasks }} 条 · 已完成 {{ item.completedTasks }} 条 · 已逾期 {{ item.overdueTasks }} 条</small></span>
            </span>
            <span class="timeline-collapse-hint">
              {{ isDurationMemberCollapsed(item) ? '展开任务' : '收起任务' }}
              <i aria-hidden="true" :class="{ 'is-collapsed': isDurationMemberCollapsed(item) }" />
            </span>
          </button>
          <div v-show="!isDurationMemberCollapsed(item)" :id="`duration-tasks-${durationMemberKey(item)}`" class="timeline-task-content">
            <div class="timeline-axis-row" aria-hidden="true">
              <span class="timeline-label-spacer" />
              <div class="timeline-track timeline-axis">
                <span v-for="tick in memberTimelineTicks(item)" :key="tick.key" :class="{ 'is-start': tick.position === 0, 'is-end': tick.position === 100 }" :style="{ left: `${tick.position}%` }">{{ tick.label }}</span>
              </div>
            </div>
            <button v-for="task in item.tasks" :key="task.id" type="button" class="timeline-task-row" @click="openTask(task.id)">
              <span class="timeline-task-label">
                <strong :title="task.title">{{ task.title }}</strong>
                <small>{{ formatDuration(task.durationSeconds) }} · {{ task.projectName }}</small>
              </span>
              <span class="timeline-track timeline-task-track">
                <i v-for="tick in memberTimelineTicks(item)" :key="tick.key" class="timeline-gridline" :style="{ left: `${tick.position}%` }" />
                <el-tooltip :content="`${task.title}｜${durationTaskStateLabel(task)}｜${timelineTaskTime(task)}｜耗时 ${formatDuration(task.durationSeconds)}`" placement="top">
                  <b class="timeline-task-bar" :class="durationTaskState(task)" :style="timelineBarStyle(task, item)" />
                </el-tooltip>
              </span>
            </button>
          </div>
        </article>
      </div>
      <el-empty v-else description="暂无员工任务耗时数据" :image-size="64" />
    </section>

    <section class="page-card member-stats-panel glass-panel">
      <div class="section-head">
        <h3>成员任务统计</h3>
        <div v-if="filters.participantId == null" class="member-summary" aria-label="成员任务概览">
          <span><b>{{ memberStatsSummary.members }}</b> 位成员</span>
          <span><b>{{ memberStatsSummary.total }}</b> 项任务</span>
          <span><b>{{ memberStatsSummary.doneRate }}%</b> 完成率</span>
          <span :class="{ danger: memberStatsSummary.overdue > 0 }"><b>{{ memberStatsSummary.overdue }}</b> 项延期</span>
        </div>
      </div>
      <div v-if="memberChartRows.length && filters.participantId == null" class="member-chart-shell">
        <div class="member-chart-legend" aria-label="图例">
          <span><i class="done" />已完成</span><span><i class="doing" />进行中</span><span><i class="overdue" />已逾期</span>
        </div>
        <div class="member-chart-scroll">
          <div class="member-chart" :style="{ minWidth: `${memberChartMinWidth}px` }" role="img" aria-label="成员任务数量柱状图">
            <div class="member-grid-lines" aria-hidden="true"><i /><i /><i /><i /><i /></div>
            <div class="member-chart-groups" :style="{ gridTemplateColumns: `repeat(${memberChartRows.length}, minmax(112px, 1fr))` }">
              <div v-for="item in memberChartRows" :key="item.memberId" class="member-chart-group">
                <span class="member-bars">
                  <el-tooltip :content="`已完成 ${item.done} 项`" placement="top">
                    <button type="button" class="done" :style="{ height: `${Math.max(3, item.done / memberChartMax * 100)}%` }"
                      :aria-label="`查看${item.memberName}的已完成任务，共${item.done}项`" @click="showMemberTasks(item, 'DONE')"><b>{{ item.done }}</b></button>
                  </el-tooltip>
                  <el-tooltip :content="`进行中 ${item.doing} 项`" placement="top">
                    <button type="button" class="doing" :style="{ height: `${Math.max(3, item.doing / memberChartMax * 100)}%` }"
                      :aria-label="`查看${item.memberName}的进行中任务，共${item.doing}项`" @click="showMemberTasks(item, 'DOING')"><b>{{ item.doing }}</b></button>
                  </el-tooltip>
                  <el-tooltip :content="`已逾期 ${item.overdue} 项`" placement="top">
                    <button type="button" class="overdue" :style="{ height: `${Math.max(3, item.overdue / memberChartMax * 100)}%` }"
                      :aria-label="`查看${item.memberName}的已逾期任务，共${item.overdue}项`" @click="showMemberTasks(item, 'OVERDUE')"><b>{{ item.overdue }}</b></button>
                  </el-tooltip>
                </span>
                <strong :title="item.memberName">{{ item.memberName }}</strong>
                <small>共 {{ item.total }} 项 · {{ item.completionRate }}%</small>
              </div>
            </div>
          </div>
        </div>
      </div>
      <button v-else-if="memberChartRows.length" type="button" class="member-focus"
        :aria-label="`${memberChartRows[0].memberName}：共${memberChartRows[0].total}项，已完成${memberChartRows[0].done}项，进行中${memberChartRows[0].doing}项，逾期${memberChartRows[0].overdue}项`"
        @click="showMemberTasks(memberChartRows[0], 'DOING')">
        <span class="member-focus-person">
          <i aria-hidden="true">{{ memberInitial(memberChartRows[0].memberName) }}</i>
          <span><strong>{{ memberChartRows[0].memberName }}</strong><small>当前筛选成员</small></span>
        </span>
        <span class="member-focus-progress">
          <span><b>完成进度</b><strong>{{ memberChartRows[0].completionRate }}%</strong></span>
          <i aria-hidden="true"><b :style="{ width: `${memberChartRows[0].completionRate}%` }" /></i>
        </span>
        <span class="member-focus-stats">
          <span><b>{{ memberChartRows[0].total }}</b><small>总任务</small></span>
          <span><b>{{ memberChartRows[0].done }}</b><small>已完成</small></span>
          <span><b>{{ memberChartRows[0].doing }}</b><small>进行中</small></span>
          <span :class="{ danger: memberChartRows[0].overdue > 0 }"><b>{{ memberChartRows[0].overdue }}</b><small>逾期</small></span>
        </span>
      </button>
      <el-empty v-else description="暂无成员任务数据" :image-size="64" />
    </section>

    <el-dialog v-model="listOpen" :title="listTitle" width="min(820px, calc(100vw - 24px))">
      <div v-loading="listLoading" class="drill-list">
        <button v-for="item in listRows" :key="item.id" type="button" @click="openListedTask(item.id)">
          <div><strong>{{ item.title }}</strong><span>{{ item.projectName || '未关联项目' }} · 持有人 {{ item.holderName || '未指定' }}</span></div>
          <aside>{{ item.dueDate || '未设截止日' }} · {{ statusMap[item.status] }}</aside>
        </button>
        <el-pagination v-if="listTotal > 10" v-model:current-page="listPage" :page-size="10" :total="listTotal" layout="prev, pager, next, total" @current-change="loadTaskList" />
        <el-empty v-if="!listRows.length && !listLoading" description="暂无符合条件的数据" :image-size="64" />
      </div>
    </el-dialog>
    <TaskDetailDrawer
      v-model="drawerOpen"
      :task-id="activeTaskId"
      show-edit-action
      management-edit
      @saved="refreshAfterTaskSaved"
      @deleted="refreshAfterTaskSaved"
    />
  </div>
</template>

<style scoped>
.cockpit {
  --cockpit-ink: #18181b;
  --cockpit-danger: #dc2626;
  --cockpit-warning: #d97706;
  --cockpit-done: #059669;
  --cockpit-doing: #2563eb;
  --cockpit-overdue: #ff6b6b;
  gap: 16px;
  width: 100%;
  max-width: none;
  margin: 0;
}

.glass-panel {
  border: 1px solid var(--kk-glass-border, rgba(255, 255, 255, 0.72));
  border-radius: var(--kk-radius, 18px);
  background: var(--kk-glass-bg, rgba(255, 255, 255, 0.46));
  box-shadow: var(--kk-glass-shadow, 0 1px 2px rgba(0, 0, 0, 0.03), 0 10px 28px rgba(0, 0, 0, 0.04));
  backdrop-filter: var(--kk-glass-blur, saturate(180%) blur(22px));
  -webkit-backdrop-filter: var(--kk-glass-blur, saturate(180%) blur(22px));
}

.control-panel {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px 18px;
  min-height: 56px;
  padding: 12px 16px;
}

.period-switcher {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px 12px;
  min-width: 0;
}

.control-label {
  flex: none;
  color: var(--kk-text-muted);
  font-size: 12px;
  font-weight: 600;
}

.period-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 0;
  min-width: 0;
}
.period-tabs button {
  min-height: 32px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 12px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: rgba(24, 24, 27, 0.04);
  color: var(--kk-text-secondary);
  font: inherit;
  font-size: 13px;
  font-weight: 500;
  line-height: 1;
  cursor: pointer;
  transition: color .15s ease, background-color .15s ease, border-color .15s ease;
}
.period-tabs button:hover {
  color: var(--kk-text);
  background: rgba(24, 24, 27, 0.07);
}
.period-tabs button.is-active {
  color: #fff;
  background: var(--cockpit-ink);
  border-color: var(--cockpit-ink);
}
.period-tabs button:focus-visible {
  outline: 2px solid var(--cockpit-ink);
  outline-offset: 2px;
}
.period-switcher :deep(.el-date-editor) { width: min(250px, 100%); }

.cockpit-filter {
  display: grid;
  grid-template-columns: minmax(180px, 240px) auto;
  align-items: center;
  gap: 10px;
  margin: 0;
}
.cockpit-filter :deep(.el-form-item) {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
}
.cockpit-filter :deep(.el-form-item__label) {
  flex: none;
  height: auto;
  margin: 0;
  padding: 0;
  color: var(--kk-text-muted);
  font-size: 12px;
  line-height: 1.2;
}
.cockpit-filter :deep(.el-form-item__content),
.cockpit-filter :deep(.el-select) { width: 100%; min-width: 0; }
.cockpit-filter :deep(.el-select__wrapper) { min-height: 34px; }
.filter-actions :deep(.el-button) {
  min-height: 34px;
  padding-inline: 14px;
  border-radius: 8px;
}

.cockpit .page-card {
  padding: 22px 24px;
}
.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}
.section-head h3 {
  margin: 0;
  color: var(--kk-text);
  font-size: 15px;
  font-weight: 700;
  line-height: 1.3;
}

.browser-layout {
  display: grid;
  grid-template-columns: minmax(188px, 232px) minmax(0, 1fr);
  gap: 14px;
  min-height: 380px;
}
.browser-projects {
  display: flex;
  flex-direction: column;
  gap: 3px;
  max-height: 520px;
  overflow: auto;
  padding: 8px;
  border: 1px solid rgba(255, 255, 255, 0.55);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.28);
  scrollbar-width: thin;
}
.browser-project-item {
  width: 100%;
  min-height: 36px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--kk-text-secondary);
  text-align: left;
  cursor: pointer;
  transition: background-color 140ms ease-out, color 140ms ease-out;
}
.browser-project-item:hover {
  background: rgba(255, 255, 255, 0.5);
  color: var(--kk-text);
}
.browser-project-item.is-active {
  background: rgba(255, 255, 255, 0.72);
  color: var(--kk-text);
  font-weight: 650;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}
.browser-project-item:focus-visible { outline: 2px solid var(--cockpit-ink); outline-offset: 1px; }
.browser-project-item > span {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
}
.browser-project-item > i {
  flex: none;
  min-width: 22px;
  height: 20px;
  display: inline-grid;
  place-items: center;
  padding: 0 6px;
  border-radius: 999px;
  background: rgba(24, 24, 27, 0.06);
  color: var(--kk-text-muted);
  font-size: 10px;
  font-style: normal;
  font-variant-numeric: tabular-nums;
}
.browser-project-item.is-active > i {
  background: rgba(24, 24, 27, 0.1);
  color: var(--kk-text);
}
.browser-scale {
  flex: none;
  padding: 1px 6px;
  border-radius: 999px;
  font-size: 10px;
  font-style: normal;
  line-height: 16px;
  background: rgba(24, 24, 27, 0.06);
  color: var(--kk-text-muted);
}
.browser-scale.is-major { background: rgba(220, 38, 38, 0.1); color: #b91c1c; }
.browser-scale.is-key { background: rgba(217, 119, 6, 0.12); color: #b45309; }

.browser-tasks { min-width: 0; min-height: 320px; }
.browser-task-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
  height: 100%;
  min-height: 360px;
}

.delivery-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 0;
}
.delivery-tabs button {
  min-height: 34px;
  padding: 0 12px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: rgba(24, 24, 27, 0.04);
  color: var(--kk-text-secondary);
  font: inherit;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: color .15s ease, background-color .15s ease, border-color .15s ease;
}
.delivery-tabs button:hover {
  color: var(--kk-text);
  background: rgba(24, 24, 27, 0.07);
}
.delivery-tabs button.is-active {
  color: #fff;
  background: var(--cockpit-ink);
  border-color: var(--cockpit-ink);
}
.delivery-tabs button:focus-visible {
  outline: 2px solid var(--cockpit-ink);
  outline-offset: 2px;
}
.delivery-tabs b {
  margin-left: 4px;
  font-variant-numeric: tabular-nums;
  font-weight: 650;
}

.browser-task-list {
  flex: 1;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  align-content: start;
  max-height: 460px;
  overflow: auto;
  padding: 2px;
  scrollbar-width: thin;
}
.browser-task-card {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 7px;
  padding: 13px 14px;
  border: 1px solid var(--kk-glass-border, rgba(255, 255, 255, 0.72));
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.52);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: transform 140ms ease-out, box-shadow 140ms ease-out, background-color 140ms ease-out;
}
.browser-task-card:hover {
  transform: translateY(-1px);
  background: rgba(255, 255, 255, 0.72);
  box-shadow: 0 8px 22px rgba(0, 0, 0, 0.06);
}
.browser-task-card:focus-visible { outline: 2px solid var(--cockpit-ink); outline-offset: 1px; }
.browser-task-top { display: flex; align-items: center; gap: 6px; }
.browser-prio,
.browser-overdue,
.browser-incomplete {
  padding: 1px 7px;
  border-radius: 999px;
  font-size: 10px;
  font-style: normal;
  font-weight: 600;
  line-height: 16px;
}
.browser-prio.is-p1 { background: #fee2e2; color: #b91c1c; }
.browser-prio.is-p2 { background: #fef3c7; color: #b45309; }
.browser-prio.is-p3 { background: #f4f4f5; color: #71717a; }
.browser-overdue { background: #fee2e2; color: #b91c1c; }
.browser-incomplete { background: #ffedd5; color: #c2410c; }
.browser-task-card > strong {
  overflow: hidden;
  color: var(--kk-text);
  font-size: 13px;
  font-weight: 650;
  line-height: 1.35;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.browser-task-card > small {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  color: var(--kk-text-muted);
  font-size: 11px;
}
.browser-task-card > small .overdue {
  color: var(--cockpit-danger);
  font-weight: 600;
}
.browser-type-empty {
  grid-column: 1 / -1;
  margin: 56px 0;
  color: var(--kk-text-muted);
  font-size: 12px;
  text-align: center;
}

.member-stats-panel > .section-head { margin-bottom: 10px; }
.member-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.member-summary span {
  padding: 6px 10px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.55);
  color: var(--kk-text-muted);
  font-size: 11px;
  white-space: nowrap;
}
.member-summary b {
  margin-right: 3px;
  color: var(--kk-text);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.member-summary .danger b { color: var(--cockpit-danger); }

.member-chart-shell { position: relative; padding-top: 4px; }
.member-chart-legend {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 14px;
  margin: 0 0 8px;
  color: var(--kk-text-muted);
  font-size: 11px;
}
.member-chart-legend span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  white-space: nowrap;
}
.member-chart-legend i {
  width: 9px;
  height: 9px;
  display: inline-block;
  border-radius: 2px;
}
.member-chart-legend i.done { background: var(--cockpit-done); }
.member-chart-legend i.doing { background: var(--cockpit-doing); }
.member-chart-legend i.overdue { background: var(--cockpit-overdue); }
.member-chart-scroll {
  overflow-x: auto;
  padding: 8px 4px 6px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.28);
  scrollbar-width: thin;
}
.member-chart {
  position: relative;
  width: 100%;
  height: 292px;
}
.member-grid-lines {
  position: absolute;
  inset: 18px 8px 60px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  pointer-events: none;
}
.member-grid-lines i {
  display: block;
  border-top: 1px dashed rgba(24, 24, 27, 0.08);
}
.member-chart-groups {
  position: absolute;
  inset: 0;
  display: grid;
}
.member-chart-group {
  min-width: 0;
  display: grid;
  grid-template-rows: 228px auto auto;
  padding: 0 10px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: inherit;
  cursor: pointer;
  text-align: center;
  transition: background-color 140ms ease-out;
}
.member-chart-group:hover { background: rgba(255, 255, 255, 0.45); }
.member-bars {
  height: 210px;
  display: flex;
  align-self: end;
  align-items: flex-end;
  justify-content: center;
  gap: 5px;
}
.member-bars button {
  position: relative;
  width: 18px;
  min-height: 3px;
  display: block;
  padding: 0;
  border: 0;
  border-radius: 4px 4px 1px 1px;
  cursor: pointer;
  transition: filter 140ms ease-out, transform 140ms ease-out;
}
.member-bars button:hover { filter: brightness(.9); transform: translateY(-2px); }
.member-bars button:focus-visible { outline: 2px solid var(--cockpit-ink); outline-offset: 2px; }
.member-bars button.done { background: var(--cockpit-done); }
.member-bars button.doing { background: var(--cockpit-doing); }
.member-bars button.overdue { background: var(--cockpit-overdue); }
.member-bars b {
  position: absolute;
  bottom: calc(100% + 3px);
  left: 50%;
  color: var(--kk-text-secondary);
  font-size: 9px;
  font-style: normal;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  transform: translateX(-50%);
}
.member-chart-group > strong {
  min-width: 0;
  overflow: hidden;
  margin-top: 8px;
  color: var(--kk-text);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.member-chart-group > small {
  margin-top: 2px;
  color: var(--kk-text-muted);
  font-size: 10px;
  font-variant-numeric: tabular-nums;
}

.member-focus {
  width: 100%;
  min-height: 96px;
  display: grid;
  grid-template-columns: minmax(150px, .75fr) minmax(220px, 1.3fr) minmax(280px, 1.2fr);
  align-items: center;
  gap: 24px;
  padding: 16px 18px;
  border: 1px solid rgba(255, 255, 255, 0.55);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.42);
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: background-color 150ms ease-out, box-shadow 150ms ease-out;
}
.member-focus:hover {
  background: rgba(255, 255, 255, 0.62);
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.05);
}
.member-focus:focus-visible { outline: 2px solid var(--cockpit-ink); outline-offset: 2px; }
.member-focus-person {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 11px;
}
.member-focus-person > i {
  width: 38px;
  height: 38px;
  display: grid;
  flex: none;
  place-items: center;
  border-radius: 50%;
  background: rgba(24, 24, 27, 0.07);
  color: var(--cockpit-ink);
  font-size: 13px;
  font-style: normal;
  font-weight: 700;
}
.member-focus-person strong,
.member-focus-person small { display: block; }
.member-focus-person strong {
  overflow: hidden;
  color: var(--kk-text);
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.member-focus-person small {
  margin-top: 4px;
  color: var(--kk-text-muted);
  font-size: 10px;
}
.member-focus-progress > span {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 9px;
}
.member-focus-progress > span b {
  color: var(--kk-text-secondary);
  font-size: 10px;
}
.member-focus-progress > span strong {
  color: var(--kk-text);
  font-size: 18px;
  font-variant-numeric: tabular-nums;
}
.member-focus-progress > i {
  height: 7px;
  display: block;
  overflow: hidden;
  border-radius: 8px;
  background: rgba(24, 24, 27, 0.06);
}
.member-focus-progress > i b {
  height: 100%;
  display: block;
  min-width: 2px;
  border-radius: inherit;
  background: var(--cockpit-ink);
}
.member-focus-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
}
.member-focus-stats > span {
  display: grid;
  justify-items: center;
  gap: 4px;
  border-right: 1px solid rgba(24, 24, 27, 0.08);
}
.member-focus-stats > span:last-child { border-right: 0; }
.member-focus-stats b {
  color: var(--kk-text);
  font-size: 17px;
  font-variant-numeric: tabular-nums;
}
.member-focus-stats small {
  color: var(--kk-text-muted);
  font-size: 9px;
}
.member-focus-stats .danger b,
.member-focus-stats .danger small { color: var(--cockpit-danger); }
.section-description {
  margin: 5px 0 0;
  color: var(--kk-text-muted);
  font-size: 12px;
  line-height: 1.5;
}
.duration-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  margin-top: 14px;
  color: var(--kk-text-muted);
  font-size: 11px;
}
.duration-legend span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.duration-legend i {
  width: 9px;
  height: 9px;
  border-radius: 3px;
}
.duration-legend i.completed { background: var(--cockpit-done); }
.duration-legend i.incomplete { background: var(--cockpit-doing); }
.duration-legend i.overdue { background: var(--cockpit-overdue); }
.duration-timelines {
  display: grid;
  gap: 14px;
  margin-top: 18px;
}
.duration-timeline-card {
  overflow: hidden;
  border: 1px solid rgba(24, 24, 27, 0.07);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.4);
}
.timeline-person {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 58px;
  padding: 10px 14px;
  border: 0;
  border-bottom: 1px solid rgba(24, 24, 27, 0.06);
  background: rgba(255, 255, 255, 0.42);
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: background-color 140ms ease-out;
}
.timeline-person:hover { background: rgba(255, 255, 255, 0.68); }
.timeline-person:focus-visible { outline: 2px solid #2563eb; outline-offset: -2px; }
.timeline-collapse-hint {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 8px;
  color: var(--kk-text-muted);
  font-size: 11px;
}
.timeline-collapse-hint > i {
  width: 8px;
  height: 8px;
  border-right: 1.5px solid currentColor;
  border-bottom: 1.5px solid currentColor;
  transform: rotate(225deg) translate(-1px, -1px);
  transition: transform 160ms ease-out;
}
.timeline-collapse-hint > i.is-collapsed { transform: rotate(45deg) translate(-1px, -1px); }
.timeline-axis-row,
.timeline-task-row {
  display: grid;
  grid-template-columns: minmax(190px, 260px) minmax(480px, 1fr);
}
.timeline-axis-row {
  min-height: 32px;
  color: var(--kk-text-muted);
  font-size: 10px;
}
.timeline-label-spacer,
.timeline-task-label {
  border-right: 1px solid rgba(24, 24, 27, 0.07);
}
.timeline-track { position: relative; min-width: 0; }
.timeline-axis {
  margin: 0 14px;
  border-bottom: 1px solid rgba(24, 24, 27, 0.12);
}
.timeline-axis > span {
  position: absolute;
  bottom: 6px;
  transform: translateX(-50%);
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
.timeline-axis > span.is-start { transform: none; }
.timeline-axis > span.is-end { transform: translateX(-100%); }
.timeline-task-row {
  width: 100%;
  min-height: 54px;
  padding: 0;
  border: 0;
  border-top: 1px solid rgba(24, 24, 27, 0.05);
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.timeline-task-row:hover { background: rgba(24, 24, 27, 0.025); }
.timeline-task-row:focus-visible { outline: 2px solid #2563eb; outline-offset: -2px; }
.timeline-task-label {
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-width: 0;
  padding: 8px 14px;
}
.timeline-task-label strong {
  overflow: hidden;
  color: var(--kk-text);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.timeline-task-label small {
  overflow: hidden;
  margin-top: 4px;
  color: var(--kk-text-muted);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.timeline-task-track { margin: 0 14px; }
.timeline-gridline {
  position: absolute;
  inset-block: 0;
  width: 1px;
  background: rgba(24, 24, 27, 0.055);
}
.timeline-task-bar {
  position: absolute;
  top: 50%;
  height: 12px;
  min-width: 3px;
  transform: translateY(-50%);
  border-radius: 999px;
  box-shadow: 0 1px 3px rgba(24, 24, 27, 0.14);
}
.timeline-task-bar.completed { background: var(--cockpit-done); }
.timeline-task-bar.incomplete { background: var(--cockpit-doing); }
.timeline-task-bar.overdue { background: var(--cockpit-overdue); }
.duration-ranking {
  display: grid;
  gap: 8px;
  margin: 18px 0 0;
  padding: 0;
  list-style: none;
}
.duration-ranking li {
  display: grid;
  grid-template-columns: 30px minmax(140px, 220px) minmax(180px, 1fr) minmax(90px, auto);
  align-items: center;
  gap: 14px;
  min-height: 58px;
  padding: 9px 14px;
  border: 1px solid rgba(24, 24, 27, 0.06);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.38);
}
.duration-rank {
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 9px;
  background: rgba(24, 24, 27, 0.06);
  color: var(--kk-text-secondary);
  font-size: 12px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.duration-rank.is-1 { color: #92400e; background: #fef3c7; }
.duration-rank.is-2 { color: #475569; background: #e2e8f0; }
.duration-rank.is-3 { color: #9a3412; background: #ffedd5; }
.duration-person {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.duration-person > i {
  width: 32px;
  height: 32px;
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: var(--cockpit-ink);
  color: #fff;
  font-size: 12px;
  font-style: normal;
  font-weight: 700;
}
.duration-person > span { min-width: 0; }
.duration-person strong,
.duration-person small { display: block; }
.duration-person strong {
  overflow: hidden;
  color: var(--kk-text);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.duration-person small {
  margin-top: 3px;
  color: var(--kk-text-muted);
  font-size: 10px;
}
.duration-bar {
  height: 8px;
  display: flex;
  overflow: hidden;
  border-radius: 999px;
  background: rgba(24, 24, 27, 0.06);
}
.duration-segment {
  height: 100%;
  display: block;
  flex: none;
  min-width: 2px;
  padding: 0;
  border: 0;
  border-radius: 0;
  cursor: help;
}
.duration-segment:focus-visible {
  position: relative;
  z-index: 1;
  outline: 2px solid #2563eb;
  outline-offset: 2px;
}
.duration-segment.completed { background: var(--cockpit-done); }
.duration-segment.incomplete { background: var(--cockpit-doing); }
.duration-value {
  color: var(--kk-text);
  font-size: 13px;
  text-align: right;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
.drill-list { min-height: 120px; }
.drill-list > button {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 14px 10px;
  border: 0;
  border-bottom: 1px solid var(--kk-card-border);
  border-radius: 8px;
  background: transparent;
  text-align: left;
  cursor: pointer;
}
.drill-list > button:hover { background: rgba(24, 24, 27, 0.028); }
.drill-list div strong,
.drill-list div span { display: block; }
.drill-list div span {
  margin-top: 5px;
  color: var(--kk-text-muted);
  font-size: 12px;
}
.drill-list aside {
  flex: none;
  color: var(--kk-text-secondary);
  font-size: 12px;
}
.drill-list .el-pagination {
  justify-content: flex-end;
  margin-top: 18px;
}

@media (max-width: 1100px) {
  .browser-task-list { grid-template-columns: 1fr; }
}
@media (max-width: 1000px) {
  .member-focus {
    grid-template-columns: 1fr 1.4fr;
    gap: 18px;
  }
  .member-focus-stats { grid-column: 1 / -1; }
}
@media (max-width: 900px) {
  .control-panel {
    grid-template-columns: 1fr;
    padding: 12px 14px;
  }
  .browser-layout { grid-template-columns: 1fr; }
  .browser-projects { max-height: 180px; }
  .section-head {
    flex-wrap: wrap;
    align-items: flex-start;
  }
}
@media (max-width: 640px) {
  .cockpit { gap: 12px; }
  .cockpit .page-card { padding: 16px; }
  .cockpit-filter { grid-template-columns: 1fr auto; }
  .period-tabs {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    width: 100%;
  }
  .period-tabs button { width: 100%; }
  .delivery-tabs {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .delivery-tabs button { width: 100%; }
  .browser-task-list { max-height: 320px; }
  .member-summary {
    display: grid;
    grid-template-columns: 1fr 1fr;
    width: 100%;
  }
  .duration-timeline-card { overflow-x: auto; }
  .timeline-axis-row,
  .timeline-task-row { width: 760px; }
  .duration-ranking li {
    grid-template-columns: 28px minmax(0, 1fr) auto;
    gap: 10px;
  }
  .duration-bar {
    grid-column: 2 / -1;
    grid-row: 2;
  }
  .member-focus {
    grid-template-columns: 1fr;
    padding: 14px;
  }
  .member-focus-stats > span { justify-items: start; }
  .drill-list > button { display: block; }
  .drill-list aside { margin-top: 8px; }
}

@media (prefers-reduced-motion: reduce) {
  .browser-task-card,
  .browser-task-card:hover,
  .member-focus,
  .member-focus:hover {
    transform: none;
    transition: none;
  }
}
</style>
