<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bizApi } from '@/api/biz'
import { sysApi } from '@/api/system'

const companies = ref<any[]>([])
const companyId = ref<number | undefined>()
const cycleType = ref<'MONTHLY' | 'WEEKLY'>('MONTHLY')
const yearMonth = ref('')
const runs = ref<any[]>([])
const lines = ref<any[]>([])
const activeRunId = ref<number | undefined>()
const loading = ref(false)

const prepareVisible = ref(false)
const prepareLoading = ref(false)
const prepareSaving = ref(false)
const prepareRows = ref<any[]>([])
const prepareSelected = ref<any[]>([])

const isWeekly = computed(() => cycleType.value === 'WEEKLY')
const periodPlaceholder = computed(() => (isWeekly.value ? 'yyyy-Www' : 'yyyy-MM'))
const periodColumnLabel = computed(() => (isWeekly.value ? '周次' : '月份'))

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

function assertPeriodMatchesTab() {
  const v = String(yearMonth.value || '').trim()
  if (!v) {
    ElMessage.warning(isWeekly.value ? '请填写周次，格式 yyyy-Www' : '请填写月份，格式 yyyy-MM')
    return false
  }
  if (isWeekly.value && !isWeekPeriod(v)) {
    ElMessage.warning('周薪批次请填写 yyyy-Www，例如 2026-W39')
    return false
  }
  if (!isWeekly.value && !isMonthPeriod(v)) {
    ElMessage.warning('月薪批次请填写 yyyy-MM，例如 2026-09')
    return false
  }
  return true
}

function fmtMoney(v: any) {
  const n = Number(v)
  if (Number.isNaN(n)) return '0.00'
  return n.toFixed(2)
}

function netOf(row: any) {
  const gross = Number(row.grossAmount || 0)
  const ded = Number(row.deductionAmount || 0)
  const net = Math.max(0, gross - ded)
  return net.toFixed(2)
}

function leaveDatesText(row: any) {
  const dates = row?.leaveDates || []
  if (!dates.length) return '全勤'
  return dates.join('、')
}

async function loadMeta() {
  companies.value = await sysApi.myCompanies()
  if (!companyId.value && companies.value.length) companyId.value = companies.value[0].id
  if (!yearMonth.value) yearMonth.value = defaultPeriod()
}

async function loadRuns() {
  if (!companyId.value) return
  loading.value = true
  try {
    const period = String(yearMonth.value || '').trim() || undefined
    const list = await bizApi.salaryRuns({
      companyId: companyId.value,
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

async function doPreview() {
  if (!companyId.value) return
  if (!assertPeriodMatchesTab()) return
  const tip = isWeekly.value
    ? '将为本周「周薪」配置人员发送/补发工资预告（不含考勤扣款）。日常发薪请用「算薪并发确认」。是否继续？'
    : '将为本月「月薪」配置人员发送/补发工资预告（不含考勤扣款）。日常发薪请用「算薪并发确认」。是否继续？'
  await ElMessageBox.confirm(tip, '手动预告')
  const run = await bizApi.salaryPreview({ companyId: companyId.value, yearMonth: yearMonth.value })
  ElMessage.success(run?.message || '预告完成')
  await loadRuns()
}

async function openPrepare() {
  if (!companyId.value) return
  if (!assertPeriodMatchesTab()) return
  prepareVisible.value = true
  prepareLoading.value = true
  prepareSelected.value = []
  try {
    const rows = await bizApi.salaryPrepareDraft({
      companyId: companyId.value,
      yearMonth: yearMonth.value,
    })
    prepareRows.value = (rows || []).map((r: any) => ({
      ...r,
      deductionAmount: Number(r.deductionAmount || 0),
      deductionRemark: r.deductionRemark || '',
    }))
  } finally {
    prepareLoading.value = false
  }
}

function onPrepareSelection(rows: any[]) {
  prepareSelected.value = rows
}

async function submitPrepare() {
  if (!companyId.value) return
  const targets = prepareSelected.value.length
    ? prepareSelected.value
    : prepareRows.value
  if (!targets.length) {
    ElMessage.warning('暂无可发确认的人员')
    return
  }
  for (const row of targets) {
    const leaveDays = Number(row.leaveDays || 0)
    const ded = Number(row.deductionAmount || 0)
    const remark = String(row.deductionRemark || '').trim()
    if (leaveDays > 0 && !remark) {
      ElMessage.warning(`${row.userName || row.userId} 非全勤，请填写扣款备注`)
      return
    }
    if (ded > 0 && !remark) {
      ElMessage.warning(`${row.userName || row.userId} 有扣款，请填写备注`)
      return
    }
    if (ded < 0) {
      ElMessage.warning(`${row.userName || row.userId} 扣款不能为负`)
      return
    }
    if (ded > Number(row.grossAmount || 0)) {
      ElMessage.warning(`${row.userName || row.userId} 扣款不能超过应发`)
      return
    }
  }
  await ElMessageBox.confirm(
    `将向 ${targets.length} 人发送工资确认（含扣款）。员工确认后即可手动发薪。是否继续？`,
    '算薪并发确认',
  )
  prepareSaving.value = true
  try {
    const run = await bizApi.salaryPrepareConfirm({
      companyId: companyId.value,
      yearMonth: yearMonth.value,
      lines: targets.map((r) => ({
        userId: r.userId,
        deductionAmount: Number(r.deductionAmount || 0),
        deductionRemark: String(r.deductionRemark || '').trim(),
      })),
    })
    ElMessage.success(run?.message || '已发送工资确认')
    prepareVisible.value = false
    await loadRuns()
  } finally {
    prepareSaving.value = false
  }
}

async function doPay() {
  if (!companyId.value) return
  if (!assertPeriodMatchesTab()) return
  const tip = isWeekly.value
    ? '将为已确认且余额足够的周薪人员生成审批，是否继续？'
    : '将为已确认且余额足够的月薪人员生成审批，是否继续？'
  await ElMessageBox.confirm(tip, '手动发薪')
  const run = await bizApi.salaryPay({ companyId: companyId.value, yearMonth: yearMonth.value })
  ElMessage.success(run?.message || '发薪完成')
  await loadRuns()
}

function onCycleTabChange() {
  yearMonth.value = defaultPeriod()
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
}

const triggerLabel: Record<string, string> = {
  AUTO: '定时',
  CRON: '定时',
  MANUAL: '手动',
}

watch(companyId, () => loadRuns())
watch(yearMonth, () => loadRuns())

onMounted(async () => {
  await loadMeta()
  await loadRuns()
})
</script>

<template>
  <div class="page-stack">
    <div class="page-card">
      <div class="toolbar">
        <div class="toolbar__left">
          <el-select v-model="companyId" placeholder="公司" style="width: 200px">
            <el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
          <el-radio-group v-model="cycleType" size="small" @change="onCycleTabChange">
            <el-radio-button value="MONTHLY">月薪批次</el-radio-button>
            <el-radio-button value="WEEKLY">周薪批次</el-radio-button>
          </el-radio-group>
          <el-input
            v-model="yearMonth"
            :placeholder="periodPlaceholder"
            clearable
            style="width: 140px"
            @keyup.enter="loadRuns"
          />
          <el-button type="primary" @click="loadRuns">查询</el-button>
        </div>
        <div class="toolbar__right">
          <el-button v-permission="'hr:salary:run:manual'" @click="doPreview">手动预告</el-button>
          <el-button v-permission="'hr:salary:run:manual'" type="warning" @click="openPrepare">
            算薪并发确认
          </el-button>
          <el-button v-permission="'hr:salary:run:manual'" type="primary" @click="doPay">手动发薪</el-button>
        </div>
      </div>

      <p class="tip">
        默认不自动发预告。发薪前用「算薪并发确认」：按考勤手填扣款与备注，发给员工确认后再「手动发薪」。
      </p>

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
              {{ row.phase === 'PREVIEW' ? '预告' : '发薪' }}
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
      </el-table>
    </div>

    <el-dialog
      v-model="prepareVisible"
      title="算薪并发确认"
      width="960px"
      destroy-on-close
    >
      <p class="tip">
        {{ isWeekly ? '周薪考勤按该周周一至周日。' : '月薪考勤按上月 21 日至本月 20 日。' }}
        非全勤请填写扣款金额与备注；未勾选则发送全部人员。实发 = 应发 − 扣款。
      </p>
      <el-table
        v-loading="prepareLoading"
        :data="prepareRows"
        stripe
        max-height="480"
        @selection-change="onPrepareSelection"
      >
        <el-table-column type="selection" width="46" />
        <el-table-column prop="userName" label="人员" width="110" />
        <el-table-column label="考勤" min-width="160">
          <template #default="{ row }">
            <el-tag size="small" :type="row.fullAttendance ? 'success' : 'warning'" effect="plain">
              {{ row.fullAttendance ? '全勤' : `请假 ${row.leaveDays} 天` }}
            </el-tag>
            <div v-if="!row.fullAttendance" class="leave-dates">{{ leaveDatesText(row) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="应发" width="100" align="right">
          <template #default="{ row }">¥{{ fmtMoney(row.grossAmount) }}</template>
        </el-table-column>
        <el-table-column label="扣款" width="130">
          <template #default="{ row }">
            <el-input-number
              v-model="row.deductionAmount"
              :min="0"
              :max="Number(row.grossAmount || 0)"
              :precision="2"
              :controls="false"
              style="width: 110px"
            />
          </template>
        </el-table-column>
        <el-table-column label="实发" width="100" align="right">
          <template #default="{ row }">¥{{ netOf(row) }}</template>
        </el-table-column>
        <el-table-column label="扣款备注" min-width="180">
          <template #default="{ row }">
            <el-input
              v-model="row.deductionRemark"
              :placeholder="row.fullAttendance ? '有扣款时必填' : '非全勤必填'"
              maxlength="200"
            />
          </template>
        </el-table-column>
        <el-table-column label="确认状态" width="100">
          <template #default="{ row }">
            {{ statusLabel[row.previewStatus] || (row.previewStatus ? row.previewStatus : '—') }}
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="prepareVisible = false">取消</el-button>
        <el-button type="primary" :loading="prepareSaving" @click="submitPrepare">发送确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.tip {
  margin: 0 0 14px;
  color: #64748b;
  font-size: 13px;
  line-height: 1.5;
}

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

.leave-dates {
  margin-top: 4px;
  color: #94a3b8;
  font-size: 12px;
  line-height: 1.4;
}
</style>
