<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { bizApi } from '@/api/biz'
import { workflowApi } from '@/api/workflow'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

const wallet = ref<any>({})
const ledgers = ref<any[]>([])
const ledgerTotal = ref(0)
const ledgerQuery = reactive({
  page: 1,
  pageSize: 10,
  keyword: '',
  dateRange: [] as string[],
  minAmount: undefined as number | undefined,
  maxAmount: undefined as number | undefined,
})

const todoApprovals = ref<any[]>([])
const mineApprovals = ref<any[]>([])
const myTasks = ref<any[]>([])
const calendarTasks = ref<any[]>([])
const myProjects = ref<any[]>([])
const calendarDate = ref(new Date())

function fmt(n?: number) {
  return Number(n || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function fmtTime(t?: string) {
  if (!t) return '—'
  return t.replace('T', ' ').slice(0, 16)
}

function dayKey(d: Date | string) {
  if (typeof d === 'string') return d.slice(0, 10)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const selectedDay = computed(() => dayKey(calendarDate.value))

const dayTasks = computed(() =>
  calendarTasks.value.filter((t) => String(t.dueDate || '').startsWith(selectedDay.value)),
)

const tasksByDay = computed(() => {
  const map: Record<string, number> = {}
  for (const t of calendarTasks.value) {
    const key = String(t.dueDate || '').slice(0, 10)
    if (!key) continue
    map[key] = (map[key] || 0) + 1
  }
  return map
})

function cellClass(day: string) {
  const n = tasksByDay.value[day] || 0
  if (!n) return ''
  const overdue = calendarTasks.value.some(
    (t) => String(t.dueDate || '').startsWith(day) && (t.overdue || Number(t.status) === 0 || Number(t.status) === 1) && day < dayKey(new Date()),
  )
  return overdue ? 'has-task overdue' : 'has-task'
}

const seeAllProjects = computed(() => {
  const roles = userStore.roles || []
  return roles.includes('admin') || roles.includes('shareholder')
})

function projectRole(p: any) {
  const uid = userStore.user?.id
  if (uid && p.ownerId === uid) return '负责人'
  if (seeAllProjects.value) return '全部可见'
  return '成员'
}

function openTask(t: any) {
  if (!userStore.hasPermission('project:task:list')) return
  router.push({ path: '/project/task', query: { projectId: String(t.projectId || '') } })
}

async function loadWallet() {
  try {
    wallet.value = await bizApi.myWallet()
  } catch {
    wallet.value = {}
  }
  const range = ledgerQuery.dateRange || []
  try {
    const res = await bizApi.myWalletLedger({
      page: ledgerQuery.page,
      pageSize: ledgerQuery.pageSize,
      keyword: ledgerQuery.keyword || undefined,
      minAmount: ledgerQuery.minAmount,
      maxAmount: ledgerQuery.maxAmount,
      startTime: range[0] ? `${range[0]} 00:00:00` : undefined,
      endTime: range[1] ? `${range[1]} 23:59:59` : undefined,
    })
    ledgers.value = res.list || []
    ledgerTotal.value = res.total || 0
  } catch {
    ledgers.value = []
    ledgerTotal.value = 0
  }
}

async function loadApprovals() {
  try {
    const [todo, mine] = await Promise.all([
      workflowApi.page({ page: 1, pageSize: 8, scope: 'todo' }),
      workflowApi.page({ page: 1, pageSize: 8, scope: 'mine' }),
    ])
    todoApprovals.value = todo.list || []
    mineApprovals.value = mine.list || []
  } catch {
    todoApprovals.value = []
    mineApprovals.value = []
  }
}

async function loadTasks() {
  const uid = userStore.user?.id
  if (!uid) {
    myTasks.value = []
    calendarTasks.value = []
    return
  }
  try {
    const seeAll = seeAllProjects.value
    // 管理员/股东看全部任务；其他人只看指派给自己的（日历与列表同源）
    const res = await bizApi.taskPage({
      page: 1,
      pageSize: 200,
      ...(seeAll ? {} : { assigneeId: uid }),
    })
    calendarTasks.value = res.list || []
    myTasks.value = calendarTasks.value.slice(0, 10)
  } catch {
    myTasks.value = []
    calendarTasks.value = []
  }
}

async function loadProjects() {
  try {
    // 后端按负责人/成员过滤，不拿全量项目列表
    myProjects.value = (await bizApi.myProjects()) || []
  } catch {
    myProjects.value = []
  }
}

onMounted(() => {
  void Promise.all([loadWallet(), loadApprovals(), loadTasks(), loadProjects()])
})
</script>

<template>
  <div class="account">
    <div class="hero page-card">
      <div>
        <h2>个人中心</h2>
        <p>{{ userStore.nickname || userStore.user?.username }} · 钱包 / 审批 / 任务 / 项目 / 日历</p>
      </div>
      <div class="bal">
        <span>我的钱包余额</span>
        <b>¥ {{ fmt(wallet.balance) }}</b>
      </div>
    </div>

    <section class="page-card">
      <div class="sec-head">
        <h3>钱包流水</h3>
        <el-button type="primary" @click="router.push('/workflow/center')">去发起报销</el-button>
      </div>
      <div class="toolbar">
        <el-date-picker
          v-model="ledgerQuery.dateRange"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="开始"
          end-placeholder="结束"
          style="width: 260px"
        />
        <el-input-number v-model="ledgerQuery.minAmount" :min="0" :precision="2" controls-position="right" placeholder="最小金额" style="width: 130px" />
        <el-input-number v-model="ledgerQuery.maxAmount" :min="0" :precision="2" controls-position="right" placeholder="最大金额" style="width: 130px" />
        <el-input v-model="ledgerQuery.keyword" clearable placeholder="编号/摘要" style="width: 160px" @keyup.enter="() => { ledgerQuery.page = 1; loadWallet() }" />
        <el-button type="primary" @click="() => { ledgerQuery.page = 1; loadWallet() }">查询</el-button>
      </div>
      <el-table :data="ledgers" stripe>
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ fmtTime(row.occurTime) }}</template>
        </el-table-column>
        <el-table-column prop="bizNo" label="编号" width="170" />
        <el-table-column prop="title" label="摘要" min-width="200" />
        <el-table-column label="金额" width="130" align="right">
          <template #default="{ row }">
            <span :class="Number(row.amount) >= 0 ? 'in' : 'out'">
              {{ Number(row.amount) >= 0 ? '+' : '' }}{{ fmt(row.amount) }}
            </span>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        style="margin-top: 12px"
        v-model:current-page="ledgerQuery.page"
        :page-size="ledgerQuery.pageSize"
        :total="ledgerTotal"
        layout="total, prev, pager, next"
        @current-change="loadWallet"
      />
    </section>

    <section class="page-card cal-section">
      <div class="sec-head">
        <h3>任务日历</h3>
        <el-button
          v-if="userStore.hasPermission('project:task:list')"
          link
          type="primary"
          @click="router.push('/project/task')"
        >
          去任务管理
        </el-button>
      </div>
      <div class="cal-wrap">
        <el-calendar v-model="calendarDate">
          <template #date-cell="{ data }">
            <div :class="['cell', cellClass(data.day)]">
              <span class="day-num">{{ data.day.split('-')[2] }}</span>
              <em v-if="tasksByDay[data.day]" class="dot">{{ tasksByDay[data.day] }}</em>
            </div>
          </template>
        </el-calendar>
        <div class="day-panel">
          <h4>{{ selectedDay }} 的任务</h4>
          <div
            v-for="t in dayTasks"
            :key="t.id"
            class="row"
            @click="openTask(t)"
          >
            <div>
              <b>{{ t.title }}</b>
              <span>{{ t.projectName || '—' }} · {{ t.assigneeName || '未分配' }}</span>
            </div>
            <em :class="{ overdue: t.overdue }">{{ t.statusLabel || t.status || '—' }}</em>
          </div>
          <div v-if="!dayTasks.length" class="empty">这一天没有到期任务</div>
        </div>
      </div>
    </section>

    <div class="three-col">
      <section class="page-card">
        <div class="sec-head">
          <h3>我的审批</h3>
          <el-button link type="primary" @click="router.push({ path: '/workflow/center', query: { scope: 'todo' } })">
            去审批中心
          </el-button>
        </div>
        <h4 class="sub">待我处理</h4>
        <div
          v-for="r in todoApprovals"
          :key="'t' + r.id"
          class="row"
          @click="router.push({ path: '/workflow/center', query: { scope: 'todo' } })"
        >
          <div>
            <b>{{ r.title }}</b>
            <span>{{ r.typeLabel }} · {{ r.bizNo }}</span>
          </div>
          <em>{{ r.statusLabel }}</em>
        </div>
        <div v-if="!todoApprovals.length" class="empty">暂无待办</div>

        <h4 class="sub">我发起的</h4>
        <div
          v-for="r in mineApprovals"
          :key="'m' + r.id"
          class="row"
          @click="router.push({ path: '/workflow/center', query: { scope: 'mine' } })"
        >
          <div>
            <b>{{ r.title }}</b>
            <span>{{ r.typeLabel }} · {{ r.bizNo }}</span>
          </div>
          <em>{{ r.statusLabel }}</em>
        </div>
        <div v-if="!mineApprovals.length" class="empty">暂无申请</div>
      </section>

      <section class="page-card">
        <div class="sec-head">
          <h3>{{ seeAllProjects ? '全部任务' : '我的任务' }}</h3>
          <el-button
            v-if="userStore.hasPermission('project:task:list')"
            link
            type="primary"
            @click="router.push('/project/task')"
          >
            去任务管理
          </el-button>
        </div>
        <div
          v-for="t in myTasks"
          :key="t.id"
          class="row"
          @click="openTask(t)"
        >
          <div>
            <b>{{ t.title }}</b>
            <span>{{ t.projectName || '—' }} · 截止 {{ t.dueDate || '未设' }}</span>
          </div>
          <em>{{ t.statusLabel || t.status || '—' }}</em>
        </div>
        <div v-if="!myTasks.length" class="empty">{{ seeAllProjects ? '暂无任务' : '暂无指派给我的任务' }}</div>
      </section>

      <section class="page-card">
        <div class="sec-head">
          <h3>{{ seeAllProjects ? '全部项目' : '参与的项目' }}</h3>
          <el-button
            v-if="userStore.hasPermission('project:list')"
            link
            type="primary"
            @click="router.push('/project/list')"
          >
            去项目管理
          </el-button>
        </div>
        <div
          v-for="p in myProjects"
          :key="p.id"
          class="row"
          @click="router.push({ path: '/project/list', query: { keyword: p.name || '' } })"
        >
          <div>
            <b>{{ p.name }}</b>
            <span>{{ p.code || '—' }} · {{ projectRole(p) }}</span>
          </div>
          <em>{{ p.statusLabel || p.status || '—' }}</em>
        </div>
        <div v-if="!myProjects.length" class="empty">{{ seeAllProjects ? '暂无项目' : '暂无参与项目' }}</div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.account { display: flex; flex-direction: column; gap: 14px; }
.hero { display: flex; justify-content: space-between; align-items: center; }
.hero h2 { margin: 0 0 4px; }
.hero p { margin: 0; color: #64748b; font-size: 13px; }
.bal { text-align: right; }
.bal span { display: block; font-size: 12px; color: #94a3b8; }
.bal b { font-size: 28px; color: #1d4ed8; }
.sec-head {
  display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;
}
.sec-head h3 { margin: 0; font-size: 16px; }
.toolbar { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 12px; }
.three-col { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; }
.cal-wrap {
  display: grid;
  grid-template-columns: 1.5fr 1fr;
  gap: 16px;
  align-items: start;
}
.cell {
  height: 100%;
  min-height: 48px;
  padding: 4px 6px;
  position: relative;
}
.cell.has-task {
  background: #eff6ff;
  border-radius: 8px;
  font-weight: 600;
  color: #1d4ed8;
}
.cell.has-task.overdue {
  background: #fef2f2;
  color: #dc2626;
}
.day-num { font-size: 13px; }
.dot {
  position: absolute;
  right: 4px;
  bottom: 4px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 999px;
  background: #3b82f6;
  color: #fff;
  font-size: 10px;
  font-style: normal;
  line-height: 16px;
  text-align: center;
}
.cell.overdue .dot { background: #dc2626; }
.day-panel h4 { margin: 0 0 10px; font-size: 14px; }
.row em.overdue { color: #dc2626; }
.sub { margin: 8px 0; font-size: 13px; color: #64748b; font-weight: 600; }
.row {
  display: flex; justify-content: space-between; gap: 12px; align-items: center;
  padding: 10px 0; border-bottom: 1px solid #f1f5f9; cursor: pointer;
}
.row:hover b { color: #1d4ed8; }
.row b { display: block; font-size: 14px; }
.row span { display: block; font-size: 12px; color: #94a3b8; margin-top: 2px; }
.row em { font-style: normal; font-size: 12px; color: #64748b; white-space: nowrap; }
.empty { color: #94a3b8; font-size: 13px; padding: 12px 0; }
.in { color: #16a34a; font-weight: 600; }
.out { color: #dc2626; font-weight: 600; }
@media (max-width: 1100px) {
  .three-col { grid-template-columns: 1fr; }
  .cal-wrap { grid-template-columns: 1fr; }
}
</style>
