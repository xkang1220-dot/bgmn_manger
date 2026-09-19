<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { bizApi } from '@/api/biz'
import { sysApi } from '@/api/system'
import {
  ticketApi,
  ticketStatusLabel,
  ticketTypeLabel,
  ticketUrgencyLabel,
  TICKET_STATUS_OPTIONS,
  type TicketVO,
} from '@/api/ticket'
import TicketReplyDialog from '@/components/ticket/TicketReplyDialog.vue'
import TicketSubmitDialog from '@/components/ticket/TicketSubmitDialog.vue'

const loading = ref(false)
const list = ref<TicketVO[]>([])
const total = ref(0)
const stats = reactive({ total: 0, completed: 0, rate: 0, unreadReplyTicketCount: 0 })
const query = reactive({
  page: 1,
  pageSize: 20,
  companyId: undefined as number | undefined,
  projectId: undefined as number | undefined,
})
const companies = ref<any[]>([])
const allProjects = ref<any[]>([])
const syncingFilter = ref(false)
const submitOpen = ref(false)
const replyOpen = ref(false)
const replyTicket = ref<TicketVO | null>(null)
const detailOpen = ref(false)
const detail = ref<TicketVO | null>(null)
const progressOpen = ref(false)
const progressForm = reactive({ id: 0, progress: 0, status: '' })

const filterProjects = computed(() => {
  if (!query.companyId) return allProjects.value
  return allProjects.value.filter((p) => Number(p.companyId) === Number(query.companyId))
})

async function loadMeta() {
  const [cos, projects] = await Promise.all([
    sysApi.myCompanies().catch(() => []),
    bizApi.projectList().catch(() => []),
  ])
  companies.value = cos
  allProjects.value = projects
}

async function load() {
  loading.value = true
  try {
    const res = await ticketApi.my({
      page: query.page,
      pageSize: query.pageSize,
      ...(query.companyId != null ? { companyId: query.companyId } : {}),
      ...(query.projectId != null ? { projectId: query.projectId } : {}),
    })
    Object.assign(stats, res.stats || {})
    list.value = res.page?.list || []
    total.value = Number(res.page?.total || 0)
  } finally {
    loading.value = false
  }
}

function onFilter() {
  if (syncingFilter.value) return
  query.page = 1
  load()
}

async function onCompanyChange() {
  syncingFilter.value = true
  query.projectId = undefined
  await nextTick()
  syncingFilter.value = false
  onFilter()
}

function onProjectChange() {
  onFilter()
}

async function openDetail(row: TicketVO) {
  detail.value = await ticketApi.detail(row.id)
  detailOpen.value = true
}

function openReply(row: TicketVO) {
  replyTicket.value = row
  replyOpen.value = true
}

function openProgress(row: TicketVO | null | undefined) {
  if (!row?.id) return
  progressForm.id = row.id
  progressForm.progress = row.progress || 0
  progressForm.status = row.status || ''
  progressOpen.value = true
}

async function saveProgress() {
  if (!progressForm.id) return
  await ticketApi.progress(progressForm.id, {
    progress: progressForm.progress,
    status: progressForm.status || undefined,
  })
  ElMessage.success('进度已更新')
  progressOpen.value = false
  await load()
  if (detailOpen.value && detail.value?.id === progressForm.id) {
    detail.value = await ticketApi.detail(progressForm.id)
  }
}

onMounted(async () => {
  await loadMeta()
  await load()
})
</script>

<template>
  <div class="page-stack">
  <div class="page-card">
    <div class="page-head">
      <div>
        <h2 class="title">我的工单</h2>
        <p class="sub">我提交的，以及指派给我的工单（需在开发人员中绑定登录账号）</p>
      </div>
      <div class="head-filters">
        <el-select v-model="query.companyId" clearable placeholder="公司" style="width: 160px" @change="onCompanyChange">
          <el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-select
          v-model="query.projectId"
          clearable
          filterable
          placeholder="项目"
          style="width: 180px"
          @change="onProjectChange"
        >
          <el-option
            v-for="p in filterProjects"
            :key="p.id"
            :label="p.code ? `${p.name}（${p.code}）` : p.name"
            :value="p.id"
          />
        </el-select>
        <el-button type="primary" @click="submitOpen = true">提交工单</el-button>
      </div>
    </div>

    <div class="stats">
      <div class="stat">
        <div class="n">{{ stats.total }}</div>
        <div class="l">全部</div>
      </div>
      <div class="stat">
        <div class="n">{{ stats.completed }}</div>
        <div class="l">已完成</div>
      </div>
      <div class="stat">
        <div class="n">{{ stats.rate }}%</div>
        <div class="l">完成率</div>
      </div>
      <div class="stat">
        <div class="n">{{ stats.unreadReplyTicketCount }}</div>
        <div class="l">未读回复</div>
      </div>
    </div>

    <el-table v-loading="loading" :data="list">
      <el-table-column prop="ticketNo" label="编号" min-width="150" />
      <el-table-column prop="title" label="标题" min-width="160" show-overflow-tooltip />
      <el-table-column prop="projectName" label="项目" min-width="140" show-overflow-tooltip>
        <template #default="{ row }">{{ row.projectName || '—' }}</template>
      </el-table-column>
      <el-table-column prop="developerNames" label="开发人员" min-width="120" show-overflow-tooltip>
        <template #default="{ row }">{{ row.developerNames || '—' }}</template>
      </el-table-column>
      <el-table-column label="类型" width="90">
        <template #default="{ row }">{{ ticketTypeLabel(row.type) }}</template>
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
      <el-table-column prop="companyName" label="公司" width="120" />
      <el-table-column prop="createTime" label="提交时间" width="160" />
      <el-table-column label="回复" width="90">
        <template #default="{ row }">
          <el-badge :is-dot="row.replySummary?.hasUnreadReply" :hidden="!row.replySummary?.hasUnreadReply">
            <el-button link type="primary" @click="openReply(row)">沟通</el-button>
          </el-badge>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          <el-button v-if="row.canUpdateProgress" link type="primary" @click="openProgress(row)">进度</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="total > query.pageSize" class="page-footer">
      <el-pagination
        v-model:current-page="query.page"
        layout="total, prev, pager, next"
        :total="total"
        :page-size="query.pageSize"
        @current-change="load"
      />
    </div>
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
      <p><b>公司：</b>{{ detail.companyName || '—' }}</p>
      <p><b>项目：</b>{{ detail.projectName || '—' }}</p>
      <p><b>开发人员：</b>{{ detail.developerNames || '—' }}</p>
      <p><b>状态：</b>{{ ticketStatusLabel(detail.status) }}（{{ detail.progress || 0 }}%）</p>
      <div class="desc" v-html="detail.description" />
      <div v-if="detail.canUpdateProgress" style="margin-top: 16px">
        <el-button type="primary" @click="openProgress(detail)">更新进度</el-button>
      </div>
    </template>
  </el-drawer>

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
  flex-wrap: wrap;
  margin-bottom: 16px;
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
.head-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
.stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.stat {
  padding: 14px 16px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.42);
  border: 1px solid rgba(255, 255, 255, 0.7);
}
.stat .n {
  font-size: 22px;
  font-weight: 650;
}
.stat .l {
  margin-top: 4px;
  color: #71717a;
  font-size: 12px;
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
}
@media (max-width: 800px) {
  .stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
