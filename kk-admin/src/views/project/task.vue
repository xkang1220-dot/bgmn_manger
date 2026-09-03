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
  { key: 'todo', label: '待办', color: '#64748b' },
  { key: 'doing', label: '进行中', color: '#f59e0b' },
  { key: 'done', label: '已完成', color: '#22c55e' },
  { key: 'overdue', label: '已逾期', color: '#ef4444' },
]

async function loadSummary() {
  summary.value = await bizApi.taskSummary(query.projectId)
}

async function load() {
  const res = await bizApi.taskPage(query)
  list.value = res.list
  total.value = res.total
  await loadSummary()
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
  <div class="page-card">
    <div class="page-header">
      <div>
        <p class="page-desc">全局任务列表；项目内请用「看板」拖拽改状态、点卡片看详情与评论</p>
      </div>
      <el-button type="primary" @click="open()">新建任务</el-button>
    </div>

    <div class="task-stats">
      <div
        v-for="card in statCards"
        :key="card.key"
        class="task-stat-card"
        :class="{
          active:
            card.key === 'overdue'
              ? query.overdue
              : card.key === 'todo'
                ? query.status === 0 && !query.overdue
                : card.key === 'doing'
                  ? query.status === 1 && !query.overdue
                  : card.key === 'done'
                    ? query.status === 2 && !query.overdue
                    : false,
        }"
        @click="card.key === 'overdue' ? filterOverdue() : filterByStatus(card.key === 'todo' ? 0 : card.key === 'doing' ? 1 : card.key === 'done' ? 2 : undefined)"
      >
        <div class="task-stat-value" :style="{ color: card.color }">{{ summary[card.key] ?? 0 }}</div>
        <div class="task-stat-label">{{ card.label }}</div>
      </div>
      <div class="task-stat-card total" @click="resetQuery">
        <div class="task-stat-value">{{ summary.total ?? 0 }}</div>
        <div class="task-stat-label">全部任务</div>
      </div>
    </div>

    <div class="toolbar">
      <el-input v-model="query.title" placeholder="任务标题" clearable style="width: 180px" @keyup.enter="load" />
      <el-select v-model="query.projectId" clearable placeholder="所属项目" style="width: 180px" @change="load">
        <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
      </el-select>
      <el-select v-model="query.status" clearable placeholder="状态" style="width: 120px" @change="load">
        <el-option v-for="(label, value) in statusMap" :key="value" :label="label" :value="Number(value)" />
      </el-select>
      <el-select v-model="query.priority" clearable placeholder="优先级" style="width: 120px" @change="load">
        <el-option v-for="(label, value) in priorityMap" :key="value" :label="label" :value="Number(value)" />
      </el-select>
      <el-select v-model="query.assigneeId" clearable filterable placeholder="负责人" style="width: 140px" @change="load">
        <el-option v-for="u in users" :key="u.id" :label="u.nickname || u.username" :value="u.id" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button @click="resetQuery">重置</el-button>
    </div>

    <el-table :data="list" row-key="id">
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
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row)">详情</el-button>
          <el-button link type="danger" @click="remove(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      style="margin-top: 12px"
      v-model:current-page="query.page"
      v-model:page-size="query.pageSize"
      :total="total"
      :page-sizes="[10, 20, 50]"
      layout="total, sizes, prev, pager, next"
      @current-change="load"
      @size-change="load"
    />

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
.page-desc {
  margin: 0;
  font-size: 13px;
  color: #64748b;
}

.task-stats {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}

.task-stat-card {
  flex: 1;
  padding: 14px 16px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
}

.task-stat-card:hover,
.task-stat-card.active {
  border-color: #409eff;
  background: #eff6ff;
}

.task-stat-value {
  font-size: 24px;
  font-weight: 600;
  line-height: 1.2;
}

.task-stat-label {
  margin-top: 4px;
  font-size: 13px;
  color: #64748b;
}

.task-title-cell {
  line-height: 1.4;
}

.task-content-preview {
  margin-top: 4px;
  font-size: 12px;
  color: #94a3b8;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 280px;
}

.task-date-range {
  font-size: 13px;
  color: #475569;
}

.date-sep {
  margin: 0 4px;
  color: #94a3b8;
}

.overdue {
  color: #ef4444;
  font-weight: 500;
}
</style>
