<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Coin, Delete, View } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bizApi } from '@/api/biz'
import TaskDetailDrawer from '@/components/task/TaskDetailDrawer.vue'

const cycleType = ref<'MONTHLY' | 'WEEKLY'>('MONTHLY')
const yearMonth = ref('')
const runs = ref<any[]>([])
const lines = ref<any[]>([])
const activeRunId = ref<number | undefined>()
const loading = ref(false)
const budgetDialog = ref(false)
const budgetStep = ref<'select' | 'result'>('select')
const budgetItems = ref<any[]>([])
const selectedBudgetItems = ref<any[]>([])
const budgetResult = ref<any>()
const budgetLoading = ref(false)
const budgetSaving = ref(false)
const budgetMonth = ref('')
const voidingLineId = ref<number>()
const taskDetailOpen = ref(false)
const activeTaskId = ref<number>()

const isWeekly = computed(() => cycleType.value === 'WEEKLY')
const periodColumnLabel = computed(() => (isWeekly.value ? '周次' : '月份'))
const effectiveBudgetItems = computed(() => (
  selectedBudgetItems.value.length ? selectedBudgetItems.value : budgetItems.value
))

function currentYm() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

function currentIsoWeek(ref = new Date()) {
  const d = new Date(Date.UTC(ref.getFullYear(), ref.getMonth(), ref.getDate()))
  const day = d.getUTCDay() || 7
  d.setUTCDate(d.getUTCDate() + 4 - day)
  const isoYear = d.getUTCFullYear()
  const week1 = new Date(Date.UTC(isoYear, 0, 4))
  const week1Day = week1.getUTCDay() || 7
  const week1Monday = new Date(week1)
  week1Monday.setUTCDate(week1.getUTCDate() - week1Day + 1)
  const week = 1 + Math.round((d.getTime() - week1Monday.getTime()) / 604800000)
  return `${isoYear}-W${String(week).padStart(2, '0')}`
}

function defaultPeriod() {
  return isWeekly.value ? currentIsoWeek() : currentYm()
}

function isWeekPeriod(v?: string) {
  return /^(\d{4})-W(\d{2})$/.test(String(v || '').trim())
}

function isMonthPeriod(v?: string) {
  return /^(\d{4})-(\d{2})$/.test(String(v || '').trim())
}

async function loadRuns() {
  loading.value = true
  try {
    const period = String(yearMonth.value || '').trim() || undefined
    const list = await bizApi.salaryRuns({
      yearMonth: period,
    })
    runs.value = (list || []).filter((r: any) => {
      const key = String(r.yearMonth || '')
      if (period) return true
      return isWeekly.value ? isWeekPeriod(key) : isMonthPeriod(key)
    })
    lines.value = []
    activeRunId.value = undefined
  } finally {
    loading.value = false
  }
}

async function openLines(run: any) {
  activeRunId.value = run.id
  lines.value = await bizApi.salaryRunLines(run.id)
}

function lineDetail(line: any) {
  if (!line?.payloadSnapshot) return undefined
  try { return JSON.parse(line.payloadSnapshot) }
  catch { return undefined }
}

async function voidLine(line: any) {
  const { value } = await ElMessageBox.prompt(
    `作废后，“${line.userName || '该人员'}”的本条预算明细将不再有效，此操作不可撤销。`,
    '确认作废',
    {
      type: 'warning',
      confirmButtonText: '确认作废',
      cancelButtonText: '取消',
      inputType: 'textarea',
      inputPlaceholder: '请输入作废原因',
      inputValidator: value => String(value || '').trim() ? true : '请输入作废原因',
    },
  )
  const reason = String(value).trim()
  voidingLineId.value = line.id
  try {
    await bizApi.voidSalaryRunLine(line.id, reason)
    ElMessage.success('批次明细已作废')
    if (activeRunId.value) {
      lines.value = await bizApi.salaryRunLines(activeRunId.value)
      const activeRun = runs.value.find(run => run.id === activeRunId.value)
      if (activeRun) {
        activeRun.status = lines.value.length > 0 && lines.value.every(item => item.status === 'VOIDED')
          ? 'VOIDED'
          : 'PARTIALLY_VOIDED'
      }
    }
  } finally {
    voidingLineId.value = undefined
  }
}

function onCycleTabChange() {
  yearMonth.value = defaultPeriod()
}

function formatLocalDate(date: Date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function budgetItemPeriod(item: any) {
  const match = /^(\d{4})-(\d{2})$/.exec(budgetMonth.value)
  if (!match) return '—'
  const payDay = Number(item?.payDay || 20)
  const end = new Date(Number(match[1]), Number(match[2]) - 1, payDay)
  const start = new Date(end.getFullYear(), end.getMonth() - 1, payDay + 1)
  return `${formatLocalDate(start)} 至 ${formatLocalDate(end)}`
}

function money(value: unknown) {
  return `¥${Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

const weekDays = ['一', '二', '三', '四', '五', '六', '日']

function calendarCells(item: any) {
  const days = item?.dailyDetails || []
  if (!days.length) return []
  const first = snapshotDate(days[0].date)
  if (!first) return days
  const leading = (first.getDay() + 6) % 7
  return [...Array.from({ length: leading }, (_, index) => ({ placeholder: true, key: `blank-${index}` })), ...days]
}

function openTaskDetail(task: any) {
  const taskId = Number(task?.taskId)
  if (!Number.isFinite(taskId)) return
  activeTaskId.value = taskId
  taskDetailOpen.value = true
}

function taskRewardOverrides() {
  const overrides: Record<number, number> = {}
  for (const person of budgetResult.value?.people || []) {
    for (const task of person.taskRewards || []) overrides[Number(task.taskId)] = Number(task.amount)
  }
  return overrides
}

function updateTaskReward(person: any, task: any, value: number | null | undefined) {
  task.amount = Number(value || 0)
  person.taskRewardAmount = (person.taskRewards || []).reduce(
    (sum: number, reward: any) => sum + Number(reward.amount || 0), 0,
  )
  person.totalAmount = Number(person.salaryAmount || 0) + person.taskRewardAmount
  budgetResult.value.totalAmount = (budgetResult.value.people || []).reduce(
    (sum: number, item: any) => sum + Number(item.totalAmount || 0), 0,
  )
}

function snapshotDate(value: unknown) {
  if (value == null || value === '') return undefined
  const raw = String(value).trim()
  const date = /^\d+$/.test(raw)
    ? new Date(Number(raw))
    : new Date(/[T ]\d{2}:\d{2}/.test(raw) ? raw.replace(' ', 'T') : `${raw}T00:00:00`)
  return Number.isNaN(date.getTime()) ? undefined : date
}

function fullDate(value: unknown) {
  const date = snapshotDate(value)
  return date ? formatLocalDate(date) : '—'
}

function calendarDay(value: unknown) {
  const date = snapshotDate(value)
  return date ? `${date.getMonth() + 1}/${date.getDate()}` : '—'
}

async function openBudget() {
  budgetDialog.value = true
  budgetStep.value = 'select'
  budgetResult.value = undefined
  selectedBudgetItems.value = []
  budgetMonth.value = yearMonth.value || currentYm()
  budgetLoading.value = true
  try { budgetItems.value = (await bizApi.salaryBudgetItems()).filter((item: any) => item.enabled === 1) }
  finally { budgetLoading.value = false }
}

function onBudgetSelection(rows: any[]) { selectedBudgetItems.value = rows }

async function calculateBudget() {
  if (!effectiveBudgetItems.value.length) return ElMessage.warning('暂无可计算的工资配置')
  if (!budgetMonth.value) return ElMessage.warning('请选择预算月份')
  budgetLoading.value = true
  try {
    budgetResult.value = await bizApi.previewSalaryBudget({
      yearMonth: budgetMonth.value,
      itemIds: effectiveBudgetItems.value.map(i => i.id),
    })
    budgetStep.value = 'result'
  } finally { budgetLoading.value = false }
}

async function saveBudget() {
  await ElMessageBox.confirm('入库后会按出款项目所属公司自动生成预算薪资批次，手动调整的任务报酬将按当前金额保存。是否继续？', '确认预算入库', { type: 'warning', confirmButtonText: '确认入库', cancelButtonText: '返回查看' })
  budgetSaving.value = true
  try {
    await bizApi.saveSalaryBudget({
      yearMonth: budgetMonth.value,
      itemIds: effectiveBudgetItems.value.map(i => i.id),
      taskRewardOverrides: taskRewardOverrides(),
    })
    ElMessage.success('预算薪资已入库')
    budgetDialog.value = false
    yearMonth.value = budgetMonth.value
    await loadRuns()
  } finally { budgetSaving.value = false }
}

function resetRunFilters() {
  yearMonth.value = defaultPeriod()
  void loadRuns()
}

const statusLabel: Record<string, string> = {
  PENDING_CONFIRM: '待确认',
  CONFIRMED: '已确认',
  APPROVAL_CREATED: '已生成审批',
  SKIPPED: '已跳过',
  FAILED: '失败',
  RUNNING: '进行中',
  DONE: '完成',
  FAILED_RUN: '失败',
  BUDGETED: '已预算',
  PARTIALLY_VOIDED: '部分作废',
  VOIDED: '已作废',
}

const statusType: Record<string, string> = {
  PENDING_CONFIRM: 'warning',
  CONFIRMED: 'success',
  APPROVAL_CREATED: 'success',
  SKIPPED: 'info',
  FAILED: 'danger',
  RUNNING: '',
  DONE: 'success',
  FAILED_RUN: 'danger',
  BUDGETED: 'success',
  PARTIALLY_VOIDED: 'warning',
  VOIDED: 'info',
}

const triggerLabel: Record<string, string> = {
  AUTO: '定时',
  CRON: '定时',
  MANUAL: '手动',
}

onMounted(async () => {
  if (!yearMonth.value) yearMonth.value = defaultPeriod()
  await loadRuns()
})
</script>

<template>
  <div class="page-stack">
    <div class="page-card">
      <div class="toolbar">
        <div class="toolbar__left">
          <el-tag effect="plain">月薪批次</el-tag>
          <el-date-picker
            v-model="yearMonth"
            type="month"
            format="YYYY年MM月"
            value-format="YYYY-MM"
            placeholder="选择月份"
            clearable
            style="width: 160px"
            @change="loadRuns"
          />
          <el-button @click="resetRunFilters">重置</el-button>
          <el-button type="primary" :icon="Coin" @click="openBudget">预算薪资</el-button>
        </div>
      </div>

      <el-table
        v-loading="loading"
        :data="runs"
        stripe
        highlight-current-row
        class="run-table"
        @row-click="openLines"
      >
        <el-table-column prop="yearMonth" :label="periodColumnLabel" width="120" />
        <el-table-column prop="phase" label="阶段" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.phase === 'PREVIEW' ? 'warning' : 'success'" effect="plain">
              {{ row.phase === 'PREVIEW' ? '预告' : row.phase === 'BUDGET' ? '预算' : '发薪' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="(statusType[row.status] as any) || 'info'">
              {{ statusLabel[row.status] || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="triggerType" label="触发" width="90">
          <template #default="{ row }">{{ triggerLabel[row.triggerType] || row.triggerType || '—' }}</template>
        </el-table-column>
        <el-table-column prop="message" label="摘要" min-width="220" show-overflow-tooltip />
        <el-table-column prop="startedAt" label="开始时间" min-width="170" />
      </el-table>
    </div>

    <div v-if="activeRunId" class="page-card">
      <div class="detail-head">
        <h3 class="card-title">批次明细</h3>
        <span class="detail-id">#{{ activeRunId }}</span>
      </div>
      <el-table :data="lines" stripe>
        <el-table-column type="expand" width="52">
          <template #default="{ row }">
            <div v-if="lineDetail(row)?.items?.length" class="saved-detail">
              <div class="saved-detail__summary">
                <span>薪资详情</span>
                <strong>{{ lineDetail(row).items.length }} 项配置 · {{ money(row.totalAmount) }}</strong>
              </div>
              <article v-for="item in lineDetail(row).items" :key="item.itemId" class="calc-card">
                <header><div><strong>{{ item.projectName }}</strong><small>{{ fullDate(item.periodStart) }} 至 {{ fullDate(item.periodEnd) }}</small></div><b>{{ money(item.amount) }}</b></header>
                <div class="attendance-grid"><span>常规日 <b>{{ item.normalDays }}</b></span><span>常规缺勤 <b>{{ item.absentNormalDays }}</b></span><span>休息日 <b>{{ item.restDays }}</b></span><span>休息日缺勤 <b>{{ item.absentRestDays }}</b></span></div>
                <section v-if="item.dailyDetails?.length" class="salary-calendar" aria-label="每日考勤与工资日历">
                  <div class="calendar-heading">
                    <div><strong>每日工资</strong><small>值班、缺勤及每日计薪明细</small></div>
                    <div class="calendar-legend" aria-label="日历标记说明"><span><i class="dot duty" />值班</span><span><i class="dot absent" />缺勤</span><span><i class="dot rest" />休息日</span></div>
                  </div>
                  <div class="calendar-grid calendar-weekdays" aria-hidden="true"><span v-for="day in weekDays" :key="day">{{ day }}</span></div>
                  <div class="calendar-grid calendar-days">
                    <div v-for="cell in calendarCells(item)" :key="cell.key || cell.date" class="calendar-day" :class="{ placeholder: cell.placeholder, absent: cell.absent, rest: cell.dayType === 'REST' }">
                      <template v-if="!cell.placeholder">
                        <div class="calendar-date"><span>{{ calendarDay(cell.date) }}</span><span class="day-tags"><em v-if="cell.duty" class="tag duty">值班</em><em v-if="cell.absent" class="tag absent">缺勤</em><em v-if="cell.dayType === 'REST'" class="tag rest">休</em></span></div>
                        <strong v-if="cell.absent" class="day-wage day-deduction">扣 {{ money(cell.deductionAmount) }}</strong>
                        <strong v-else class="day-wage">{{ money(cell.amount) }}</strong>
                      </template>
                    </div>
                  </div>
                </section>
                <div class="formula-detail"><el-icon><View /></el-icon><div><strong>计算方式</strong><p>{{ item.formula }}</p><code>{{ item.formulaDetail }} = {{ money(item.amount) }}</code></div></div>
              </article>
              <article v-if="lineDetail(row)?.taskRewards?.length" class="calc-card task-reward-card">
                <header><div><strong>任务报酬</strong><small>计薪周期内已确认完成的任务</small></div><b>{{ money(lineDetail(row).taskRewardAmount) }}</b></header>
                <div class="task-reward-list">
                  <div
                    v-for="task in lineDetail(row).taskRewards"
                    :key="task.taskId"
                    class="task-reward-item"
                    role="button"
                    tabindex="0"
                    :aria-label="`查看任务明细：${task.taskTitle}`"
                    @click="openTaskDetail(task)"
                    @keydown.enter.prevent="openTaskDetail(task)"
                    @keydown.space.prevent="openTaskDetail(task)"
                  >
                    <span><strong>{{ task.taskTitle }}</strong><small>{{ task.projectName }} · 完成于 {{ fullDate(task.completedAt) }}</small></span>
                    <b>{{ money(task.amount) }}</b>
                  </div>
                </div>
              </article>
            </div>
            <el-empty v-else description="该明细暂无薪资详情" :image-size="56" />
          </template>
        </el-table-column>
        <el-table-column prop="userName" label="人员" width="120" />
        <el-table-column prop="totalAmount" label="合计" width="110" align="right">
          <template #default="{ row }">
            {{ row.totalAmount != null ? `¥ ${row.totalAmount}` : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="(statusType[row.status] as any) || 'info'">
              {{ statusLabel[row.status] || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="skipReason" label="原因" min-width="180" show-overflow-tooltip />
        <el-table-column prop="approvalId" label="审批单" width="100" />
        <el-table-column prop="confirmedAt" label="确认时间" min-width="170" />
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'BUDGETED'"
              type="danger"
              link
              :icon="Delete"
              :loading="voidingLineId === row.id"
              @click.stop="voidLine(row)"
            >作废</el-button>
            <span v-else class="operation-placeholder">—</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="budgetDialog" width="min(1000px, calc(100vw - 32px))" :close-on-click-modal="false" destroy-on-close>
      <template #header>
        <div class="budget-title"><span class="budget-icon"><el-icon><Coin /></el-icon></span><div><strong>预算薪资</strong><small>{{ budgetStep === 'select' ? '选择工资配置，出款公司按项目自动确定' : '预算仅供核对，确认后按项目所属公司自动入库' }}</small></div></div>
      </template>

      <div v-if="budgetStep === 'select'" v-loading="budgetLoading">
        <div class="budget-context">
          <label for="budget-month">预算月份</label>
          <el-date-picker
            id="budget-month"
            v-model="budgetMonth"
            type="month"
            value-format="YYYY-MM"
            format="YYYY年MM月"
            placeholder="选择预算月份"
            :clearable="false"
            class="budget-date-picker"
          />
          <i /> <span>计薪周期</span><strong>按各配置的发薪日自动计算</strong><i /> <span>可用配置</span><strong>{{ budgetItems.length }} 项</strong>
        </div>
        <el-table :data="budgetItems" row-key="id" max-height="440" @selection-change="onBudgetSelection">
          <el-table-column type="selection" width="48" />
          <el-table-column prop="userName" label="员工" min-width="110" />
          <el-table-column prop="projectName" label="出款项目" min-width="150" show-overflow-tooltip />
          <el-table-column label="计薪方式" width="110"><template #default="{ row }">{{ row.payMode === 'DAILY' ? '按天计薪' : '固定月薪' }}</template></el-table-column>
          <el-table-column label="薪资标准" min-width="190"><template #default="{ row }"><strong>{{ money(row.payMode === 'DAILY' ? row.normalDayRate : row.amount) }}</strong><small class="muted">{{ row.payMode === 'DAILY' ? ' / 工作日' : ' / 月' }}</small></template></el-table-column>
          <el-table-column label="发薪规则" min-width="140"><template #default="{ row }">每月 {{ row.payDay || 20 }} 日结算</template></el-table-column>
          <el-table-column label="本期计薪周期" min-width="210"><template #default="{ row }">{{ budgetItemPeriod(row) }}</template></el-table-column>
        </el-table>
        <el-empty v-if="!budgetLoading && !budgetItems.length" description="暂无启用的工资配置" :image-size="72" />
      </div>

      <div v-else v-loading="budgetLoading" class="budget-result">
        <div class="budget-result-range">预算月份：{{ budgetResult?.yearMonth }}；覆盖周期：{{ budgetResult?.periodStart }} 至 {{ budgetResult?.periodEnd }}（各项以其发薪日为准）</div>
        <div class="budget-summary"><div><span>预算人数</span><strong>{{ budgetResult?.peopleCount || 0 }}</strong></div><div><span>工资配置</span><strong>{{ budgetResult?.itemCount || 0 }}</strong></div><div class="total"><span>预算薪资合计</span><strong>{{ money(budgetResult?.totalAmount) }}</strong></div></div>
        <el-collapse accordion>
          <el-collapse-item v-for="person in budgetResult?.people || []" :key="person.userId" :name="person.userId">
            <template #title><div class="person-title"><span>{{ person.userName }}</span><small>{{ person.items?.length }} 项配置</small><strong>{{ money(person.totalAmount) }}</strong></div></template>
            <article v-for="item in person.items" :key="item.itemId" class="calc-card">
              <header><div><strong>{{ item.projectName }}</strong><small>{{ item.periodStart }} 至 {{ item.periodEnd }}</small></div><b>{{ money(item.amount) }}</b></header>
              <div class="attendance-grid"><span>常规日 <b>{{ item.normalDays }}</b></span><span>常规缺勤 <b>{{ item.absentNormalDays }}</b></span><span>休息日 <b>{{ item.restDays }}</b></span><span>休息日缺勤 <b>{{ item.absentRestDays }}</b></span></div>
              <section v-if="item.dailyDetails?.length" class="salary-calendar" aria-label="每日考勤与工资日历">
                <div class="calendar-heading">
                  <div><strong>每日工资</strong><small>值班、缺勤及每日计薪明细</small></div>
                  <div class="calendar-legend" aria-label="日历标记说明"><span><i class="dot duty" />值班</span><span><i class="dot absent" />缺勤</span><span><i class="dot rest" />休息日</span></div>
                </div>
                <div class="calendar-grid calendar-weekdays" aria-hidden="true"><span v-for="day in weekDays" :key="day">{{ day }}</span></div>
                <div class="calendar-grid calendar-days">
                  <div v-for="cell in calendarCells(item)" :key="cell.key || cell.date" class="calendar-day" :class="{ placeholder: cell.placeholder, absent: cell.absent, rest: cell.dayType === 'REST' }">
                    <template v-if="!cell.placeholder">
                      <div class="calendar-date"><span>{{ calendarDay(cell.date) }}</span><span class="day-tags"><em v-if="cell.duty" class="tag duty">值班</em><em v-if="cell.absent" class="tag absent">缺勤</em><em v-if="cell.dayType === 'REST'" class="tag rest">休</em></span></div>
                      <strong v-if="cell.absent" class="day-wage day-deduction">扣 {{ money(cell.deductionAmount) }}</strong>
                      <strong v-else class="day-wage">{{ money(cell.amount) }}</strong>
                    </template>
                  </div>
                </div>
              </section>
              <div class="formula-detail"><el-icon><View /></el-icon><div><strong>计算方式</strong><p>{{ item.formula }}</p><code>{{ item.formulaDetail }} = {{ money(item.amount) }}</code></div></div>
            </article>
            <article v-if="person.taskRewards?.length" class="calc-card task-reward-card">
              <header><div><strong>任务报酬</strong><small>计薪周期内已确认完成的任务，金额可手动调整（不含待确认）</small></div><b>{{ money(person.taskRewardAmount) }}</b></header>
              <div class="task-reward-list">
                <div
                  v-for="task in person.taskRewards"
                  :key="task.taskId"
                  class="task-reward-item task-reward-item--editable"
                >
                  <button type="button" class="task-reward-link" :aria-label="`查看任务明细：${task.taskTitle}`" @click="openTaskDetail(task)">
                    <strong>{{ task.taskTitle }}</strong><small>{{ task.projectName }} · 完成于 {{ fullDate(task.completedAt) }}</small>
                  </button>
                  <el-input-number
                    v-model="task.amount"
                    class="task-reward-input"
                    :min="0"
                    :max="999999999999.99"
                    :precision="2"
                    :step="100"
                    controls-position="right"
                    :aria-label="`${task.taskTitle}的任务报酬金额`"
                    @change="(value: number | undefined) => updateTaskReward(person, task, value)"
                  />
                </div>
              </div>
            </article>
          </el-collapse-item>
        </el-collapse>
      </div>

      <template #footer><div class="budget-footer"><span v-if="budgetStep === 'select'">{{ selectedBudgetItems.length ? `已选择 ${selectedBudgetItems.length} 项` : `未选择，默认全部 ${budgetItems.length} 项` }}</span><span v-else>关闭窗口不会保存本次预算</span><div><el-button @click="budgetDialog = false">关闭</el-button><el-button v-if="budgetStep === 'result'" @click="budgetStep = 'select'">返回修改</el-button><el-button v-if="budgetStep === 'select'" type="primary" :loading="budgetLoading" :disabled="!budgetItems.length" @click="calculateBudget">开始计算</el-button><el-button v-else type="primary" :loading="budgetSaving" @click="saveBudget">确认入库</el-button></div></div></template>
    </el-dialog>

    <TaskDetailDrawer v-model="taskDetailOpen" :task-id="activeTaskId" />

  </div>
</template>

<style scoped>
.run-table :deep(.el-table__row) {
  cursor: pointer;
}

.detail-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 12px;
}

.card-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.detail-id {
  color: #94a3b8;
  font-size: 13px;
}

.saved-detail {
  padding: 4px 18px 14px 52px;
}

.saved-detail__summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
  color: #71717a;
  font-size: 13px;
}

.saved-detail__summary strong {
  color: #18181b;
}

.operation-placeholder {
  color: #a1a1aa;
}

.budget-title{display:flex;align-items:center;gap:12px}.budget-title>div strong,.budget-title>div small{display:block}.budget-title>div strong{font-size:18px}.budget-title>div small{margin-top:4px;color:#64748b;font-size:12px}.budget-icon{display:grid;place-items:center;width:42px;height:42px;border-radius:12px;background:#18181b;color:#fff;font-size:20px}.budget-context{display:flex;align-items:center;flex-wrap:wrap;gap:8px;margin-bottom:14px;padding:12px 14px;border-radius:10px;background:#f8fafc;color:#64748b;font-size:13px}.budget-context strong{color:#18181b}.budget-context i{width:1px;height:14px;margin:0 6px;background:#d4d4d8}.budget-date-picker{width:180px}.budget-result-range{margin-bottom:10px;color:#64748b;font-size:13px}.muted{color:#71717a}.budget-summary{display:grid;grid-template-columns:1fr 1fr 2fr;gap:10px;margin-bottom:16px}.budget-summary>div{padding:14px 16px;border:1px solid #e4e4e7;border-radius:12px}.budget-summary span,.budget-summary strong{display:block}.budget-summary span{color:#71717a;font-size:12px}.budget-summary strong{margin-top:5px;font-size:20px}.budget-summary .total{background:#18181b;color:#fff}.budget-summary .total span{color:#d4d4d8}.person-title{display:flex;align-items:center;width:100%;gap:10px;padding-right:16px}.person-title>span{font-weight:600}.person-title small{color:#71717a}.person-title strong{margin-left:auto;font-size:16px}.calc-card{margin:0 4px 12px;padding:15px;border:1px solid #e4e4e7;border-radius:12px}.calc-card header{display:flex;justify-content:space-between;gap:16px}.calc-card header strong,.calc-card header small{display:block}.calc-card header small{margin-top:3px;color:#71717a;font-size:12px}.calc-card header>b{font-size:18px}.attendance-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:8px;margin:13px 0}.attendance-grid span{padding:8px;border-radius:8px;background:#f8fafc;color:#64748b;font-size:12px}.attendance-grid b{float:right;color:#18181b}.salary-calendar{margin:13px 0;padding:14px;border:1px solid #e4e4e7;border-radius:10px;background:#fff}.calendar-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;margin-bottom:12px}.calendar-heading strong,.calendar-heading small{display:block}.calendar-heading strong{font-size:13px}.calendar-heading small{margin-top:3px;color:#71717a;font-size:11px}.calendar-legend{display:flex;align-items:center;flex-wrap:wrap;gap:10px;color:#52525b;font-size:11px}.calendar-legend span{display:flex;align-items:center;gap:4px}.dot{display:inline-block;width:7px;height:7px;border-radius:50%}.dot.duty{background:#2563eb}.dot.absent{background:#dc2626}.dot.rest{background:#a1a1aa}.calendar-grid{display:grid;grid-template-columns:repeat(7,minmax(0,1fr))}.calendar-weekdays{border-bottom:1px solid #e4e4e7;color:#71717a;font-size:11px;text-align:center}.calendar-weekdays span{padding:6px}.calendar-days{gap:6px;padding-top:6px}.calendar-day{min-height:68px;padding:7px;border:1px solid #e4e4e7;border-radius:8px;background:#fff}.calendar-day.rest{background:#fafafa}.calendar-day.absent{border-color:#fecaca;background:#fff7f7}.calendar-day.placeholder{border-color:transparent;background:transparent}.calendar-date{display:flex;align-items:flex-start;justify-content:space-between;gap:4px;color:#52525b;font-size:11px}.day-tags{display:flex;flex-wrap:wrap;justify-content:flex-end;gap:2px}.tag{padding:1px 4px;border-radius:999px;font-size:9px;font-style:normal;font-weight:600;line-height:15px}.tag.duty{background:#dbeafe;color:#1d4ed8}.tag.absent{background:#fee2e2;color:#b91c1c}.tag.rest{background:#e4e4e7;color:#52525b}.day-wage{display:block;margin-top:13px;color:#18181b;font-size:12px;font-variant-numeric:tabular-nums;white-space:nowrap}.calendar-day.absent .day-wage{color:#b91c1c}.formula-detail{display:flex;gap:10px;padding:12px;border-left:3px solid #71717a;background:#f4f4f5;color:#52525b}.formula-detail p{margin:3px 0;font-size:12px}.formula-detail code{font-family:inherit;color:#18181b;font-size:12px}.budget-footer{display:flex;align-items:center;justify-content:space-between}.budget-footer>span{color:#71717a;font-size:12px}

.task-reward-card{border-color:#bfdbfe;background:#f8fbff}.task-reward-list{display:grid;gap:8px;margin-top:13px}.task-reward-list>div{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:10px 12px;border-radius:8px;background:#fff}.task-reward-list span strong,.task-reward-list span small{display:block}.task-reward-list span small{margin-top:3px;color:#71717a;font-size:12px}.task-reward-list>div>b{white-space:nowrap;color:#1d4ed8}.task-reward-item{cursor:pointer;transition:background-color .18s ease,box-shadow .18s ease,transform .18s ease}.task-reward-item:hover{background:#eff6ff;box-shadow:0 2px 8px rgb(37 99 235 / 10%);transform:translateY(-1px)}.task-reward-item:focus-visible{outline:2px solid #2563eb;outline-offset:2px}.task-reward-item:active{transform:translateY(0)}
.task-reward-link{min-width:0;padding:0;border:0;background:transparent;text-align:left;color:inherit;cursor:pointer}.task-reward-link strong,.task-reward-link small{display:block}.task-reward-link small{margin-top:3px;color:#71717a;font-size:12px}.task-reward-link:focus-visible{border-radius:4px;outline:2px solid #2563eb;outline-offset:3px}.task-reward-input{width:160px;flex:0 0 160px}.task-reward-input :deep(.el-input__inner){font-weight:700;color:#1d4ed8}
.task-reward-item--editable{cursor:default}.task-reward-item--editable:hover{transform:none}.task-reward-item--editable:active{transform:none}

@media(prefers-reduced-motion:reduce){.task-reward-item{transition:none}.task-reward-item:hover,.task-reward-item:active{transform:none}}

@media(max-width:640px){.toolbar__left{align-items:stretch;flex-direction:column}.toolbar__left>*{width:100%!important}.saved-detail{padding:4px 4px 12px}.saved-detail__summary{align-items:flex-start;flex-direction:column;gap:4px}.budget-context i{display:none}.budget-date-picker{width:100%}.budget-summary{grid-template-columns:1fr 1fr}.budget-summary .total{grid-column:1/-1}.attendance-grid{grid-template-columns:1fr 1fr}.calendar-heading{align-items:stretch;flex-direction:column}.salary-calendar{padding:10px}.calendar-days{gap:2px}.calendar-day{min-height:58px;padding:3px;border-radius:5px}.calendar-date{display:block;font-size:9px}.day-tags{justify-content:flex-start;margin-top:2px}.tag{padding:0 2px;font-size:8px;line-height:12px}.day-wage{margin-top:8px;font-size:9px;letter-spacing:-.03em}.budget-footer{align-items:stretch;flex-direction:column;gap:10px}.budget-footer>div{display:grid;grid-template-columns:1fr 1fr;gap:8px}.budget-footer .el-button{margin:0}}

</style>
