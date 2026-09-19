<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bizApi } from '@/api/biz'
import { sysApi } from '@/api/system'
import {
  ticketApi,
  ticketStatusLabel,
  ticketTypeLabel,
  ticketUrgencyLabel,
  TICKET_STATUS_OPTIONS,
  TICKET_TYPE_OPTIONS,
  TICKET_URGENCY_OPTIONS,
  type TicketVO,
} from '@/api/ticket'
import TicketReplyDialog from '@/components/ticket/TicketReplyDialog.vue'
import TicketRichEditor from '@/components/ticket/TicketRichEditor.vue'
import TicketSubmitDialog from '@/components/ticket/TicketSubmitDialog.vue'

const companies = ref<any[]>([])
const list = ref<TicketVO[]>([])
const total = ref(0)
const loading = ref(false)
const selected = ref<TicketVO[]>([])
const activeTab = ref('orders')

const query = reactive({
  page: 1,
  pageSize: 20,
  companyId: undefined as number | undefined,
  projectId: undefined as number | undefined,
  title: '',
  type: '',
  status: '',
  urgency: '',
  developerId: undefined as number | undefined,
  cycleId: undefined as number | undefined,
})

const submitOpen = ref(false)
const replyOpen = ref(false)
const replyTicket = ref<TicketVO | null>(null)
const detailOpen = ref(false)
const detail = ref<TicketVO | null>(null)
const editOpen = ref(false)
const editForm = reactive({
  id: 0,
  companyId: undefined as number | undefined,
  projectId: undefined as number | undefined,
  clearProject: false,
  originalProjectId: undefined as number | undefined,
  title: '',
  type: 'bug',
  urgency: 'normal',
  description: '',
})
const editProjectName = ref('')
const allProjects = ref<any[]>([])
const filterProjects = computed(() => {
  if (!query.companyId) return allProjects.value
  return allProjects.value.filter((p) => Number(p.companyId) === Number(query.companyId))
})
const editProjects = computed(() => {
  if (!editForm.companyId) return []
  const list = allProjects.value.filter((p) => Number(p.companyId) === Number(editForm.companyId))
  // 已关联但当前列表不可见（删除/无权限）时，保留选项避免误清空
  if (
    editForm.projectId != null &&
    !list.some((p) => Number(p.id) === Number(editForm.projectId))
  ) {
    return [
      {
        id: editForm.projectId,
        name: editProjectName.value || `项目 #${editForm.projectId}`,
        code: '',
        companyId: editForm.companyId,
      },
      ...list,
    ]
  }
  return list
})
const batchOpen = ref(false)
const batchForm = reactive({
  type: '' as string,
  urgency: '' as string,
  status: '' as string,
  expectedCompleteDate: '' as string,
  clearExpectedCompleteDate: false,
  enableType: false,
  enableUrgency: false,
  enableStatus: false,
  enableDate: false,
})
const assignOpen = ref(false)
const assignForm = reactive({
  id: 0,
  developerIds: [] as number[],
  cycleId: undefined as number | undefined,
  clearCycle: false,
  status: '' as string,
})
const progressOpen = ref(false)
const progressForm = reactive({ id: 0, progress: 0, status: '' })

const developers = ref<any[]>([])
const cycles = ref<any[]>([])
const companyUsers = ref<any[]>([])
const newDev = reactive({ name: '', role: '', sysUserId: undefined as number | undefined })
const newCycle = reactive({
  name: '',
  startDate: '',
  endDate: '',
  status: 'planning',
  description: '',
})

const typeTone: Record<string, 'danger' | 'primary' | 'info'> = {
  bug: 'danger',
  requirement: 'primary',
  other: 'info',
}

function tagTypeOf(type?: string) {
  return typeTone[type || ''] || 'info'
}

function userLabel(u: any) {
  if (!u) return '—'
  return u.nickname || u.username || String(u.id)
}

async function loadCompanies() {
  companies.value = await sysApi.myCompanies()
  if (!query.companyId && companies.value.length === 1) {
    query.companyId = companies.value[0].id
  }
}

async function loadProjects() {
  allProjects.value = await bizApi.projectList().catch(() => [])
}

async function loadMeta() {
  if (!query.companyId) {
    developers.value = []
    cycles.value = []
    companyUsers.value = []
    return
  }
  const [devs, cyc, users] = await Promise.all([
    ticketApi.developerList(query.companyId).catch(() => []),
    ticketApi.cycleList(query.companyId).catch(() => []),
    sysApi.userList({ companyId: query.companyId }).catch(() => []),
  ])
  developers.value = devs
  cycles.value = cyc
  companyUsers.value = users
}

async function load() {
  loading.value = true
  try {
    const res = await ticketApi.page({
      page: query.page,
      pageSize: query.pageSize,
      companyId: query.companyId,
      projectId: query.projectId,
      title: query.title || undefined,
      type: query.type || undefined,
      status: query.status || undefined,
      urgency: query.urgency || undefined,
      developerId: query.developerId,
      cycleId: query.cycleId,
    })
    list.value = res.list || []
    total.value = Number(res.total || 0)
  } finally {
    loading.value = false
  }
}

watch(
  () => query.companyId,
  async () => {
    query.page = 1
    query.projectId = undefined
    await loadMeta()
    await load()
  },
)

function onSelection(rows: TicketVO[]) {
  selected.value = rows
}

async function openDetail(row: TicketVO) {
  detail.value = await ticketApi.detail(row.id)
  detailOpen.value = true
}

function openReply(row: TicketVO) {
  replyTicket.value = row
  replyOpen.value = true
}

async function openEdit(row: TicketVO) {
  const d = await ticketApi.detail(row.id)
  editForm.id = d.id
  editForm.companyId = d.companyId
  editForm.projectId = d.projectId
  editForm.originalProjectId = d.projectId
  editForm.clearProject = false
  editProjectName.value = d.projectName || ''
  editForm.title = d.title
  editForm.type = d.type
  editForm.urgency = d.urgency
  editForm.description = d.description || ''
  editOpen.value = true
}

function onEditProjectChange(v: number | undefined) {
  editForm.clearProject = v == null
}

async function saveEdit() {
  const payload: Record<string, unknown> = {
    title: editForm.title,
    type: editForm.type,
    urgency: editForm.urgency,
    description: editForm.description,
  }
  const nextId = editForm.clearProject ? null : editForm.projectId ?? null
  const prevId = editForm.originalProjectId ?? null
  if (nextId == null && prevId != null) {
    payload.clearProject = true
  } else if (nextId != null && Number(nextId) !== Number(prevId)) {
    payload.projectId = nextId
  }
  await ticketApi.update(editForm.id, payload)
  ElMessage.success('已保存')
  editOpen.value = false
  await load()
}

function openBatch() {
  if (!selected.value.length) {
    ElMessage.warning('请先勾选工单')
    return
  }
  batchForm.type = ''
  batchForm.urgency = ''
  batchForm.status = ''
  batchForm.expectedCompleteDate = ''
  batchForm.clearExpectedCompleteDate = false
  batchForm.enableType = false
  batchForm.enableUrgency = false
  batchForm.enableStatus = false
  batchForm.enableDate = false
  batchOpen.value = true
}

async function saveBatch() {
  if (!batchForm.enableType && !batchForm.enableUrgency && !batchForm.enableStatus && !batchForm.enableDate) {
    ElMessage.warning('请至少勾选一个要修改的字段')
    return
  }
  const data: Record<string, unknown> = { ids: selected.value.map((r) => r.id) }
  if (batchForm.enableType) data.type = batchForm.type
  if (batchForm.enableUrgency) data.urgency = batchForm.urgency
  if (batchForm.enableStatus) data.status = batchForm.status
  if (batchForm.enableDate) {
    if (batchForm.clearExpectedCompleteDate) data.clearExpectedCompleteDate = true
    else if (batchForm.expectedCompleteDate) data.expectedCompleteDate = batchForm.expectedCompleteDate
  }
  await ticketApi.batchUpdate(data)
  ElMessage.success('批量更新成功')
  batchOpen.value = false
  await load()
}

async function removeOne(row: TicketVO) {
  await ElMessageBox.confirm(`确认删除工单「${row.title}」？`, '删除确认', { type: 'warning' })
  await ticketApi.remove(row.id)
  ElMessage.success('已删除')
  await load()
}

async function removeBatch() {
  if (!selected.value.length) {
    ElMessage.warning('请先勾选工单')
    return
  }
  await ElMessageBox.confirm(`确认删除选中的 ${selected.value.length} 条工单？`, '批量删除', { type: 'warning' })
  await ticketApi.batchRemove(selected.value.map((r) => r.id))
  ElMessage.success('已删除')
  await load()
}

async function openAssign(row: TicketVO) {
  assignForm.id = row.id
  assignForm.developerIds = [...(row.developerIds || [])]
  assignForm.cycleId = row.cycleId
  assignForm.clearCycle = false
  assignForm.status = ''
  if (row.companyId) {
    const [devs, cyc] = await Promise.all([
      ticketApi.developerList(row.companyId, 1).catch(() => []),
      ticketApi.cycleList(row.companyId).catch(() => []),
    ])
    developers.value = devs
    cycles.value = cyc
  }
  assignOpen.value = true
}

async function saveAssign() {
  await ticketApi.assign(assignForm.id, {
    developerIds: assignForm.developerIds,
    cycleId: assignForm.clearCycle ? undefined : assignForm.cycleId,
    clearCycle: assignForm.clearCycle,
    status: assignForm.status || undefined,
  })
  ElMessage.success('已分配')
  assignOpen.value = false
  await load()
}

function openProgress(row: TicketVO) {
  progressForm.id = row.id
  progressForm.progress = row.progress || 0
  progressForm.status = row.status || ''
  progressOpen.value = true
}

async function saveProgress() {
  await ticketApi.progress(progressForm.id, {
    progress: progressForm.progress,
    status: progressForm.status || undefined,
  })
  ElMessage.success('进度已更新')
  progressOpen.value = false
  await load()
}

async function addDeveloper() {
  if (!query.companyId || !newDev.name.trim()) {
    ElMessage.warning('请填写姓名并选择公司')
    return
  }
  await ticketApi.createDeveloper({
    companyId: query.companyId,
    name: newDev.name.trim(),
    role: newDev.role || undefined,
    sysUserId: newDev.sysUserId,
  })
  newDev.name = ''
  newDev.role = ''
  newDev.sysUserId = undefined
  ElMessage.success('已添加')
  await loadMeta()
}

async function bindDeveloperUser(row: any, sysUserId?: number | null) {
  const next = sysUserId == null ? null : Number(sysUserId)
  await ticketApi.updateDeveloper(row.id, { sysUserId: next })
  row.sysUserId = next ?? undefined
  ElMessage.success(next ? '已绑定登录账号' : '已解除绑定')
}

async function toggleDev(row: any) {
  await ticketApi.updateDeveloperStatus(row.id, row.status === 1 ? 0 : 1)
  await loadMeta()
}

async function removeDev(row: any) {
  await ElMessageBox.confirm(`删除开发人员「${row.name}」？`, '确认', { type: 'warning' })
  await ticketApi.deleteDeveloper(row.id)
  await loadMeta()
}

async function addCycle() {
  if (!query.companyId || !newCycle.name || !newCycle.startDate || !newCycle.endDate) {
    ElMessage.warning('请完善周期信息')
    return
  }
  await ticketApi.createCycle({
    companyId: query.companyId,
    name: newCycle.name,
    startDate: newCycle.startDate,
    endDate: newCycle.endDate,
    status: newCycle.status,
    description: newCycle.description || undefined,
  })
  newCycle.name = ''
  newCycle.startDate = ''
  newCycle.endDate = ''
  newCycle.status = 'planning'
  newCycle.description = ''
  ElMessage.success('已创建周期')
  await loadMeta()
}

async function setCycleActive(row: any) {
  await ticketApi.updateCycle(row.id, { status: 'active' })
  ElMessage.success('已设为进行中')
  await loadMeta()
}

onMounted(async () => {
  await loadCompanies()
  await loadProjects()
  await loadMeta()
  await load()
})
</script>

<template>
  <div class="page-stack">
  <div class="page-card">
    <div class="page-head">
      <div>
        <h2 class="title">工单管理</h2>
        <p class="sub">公司内 BUG / 需求协作，与项目任务相互独立</p>
      </div>
      <div class="head-actions">
        <el-button type="primary" @click="submitOpen = true">提交工单</el-button>
        <el-button @click="openBatch">批量设置</el-button>
        <el-button type="danger" plain @click="removeBatch">批量删除</el-button>
      </div>
    </div>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="工单列表" name="orders">
        <div class="filters">
          <el-select v-model="query.companyId" clearable placeholder="公司" style="width: 160px">
            <el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
          <el-select v-model="query.projectId" clearable filterable placeholder="项目" style="width: 180px">
            <el-option
              v-for="p in filterProjects"
              :key="p.id"
              :label="p.code ? `${p.name}（${p.code}）` : p.name"
              :value="p.id"
            />
          </el-select>
          <el-input v-model="query.title" clearable placeholder="标题" style="width: 180px" @keyup.enter="load" />
          <el-select v-model="query.type" clearable placeholder="类型" style="width: 120px">
            <el-option v-for="o in TICKET_TYPE_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <el-select v-model="query.status" clearable placeholder="状态" style="width: 120px">
            <el-option v-for="o in TICKET_STATUS_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <el-select v-model="query.urgency" clearable placeholder="紧急程度" style="width: 120px">
            <el-option v-for="o in TICKET_URGENCY_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <el-select v-model="query.developerId" clearable placeholder="开发人员" style="width: 140px">
            <el-option v-for="d in developers" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
          <el-select v-model="query.cycleId" clearable placeholder="周期" style="width: 160px">
            <el-option v-for="c in cycles" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
          <el-button type="primary" @click="query.page = 1; load()">查询</el-button>
        </div>

        <el-table v-loading="loading" :data="list" row-key="id" @selection-change="onSelection">
          <el-table-column type="selection" width="48" />
          <el-table-column prop="ticketNo" label="编号" min-width="150" />
          <el-table-column prop="title" label="标题" min-width="160" show-overflow-tooltip />
          <el-table-column prop="projectName" label="项目" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ row.projectName || '—' }}</template>
          </el-table-column>
          <el-table-column label="类型" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="tagTypeOf(row.type)" effect="plain">{{ ticketTypeLabel(row.type) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="紧急" width="80">
            <template #default="{ row }">{{ ticketUrgencyLabel(row.urgency) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">{{ ticketStatusLabel(row.status) }}</template>
          </el-table-column>
          <el-table-column label="进度" width="100">
            <template #default="{ row }">
              <el-progress :percentage="row.progress || 0" :stroke-width="8" />
            </template>
          </el-table-column>
          <el-table-column prop="expectedCompleteDate" label="预计完成" width="110" />
          <el-table-column prop="developerNames" label="开发人员" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">{{ row.developerNames || '—' }}</template>
          </el-table-column>
          <el-table-column prop="submitterName" label="提交人" width="100" />
          <el-table-column prop="createTime" label="时间" width="160" />
          <el-table-column label="回复" width="80">
            <template #default="{ row }">
              <el-badge :is-dot="row.replySummary?.hasUnreadReply" :hidden="!row.replySummary?.hasUnreadReply">
                <el-button link type="primary" @click="openReply(row)">{{ row.replySummary?.replyCount || 0 }}</el-button>
              </el-badge>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="280" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link type="primary" @click="openAssign(row)">分配</el-button>
              <el-button link type="primary" @click="openProgress(row)">进度</el-button>
              <el-button link type="danger" @click="removeOne(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div v-if="total > query.pageSize" class="page-footer">
          <el-pagination
            v-model:current-page="query.page"
            v-model:page-size="query.pageSize"
            layout="total, prev, pager, next"
            :total="total"
            @current-change="load"
          />
        </div>
      </el-tab-pane>

      <el-tab-pane label="开发人员" name="developers">
        <div class="meta-bar">
          <el-select v-model="query.companyId" placeholder="公司" style="width: 180px">
            <el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
          <el-input v-model="newDev.name" placeholder="姓名" style="width: 140px" />
          <el-input v-model="newDev.role" placeholder="角色（可选）" style="width: 140px" />
          <el-select
            v-model="newDev.sysUserId"
            clearable
            filterable
            placeholder="绑定登录账号（被分配人可见）"
            style="width: 220px"
          >
            <el-option
              v-for="u in companyUsers"
              :key="u.id"
              :label="userLabel(u)"
              :value="u.id"
            />
          </el-select>
          <el-button type="primary" @click="addDeveloper">添加</el-button>
        </div>
        <el-table :data="developers">
          <el-table-column prop="name" label="姓名" />
          <el-table-column prop="role" label="角色" />
          <el-table-column label="绑定账号" min-width="200">
            <template #default="{ row }">
              <el-select
                :model-value="row.sysUserId"
                clearable
                filterable
                placeholder="未绑定"
                style="width: 100%"
                @change="(v: number | undefined | null) => bindDeveloperUser(row, v)"
              >
                <el-option
                  v-for="u in companyUsers"
                  :key="u.id"
                  :label="userLabel(u)"
                  :value="u.id"
                />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">{{ row.status === 1 ? '启用' : '禁用' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="180">
            <template #default="{ row }">
              <el-button link type="primary" @click="toggleDev(row)">{{ row.status === 1 ? '禁用' : '启用' }}</el-button>
              <el-button link type="danger" @click="removeDev(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="开发周期" name="cycles">
        <div class="meta-bar">
          <el-select v-model="query.companyId" placeholder="公司" style="width: 160px">
            <el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
          <el-input v-model="newCycle.name" placeholder="周期名称" style="width: 140px" />
          <el-date-picker v-model="newCycle.startDate" type="date" value-format="YYYY-MM-DD" placeholder="开始" />
          <el-date-picker v-model="newCycle.endDate" type="date" value-format="YYYY-MM-DD" placeholder="结束" />
          <el-select v-model="newCycle.status" style="width: 120px">
            <el-option label="规划中" value="planning" />
            <el-option label="进行中" value="active" />
            <el-option label="已结束" value="completed" />
          </el-select>
          <el-button type="primary" @click="addCycle">创建</el-button>
        </div>
        <el-table :data="cycles">
          <el-table-column prop="name" label="名称" />
          <el-table-column prop="startDate" label="开始" width="120" />
          <el-table-column prop="endDate" label="结束" width="120" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag v-if="row.status === 'active'" type="success" size="small">进行中</el-tag>
              <span v-else-if="row.status === 'planning'">规划中</span>
              <span v-else>已结束</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button v-if="row.status !== 'active'" link type="primary" @click="setCycleActive(row)">设为进行中</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>

  <TicketSubmitDialog v-model="submitOpen" @success="load" />
  <TicketReplyDialog
    v-model="replyOpen"
    :ticket-id="replyTicket?.id"
    :ticket-title="replyTicket?.title"
    @closed="load"
  />

  <el-drawer v-model="detailOpen" title="工单详情" size="520px">
    <template v-if="detail">
      <p><b>编号：</b>{{ detail.ticketNo }}</p>
      <p><b>标题：</b>{{ detail.title }}</p>
      <p><b>公司：</b>{{ detail.companyName }}</p>
      <p><b>项目：</b>{{ detail.projectName || '—' }}</p>
      <p><b>类型 / 紧急 / 状态：</b>{{ ticketTypeLabel(detail.type) }} / {{ ticketUrgencyLabel(detail.urgency) }} / {{ ticketStatusLabel(detail.status) }}</p>
      <p><b>开发人员：</b>{{ detail.developerNames || '—' }}</p>
      <p><b>周期：</b>{{ detail.cycleName || '—' }}</p>
      <div class="desc" v-html="detail.description" />
    </template>
  </el-drawer>

  <el-dialog v-model="editOpen" title="编辑工单" width="640px" destroy-on-close>
    <el-form label-width="80px">
      <el-form-item label="标题"><el-input v-model="editForm.title" /></el-form-item>
      <el-form-item label="项目">
        <el-select
          v-model="editForm.projectId"
          clearable
          filterable
          placeholder="可选，关联项目"
          style="width: 100%"
          @clear="editForm.clearProject = true"
          @change="onEditProjectChange"
        >
          <el-option
            v-for="p in editProjects"
            :key="p.id"
            :label="p.code ? `${p.name}（${p.code}）` : p.name"
            :value="p.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="类型">
        <el-select v-model="editForm.type"><el-option v-for="o in TICKET_TYPE_OPTIONS" :key="o.value" :label="o.label" :value="o.value" /></el-select>
      </el-form-item>
      <el-form-item label="紧急">
        <el-select v-model="editForm.urgency"><el-option v-for="o in TICKET_URGENCY_OPTIONS" :key="o.value" :label="o.label" :value="o.value" /></el-select>
      </el-form-item>
      <el-form-item label="描述"><TicketRichEditor v-model="editForm.description" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="editOpen = false">取消</el-button>
      <el-button type="primary" @click="saveEdit">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="batchOpen" title="批量设置" width="480px">
    <el-form label-width="100px">
      <el-form-item label="类型">
        <el-checkbox v-model="batchForm.enableType">修改</el-checkbox>
        <el-select v-model="batchForm.type" :disabled="!batchForm.enableType" style="margin-left: 8px; width: 160px">
          <el-option v-for="o in TICKET_TYPE_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="紧急程度">
        <el-checkbox v-model="batchForm.enableUrgency">修改</el-checkbox>
        <el-select v-model="batchForm.urgency" :disabled="!batchForm.enableUrgency" style="margin-left: 8px; width: 160px">
          <el-option v-for="o in TICKET_URGENCY_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-checkbox v-model="batchForm.enableStatus">修改</el-checkbox>
        <el-select v-model="batchForm.status" :disabled="!batchForm.enableStatus" style="margin-left: 8px; width: 160px">
          <el-option v-for="o in TICKET_STATUS_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="预计完成">
        <el-checkbox v-model="batchForm.enableDate">修改</el-checkbox>
        <el-date-picker v-model="batchForm.expectedCompleteDate" type="date" value-format="YYYY-MM-DD" :disabled="!batchForm.enableDate || batchForm.clearExpectedCompleteDate" style="margin-left: 8px" />
        <el-checkbox v-model="batchForm.clearExpectedCompleteDate" :disabled="!batchForm.enableDate" style="margin-left: 8px">清空</el-checkbox>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="batchOpen = false">取消</el-button>
      <el-button type="primary" @click="saveBatch">确定</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="assignOpen" title="分配" width="480px">
    <el-form label-width="90px">
      <el-form-item label="开发人员">
        <el-select v-model="assignForm.developerIds" multiple style="width: 100%">
          <el-option
            v-for="d in developers.filter((x) => x.status === 1)"
            :key="d.id"
            :label="d.sysUserId ? d.name : `${d.name}（未绑定账号）`"
            :value="d.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="周期">
        <el-select v-model="assignForm.cycleId" clearable :disabled="assignForm.clearCycle" style="width: 100%">
          <el-option v-for="c in cycles" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-checkbox v-model="assignForm.clearCycle" style="margin-top: 8px">解绑周期</el-checkbox>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="assignForm.status" clearable style="width: 100%">
          <el-option v-for="o in TICKET_STATUS_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="assignOpen = false">取消</el-button>
      <el-button type="primary" @click="saveAssign">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="progressOpen" title="更新进度" width="420px">
    <el-form label-width="80px">
      <el-form-item label="进度">
        <el-slider v-model="progressForm.progress" :max="100" show-input />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="progressForm.status" clearable style="width: 100%">
          <el-option v-for="o in TICKET_STATUS_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="progressOpen = false">取消</el-button>
      <el-button type="primary" @click="saveProgress">保存</el-button>
    </template>
  </el-dialog>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 12px;
}
.title {
  margin: 0;
  font-size: 20px;
  font-weight: 650;
}
.sub {
  margin: 6px 0 0;
  color: #71717a;
  font-size: 13px;
}
.head-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.filters,
.meta-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 14px;
}
.page-footer {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
.desc {
  margin-top: 12px;
  line-height: 1.6;
}
.desc :deep(img) {
  max-width: 100%;
  border-radius: 8px;
}
</style>
