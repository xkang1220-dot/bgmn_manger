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
const summary = ref<any>({})
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
  { key: 'riskProjects', label: '风险项目', hint: '需要负责人介入', icon: 'DataAnalysis', tone: 'rose' },
  { key: 'overdue', label: '已逾期', hint: '必须立即处理', icon: 'Warning', tone: 'rose' },
  { key: 'dueSoon', label: '7天内到期', hint: '提前确认交付', icon: 'Timer', tone: 'amber' },
  { key: 'pending', label: '待确认完成', hint: '等待管理确认', icon: 'CircleCheck', tone: 'cyan' },
  { key: 'done30', label: '近30天完成', hint: '近期交付结果', icon: 'TrendCharts', tone: 'indigo' },
]

function isStatActive(key: string) {
  if (key === 'overdue') return !!query.overdue
  if (key === 'pending') return query.status === 4 && !query.overdue
  return false
}

function onStatClick(key: string) {
  if (key === 'overdue') {
    filterOverdue()
    return
  }
  if (key === 'pending') filterByStatus(4)
}

const riskTypeMap: Record<string, { label: string; type: 'danger' | 'warning' | 'info' | 'success' }> = {
  BLOCKED: { label: '已阻塞', type: 'danger' },
  OVERDUE: { label: '已逾期', type: 'danger' },
  DUE_SOON: { label: '即将到期', type: 'warning' },
  STALE: { label: '长期未更新', type: 'warning' },
  NO_DUE_DATE: { label: '未设截止时间', type: 'info' },
  PENDING: { label: '待确认', type: 'success' },
}

const maxTrend = computed(() => Math.max(1, ...(summary.value.trend || []).flatMap((item: any) => [item.created || 0, item.completed || 0])))
const maxOwnerLoad = computed(() => Math.max(1, ...(summary.value.ownerLoad || []).map((item: any) => item.open || 0)))

const healthMap: Record<string, { label: string; type: 'danger' | 'warning' | 'success' }> = {
  DANGER: { label: '危险', type: 'danger' },
  WARNING: { label: '预警', type: 'warning' },
  HEALTHY: { label: '健康', type: 'success' },
}

function selectProject(projectId: number) {
  query.projectId = projectId
  query.page = 1
  load()
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
  summary.value = await bizApi.managementTaskDashboard(params)
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
        <h2 class="page-title">交付管理驾驶舱</h2>
        <p class="page-desc">
          {{ isTaskManager ? '先处理风险和待确认事项，再下钻项目与具体任务。' : '聚焦本人负责或参与项目的交付风险与近期任务。' }}
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
          <div class="stat-hint">{{ card.hint }}</div>
        </div>
        <el-icon class="stat-glyph" :size="44"><component :is="card.icon" /></el-icon>
      </button>
    </div>

    <div class="management-grid">
      <section class="page-card action-center">
        <div class="section-head">
          <div>
            <h3>风险行动中心</h3>
            <p>按影响程度排序，优先处理可能影响交付的事项</p>
          </div>
          <div class="risk-badges">
            <span>无截止 {{ summary.noDueDate ?? 0 }}</span>
            <span>停滞 {{ summary.stale ?? 0 }}</span>
          </div>
        </div>
        <div v-if="summary.riskTasks?.length" class="risk-list">
          <button v-for="item in summary.riskTasks" :key="item.id" type="button" class="risk-row" @click="open(item)">
            <el-tag :type="riskTypeMap[item.riskType]?.type || 'info'" size="small">
              {{ riskTypeMap[item.riskType]?.label || '关注' }}
            </el-tag>
            <div class="risk-main">
              <strong>{{ item.title }}</strong>
              <span>{{ item.projectName }} · 负责人 {{ item.ownerName }}</span>
            </div>
            <div class="risk-meta">
              <strong>{{ item.dueDate || '未设日期' }}</strong>
              <span>进度 {{ item.progress ?? 0 }}%</span>
            </div>
          </button>
        </div>
        <el-empty v-else description="当前没有需要优先介入的风险任务" :image-size="64" />
      </section>

      <section class="page-card project-health">
        <div class="section-head">
          <div>
            <h3>项目健康榜</h3>
            <p>风险项目优先，点击项目查看任务</p>
          </div>
        </div>
        <div v-if="summary.projectHealth?.length" class="health-list">
          <button
            v-for="item in summary.projectHealth"
            :key="item.projectId"
            type="button"
            class="health-row"
            @click="selectProject(item.projectId)"
          >
            <div class="health-project">
              <div><strong>{{ item.projectName }}</strong><span>{{ item.ownerName }}</span></div>
              <el-tag :type="healthMap[item.level]?.type || 'info'" size="small">
                {{ healthMap[item.level]?.label || '未知' }}
              </el-tag>
            </div>
            <div class="health-metrics">
              <span><b>{{ item.open }}</b>未结</span>
              <span class="danger"><b>{{ item.overdue }}</b>逾期</span>
              <span><b>{{ item.dueSoon }}</b>临期</span>
              <span><b>{{ item.pending }}</b>待确认</span>
            </div>
          </button>
        </div>
        <el-empty v-else description="暂无进行中的项目任务" :image-size="64" />
      </section>
    </div>

    <div class="insight-grid">
      <section class="page-card performance-panel">
        <div class="section-head">
          <div><h3>交付效率</h3><p>按实际开始和完成时间计算</p></div>
        </div>
        <div class="performance-metrics">
          <div><span>按期完成率</span><strong>{{ summary.onTimeRate == null ? '—' : `${summary.onTimeRate}%` }}</strong></div>
          <div><span>平均交付周期</span><strong>{{ summary.avgCycleDays == null ? '—' : `${summary.avgCycleDays}天` }}</strong></div>
          <div><span>当前阻塞</span><strong :class="{ danger: summary.blocked > 0 }">{{ summary.blocked ?? 0 }}</strong></div>
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
        <div class="section-head"><div><h3>主责人负载</h3><p>未结任务及风险分布</p></div></div>
        <div v-if="summary.ownerLoad?.length" class="owner-list">
          <div v-for="item in summary.ownerLoad" :key="item.ownerId" class="owner-row">
            <div class="owner-line"><strong>{{ item.ownerName }}</strong><span>{{ item.open }}项 · 逾期{{ item.overdue }} · 阻塞{{ item.blocked }}</span></div>
            <div class="owner-bar"><i :style="{ width: `${item.open / maxOwnerLoad * 100}%` }" /></div>
          </div>
        </div>
        <el-empty v-else description="暂无主责人负载数据" :image-size="56" />
      </section>
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
      <div class="section-head task-section-head">
        <div><h3>任务明细</h3><p>用于筛选、下钻和日常执行</p></div>
      </div>
      <el-table v-loading="listLoading" :data="list" row-key="id" stripe empty-text="暂无任务">
        <el-table-column label="风险" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.overdue" type="danger" size="small">逾期</el-tag>
            <el-tag v-else-if="!row.dueDate && [0, 1].includes(row.status)" type="info" size="small">无日期</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="任务" min-width="220">
          <template #default="{ row }">
            <div class="task-title-cell">
              <el-link type="primary" :underline="false" @click="open(row)">{{ row.title }}</el-link>
              <div v-if="row.content" class="task-content-preview">{{ row.content }}</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="projectName" label="项目" width="140" show-overflow-tooltip />
        <el-table-column prop="assigneeName" label="主责人" width="110" show-overflow-tooltip>
          <template #default="{ row }">{{ row.assigneeName || '未指定' }}</template>
        </el-table-column>
        <el-table-column label="优先级" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="priorityType[row.priority] || 'info'" size="small">{{ priorityMap[row.priority] || '中' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="协作人员" min-width="150" show-overflow-tooltip>
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
.page-title { margin: 0 0 5px; font-size: 20px; color: var(--kk-text); }
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
.stat-hint { margin-top: 3px; font-size: 12px; color: var(--kk-text-muted); }

.management-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(360px, 1fr);
  gap: 14px;
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
.insight-grid { display: grid; grid-template-columns: minmax(0, 1.25fr) minmax(340px, .75fr); gap: 14px; }
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
  .management-grid { grid-template-columns: 1fr; }
  .insight-grid { grid-template-columns: 1fr; }
}
@media (prefers-reduced-transparency: reduce) {
  .stat-card {
    background: #fff;
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}
</style>
