<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Plus } from '@element-plus/icons-vue'
import { bizApi } from '@/api/biz'
import { sysApi } from '@/api/system'
import { workflowApi } from '@/api/workflow'
import { approvalFlowTip } from '@/utils/approvalTip'
import TaskKanban from '@/components/task/TaskKanban.vue'
import TaskDetailDrawer from '@/components/task/TaskDetailDrawer.vue'

const route = useRoute()
const router = useRouter()

const query = reactive({
  page: 1,
  pageSize: 50,
  name: '',
  status: 1 as number | undefined,
  companyId: undefined as number | undefined,
})
const list = ref<any[]>([])
const total = ref(0)
const users = ref<any[]>([])
const companies = ref<any[]>([])

const activeProjectId = ref<number | null>(null)
const detail = ref<any>(null)
const detailTab = ref('board')
const taskSummary = ref<Record<string, number>>({})
const tasks = ref<any[]>([])
const taskTotal = ref(0)
const projectFlows = ref<any[]>([])
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
  companyId: undefined as number | undefined,
  parentId: undefined as number | undefined,
  ownerId: undefined,
  participants: [] as { userId?: number; layer: string }[],
  status: 1,
  scale: 'NORMAL',
  startDate: '',
  endDate: '',
  actualEndDate: '',
  description: '',
})

const children = ref<any[]>([])
const creatingChild = ref(false)

const statusMap: Record<number, string> = { 0: '筹备', 1: '进行中', 2: '已完成', 3: '已关闭' }
const scaleMap: Record<string, string> = { NORMAL: '常规', KEY: '重点', MAJOR: '重大' }
const scaleOptions = [
  { value: 'NORMAL', label: '常规', tip: '创建免审' },
  { value: 'KEY', label: '重点', tip: '创建需审批；唯一审批人本人可免审' },
  { value: 'MAJOR', label: '重大', tip: '创建需审批；唯一审批人本人可免审' },
]
const taskStatusMap: Record<number, string> = { 0: '待办', 1: '进行中', 2: '已完成', 3: '已关闭' }
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
const isMajorShell = computed(
  () => !!detail.value && detail.value.scale === 'MAJOR' && !detail.value.parentId,
)
const isChildProject = computed(() => !!detail.value?.parentId)
const saving = ref(false)
const filteredEmpty = computed(
  () =>
    !list.value.length &&
    (!!query.name.trim() || query.status !== undefined || query.companyId !== undefined),
)

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
  const params: Record<string, unknown> = {
    page: query.page,
    pageSize: query.pageSize,
  }
  if (query.name.trim()) params.name = query.name.trim()
  if (query.status !== undefined) params.status = query.status
  if (query.companyId != null) params.companyId = query.companyId
  const res = await bizApi.projectPage(params)
  list.value = res.list
  total.value = res.total
}

function onSearch() {
  query.page = 1
  load()
}

function resetFilter() {
  query.name = ''
  query.status = 1
  query.companyId = undefined
  query.page = 1
  load()
}

async function enterProject(row: any) {
  activeProjectId.value = row.id
  detailTab.value = row.scale === 'MAJOR' && !row.parentId ? 'children' : 'board'
  taskQuery.page = 1
  taskQuery.status = undefined
  children.value = []
  await router.replace({ query: { ...route.query, id: String(row.id) } })
  await loadDetail(row.id)
}

async function loadDetail(id: number) {
  loadingDetail.value = true
  try {
    detail.value = await bizApi.projectDetail(id)
    if (detail.value?.scale === 'MAJOR' && !detail.value?.parentId) {
      await loadChildren()
      if (detailTab.value === 'board' || detailTab.value === 'tasks') {
        detailTab.value = 'children'
      }
    } else {
      children.value = []
      await loadTaskSummary()
    }
    await loadActiveTabData()
  } finally {
    loadingDetail.value = false
  }
}

async function loadChildren() {
  if (!activeProjectId.value) {
    children.value = []
    return
  }
  try {
    children.value = (await bizApi.projectChildren(activeProjectId.value)) || []
  } catch {
    children.value = []
  }
}

async function loadActiveTabData() {
  if (detailTab.value === 'children') {
    await loadChildren()
  } else if (detailTab.value === 'tasks') {
    await loadTasks()
  } else if (detailTab.value === 'flows') {
    await loadProjectFlows()
  }
}

function backToList() {
  if (detail.value?.parentId) {
    const parentId = detail.value.parentId
    void enterProject({ id: parentId, scale: 'MAJOR', parentId: null })
    return
  }
  activeProjectId.value = null
  detail.value = null
  children.value = []
  creatingChild.value = false
  const q = { ...route.query }
  delete q.id
  router.replace({ query: q })
}

function open(row?: any) {
  isEdit.value = !!row
  creatingChild.value = false
  if (row) {
    bizApi.projectDetail(row.id).then((d) => {
      Object.assign(form, {
        id: d.id,
        name: d.name,
        code: d.code,
        companyId: d.companyId,
        companyName: d.companyName,
        parentId: d.parentId,
        parentName: d.parentName,
        ownerId: d.ownerId,
        participants: (d.members || []).map((m: any) => ({
          userId: m.userId,
          layer: m.layer || '',
        })),
        status: d.status,
        scale: d.scale || 'NORMAL',
        startDate: d.startDate || '',
        endDate: d.endDate || '',
        actualEndDate: d.actualEndDate || '',
        description: d.description || '',
      })
      if (!form.participants.length) {
        form.participants = [{ userId: undefined, layer: '' }]
      }
      loadOwners(d.companyId)
      dialog.value = true
    })
  } else {
    const only = companies.value.length === 1 ? companies.value[0].id : undefined
    Object.assign(form, {
      id: undefined,
      name: '',
      code: '',
      companyId: only,
      companyName: undefined,
      parentId: undefined,
      parentName: undefined,
      ownerId: undefined,
      participants: [{ userId: undefined, layer: '' }] as { userId?: number; layer: string }[],
      status: 1,
      scale: 'NORMAL',
      startDate: '',
      endDate: '',
      actualEndDate: '',
      description: '',
    })
    loadOwners(only)
    dialog.value = true
    void previewNextCode(only)
  }
}

function openCreateChild() {
  if (!detail.value?.id) return
  isEdit.value = false
  creatingChild.value = true
  Object.assign(form, {
    id: undefined,
    name: '',
    code: '',
    companyId: detail.value.companyId,
    companyName: detail.value.companyName,
    parentId: detail.value.id,
    ownerId: undefined,
    participants: [{ userId: undefined, layer: '' }] as { userId?: number; layer: string }[],
    status: 1,
    scale: 'KEY',
    startDate: '',
    endDate: '',
    actualEndDate: '',
    description: '',
  })
  loadOwners(detail.value.companyId)
  dialog.value = true
  void previewNextCode(detail.value.companyId)
}

async function loadProjectFlows() {
  if (!activeProjectId.value) return
  projectFlows.value = await bizApi.projectFlows(activeProjectId.value)
}

async function loadTaskSummary() {
  if (!activeProjectId.value) return
  taskSummary.value = await bizApi.taskSummary({ projectId: activeProjectId.value })
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

async function previewNextCode(companyId?: number | string | null) {
  const id = companyId == null || companyId === '' ? undefined : Number(companyId)
  if (isEdit.value || id == null || Number.isNaN(id)) {
    if (!isEdit.value) form.code = ''
    return
  }
  try {
    form.code = await bizApi.projectNextCode(id)
  } catch {
    form.code = ''
  }
}

async function loadOwners(companyId?: number | string | null) {
  const id = companyId == null || companyId === '' ? undefined : Number(companyId)
  users.value = id != null && !Number.isNaN(id)
    ? await sysApi.userList({ companyId: id })
    : await sysApi.userList()
}

async function onCompanyChange(companyId?: number | string | null) {
  form.ownerId = undefined
  form.participants = [{ userId: undefined, layer: '' }]
  await loadOwners(companyId)
  await previewNextCode(companyId)
}

function addParticipantRow() {
  form.participants.push({ userId: undefined, layer: '' })
}

function removeParticipantRow(index: number | string) {
  form.participants.splice(Number(index), 1)
  if (!form.participants.length) {
    form.participants.push({ userId: undefined, layer: '' })
  }
}

function formatMemberLabel(m: any) {
  const name = m.nickname || m.userName || m.userId
  return m.layer ? `${name}（${m.layer}）` : name
}

async function save() {
  if (!form.name?.trim()) {
    ElMessage.warning('请填写项目名称')
    return
  }
  if (!isEdit.value && !form.companyId) {
    ElMessage.warning('请选择所属公司')
    return
  }
  if (!form.scale) {
    ElMessage.warning('请选择项目规模')
    return
  }
  const memberRows = (form.participants || []).filter((p: any) => p.userId != null)
  for (const row of memberRows) {
    if (!String(row.layer || '').trim()) {
      ElMessage.warning('请填写每位项目参与人的职责')
      return
    }
  }
  const userIds = memberRows.map((p: any) => p.userId)
  if (new Set(userIds).size !== userIds.length) {
    ElMessage.warning('项目参与人不能重复')
    return
  }
  saving.value = true
  try {
    const payload = {
      ...form,
      startDate: form.startDate || null,
      endDate: form.endDate || null,
      actualEndDate: form.actualEndDate || null,
      members: memberRows.map((p: any) => ({
        userId: p.userId,
        layer: String(p.layer).trim(),
        percent: 0,
      })),
    }
    delete payload.participants
    delete payload.code
    if (isEdit.value) {
      const res = await bizApi.saveProject(payload, true)
      if (res?.type === 'PROJECT_SCALE_CHANGE' || res?.bizNo) {
        ElMessage.success(approvalFlowTip(res, '已提交规模变更审批'))
      } else {
        ElMessage.success('保存成功')
      }
    } else {
      const res = await bizApi.saveProject(payload, false)
      if (res?.type === 'PROJECT_CREATE' || res?.bizNo) {
        ElMessage.success(approvalFlowTip(res))
      } else if ((form.scale === 'KEY' || form.scale === 'MAJOR') && res?.id) {
        ElMessage.success('你是唯一审批人，已直接创建成功')
      } else {
        ElMessage.success('创建成功')
      }
    }
    dialog.value = false
    creatingChild.value = false
    await load()
    if (activeProjectId.value) {
      await loadDetail(activeProjectId.value)
    }
  } finally {
    saving.value = false
  }
}

async function remove(id: number) {
  const project = list.value.find((p) => p.id === id) || detail.value
  const companyId = project?.companyId
  let confirmText = '删除项目需提交审批，确认提交？'
  let soleSelf = false
  if (companyId) {
    try {
      const desc = await workflowApi.flowDescribe('PROJECT_DELETE', companyId)
      soleSelf = !!desc?.soleApproverSelf
      confirmText = soleSelf
        ? `${desc.tip}，确认删除？`
        : `${desc.tip}，确认提交删除审批？`
    } catch {
      /* 配置缺失时仍允许点确认，提交接口会给出明确错误 */
    }
  }
  await ElMessageBox.confirm(confirmText)
  const res = await bizApi.deleteProject(id)
  if (res?.type === 'PROJECT_DELETE' || res?.bizNo) {
    ElMessage.success(approvalFlowTip(res, '已提交删除审批'))
  } else {
    ElMessage.success(soleSelf ? '你是唯一审批人，已直接删除成功' : '删除成功')
  }
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
  companies.value = await sysApi.myCompanies()
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
          <el-button v-permission="'project:add'" type="primary" :icon="Plus" @click="open()">新建项目</el-button>
        </div>
      </div>

      <el-form class="filter-bar" label-position="top" @submit.prevent="onSearch">
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
        <el-form-item label="跟进公司">
          <el-select
            v-model="query.companyId"
            clearable
            filterable
            placeholder="全部公司"
            class="filter-company"
            @change="onSearch"
          >
            <el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
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
            <div class="project-card__tags">
              <span class="status-pill status-pill--sm" :class="`status-pill--${row.status}`">
                {{ statusMap[row.status] || '—' }}
              </span>
              <span class="scale-pill scale-pill--sm" :class="`scale-pill--${row.scale || 'NORMAL'}`">
                {{ scaleMap[row.scale] || '常规' }}
              </span>
              <span v-if="row.scale === 'MAJOR' && row.childCount" class="meta-chip">{{ row.childCount }} 个小项目</span>
            </div>
            <span class="project-code">{{ row.code || '未编号' }}</span>
          </div>
          <div class="project-card__main">
            <div>
              <h3 class="project-card__title">{{ row.name }}</h3>
              <div class="project-card__meta">
                {{ row.companyName || '未指定公司' }} · 负责人 {{ row.ownerName || '未指定' }}
              </div>
            </div>
            <el-icon class="project-card__icon" :size="40"><FolderOpened /></el-icon>
          </div>
          <div class="project-card__actions" @click.stop>
            <el-button
              v-permission="'project:edit'"
              class="icon-btn"
              text
              aria-label="编辑"
              title="编辑"
              @click="open(row)"
            >
              <el-icon :size="16"><EditPen /></el-icon>
            </el-button>
            <el-button
              v-permission="'project:remove'"
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
            {{ isChildProject ? '返回重大项目' : '返回项目墙' }}
          </button>
        </div>

        <div class="project-hero__body">
          <div class="project-hero__intro">
            <div class="project-hero__title-row">
              <h1 class="project-hero__title">{{ detail.name }}</h1>
              <span class="status-pill" :class="`status-pill--${detail.status}`">{{ statusMap[detail.status] }}</span>
              <span class="scale-pill" :class="`scale-pill--${detail.scale || 'NORMAL'}`">
                {{ scaleMap[detail.scale] || '常规' }}
              </span>
              <span v-if="isChildProject" class="meta-chip">所属 · {{ detail.parentName || '重大项目' }}</span>
              <span class="project-code">{{ detail.code || '未编号' }}</span>
            </div>
            <div class="meta-chips">
              <span class="meta-chip">
                <el-icon :size="14"><User /></el-icon>
                {{ detail.ownerName || '未指定' }}
              </span>
              <span v-if="detail.startDate || detail.endDate || detail.actualEndDate" class="meta-chip">
                <el-icon :size="14"><Calendar /></el-icon>
                预计 {{ detail.startDate || '—' }} ~ {{ detail.endDate || '—' }}
                <template v-if="detail.actualEndDate"> · 实际 {{ detail.actualEndDate }}</template>
              </span>
            </div>
          </div>

          <div class="metric-bar" v-if="!isMajorShell">
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
          <div v-else class="metric-bar">
            <div class="metric-item">
              <div class="metric-copy">
                <span class="metric-label">小项目数</span>
                <span class="metric-value">{{ children.length || detail.childCount || 0 }}</span>
              </div>
            </div>
          </div>

          <div class="project-hero__ops">
            <el-button
              v-permission="'project:edit'"
              class="icon-btn"
              text
              aria-label="编辑"
              title="编辑"
              @click="open(detail)"
            >
              <el-icon :size="16"><EditPen /></el-icon>
            </el-button>
            <el-button
              v-permission="'project:remove'"
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
          <el-tab-pane v-if="isMajorShell" label="小项目" name="children">
            <div class="panel-toolbar">
              <p class="form-tip" style="margin: 0">重大项目为外壳：任务与账款在小项目内；此处管理小项目列表。</p>
              <div class="panel-toolbar__right">
                <el-button v-permission="'project:add'" size="small" type="primary" @click="openCreateChild">
                  新建小项目
                </el-button>
              </div>
            </div>
            <div v-if="children.length" class="child-grid">
              <article
                v-for="row in children"
                :key="row.id"
                class="child-card"
                role="button"
                tabindex="0"
                @click="enterProject(row)"
                @keyup.enter="enterProject(row)"
              >
                <div class="child-card__head">
                  <h4>{{ row.name }}</h4>
                  <span class="status-pill status-pill--sm" :class="`status-pill--${row.status}`">
                    {{ statusMap[row.status] }}
                  </span>
                </div>
                <p>{{ row.code || '未编号' }} · 负责人 {{ row.ownerName || '—' }}</p>
                <span class="project-card__go">进入详情</span>
              </article>
            </div>
            <el-empty v-else description="暂无小项目，请先新建" />
          </el-tab-pane>

          <el-tab-pane v-if="!isMajorShell" label="看板" name="board">
            <TaskKanban
              v-if="activeProjectId && detailTab === 'board'"
              ref="kanbanRef"
              :project-id="activeProjectId"
              @open-task="openTaskDetail"
              @create-task="createTask"
              @changed="onTaskSaved"
            />
          </el-tab-pane>

          <el-tab-pane v-if="!isMajorShell" label="任务列表" name="tasks">
            <div class="panel-toolbar">
              <div class="filter-pills">
                <button
                  v-for="item in [
                    { label: '全部', value: undefined },
                    { label: '待办', value: 0 },
                    { label: '进行中', value: 1 },
                    { label: '已完成', value: 2 },
                    { label: '已关闭', value: 3 },
                  ]"
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
                <el-button v-permission="'project:task:add'" size="small" type="primary" @click="createTask">新建任务</el-button>
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
                <el-table-column label="参与人员" min-width="160" show-overflow-tooltip>
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
                <span class="info-label">所属公司</span>
                <span class="info-value">{{ detail.companyName || '—' }}</span>
              </div>
              <div class="info-item">
                <span class="info-label">负责人</span>
                <span class="info-value">{{ detail.ownerName || '—' }}</span>
              </div>
              <div class="info-item info-item--full">
                <span class="info-label">项目参与人</span>
                <span class="info-value">{{
                  detail.members?.length
                    ? detail.members.map((m: any) => formatMemberLabel(m)).join('、')
                    : '无'
                }}</span>
              </div>
              <div class="info-item">
                <span class="info-label">状态</span>
                <span class="info-value">{{ statusMap[detail.status] }}</span>
              </div>
              <div class="info-item">
                <span class="info-label">规模</span>
                <span class="info-value">{{ scaleMap[detail.scale] || '常规' }}</span>
              </div>
              <div class="info-item">
                <span class="info-label">预计周期</span>
                <span class="info-value">{{ detail.startDate || '—' }} ~ {{ detail.endDate || '—' }}</span>
              </div>
              <div class="info-item">
                <span class="info-label">实际结束</span>
                <span class="info-value">{{ detail.actualEndDate || '—' }}</span>
              </div>
              <div class="info-item info-item--full">
                <span class="info-label">说明</span>
                <span class="info-value desc-text">{{ detail.description || '暂无说明' }}</span>
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane label="操作记录" name="flows">
            <el-timeline v-if="projectFlows.length" class="project-flow-timeline">
              <el-timeline-item
                v-for="flow in projectFlows"
                :key="flow.id"
                :timestamp="flow.createTime ? String(flow.createTime).replace('T', ' ').slice(0, 16) : ''"
                placement="top"
              >
                <div class="flow-item">
                  <div class="flow-item__title">
                    {{ flow.actionLabel || flow.action }}
                    <span class="flow-item__op">{{ flow.operatorName || '系统' }}</span>
                  </div>
                  <div class="flow-item__summary">{{ flow.summary || '—' }}</div>
                  <div v-if="flow.approvalId" class="flow-item__meta">关联审批 #{{ flow.approvalId }}</div>
                </div>
              </el-timeline-item>
            </el-timeline>
            <el-empty v-else description="暂无操作记录" />
          </el-tab-pane>
        </el-tabs>
      </section>
    </template>

    <el-dialog
      v-model="dialog"
      :title="isEdit ? '编辑项目' : creatingChild || form.parentId ? '新建小项目' : '新建项目'"
      width="640px"
      :close-on-click-modal="false"
      @closed="creatingChild = false"
    >
      <el-form label-width="110px">
        <el-form-item v-if="creatingChild || form.parentId" label="所属重大">
          <span class="info-value">{{
            creatingChild ? (detail?.name || '—') : (detail?.parentName || form.parentName || '—')
          }}</span>
        </el-form-item>
        <el-form-item label="所属公司" required>
          <el-select
            v-if="!isEdit && !creatingChild && !form.parentId"
            v-model="form.companyId"
            filterable
            placeholder="选择公司"
            style="width: 100%"
            @change="onCompanyChange"
          >
            <el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
          <span v-else class="info-value">{{ form.companyName || detail?.companyName || '—' }}</span>
        </el-form-item>
        <el-form-item label="名称" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="编号">
          <el-input
            v-model="form.code"
            disabled
            :placeholder="isEdit ? '—' : (form.companyId ? '生成中…' : '请先选择所属公司')"
          />
        </el-form-item>
        <el-form-item label="负责人">
          <el-select v-model="form.ownerId" filterable clearable style="width: 100%" :disabled="!isEdit && !form.companyId">
            <el-option v-for="u in users" :key="u.id" :label="u.nickname || u.username" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="项目参与人">
          <div class="participant-editor" :class="{ 'is-disabled': !isEdit && !form.companyId }">
            <div v-for="(row, index) in form.participants" :key="index" class="participant-row">
              <el-select
                v-model="row.userId"
                filterable
                clearable
                placeholder="选择人员"
                style="width: 42%"
                :disabled="!isEdit && !form.companyId"
              >
                <el-option v-for="u in users" :key="u.id" :label="u.nickname || u.username" :value="u.id" />
              </el-select>
              <el-input
                v-model="row.layer"
                maxlength="64"
                show-word-limit
                placeholder="职责（必填）"
                style="flex: 1"
                :disabled="!isEdit && !form.companyId"
              />
              <el-button link type="danger" :disabled="form.participants.length <= 1" @click="removeParticipantRow(index)">删除</el-button>
            </div>
            <el-button link type="primary" :disabled="!isEdit && !form.companyId" @click="addParticipantRow">添加参与人</el-button>
          </div>
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="开始日期">
              <el-date-picker v-model="form.startDate" value-format="YYYY-MM-DD" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="预计结束时间">
              <el-date-picker v-model="form.endDate" value-format="YYYY-MM-DD" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="实际结束时间">
          <el-date-picker v-model="form.actualEndDate" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 100%">
            <el-option :value="0" label="筹备" />
            <el-option :value="1" label="进行中" />
            <el-option :value="2" label="已完成" />
            <el-option :value="3" label="已关闭" />
          </el-select>
        </el-form-item>
        <el-form-item label="规模" required>
          <template v-if="creatingChild || form.parentId">
            <span class="info-value">重点（小项目固定）</span>
          </template>
          <template v-else>
            <el-select v-model="form.scale" style="width: 100%" placeholder="选择规模" :disabled="!!form.parentId">
              <el-option
                v-for="opt in scaleOptions"
                :key="opt.value"
                :value="opt.value"
                :label="`${opt.label}（${opt.tip}）`"
              />
            </el-select>
            <div v-if="!isEdit" class="form-tip">常规创建免审；重点/重大需审批，若你是该公司唯一审批人则直接创建。重大项目内再建小项目。</div>
            <div v-else class="form-tip">规模不变或降到常规直存；改为重点/重大（含互切）需审批。</div>
          </template>
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
.participant-editor {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.participant-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.filter-keyword--wide {
  width: 220px;
}

.filter-company {
  width: 200px;
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

.project-card__tags {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
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

.child-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 12px;
  margin-top: 12px;
}
.child-card {
  padding: 16px;
  cursor: pointer;
  background: var(--kk-glass-bg);
  border: 1px solid var(--kk-glass-border);
  border-radius: var(--kk-radius);
  box-shadow: var(--kk-glass-shadow);
  backdrop-filter: var(--kk-glass-blur);
  -webkit-backdrop-filter: var(--kk-glass-blur);
}
.child-card:hover {
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.06);
}
.child-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.child-card__head h4 {
  margin: 0;
  font-size: 15px;
}
.child-card p {
  margin: 8px 0 12px;
  font-size: 12px;
  color: var(--kk-text-muted);
}
.status-pill--sm {
  font-size: 11px;
  padding: 2px 8px;
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

.scale-pill {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
}
.scale-pill--sm {
  padding: 1px 8px;
  font-size: 11px;
}
.scale-pill--NORMAL { background: #f1f5f9; color: #64748b; }
.scale-pill--KEY { background: #dbeafe; color: #1d4ed8; }
.scale-pill--MAJOR { background: #fee2e2; color: #b91c1c; }

.form-tip {
  margin-top: 6px;
  font-size: 12px;
  color: #94a3b8;
  line-height: 1.4;
}

.project-flow-timeline {
  padding: 8px 12px 0;
}
.flow-item__title {
  display: flex;
  align-items: baseline;
  gap: 10px;
  font-weight: 600;
  color: #0f172a;
}
.flow-item__op {
  font-size: 12px;
  font-weight: 500;
  color: #64748b;
}
.flow-item__summary {
  margin-top: 4px;
  font-size: 13px;
  color: #334155;
}
.flow-item__meta {
  margin-top: 4px;
  font-size: 12px;
  color: #94a3b8;
}

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
