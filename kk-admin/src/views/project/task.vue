<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bizApi } from '@/api/biz'
import { useUserStore } from '@/stores/user'
import TaskDetailDrawer from '@/components/task/TaskDetailDrawer.vue'
import CompanyTaskShareDialog from '@/components/task/CompanyTaskShareDialog.vue'
import ProjectCascadeSelect from '@/components/project/ProjectCascadeSelect.vue'
import { companyTaskShareApi, type CompanyTaskShareOption } from '@/api/companyTaskShare'

const route = useRoute()
const userStore = useUserStore()
const isTaskManager = computed(() => userStore.hasPermission('project:task:confirm'))

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

const list = ref<any[]>([])
const total = ref(0)
const summary = ref<Record<string, number>>({})
const projects = ref<any[]>([])
const taskDrawer = ref(false)
const activeTaskId = ref<number | null>(null)
const listLoading = ref(false)
const shareOpen = ref(false)
const shareCompanies = ref<CompanyTaskShareOption[]>([])

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

const statCards = [
  { key: 'todo', label: '待办', icon: 'Clock', tone: 'slate' },
  { key: 'doing', label: '进行中', icon: 'Loading', tone: 'amber' },
  { key: 'done', label: '已完成', icon: 'CircleCheck', tone: 'cyan' },
  { key: 'overdue', label: '已逾期', icon: 'Warning', tone: 'rose' },
  { key: 'total', label: '全部任务', icon: 'Tickets', tone: 'indigo' },
]

function isStatActive(key: string) {
  if (key === 'overdue') return !!query.overdue
  if (key === 'todo') return query.status === 0 && !query.overdue
  if (key === 'doing') return query.status === 1 && !query.overdue
  if (key === 'done') return query.status === 2 && !query.overdue
  if (key === 'total') return !query.overdue && query.status == null && !query.statuses?.length
  return false
}

function onStatClick(key: string) {
  if (key === 'total') {
    query.status = undefined
    query.statuses = undefined
    query.overdue = undefined
    query.page = 1
    load()
    return
  }
  if (key === 'overdue') {
    filterOverdue()
    return
  }
  filterByStatus(key === 'todo' ? 0 : key === 'doing' ? 1 : 2)
}

async function loadSummary() {
  // 统计随筛选变：项目/标题/优先级；状态与逾期由卡片本身表达，不传入
  const params: {
    projectId?: number
    priority?: number
    title?: string
  } = {}
  if (query.projectId != null) params.projectId = query.projectId
  if (query.priority != null) params.priority = query.priority
  if (query.title.trim()) params.title = query.title.trim()
  summary.value = await bizApi.managementTaskSummary(params)
}

async function load() {
  listLoading.value = true
  try {
    const params: Record<string, unknown> = {
      page: query.page,
      pageSize: query.pageSize,
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

function open(row?: any) {
  activeTaskId.value = row?.id ?? null
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

function progressStatus(row: any) {
  if (row.status === 2) return 'success'
  if (row.overdue) return 'exception'
  if (row.progress >= 80) return 'warning'
  return undefined
}

onMounted(async () => {
  projects.value = await bizApi.taskManagementProjects()
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
        <p class="page-desc">
          {{ isTaskManager ? '查看系统全部任务，并处理普通用户提交的完成申请。' : '查看本人负责或参与项目中的任务；项目内可用「看板」更新状态。' }}
        </p>
      </div>
      <div class="page-actions">
        <el-button v-if="shareCompanies.length" v-permission="'project:task:share'" @click="shareOpen = true">今日工作外链</el-button>
        <el-button v-permission="'project:task:add'" type="primary" @click="open()">新建任务</el-button>
      </div>
    </div>

    <div class="stat-grid">
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
        </div>
        <el-icon class="stat-glyph" :size="44"><component :is="card.icon" /></el-icon>
      </button>
    </div>

    <el-form class="filter-bar" @submit.prevent="onFilter">
      <el-form-item label="标题">
        <el-input
          v-model="query.title"
          clearable
          placeholder="任务标题"
          class="filter-keyword--wide"
          @keyup.enter="onFilter"
        />
      </el-form-item>
      <el-form-item label="项目">
        <ProjectCascadeSelect
          v-model="query.projectId"
          :projects="projects"
          mode="filter"
          exclude-completed
          top-placeholder="全部"
          child-placeholder="全部小项目"
          class="filter-select--wide"
          top-width="200px"
          child-width="180px"
        />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="statusFilterKey" clearable placeholder="全部" class="filter-select--wide">
          <el-option v-for="item in statusFilterOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="优先级">
        <el-select v-model="query.priority" clearable placeholder="全部" class="filter-select">
          <el-option v-for="(label, value) in priorityMap" :key="value" :label="label" :value="Number(value)" />
        </el-select>
      </el-form-item>
      <el-form-item class="filter-actions">
        <el-button type="primary" native-type="submit" :loading="listLoading">查询</el-button>
        <el-button @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="page-card">
      <el-table v-loading="listLoading" :data="list" row-key="id" stripe empty-text="暂无任务">
        <el-table-column label="任务" min-width="220">
          <template #default="{ row }">
            <div class="task-title-cell">
              <el-link type="primary" :underline="false" @click="open(row)">{{ row.title }}</el-link>
              <div v-if="row.content" class="task-content-preview">{{ row.content }}</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="projectName" label="项目" width="140" show-overflow-tooltip />
        <el-table-column label="优先级" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="priorityType[row.priority] || 'info'" size="small">{{ priorityMap[row.priority] || '中' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="参与人员" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.participantNames?.length ? row.participantNames.join('、') : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="进度" width="130">
          <template #default="{ row }">
            <div v-if="progressStatus(row) === 'warning'" class="task-progress">
              <el-progress
                class="task-progress__bar"
                :percentage="row.progress ?? 0"
                status="warning"
                :stroke-width="8"
                :show-text="false"
              />
              <span class="task-progress__status">
                <el-tooltip v-if="!row.dueDate" content="当前任务暂未设置截至时间。" placement="top">
                  <el-icon class="task-progress__warning-icon" aria-label="当前任务暂未设置截至时间。">
                    <WarningFilled />
                  </el-icon>
                </el-tooltip>
                <el-icon v-else class="task-progress__warning-icon"><WarningFilled /></el-icon>
              </span>
            </div>
            <el-progress
              v-else
              :percentage="row.progress ?? 0"
              :status="progressStatus(row)"
              :stroke-width="8"
            />
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="statusType[row.status]" size="small">{{ statusMap[row.status] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="计划周期" width="180">
          <template #default="{ row }">
            <div class="task-date-range">
              <span>{{ row.startDate || '—' }}</span>
              <span class="date-sep">~</span>
              <span :class="{ overdue: row.overdue }">{{ row.dueDate || '—' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" :width="isTaskManager ? 220 : 100" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="open(row)">详情</el-button>
            <template v-if="isTaskManager && row.status === 4">
              <el-button link type="success" @click="review(row, true)">确认完成</el-button>
              <el-button link type="danger" @click="review(row, false)">驳回</el-button>
            </template>
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

    <CompanyTaskShareDialog v-model="shareOpen" :companies="shareCompanies" />
    <TaskDetailDrawer
      v-model="taskDrawer"
      :task-id="activeTaskId"
      :default-project-id="query.projectId"
      @saved="load"
      @deleted="load"
    />
  </div>
</template>

<style scoped>
.stat-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
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
.stat-card--indigo::before { background: #d4d4d8; }
.stat-card--slate .stat-glyph { color: #64748b; }
.stat-card--amber .stat-glyph { color: #d97706; }
.stat-card--cyan .stat-glyph { color: #0891b2; }
.stat-card--rose .stat-glyph { color: #dc2626; }
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

.task-title-cell { line-height: 1.4; }
.task-content-preview {
  margin-top: 4px;
  font-size: 12px;
  color: var(--kk-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 280px;
}
.task-progress { display: flex; align-items: center; }
.task-progress__bar { flex: 1; }
.task-progress__status {
  min-width: 50px;
  margin-left: 5px;
  line-height: 1;
}
.task-progress__warning-icon {
  display: block;
  color: var(--el-color-warning);
  cursor: help;
}
.task-date-range { font-size: 13px; color: var(--kk-text-secondary); }
.date-sep { margin: 0 4px; color: var(--kk-text-muted); }
.overdue { color: var(--kk-danger); font-weight: 500; }

@media (max-width: 1100px) {
  .stat-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (prefers-reduced-transparency: reduce) {
  .stat-card {
    background: #fff;
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}
</style>
