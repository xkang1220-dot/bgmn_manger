<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bizApi } from '@/api/biz'
import { sysApi } from '@/api/system'
import TaskDetailDrawer from '@/components/task/TaskDetailDrawer.vue'

const route = useRoute()

const query = reactive({
  page: 1,
  pageSize: 10,
  title: '',
  projectId: undefined as number | undefined,
  status: undefined as number | undefined,
  priority: undefined as number | undefined,
  assigneeId: undefined as number | undefined,
  overdue: undefined as boolean | undefined,
})

const list = ref<any[]>([])
const total = ref(0)
const summary = ref<Record<string, number>>({})
const projects = ref<any[]>([])
const users = ref<any[]>([])
const taskDrawer = ref(false)
const activeTaskId = ref<number | null>(null)
const listLoading = ref(false)

const statusMap: Record<number, string> = { 0: '待办', 1: '进行中', 2: '已完成', 3: '已取消' }
const priorityMap: Record<number, string> = { 1: '高', 2: '中', 3: '低' }
const statusType: Record<number, '' | 'success' | 'warning' | 'info' | 'danger'> = {
  0: 'info',
  1: 'warning',
  2: 'success',
  3: 'info',
}
const priorityType: Record<number, '' | 'success' | 'warning' | 'info' | 'danger'> = {
  1: 'danger',
  2: 'warning',
  3: 'info',
}

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
  return false
}

function onStatClick(key: string) {
  if (key === 'total') {
    resetQuery()
    return
  }
  if (key === 'overdue') {
    filterOverdue()
    return
  }
  filterByStatus(key === 'todo' ? 0 : key === 'doing' ? 1 : 2)
}

async function loadSummary() {
  summary.value = await bizApi.taskSummary(query.projectId)
}

async function load() {
  listLoading.value = true
  try {
    const res = await bizApi.taskPage(query)
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
    priority: undefined,
    assigneeId: undefined,
    overdue: undefined,
  })
  load()
}

function filterByStatus(status?: number) {
  query.status = status
  query.overdue = undefined
  query.page = 1
  load()
}

function filterOverdue() {
  query.status = undefined
  query.overdue = true
  query.page = 1
  load()
}

function open(row?: any) {
  activeTaskId.value = row?.id ?? null
  taskDrawer.value = true
}

async function remove(id: number) {
  await ElMessageBox.confirm('确认删除该任务？')
  await bizApi.deleteTask(id)
  ElMessage.success('已删除')
  await load()
}

function progressStatus(row: any) {
  if (row.status === 2) return 'success'
  if (row.overdue) return 'exception'
  if (row.progress >= 80) return 'warning'
  return undefined
}

onMounted(async () => {
  projects.value = await bizApi.projectList()
  users.value = await sysApi.userList()
  const pid = route.query.projectId
  if (pid) {
    const num = Number(pid)
    if (!Number.isNaN(num)) query.projectId = num
  }
  await load()
})
</script>

<template>
  <div class="page-stack">
    <div class="page-top">
      <div class="page-top__main">
        <p class="page-desc">全局任务列表；项目内请用「看板」拖拽改状态、点卡片看详情与评论</p>
      </div>
      <div class="page-actions">
        <el-button type="primary" @click="open()">新建任务</el-button>
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
        <el-select v-model="query.projectId" clearable placeholder="全部" class="filter-select--wide">
          <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="query.status" clearable placeholder="全部" class="filter-select">
          <el-option v-for="(label, value) in statusMap" :key="value" :label="label" :value="Number(value)" />
        </el-select>
      </el-form-item>
      <el-form-item label="优先级">
        <el-select v-model="query.priority" clearable placeholder="全部" class="filter-select">
          <el-option v-for="(label, value) in priorityMap" :key="value" :label="label" :value="Number(value)" />
        </el-select>
      </el-form-item>
      <el-form-item label="负责人">
        <el-select v-model="query.assigneeId" clearable filterable placeholder="全部" class="filter-select--wide">
          <el-option v-for="u in users" :key="u.id" :label="u.nickname || u.username" :value="u.id" />
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
        <el-table-column prop="assigneeName" label="负责人" width="100" show-overflow-tooltip />
        <el-table-column label="参与人员" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.participantNames?.length ? row.participantNames.join('、') : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="进度" width="130">
          <template #default="{ row }">
            <el-progress :percentage="row.progress ?? 0" :status="progressStatus(row)" :stroke-width="8" />
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
        <el-table-column label="操作" width="140" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="open(row)">详情</el-button>
            <el-button link type="danger" @click="remove(row.id)">删除</el-button>
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
