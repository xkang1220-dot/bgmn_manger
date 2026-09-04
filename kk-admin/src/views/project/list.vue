<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Plus } from '@element-plus/icons-vue'
import { bizApi } from '@/api/biz'
import { sysApi } from '@/api/system'
import TaskKanban from '@/components/task/TaskKanban.vue'
import TaskDetailDrawer from '@/components/task/TaskDetailDrawer.vue'

const route = useRoute()
const router = useRouter()

const query = reactive({
  page: 1,
  pageSize: 50,
  name: '',
  status: undefined as number | undefined,
})
const list = ref<any[]>([])
const total = ref(0)
const users = ref<any[]>([])

const activeProjectId = ref<number | null>(null)
const detail = ref<any>(null)
const detailTab = ref('board')
const taskSummary = ref<Record<string, number>>({})
const tasks = ref<any[]>([])
const taskTotal = ref(0)
const taskQuery = reactive({ page: 1, pageSize: 10, status: undefined as number | undefined })
const loadingDetail = ref(false)
const kanbanRef = ref<InstanceType<typeof TaskKanban> | null>(null)
const taskDrawer = ref(false)
const activeTaskId = ref<number | null>(null)

const dialog = ref(false)
const isEdit = ref(false)

const form = reactive<any>({
  name: '',
  code: '',
  ownerId: undefined,
  status: 1,
  startDate: '',
  endDate: '',
  description: '',
})

const statusMap: Record<number, string> = { 0: '筹备', 1: '进行中', 2: '已完成', 3: '已关闭' }
const taskStatusMap: Record<number, string> = { 0: '待办', 1: '进行中', 2: '已完成', 3: '已取消' }
const taskStatusType: Record<number, '' | 'success' | 'warning' | 'info' | 'danger'> = {
  0: 'info',
  1: 'warning',
  2: 'success',
  3: 'info',
}
const priorityMap: Record<number, string> = { 1: '高', 2: '中', 3: '低' }
const priorityType: Record<number, '' | 'success' | 'warning' | 'info' | 'danger'> = {
  1: 'danger',
  2: 'warning',
  3: 'info',
}

const statusFilters = [
  { label: '全部', value: undefined as number | undefined },
  { label: '筹备', value: 0 },
  { label: '进行中', value: 1 },
  { label: '已完成', value: 2 },
  { label: '已关闭', value: 3 },
]

const inDetail = computed(() => activeProjectId.value != null && detail.value != null)
const saving = ref(false)
const filteredEmpty = computed(() => !list.value.length && (!!query.name.trim() || query.status !== undefined))

const statusKey = computed({
  get: () => (query.status === undefined ? 'all' : String(query.status)),
  set: (v: string) => {
    query.status = v === 'all' ? undefined : Number(v)
    query.page = 1
    load()
  },
})

function statusTone(status?: number) {
  return ({ 0: 'slate', 1: 'amber', 2: 'cyan', 3: 'slate' } as Record<number, string>)[status ?? 0] || 'slate'
}

async function load() {
  const res = await bizApi.projectPage(query)
  list.value = res.list
  total.value = res.total
}

function onSearch() {
  query.page = 1
  load()
}

function resetFilter() {
  query.name = ''
  query.status = undefined
  query.page = 1
  load()
}

async function enterProject(row: any) {
  activeProjectId.value = row.id
  detailTab.value = 'board'
  taskQuery.page = 1
  taskQuery.status = undefined
  await router.replace({ query: { ...route.query, id: String(row.id) } })
  await loadDetail(row.id)
}

async function loadDetail(id: number) {
  loadingDetail.value = true
  try {
    detail.value = await bizApi.projectDetail(id)
    await loadTaskSummary()
    await loadActiveTabData()
  } finally {
    loadingDetail.value = false
  }
}

async function loadActiveTabData() {
  if (detailTab.value === 'tasks') {
    await loadTasks()
  }
}

async function loadTaskSummary() {
  if (!activeProjectId.value) return
  taskSummary.value = await bizApi.taskSummary(activeProjectId.value)
}

async function loadTasks() {
  if (!activeProjectId.value) return
  const res = await bizApi.taskPage({
    page: taskQuery.page,
    pageSize: taskQuery.pageSize,
    projectId: activeProjectId.value,
    status: taskQuery.status,
  })
  tasks.value = res.list
  taskTotal.value = res.total
}

function backToList() {
  activeProjectId.value = null
  detail.value = null
  const q = { ...route.query }
  delete q.id
  router.replace({ query: q })
}

function open(row?: any) {
  isEdit.value = !!row
  if (row) {
    bizApi.projectDetail(row.id).then((d) => {
      Object.assign(form, {
        id: d.id,
        name: d.name,
        code: d.code,
        ownerId: d.ownerId,
        status: d.status,
        startDate: d.startDate || '',
        endDate: d.endDate || '',
        description: d.description || '',
      })
      dialog.value = true
    })
  } else {
    Object.assign(form, {
      id: undefined,
      name: '',
      code: '',
      ownerId: undefined,
      status: 1,
      startDate: '',
      endDate: '',
      description: '',
    })
    dialog.value = true
  }
}

async function save() {
  if (!form.name?.trim()) {
    ElMessage.warning('请填写项目名称')
    return
  }
  saving.value = true
  try {
    if (isEdit.value) {
      await bizApi.saveProject(form, true)
      ElMessage.success('保存成功')
    } else {
      await bizApi.saveProject(form, false)
      ElMessage.success('已提交创建审批，需全体股东通过（3天未操作自动通过）')
    }
    dialog.value = false
    await load()
    if (activeProjectId.value) {
      await loadDetail(activeProjectId.value)
    }
  } finally {
    saving.value = false
  }
}

async function remove(id: number) {
  await ElMessageBox.confirm('删除项目需全体股东审批，确认提交？')
  await bizApi.deleteProject(id)
  ElMessage.success('已提交删除审批')
  if (activeProjectId.value === id) backToList()
  await load()
}

function goTaskManage() {
  router.push({ path: '/project/task', query: { projectId: String(activeProjectId.value) } })
}

function openTaskDetail(task?: any) {
  activeTaskId.value = task?.id ?? null
  taskDrawer.value = true
}

function createTask() {
  activeTaskId.value = null
  taskDrawer.value = true
}

async function onTaskSaved() {
  await Promise.all([loadTaskSummary(), loadActiveTabData()])
  if (detailTab.value === 'board') {
    kanbanRef.value?.load()
  }
}

watch(
  () => taskQuery.status,
  () => {
    if (detailTab.value !== 'tasks') return
    taskQuery.page = 1
    loadTasks()
  },
)

watch(detailTab, () => {
  void loadActiveTabData()
})

onMounted(async () => {
  users.value = await sysApi.userList()
  await load()
  const id = route.query.id
  if (id) {
    const num = Number(id)
    if (!Number.isNaN(num)) {
      activeProjectId.value = num
      await loadDetail(num)
    }
  }
})
</script>

<template>
  <div class="page-stack">
    <template v-if="!inDetail">
      <div class="page-top">
        <div class="page-top__main">
          <p class="page-desc">以项目卡片浏览；点进卡片查看看板与任务。分成请到「财务 → 项目账款」</p>
        </div>
        <div class="page-actions">
          <el-button type="primary" :icon="Plus" @click="open()">新建项目</el-button>
        </div>
      </div>

      <el-form class="filter-bar" @submit.prevent="onSearch">
        <el-form-item label="状态">
          <el-radio-group v-model="statusKey">
            <el-radio-button v-for="item in statusFilters" :key="String(item.value)" :value="item.value === undefined ? 'all' : String(item.value)">
              {{ item.label }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="名称">
          <el-input
            v-model="query.name"
            placeholder="搜索项目名称"
            clearable
            class="filter-keyword--wide"
            @keyup.enter="onSearch"
            @clear="onSearch"
          />
        </el-form-item>
        <el-form-item class="filter-actions">
          <el-button type="primary" native-type="submit">查询</el-button>
          <el-button @click="resetFilter">重置</el-button>
        </el-form-item>
      </el-form>

      <div v-if="list.length" class="project-grid">
        <article
          v-for="row in list"
          :key="row.id"
          class="project-card"
          :class="'project-card--' + statusTone(row.status)"
          role="button"
          tabindex="0"
          @click="enterProject(row)"
          @keyup.enter="enterProject(row)"
        >
          <div class="project-card__head">
            <span class="status-pill status-pill--sm" :class="`status-pill--${row.status}`">
              {{ statusMap[row.status] || '—' }}
            </span>
            <span class="project-code">{{ row.code || '未编号' }}</span>
          </div>
          <div class="project-card__main">
            <div>
              <h3 class="project-card__title">{{ row.name }}</h3>
              <div class="project-card__meta">负责人 {{ row.ownerName || '未指定' }}</div>
            </div>
            <el-icon class="project-card__icon" :size="40"><FolderOpened /></el-icon>
          </div>
          <div class="project-card__actions" @click.stop>
            <el-button
              class="icon-btn"
              text
              aria-label="编辑"
              title="编辑"
              @click="open(row)"
            >
              <el-icon :size="16"><EditPen /></el-icon>
            </el-button>
            <el-button
              class="icon-btn is-danger"
              text
              aria-label="删除"
              title="删除"
              @click="remove(row.id)"
            >
              <el-icon :size="16"><Delete /></el-icon>
            </el-button>
            <span class="project-card__go">进入看板</span>
          </div>
        </article>
      </div>
      <el-empty v-else :description="filteredEmpty ? '没有匹配的项目' : '暂无项目'" />

      <div v-if="total > query.pageSize" class="page-footer">
        <el-pagination
          v-model:current-page="query.page"
          :page-size="query.pageSize"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="load"
        />
      </div>
    </template>

    <template v-else>
      <section class="project-hero" v-loading="loadingDetail">
        <div class="project-hero__top">
          <button type="button" class="back-btn" @click="backToList">
            <el-icon><ArrowLeft /></el-icon>
            返回项目墙
          </button>
        </div>

        <div class="project-hero__body">
          <div class="project-hero__intro">
            <div class="project-hero__title-row">
              <h1 class="project-hero__title">{{ detail.name }}</h1>
              <span class="status-pill" :class="`status-pill--${detail.status}`">{{ statusMap[detail.status] }}</span>
              <span class="project-code">{{ detail.code || '未编号' }}</span>
            </div>
            <div class="meta-chips">
              <span class="meta-chip">
                <el-icon :size="14"><User /></el-icon>
                {{ detail.ownerName || '未指定' }}
              </span>
              <span v-if="detail.startDate || detail.endDate" class="meta-chip">
                <el-icon :size="14"><Calendar /></el-icon>
                {{ detail.startDate || '—' }} ~ {{ detail.endDate || '—' }}
              </span>
            </div>
          </div>

          <div class="metric-bar">
            <div class="metric-item">
              <el-icon class="metric-icon" :size="18"><Tickets /></el-icon>
              <div class="metric-copy">
                <span class="metric-label">任务总数</span>
                <span class="metric-value">{{ taskSummary.total ?? 0 }}</span>
              </div>
            </div>
            <div class="metric-item">
              <el-icon class="metric-icon is-todo" :size="18"><Clock /></el-icon>
              <div class="metric-copy">
                <span class="metric-label">待办</span>
                <span class="metric-value">{{ taskSummary.todo ?? 0 }}</span>
              </div>
            </div>
            <div class="metric-item">
              <el-icon class="metric-icon is-doing" :size="18"><Flag /></el-icon>
              <div class="metric-copy">
                <span class="metric-label">进行中</span>
                <span class="metric-value">{{ taskSummary.doing ?? 0 }}</span>
              </div>
            </div>
            <div class="metric-item">
              <el-icon class="metric-icon is-done" :size="18"><CircleCheck /></el-icon>
              <div class="metric-copy">
                <span class="metric-label">已完成</span>
                <span class="metric-value">{{ taskSummary.done ?? 0 }}</span>
              </div>
            </div>
            <div class="metric-item">
              <el-icon class="metric-icon is-overdue" :size="18"><Warning /></el-icon>
              <div class="metric-copy">
                <span class="metric-label">逾期</span>
                <span class="metric-value" :class="{ overdue: (taskSummary.overdue ?? 0) > 0 }">
                  {{ taskSummary.overdue ?? 0 }}
                </span>
              </div>
            </div>
          </div>

          <div class="project-hero__ops">
            <el-button
              class="icon-btn"
              text
              aria-label="编辑"
              title="编辑"
              @click="open(detail)"
            >
              <el-icon :size="16"><EditPen /></el-icon>
            </el-button>
            <el-button
              class="icon-btn is-danger"
              text
              aria-label="删除"
              title="删除"
              @click="remove(detail.id)"
            >
              <el-icon :size="16"><Delete /></el-icon>
            </el-button>
          </div>
        </div>
      </section>

      <section class="project-panel" v-loading="loadingDetail">
        <el-tabs v-model="detailTab" class="project-tabs">
          <el-tab-pane label="看板" name="board">
            <TaskKanban
              v-if="activeProjectId && detailTab === 'board'"
              ref="kanbanRef"
              :project-id="activeProjectId"
              @open-task="openTaskDetail"
              @create-task="createTask"
              @changed="onTaskSaved"
            />
          </el-tab-pane>

          <el-tab-pane label="任务列表" name="tasks">
            <div class="panel-toolbar">
              <div class="filter-pills">
                <button
                  v-for="item in [{ label: '全部', value: undefined }, { label: '待办', value: 0 }, { label: '进行中', value: 1 }, { label: '已完成', value: 2 }]"
                  :key="String(item.value)"
                  type="button"
                  class="filter-pill"
                  :class="{ active: taskQuery.status === item.value }"
                  @click="taskQuery.status = item.value"
                >
                  {{ item.label }}
                </button>
              </div>
              <div class="panel-toolbar__right">
                <el-button size="small" type="primary" @click="createTask">新建任务</el-button>
                <el-button size="small" @click="goTaskManage">全部任务</el-button>
              </div>
            </div>
            <div class="table-wrap">
              <el-table :data="tasks" stripe @row-click="openTaskDetail">
                <el-table-column prop="title" label="任务" min-width="180" show-overflow-tooltip>
                  <template #default="{ row }">
                    <el-link type="primary" :underline="false" @click.stop="openTaskDetail(row)">{{ row.title }}</el-link>
                  </template>
                </el-table-column>
                <el-table-column label="优先级" width="80" align="center">
                  <template #default="{ row }">
                    <el-tag :type="priorityType[row.priority]" size="small" effect="light">{{ priorityMap[row.priority] || '中' }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="assigneeName" label="负责人" width="100" />
                <el-table-column label="参与人员" min-width="140" show-overflow-tooltip>
                  <template #default="{ row }">
                    {{ row.participantNames?.length ? row.participantNames.join('、') : '—' }}
                  </template>
                </el-table-column>
                <el-table-column label="进度" width="120">
                  <template #default="{ row }">
                    <el-progress :percentage="row.progress ?? 0" :stroke-width="6" />
                  </template>
                </el-table-column>
                <el-table-column label="状态" width="90" align="center">
                  <template #default="{ row }">
                    <el-tag :type="taskStatusType[row.status]" size="small" effect="light">{{ taskStatusMap[row.status] }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="截止日期" width="120">
                  <template #default="{ row }">
                    <span :class="{ overdue: row.overdue }">{{ row.dueDate || '—' }}</span>
                  </template>
                </el-table-column>
              </el-table>
            </div>
            <div class="page-footer">
              <el-pagination
                v-model:current-page="taskQuery.page"
                :page-size="taskQuery.pageSize"
                :total="taskTotal"
                layout="total, prev, pager, next"
                @current-change="loadTasks"
              />
            </div>
          </el-tab-pane>

          <el-tab-pane label="项目信息" name="info">
            <div class="info-grid">
              <div class="info-item">
                <span class="info-label">项目名称</span>
                <span class="info-value">{{ detail.name }}</span>
              </div>
              <div class="info-item">
                <span class="info-label">编号</span>
                <span class="info-value">{{ detail.code || '—' }}</span>
              </div>
              <div class="info-item">
                <span class="info-label">负责人</span>
                <span class="info-value">{{ detail.ownerName || '—' }}</span>
              </div>
              <div class="info-item">
                <span class="info-label">状态</span>
                <span class="info-value">{{ statusMap[detail.status] }}</span>
              </div>
              <div class="info-item">
                <span class="info-label">周期</span>
                <span class="info-value">{{ detail.startDate || '—' }} ~ {{ detail.endDate || '—' }}</span>
              </div>
              <div class="info-item info-item--full">
                <span class="info-label">说明</span>
                <span class="info-value desc-text">{{ detail.description || '暂无说明' }}</span>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </section>
    </template>

    <el-dialog v-model="dialog" :title="isEdit ? '编辑项目' : '新建项目'" width="560px" :close-on-click-modal="false">
      <el-form label-width="90px">
        <el-form-item label="名称" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="编号"><el-input v-model="form.code" /></el-form-item>
        <el-form-item label="负责人">
          <el-select v-model="form.ownerId" filterable clearable style="width: 100%">
            <el-option v-for="u in users" :key="u.id" :label="u.nickname || u.username" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="开始日期">
              <el-date-picker v-model="form.startDate" value-format="YYYY-MM-DD" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="结束日期">
              <el-date-picker v-model="form.endDate" value-format="YYYY-MM-DD" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 100%">
            <el-option :value="0" label="筹备" />
            <el-option :value="1" label="进行中" />
            <el-option :value="2" label="已完成" />
            <el-option :value="3" label="已关闭" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <TaskDetailDrawer
      v-model="taskDrawer"
      :task-id="activeTaskId"
      :default-project-id="activeProjectId"
      @saved="onTaskSaved"
      @deleted="onTaskSaved"
    />
  </div>
</template>

<style scoped>
.filter-keyword--wide {
  width: 220px;
}

.project-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
}

.project-card {
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  min-height: 176px;
  padding: 18px 18px 14px;
  cursor: pointer;
  background: var(--kk-glass-bg);
  border: 1px solid var(--kk-glass-border);
  border-radius: var(--kk-radius);
  box-shadow: var(--kk-glass-shadow);
  backdrop-filter: var(--kk-glass-blur);
  -webkit-backdrop-filter: var(--kk-glass-blur);
  transition: box-shadow 0.15s var(--kk-ease);
}

.project-card::before {
  content: "";
  position: absolute;
  right: -24px;
  bottom: -36px;
  width: 130px;
  height: 130px;
  border-radius: 50%;
  filter: blur(28px);
  opacity: 0.22;
  pointer-events: none;
}

.project-card--amber::before { background: #fde68a; }
.project-card--cyan::before { background: #a5f3fc; }
.project-card--slate::before { background: #e2e8f0; }
.project-card--amber .project-card__icon { color: #d97706; }
.project-card--cyan .project-card__icon { color: #0891b2; }
.project-card--slate .project-card__icon { color: #64748b; }

.project-card:hover { box-shadow: 0 8px 28px rgba(0, 0, 0, 0.08); }
.project-card:focus-visible {
  outline: 2px solid var(--kk-primary);
  outline-offset: 2px;
}

.project-card__head {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.project-code {
  font-size: 12px;
  color: var(--kk-text-muted);
  font-variant-numeric: tabular-nums;
}

.project-card__main {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin: 14px 0 12px;
}

.project-card__title {
  margin: 0 0 6px;
  font-size: 16px;
  font-weight: 600;
  line-height: 1.4;
  color: var(--kk-text);
}

.project-card__meta {
  font-size: 12px;
  color: var(--kk-text-muted);
}

.project-card__icon {
  flex-shrink: 0;
  opacity: 1;
}

.project-card__actions {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 2px;
  margin-top: auto;
  padding-top: 12px;
  border-top: 1px solid rgba(0, 0, 0, 0.06);
}

.project-card__actions .icon-btn {
  width: 32px;
  height: 32px;
  padding: 0;
  color: var(--kk-text-secondary);
}

.project-card__actions .icon-btn:hover {
  color: var(--kk-primary);
}

.project-card__actions .icon-btn.is-danger:hover {
  color: var(--kk-danger);
}

.project-card__go {
  margin-left: auto;
  font-size: 12px;
  color: var(--kk-text-muted);
}

.project-hero {
  background: var(--kk-glass-bg);
  border: 1px solid var(--kk-glass-border);
  border-radius: var(--kk-radius);
  box-shadow: var(--kk-glass-shadow);
  backdrop-filter: var(--kk-glass-blur);
  -webkit-backdrop-filter: var(--kk-glass-blur);
  overflow: hidden;
}

.project-hero__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 20px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
}

.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: none;
  background: none;
  color: #64748b;
  font-size: 13px;
  cursor: pointer;
  padding: 4px 0;
}

.back-btn:hover {
  color: var(--kk-primary);
}

.project-hero__body {
  padding: 20px 24px 16px;
}

.project-hero__ops {
  display: flex;
  align-items: center;
  gap: 2px;
  margin-top: 14px;
  padding-top: 10px;
  border-top: 1px solid rgba(0, 0, 0, 0.06);
}

.project-hero__ops .icon-btn {
  width: 32px;
  height: 32px;
  padding: 0;
  color: var(--kk-text-secondary);
}

.project-hero__ops .icon-btn:hover {
  color: var(--kk-primary);
}

.project-hero__ops .icon-btn.is-danger:hover {
  color: var(--kk-danger);
}

.project-hero__title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.project-hero__title {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: #0f172a;
}

.status-pill {
  display: inline-block;
  padding: 3px 10px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
}

.status-pill--sm {
  padding: 2px 8px;
  font-size: 11px;
}

.status-pill--0 { background: #f1f5f9; color: #64748b; }
.status-pill--1 { background: #fef3c7; color: #b45309; }
.status-pill--2 { background: #d1fae5; color: #047857; }
.status-pill--3 { background: #f1f5f9; color: #475569; }

.meta-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 18px;
}

.meta-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  background: rgba(255, 255, 255, 0.45);
  border: 1px solid rgba(255, 255, 255, 0.7);
  border-radius: 999px;
  font-size: 13px;
  color: var(--kk-text);
}

.meta-chip .el-icon {
  color: var(--kk-text-muted);
}

.metric-bar {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
}

.metric-item {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  padding: 12px 14px;
  background: rgba(255, 255, 255, 0.45);
  border: 1px solid rgba(255, 255, 255, 0.7);
  border-radius: 14px;
  transition: transform 0.18s var(--kk-ease), box-shadow 0.18s var(--kk-ease);
}

.metric-item:hover {
  transform: translateY(-1px);
  box-shadow: 0 8px 20px rgba(0, 0, 0, 0.05);
}

.metric-icon {
  flex-shrink: 0;
  color: var(--kk-primary);
}

.metric-icon.is-todo { color: #71717a; }
.metric-icon.is-doing { color: #d97706; }
.metric-icon.is-done { color: #059669; }
.metric-icon.is-overdue { color: var(--kk-danger); }

.metric-copy {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.metric-label {
  font-size: 12px;
  color: var(--kk-text-muted);
}

.metric-value {
  font-size: 18px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.03em;
  color: var(--kk-text);
}

.project-panel {
  background: var(--kk-glass-bg);
  border: 1px solid var(--kk-glass-border);
  border-radius: var(--kk-radius);
  box-shadow: var(--kk-glass-shadow);
  backdrop-filter: var(--kk-glass-blur);
  -webkit-backdrop-filter: var(--kk-glass-blur);
  padding: 0 4px 4px;
}

.project-tabs :deep(.el-tabs__header) {
  margin: 0;
  padding: 0 16px;
  border-bottom: 1px solid #f1f5f9;
}

.project-tabs :deep(.el-tabs__nav-wrap::after) {
  display: none;
}

.project-tabs :deep(.el-tabs__item) {
  height: 46px;
  font-size: 14px;
  color: #64748b;
  padding: 0 18px;
}

.project-tabs :deep(.el-tabs__item.is-active) {
  color: var(--kk-primary);
  font-weight: 600;
}

.project-tabs :deep(.el-tabs__active-bar) {
  background: var(--kk-primary);
  height: 2px;
}

.project-tabs :deep(.el-tabs__content) {
  padding: 16px 16px 8px;
}

.panel-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
  gap: 12px;
}

.panel-toolbar__right {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.filter-pills {
  display: flex;
  gap: 6px;
}

.filter-pill {
  border: none;
  background: #f1f5f9;
  color: #64748b;
  border-radius: 7px;
  padding: 6px 14px;
  font-size: 13px;
  cursor: pointer;
}

.filter-pill.active {
  background: var(--kk-primary);
  color: #fff;
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 1px;
  background: #eef2f7;
  border: 1px solid #eef2f7;
  border-radius: 10px;
  overflow: hidden;
}

.info-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px 16px;
  background: #fff;
}

.info-item--full {
  grid-column: 1 / -1;
}

.info-label {
  font-size: 12px;
  color: #94a3b8;
}

.info-value {
  font-size: 14px;
  color: #0f172a;
  font-weight: 500;
}

.desc-text {
  white-space: pre-wrap;
  line-height: 1.6;
  font-weight: 400;
}

.overdue {
  color: #ef4444;
  font-weight: 500;
}

@media (prefers-reduced-transparency: reduce) {
  .project-card,
  .project-hero,
  .project-panel {
    background: #fff;
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}

@media (max-width: 960px) {
  .metric-bar {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .metric-item:last-child {
    grid-column: 1 / -1;
  }
  .info-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .project-hero__top {
    flex-direction: column;
    align-items: flex-start;
    gap: 10px;
  }
}
</style>
