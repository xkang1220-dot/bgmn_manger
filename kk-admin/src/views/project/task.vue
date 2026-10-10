<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { TableInstance } from 'element-plus'
import { ArrowRight, Expand, Fold, Rank } from '@element-plus/icons-vue'
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

const query = reactive({
  page: 1,
  pageSize: 10,
  title: '',
  projectId: undefined as number | undefined,
  status: 0 as number | undefined,
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
const projects = ref<any[]>([])
const taskDrawer = ref(false)
const activeTaskId = ref<number | null>(null)
type TaskDrawerAction = 'view' | 'edit' | 'transfer' | 'close' | 'subtask'
const taskDrawerAction = ref<TaskDrawerAction>('view')
const listLoading = ref(false)
const shareOpen = ref(false)
const shareCompanies = ref<CompanyTaskShareOption[]>([])
type TaskViewMode = 'timeline' | 'details'
const taskViewMode = ref<TaskViewMode>('details')
const projectNavCollapsed = ref(false)
const taskOrder = ref<Array<number | string>>([])
const draggingTaskId = ref<number | string | null>(null)
const hoveredTaskId = ref<number | string | null>(null)
const hoverTimers = new Map<string, ReturnType<typeof setTimeout>>()

const orderedList = computed(() => {
  const ranks = new Map(taskOrder.value.map((id, index) => [String(id), index]))
  return [...list.value]
    .filter((item) => !item.parentTaskId)
    .sort((a, b) => {
      const aRank = ranks.get(String(a.id))
      const bRank = ranks.get(String(b.id))
      if (aRank == null && bRank == null) return 0
      if (aRank == null) return 1
      if (bRank == null) return -1
      return aRank - bRank
    })
})

const detailTreeList = computed(() => orderedList.value)
const taskTableRef = ref<TableInstance>()
const expandedTaskIds = ref<Set<string>>(new Set())

function taskRowClassName({ row }: { row: any }) {
  return row.parentTaskId ? 'is-subtask' : ''
}

function isSubtaskRow(row: any) {
  return !!row?.parentTaskId
}

function hasSubtasks(row: any) {
  return Array.isArray(row?.children) && row.children.length > 0
}

function isTaskExpanded(row: any) {
  return expandedTaskIds.value.has(String(row.id))
}

function toggleTaskExpand(row: any) {
  if (!hasSubtasks(row)) return
  const key = String(row.id)
  const next = !expandedTaskIds.value.has(key)
  taskTableRef.value?.toggleRowExpansion(row, next)
  const ids = new Set(expandedTaskIds.value)
  if (next) ids.add(key)
  else ids.delete(key)
  expandedTaskIds.value = ids
}

async function expandTasksWithChildren() {
  await nextTick()
  const ids = new Set<string>()
  for (const row of detailTreeList.value) {
    if (!hasSubtasks(row)) continue
    ids.add(String(row.id))
    taskTableRef.value?.toggleRowExpansion(row, true)
  }
  expandedTaskIds.value = ids
}

async function loadTaskOrder() {
  try {
    const value = await bizApi.taskOrder(query.projectId)
    taskOrder.value = Array.isArray(value) ? value : []
  } catch {
    taskOrder.value = []
  }
}

function clearTaskHoverTimer(taskId: number | string) {
  const key = String(taskId)
  const timer = hoverTimers.get(key)
  if (timer) {
    clearTimeout(timer)
    hoverTimers.delete(key)
  }
}

function onTaskMouseEnter(taskId: number | string) {
  clearTaskHoverTimer(taskId)
  const timer = setTimeout(() => {
    hoveredTaskId.value = taskId
  }, 2000)
  hoverTimers.set(String(taskId), timer)
}

function onTaskMouseLeave(taskId: number | string) {
  clearTaskHoverTimer(taskId)
  if (String(hoveredTaskId.value) === String(taskId)) {
    hoveredTaskId.value = null
  }
}

function onTaskDragStart(event: DragEvent, taskId: number | string) {
  draggingTaskId.value = taskId
  hoveredTaskId.value = null
  clearTaskHoverTimer(taskId)
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', String(taskId))
  }
}

function onTaskDragOver(event: DragEvent, row: any) {
  if (isSubtaskRow(row)) return
  event.preventDefault()
}

function onTaskDrop(targetId: number | string) {
  const sourceId = draggingTaskId.value
  draggingTaskId.value = null
  hoveredTaskId.value = null
  if (sourceId == null || String(sourceId) === String(targetId)) return
  const ids = orderedList.value.map((item) => item.id)
  const sourceIndex = ids.findIndex((id) => String(id) === String(sourceId))
  const targetIndex = ids.findIndex((id) => String(id) === String(targetId))
  if (sourceIndex < 0 || targetIndex < 0) return
  const [moved] = ids.splice(sourceIndex, 1)
  ids.splice(targetIndex, 0, moved)
  taskOrder.value = ids
  void bizApi.saveTaskOrder(query.projectId, ids).catch(() => ElMessage.error('任务排序保存失败，请稍后重试'))
}

function fmtTaskTime(value?: string) {
  if (!value) return '未设置'
  return String(value).replace('T', ' ').slice(0, 16)
}

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

type ProjectNavItem = {
  id: number
  label: string
  scale?: string
}

const visibleProjects = computed(() => projects.value.filter((project) => ![2, 3].includes(Number(project.status))))

const projectNavItems = computed<ProjectNavItem[]>(() => {
  const visible = visibleProjects.value
  const childrenByParent = new Map<number, any[]>()
  visible.forEach((project) => {
    if (!project.parentId) return
    const parentId = Number(project.parentId)
    childrenByParent.set(parentId, [...(childrenByParent.get(parentId) || []), project])
  })

  const items: ProjectNavItem[] = []
  visible.filter((project) => !project.parentId).forEach((project) => {
    items.push({ id: Number(project.id), label: project.name, scale: project.scale })
    for (const child of childrenByParent.get(Number(project.id)) || []) {
      items.push({ id: Number(child.id), label: child.name, scale: child.scale })
    }
  })
  return items
})

const projectNavCount = computed(() => projectNavItems.value.length)

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

async function selectProject(projectId?: number) {
  query.projectId = projectId
  query.page = 1
  await loadTaskOrder()
  await load()
}

function onProjectNavClick(projectId?: number) {
  selectProject(projectId)
}

async function load() {
  listLoading.value = true
  try {
    const params: Record<string, unknown> = {
      page: query.page,
      pageSize: query.pageSize,
      taskTree: true,
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
    list.value = res.list || []
    total.value = res.total
    await expandTasksWithChildren()
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
    status: 0,
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

function canCreateSubtask(row: any) {
  // 仅父任务可新建子任务；子任务行不展示该入口
  return !isSubtaskRow(row) && [0, 1].includes(row.status) && userStore.hasPermission('project:task:add')
}

function hasUnfinishedChildren(row: any) {
  return (row.children || []).some((child: any) => child.status !== 2)
}

function canEditTask(row: any) {
  return userStore.hasPermission('project:task:edit') && row.canEdit && row.status !== 3
}

function canTransferTask(row: any) {
  return userStore.hasPermission('project:task:add') && row.canTransfer && row.status !== 3
}

function canCloseTask(row: any) {
  return userStore.hasPermission('project:task:edit') && row.canEdit && row.status !== 3 && !hasUnfinishedChildren(row)
}

function canReviewTask(row: any) {
  return isTaskManager && row.status === 4
}

function hasTaskActions(row: any) {
  return canCreateSubtask(row) || canEditTask(row) || canTransferTask(row) || canCloseTask(row) || canReviewTask(row)
}

function onTaskAction(row: any, command: string) {
  if (command === 'subtask') open(row, 'subtask')
  else if (command === 'edit') open(row, 'edit')
  else if (command === 'transfer') open(row, 'transfer')
  else if (command === 'close') open(row, 'close')
  else if (command === 'review-pass') void review(row, true)
  else if (command === 'review-reject') void review(row, false)
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
  projects.value = await bizApi.myProjects().catch(() => [])
  shareCompanies.value = userStore.hasPermission('project:task:share')
    ? await companyTaskShareApi.options().catch(() => [])
    : []
  const pid = route.query.projectId
  if (pid) {
    const num = Number(pid)
    if (!Number.isNaN(num)) query.projectId = num
  }
  await loadTaskOrder()
  await load()
  const tid = route.query.taskId
  if (tid) {
    const num = Number(tid)
    if (!Number.isNaN(num)) open({ id: num })
  }
})

onUnmounted(() => {
  for (const timer of hoverTimers.values()) clearTimeout(timer)
  hoverTimers.clear()
})
</script>

<template>
  <div class="page-stack task-page">
    <div class="page-card task-workspace" :class="{ 'is-nav-collapsed': projectNavCollapsed }">
      <div class="project-panel-head">
        <h3 v-show="!projectNavCollapsed">项目列表</h3>
        <button
          type="button"
          class="project-collapse-btn"
          :aria-label="projectNavCollapsed ? '展开项目列表' : '折叠项目列表'"
          :title="projectNavCollapsed ? '展开项目列表' : '折叠项目列表'"
          @click="projectNavCollapsed = !projectNavCollapsed"
        >
          <el-icon :size="16">
            <Expand v-if="projectNavCollapsed" />
            <Fold v-else />
          </el-icon>
        </button>
      </div>

      <div class="task-view-toolbar">
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
        <div class="page-actions">
          <el-button v-if="shareCompanies.length" v-permission="'project:task:share'" @click="shareOpen = true">今日工作外链</el-button>
          <el-button type="primary" @click="open()">新建任务</el-button>
        </div>
      </div>

      <aside v-show="!projectNavCollapsed" class="project-tree-panel">
        <nav class="project-nav" aria-label="项目列表">
          <button
            type="button"
            class="project-nav-item"
            :class="{ 'is-active': query.projectId == null }"
            @click="onProjectNavClick(undefined)"
          >
            <span>全部项目</span>
            <i>{{ projectNavCount }}</i>
          </button>
          <button
            v-for="item in projectNavItems"
            :key="item.id"
            type="button"
            class="project-nav-item"
            :class="{ 'is-active': Number(query.projectId) === item.id }"
            :title="item.label"
            @click="onProjectNavClick(item.id)"
          >
            <span>{{ item.label }}</span>
            <em
              v-if="item.scale"
              class="project-scale-tag"
              :class="`is-${String(item.scale).toLowerCase()}`"
            >{{ item.scale === 'MAJOR' ? '重大' : item.scale === 'KEY' ? '重点' : '常规' }}</em>
          </button>
        </nav>
      </aside>

      <div class="task-workspace-main">
      <div
        v-show="taskViewMode === 'timeline'"
        id="task-view-panel-timeline"
        role="tabpanel"
        aria-labelledby="task-view-tab-timeline"
        class="task-view-panel task-view-panel--timeline"
      >
        <TaskTimeline :tasks="orderedList" :loading="listLoading" @open="open" />
      </div>

      <div
        v-show="taskViewMode === 'details'"
        id="task-view-panel-details"
        role="tabpanel"
        aria-labelledby="task-view-tab-details"
        class="task-view-panel task-view-panel--details"
      >
        <div class="details-toolbar">
          <nav class="delivery-tabs" aria-label="任务交付状态">
            <button type="button" :class="{ 'is-active': query.status === 0 && !query.overdue }" @click="filterByStatus(0)">我的待办</button>
            <button type="button" :class="{ 'is-active': query.status === 1 && !query.overdue }" @click="filterByStatus(1)">进行中</button>
            <button type="button" :class="{ 'is-active': query.overdue }" @click="filterOverdue">已逾期</button>
            <button type="button" :class="{ 'is-active': query.status === 4 && !query.overdue }" @click="filterByStatus(4)">待确认完成</button>
            <button type="button" :class="{ 'is-active': query.status === 2 && !query.overdue }" @click="filterByStatus(2)">已完成</button>
          </nav>
          <el-form class="filter-bar task-filter-bar" @submit.prevent="onFilter">
            <el-form-item label="标题">
              <el-input v-model="query.title" clearable placeholder="任务标题" class="filter-keyword--wide" @keyup.enter="onFilter" @change="onFilter" />
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
        </div>
        <div class="task-table-scroll">
        <el-table
          ref="taskTableRef"
          v-loading="listLoading"
          :data="detailTreeList"
          row-key="id"
          height="100%"
          stripe
          :tree-props="{ children: 'children' }"
          :row-class-name="taskRowClassName"
          empty-text="暂无任务"
          class="task-table"
        >
        <el-table-column
          label=""
          width="48"
          align="center"
          class-name="task-expand-col"
          label-class-name="task-expand-col"
        >
          <template #default="{ row }">
            <button
              v-if="hasSubtasks(row)"
              type="button"
              class="task-expand-btn"
              :class="{ 'is-expanded': isTaskExpanded(row) }"
              :aria-expanded="isTaskExpanded(row)"
              :aria-label="isTaskExpanded(row) ? '收起子任务' : '展开子任务'"
              :title="isTaskExpanded(row) ? '收起子任务' : '展开子任务'"
              @click.stop="toggleTaskExpand(row)"
            >
              <el-icon :size="14"><ArrowRight /></el-icon>
            </button>
          </template>
        </el-table-column>
        <el-table-column label="任务" min-width="360">
          <template #default="{ row }">
            <div
              class="task-title-cell"
              :class="{
                'is-subtask': isSubtaskRow(row),
                'is-dragging': String(draggingTaskId) === String(row.id),
                'show-drag-hint': !isSubtaskRow(row) && String(hoveredTaskId) === String(row.id),
              }"
              :draggable="!isSubtaskRow(row)"
              @mouseenter="!isSubtaskRow(row) && onTaskMouseEnter(row.id)"
              @mouseleave="!isSubtaskRow(row) && onTaskMouseLeave(row.id)"
              @dragstart="!isSubtaskRow(row) && onTaskDragStart($event, row.id)"
              @dragend="draggingTaskId = null"
              @dragover="onTaskDragOver($event, row)"
              @drop.prevent="!isSubtaskRow(row) && onTaskDrop(row.id)"
            >
              <el-icon
                v-show="!isSubtaskRow(row) && String(hoveredTaskId) === String(row.id)"
                class="task-drag-hint"
                :size="16"
                title="按住拖动可调整顺序"
              >
                <Rank />
              </el-icon>
              <span v-if="isSubtaskRow(row)" class="task-level-branch" aria-hidden="true" />
              <div class="task-title-body">
                <div class="task-title-line">
                  <span v-if="isSubtaskRow(row)" class="task-level-badge is-child" title="子任务">子</span>
                  <span class="task-title-wrap" :class="{ 'has-count': !isSubtaskRow(row) && row.children?.length }">
                    <el-link type="primary" :underline="false" @click="open(row)">{{ row.title }}</el-link>
                    <i
                      v-if="!isSubtaskRow(row) && row.children?.length"
                      class="task-child-count"
                      :title="`${row.children.length} 个子任务`"
                    >{{ row.children.length }}</i>
                  </span>
                  <el-tag v-if="row.overdue" type="danger" size="small" effect="light">逾期</el-tag>
                  <el-tag v-else-if="!row.dueDate && [0, 1].includes(row.status)" type="info" size="small" effect="plain">无日期</el-tag>
                  <el-tag :type="priorityType[row.priority] || 'info'" size="small" effect="plain">
                    {{ priorityMap[row.priority] || '中' }}优先级
                  </el-tag>
                </div>
                <div v-if="taskContentPreview(row.content)" class="task-content-preview">{{ taskContentPreview(row.content) }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="projectName" label="所属项目" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="task-project-name">{{ row.projectName || '—' }}</span>
          </template>
        </el-table-column>
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
        <el-table-column label="实际时间" min-width="190">
          <template #default="{ row }">
            <div class="task-date-range">
              <span><i>开始</i>{{ fmtTaskTime(row.startedAt) }}</span>
              <span><i>结束</i>{{ fmtTaskTime(row.completedAt) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="128" fixed="right" align="center">
          <template #default="{ row }">
            <div class="task-row-actions">
              <button type="button" class="task-action-btn is-detail" @click="open(row)">详情</button>
              <el-dropdown
                v-if="hasTaskActions(row)"
                trigger="hover"
                popper-class="task-action-dropdown"
                @command="onTaskAction(row, String($event))"
              >
                <button type="button" class="task-action-btn is-more">更多</button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item v-if="canCreateSubtask(row)" command="subtask">子任务</el-dropdown-item>
                    <el-dropdown-item v-if="canEditTask(row)" command="edit">编辑</el-dropdown-item>
                    <el-dropdown-item v-if="canTransferTask(row)" command="transfer">移交</el-dropdown-item>
                    <el-dropdown-item v-if="canCloseTask(row)" command="close" divided>关闭</el-dropdown-item>
                    <el-dropdown-item v-if="canReviewTask(row)" command="review-pass" divided>确认完成</el-dropdown-item>
                    <el-dropdown-item v-if="canReviewTask(row)" command="review-reject">驳回</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </el-table-column>
        </el-table>
        </div>
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
.task-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 108px);
  max-height: calc(100vh - 108px);
  min-height: 0;
  overflow: hidden;
}
.task-page > .task-workspace {
  flex: 1 1 auto !important;
  min-height: 0;
  max-height: 100%;
}
.task-table :deep(.el-table__cell) { padding: 13px 0; }
.task-table :deep(.el-table__header .el-table__cell) { padding: 11px 0; }
.task-table :deep(.el-table__row) { height: 66px; }
.task-table :deep(.el-table__row:not(.is-subtask) .task-title-line .el-link) {
  font-size: 14px;
  font-weight: 650;
  color: var(--kk-text);
}
.task-table :deep(.el-table__row.is-subtask > td.el-table__cell) {
  padding-top: 9px;
  padding-bottom: 9px;
  background-color: rgba(37, 99, 235, 0.04) !important;
}
.task-table :deep(.el-table__row.is-subtask:hover > td.el-table__cell) {
  background-color: rgba(37, 99, 235, 0.07) !important;
}
.task-table :deep(.el-table__row.is-subtask .el-table__cell.task-expand-col) {
  box-shadow: inset 3px 0 0 rgba(37, 99, 235, 0.55);
}
.task-table :deep(.el-table__row.is-subtask .task-title-line .el-link) {
  max-width: 220px;
  font-size: 13px;
  font-weight: 500;
  color: var(--kk-text-secondary);
}
.task-table :deep(.el-table__row.is-subtask .task-content-preview),
.task-table :deep(.el-table__row.is-subtask .task-owner-cell strong),
.task-table :deep(.el-table__row.is-subtask .task-date-range) {
  color: var(--kk-text-muted);
}
/* 隐藏表格自带树形展开，改用独立列按钮 */
.task-table :deep(.el-table__expand-icon),
.task-table :deep(.el-table__indent),
.task-table :deep(.el-table__placeholder) {
  display: none !important;
}
.task-table :deep(.el-table__cell.task-expand-col) {
  padding-left: 0 !important;
  padding-right: 0 !important;
}
.task-table :deep(.el-table__cell.task-expand-col .cell) {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
}
.task-expand-btn {
  display: inline-grid;
  place-items: center;
  width: 26px;
  height: 26px;
  padding: 0;
  border: 1px solid rgba(24, 24, 27, 0.1);
  border-radius: 7px;
  color: var(--kk-text-secondary);
  background: rgba(255, 255, 255, 0.72);
  cursor: pointer;
  transition: color .15s var(--kk-ease), background-color .15s var(--kk-ease), border-color .15s var(--kk-ease), transform .15s var(--kk-ease);
}
.task-expand-btn:hover {
  color: #1d4ed8;
  border-color: rgba(37, 99, 235, 0.28);
  background: rgba(37, 99, 235, 0.08);
}
.task-expand-btn:focus-visible {
  outline: 2px solid var(--kk-primary);
  outline-offset: 2px;
}
.task-expand-btn .el-icon {
  transition: transform .15s var(--kk-ease);
}
.task-expand-btn.is-expanded .el-icon {
  transform: rotate(90deg);
}
.task-title-cell.is-subtask { cursor: default; gap: 8px; }
.task-level-branch {
  position: relative;
  flex: 0 0 auto;
  width: 14px;
  height: 22px;
  margin-top: 1px;
}
.task-level-branch::before {
  content: '';
  position: absolute;
  left: 5px;
  top: 0;
  width: 1.5px;
  height: 12px;
  background: rgba(37, 99, 235, 0.35);
}
.task-level-branch::after {
  content: '';
  position: absolute;
  left: 5px;
  top: 11px;
  width: 9px;
  height: 1.5px;
  background: rgba(37, 99, 235, 0.35);
  border-radius: 1px;
}
.task-table :deep(td.el-table__cell:has(.task-title-wrap) > .cell) {
  overflow: visible;
}
.task-title-wrap {
  position: relative;
  display: inline-flex;
  align-items: center;
  min-width: 0;
  max-width: 250px;
}
.task-title-wrap.has-count {
  padding-top: 6px;
  padding-right: 16px;
}
.task-title-wrap :deep(.el-link) {
  max-width: 100%;
}
.task-child-count {
  position: absolute;
  top: 0;
  right: 0;
  z-index: 2;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 999px;
  font-size: 10px;
  font-style: normal;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  line-height: 1;
  color: #b91c1c;
  background: rgba(239, 68, 68, 0.14);
  box-shadow: 0 0 0 1.5px rgba(255, 255, 255, 0.92);
  pointer-events: none;
}
.task-level-badge {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  border-radius: 50%;
  font-size: 11px;
  font-weight: 650;
  line-height: 1;
}
.task-level-badge.is-child {
  color: var(--kk-text-secondary);
  background: rgba(24, 24, 27, 0.06);
  border: 1px solid rgba(24, 24, 27, 0.1);
}
.task-row-actions { display: flex; align-items: center; justify-content: center; flex-wrap: wrap; gap: 4px 12px; }
.task-row-actions :deep(.el-dropdown) { line-height: 1; vertical-align: middle; }
.task-row-actions :deep(.el-tooltip__trigger) {
  outline: none !important;
  box-shadow: none !important;
}
.task-action-btn {
  display: inline-flex;
  align-items: center;
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font: inherit;
  font-size: 13px;
  font-weight: 500;
  line-height: 1.2;
  letter-spacing: 0.02em;
  cursor: pointer;
  transition: color .15s var(--kk-ease);
}
.task-action-btn:focus,
.task-action-btn:focus-visible {
  outline: none;
  box-shadow: none;
}
.task-action-btn.is-detail {
  color: #2563eb;
}
.task-action-btn.is-detail:hover {
  color: #1d4ed8;
}
.task-action-btn.is-more {
  color: var(--kk-text-secondary);
}
.task-action-btn.is-more:hover {
  color: var(--kk-text);
}
.details-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px 16px;
  flex: 0 0 auto;
  flex-wrap: wrap;
  margin: 0 0 14px;
  padding: 14px 0 0;
}
.delivery-tabs { display: flex; flex-wrap: wrap; gap: 6px; margin: 0; min-width: 0; }
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
  transition: color .15s var(--kk-ease), background-color .15s var(--kk-ease), border-color .15s var(--kk-ease);
}
.delivery-tabs button:hover { color: var(--kk-text); background: rgba(24, 24, 27, 0.07); }
.delivery-tabs button.is-active {
  color: #fff;
  background: #18181b;
  border-color: #18181b;
}
.delivery-tabs button:focus-visible { outline: 2px solid var(--kk-primary); outline-offset: 2px; }
.task-title-cell[draggable="true"] { cursor: grab; }
.task-title-cell[draggable="true"]:active { cursor: grabbing; }
.task-title-cell.is-dragging { opacity: .48; }
.task-title-cell {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  min-width: 0;
  line-height: 1.4;
}
.task-drag-hint {
  flex: 0 0 auto;
  margin-top: 2px;
  color: var(--kk-text-muted);
  opacity: 0.72;
  animation: taskDragHintPulse 1.4s ease-in-out infinite;
}
.task-title-body { min-width: 0; flex: 1; }
.task-title-line { display: flex; align-items: center; gap: 6px; min-width: 0; }
@keyframes taskDragHintPulse {
  0%, 100% { opacity: 0.35; }
  50% { opacity: 0.85; }
}
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
.task-project-name {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  font-weight: 500;
  color: var(--kk-text-secondary);
}
.task-owner-cell { display: flex; flex-direction: column; gap: 5px; min-width: 0; }
.task-owner-cell strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; color: var(--kk-text); }
.task-owner-cell span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; color: var(--kk-text-muted); }
.delivery-cell { max-width: 170px; }
.delivery-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 7px; }
.delivery-head > span { font-size: 12px; font-variant-numeric: tabular-nums; color: var(--kk-text-secondary); }
.task-workspace {
  --task-rail: 248px;
  --task-rail-collapsed: 44px;
  --task-pad-x: 18px;
  --task-line: rgba(24, 24, 27, 0.07);
  --task-radius: var(--kk-radius);
  display: grid;
  grid-template-columns: var(--task-rail) minmax(0, 1fr);
  grid-template-rows: auto minmax(0, 1fr);
  gap: 0;
  align-items: stretch;
  height: 100%;
  max-height: 100%;
  padding: 0;
  border-radius: var(--task-radius);
  overflow: hidden;
  transition: grid-template-columns .2s var(--kk-ease);
}
/* 铺满内边距后，全局 page-card 悬停光晕/内阴影会露出直角，这里关掉 */
.task-workspace.page-card:hover {
  border-color: var(--kk-glass-border);
  box-shadow: var(--kk-glass-shadow);
}
.task-workspace.page-card:hover::after {
  opacity: 0;
}
.task-workspace.is-nav-collapsed {
  grid-template-columns: var(--task-rail-collapsed) minmax(0, 1fr);
}
.task-workspace.is-nav-collapsed .project-tree-panel {
  display: none;
}
.task-workspace.is-nav-collapsed .project-panel-head {
  grid-row: 1 / -1;
  justify-content: center;
  align-items: flex-start;
  padding: 14px 0 0;
  border-bottom: 0;
  border-radius: var(--task-radius) 0 0 var(--task-radius);
}
.task-workspace-main {
  display: flex;
  flex-direction: column;
  grid-column: 2;
  grid-row: 2;
  min-width: 0;
  min-height: 0;
  height: 100%;
  overflow: hidden;
  padding: 0 22px 16px;
  border-bottom-right-radius: var(--task-radius);
}
.filter-bar.task-filter-bar {
  flex: 0 1 auto;
  align-items: center;
  gap: 10px;
  margin: 0;
  padding: 0;
  background: transparent;
  border: 0;
  border-radius: 0;
  justify-content: flex-end;
}
.filter-bar.task-filter-bar .el-form-item {
  flex-direction: row;
  align-items: center;
  gap: 6px;
}
.filter-bar.task-filter-bar .el-form-item__label {
  padding: 0;
  line-height: 32px;
  font-size: 12px;
  font-weight: 500;
  color: var(--kk-text-muted);
}
.filter-bar.task-filter-bar .filter-actions .el-form-item__label {
  display: none;
}
.period-select { width: 120px; }
.task-view-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  grid-column: 2;
  grid-row: 1;
  min-height: 56px;
  margin: 0;
  padding: 12px 22px;
  border-bottom: 1px solid var(--task-line);
  border-top-right-radius: var(--task-radius);
}
.task-view-switcher {
  display: inline-flex;
  align-items: center;
  flex: 0 0 auto;
  gap: 2px;
  padding: 3px;
  border: 1px solid rgba(24, 24, 27, 0.08);
  border-radius: 10px;
  background: rgba(24, 24, 27, 0.04);
}
.task-view-switcher button {
  min-height: 34px;
  padding: 6px 14px;
  border: 0;
  border-radius: 8px;
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
  color: var(--kk-text);
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 1px 3px rgba(24, 24, 27, .08);
}
.task-view-switcher button:focus-visible {
  outline: 2px solid var(--kk-primary);
  outline-offset: 2px;
}
.task-view-panel {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
  height: 100%;
  overflow: hidden;
}
.task-view-panel--timeline {
  padding-top: 14px;
  overflow: auto;
  overscroll-behavior: contain;
}
.task-view-panel--details {
  overflow: hidden;
}
.task-table-scroll {
  position: relative;
  flex: 1 1 auto;
  min-height: 180px;
  height: 100%;
  overflow: hidden;
}
.task-table-scroll :deep(.el-table) {
  height: 100%;
}
.task-table-scroll :deep(.el-table__inner-wrapper) {
  height: 100%;
}
.task-view-panel--details .page-footer {
  flex: 0 0 auto;
  margin-top: 10px;
  padding-bottom: 0;
}
.project-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  grid-column: 1;
  grid-row: 1;
  min-height: 56px;
  padding: 12px var(--task-pad-x);
  background: rgba(24, 24, 27, 0.018);
  border-right: 1px solid var(--task-line);
  border-bottom: 1px solid var(--task-line);
  border-top-left-radius: var(--task-radius);
}
.project-panel-head h3 {
  margin: 0;
  min-width: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--kk-text-secondary);
  letter-spacing: 0.02em;
}
.project-collapse-btn {
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 1px solid transparent;
  border-radius: 8px;
  color: var(--kk-text-muted);
  background: transparent;
  cursor: pointer;
  transition: color .15s var(--kk-ease), background-color .15s var(--kk-ease), border-color .15s var(--kk-ease);
}
.project-collapse-btn:hover {
  color: var(--kk-text);
  background: rgba(255, 255, 255, 0.55);
  border-color: rgba(24, 24, 27, 0.06);
}
.project-collapse-btn:focus-visible {
  outline: 2px solid var(--kk-primary);
  outline-offset: 2px;
}
.project-tree-panel {
  display: flex;
  flex-direction: column;
  grid-column: 1;
  grid-row: 2;
  min-width: 0;
  min-height: 0;
  padding: 10px 10px 16px;
  overflow: hidden;
  background: rgba(24, 24, 27, 0.018);
  border-right: 1px solid var(--task-line);
  border-bottom-left-radius: var(--task-radius);
}
.project-nav {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  gap: 3px;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.project-nav-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-height: 38px;
  padding: 0 10px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  color: var(--kk-text-secondary);
  font: inherit;
  font-size: 13px;
  font-weight: 500;
  text-align: left;
  cursor: pointer;
  transition: color .15s var(--kk-ease), background-color .15s var(--kk-ease), border-color .15s var(--kk-ease);
}
.project-nav-item:hover {
  color: var(--kk-text);
  background: rgba(255, 255, 255, 0.45);
}
.project-nav-item.is-active {
  color: var(--kk-text);
  background: rgba(255, 255, 255, 0.72);
  border-color: rgba(255, 255, 255, 0.85);
  box-shadow: 0 1px 2px rgba(24, 24, 27, 0.04);
}
.project-nav-item:focus-visible {
  outline: 2px solid var(--kk-primary);
  outline-offset: 2px;
}
.project-nav-item > span { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.project-nav-item > i {
  flex: 0 0 auto;
  min-width: 22px;
  padding: 1px 6px;
  border-radius: 999px;
  text-align: center;
  font-size: 11px;
  font-style: normal;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
  color: var(--kk-text-muted);
  background: rgba(24, 24, 27, 0.06);
}
.project-nav-item.is-active > i {
  color: var(--kk-text-secondary);
  background: rgba(24, 24, 27, 0.08);
}
.project-scale-tag {
  flex: 0 0 auto;
  padding: 1px 7px;
  border-radius: 999px;
  border: 1px solid transparent;
  font-size: 11px;
  font-style: normal;
  font-weight: 600;
  line-height: 1.4;
  letter-spacing: 0.02em;
}
.project-scale-tag.is-major {
  color: #b91c1c;
  background: rgba(239, 68, 68, 0.1);
  border-color: rgba(239, 68, 68, 0.18);
}
.project-scale-tag.is-key {
  color: #b45309;
  background: rgba(245, 158, 11, 0.12);
  border-color: rgba(245, 158, 11, 0.22);
}
.project-scale-tag.is-normal {
  color: #475569;
  background: rgba(100, 116, 139, 0.1);
  border-color: rgba(100, 116, 139, 0.16);
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
.task-date-range { display: flex; flex-direction: column; gap: 5px; font-size: 12px; color: var(--kk-text-secondary); }
.task-date-range span { display: flex; align-items: center; gap: 7px; white-space: nowrap; }
.task-date-range i { width: 28px; flex-shrink: 0; font-style: normal; color: var(--kk-text-muted); }
.overdue { color: var(--kk-danger); font-weight: 500; }

@media (max-width: 900px) {
  .task-page { height: calc(100vh - 100px); }
  .task-workspace {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: auto auto minmax(120px, 0.35fr) minmax(0, 1fr);
  }
  .task-workspace.is-nav-collapsed {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: auto auto minmax(0, 1fr);
  }
  .project-panel-head,
  .task-view-toolbar,
  .project-tree-panel,
  .task-workspace-main { grid-column: 1; }
  .project-panel-head {
    grid-row: 1;
    border-right: 0;
    border-radius: var(--task-radius) var(--task-radius) 0 0;
  }
  .task-view-toolbar {
    grid-row: 2;
    border-radius: 0;
  }
  .project-tree-panel {
    grid-row: 3;
    border-right: 0;
    border-bottom: 1px solid var(--task-line);
    border-radius: 0;
  }
  .task-workspace-main {
    grid-row: 4;
    min-height: 0;
    border-radius: 0 0 var(--task-radius) var(--task-radius);
  }
  .task-workspace.is-nav-collapsed .project-panel-head {
    grid-row: 1;
    justify-content: flex-end;
    align-items: center;
    padding: 10px 14px;
    border-bottom: 1px solid var(--task-line);
    border-radius: var(--task-radius) var(--task-radius) 0 0;
  }
  .task-workspace.is-nav-collapsed .task-view-toolbar { grid-row: 2; }
  .task-workspace.is-nav-collapsed .task-workspace-main { grid-row: 3; }
}
@media (max-width: 640px) {
  .task-page { height: calc(100vh - 88px); }
  .task-view-toolbar { flex-wrap: wrap; }
  .section-head { gap: 10px; }
}
</style>
