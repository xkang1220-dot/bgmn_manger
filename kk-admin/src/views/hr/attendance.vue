<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { bizApi } from '@/api/biz'

const calendarDate = ref(new Date())
const records = ref<any[]>([])
const todayAbsences = ref<any[]>([])
const users = ref<any[]>([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const detailVisible = ref(false)
const selectedDate = ref('')
const selectedUserIds = ref<number[]>([])
const contextMenuVisible = ref(false)
const contextMenuDate = ref('')
const contextMenuX = ref(0)
const contextMenuY = ref(0)
const monthlyDetail = ref<any>({ workdayCount: 0, employees: [] })
const employeeKeyword = ref('')
const abnormalOnly = ref(false)
const employeeDetailVisible = ref(false)
const selectedEmployee = ref<any>(null)
const today = new Date()
const todayKey = `${today.getFullYear()}-${pad(today.getMonth() + 1)}-${pad(today.getDate())}`

const selectedDayRecords = computed(() => recordsByDay.value[selectedDate.value] || [])
const todayRecords = computed(() => todayAbsences.value)
const activeDateKey = computed(() => {
  const date = calendarDate.value
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
})
const activeDateRecords = computed(() => activeDateKey.value === todayKey
  ? todayRecords.value
  : (recordsByDay.value[activeDateKey.value] || []))
const activeDateTitle = computed(() => {
  if (activeDateKey.value === todayKey) return '今日未出勤'
  const [year, month, day] = activeDateKey.value.split('-').map(Number)
  return `${year}年${month}月${day}日未出勤`
})
const monthLabel = computed(() => `${calendarDate.value.getFullYear()}年${calendarDate.value.getMonth() + 1}月`)
const attendanceDays = computed(() => {
  const year = calendarDate.value.getFullYear()
  const month = calendarDate.value.getMonth()
  if (year > today.getFullYear() || (year === today.getFullYear() && month > today.getMonth())) return 0
  if (year === today.getFullYear() && month === today.getMonth()) return today.getDate()
  return new Date(year, month + 1, 0).getDate()
})
const expectedAttendance = computed(() => users.value.length * attendanceDays.value)
const actualAttendance = computed(() => Math.max(0, expectedAttendance.value - records.value.length))
const attendanceRate = computed(() => expectedAttendance.value
  ? `${(actualAttendance.value / expectedAttendance.value * 100).toFixed(1)}%`
  : '--')
const monthlyEmployees = computed<any[]>(() => monthlyDetail.value?.employees || [])
const filteredMonthlyEmployees = computed(() => {
  const keyword = employeeKeyword.value.trim().toLowerCase()
  return monthlyEmployees.value.filter((employee) => {
    if (abnormalOnly.value && Number(employee.absentDays) <= 0) return false
    if (!keyword) return true
    return String(employee.employeeName || '').toLowerCase().includes(keyword)
      || String(employee.username || '').toLowerCase().includes(keyword)
  })
})

const recordsByDay = computed<Record<string, any[]>>(() => {
  const result: Record<string, any[]> = {}
  for (const row of records.value) {
    const day = String(row.leaveDate || '')
    if (day) (result[day] ||= []).push(row)
  }
  return result
})

function pad(value: number) { return String(value).padStart(2, '0') }

function monthRange(date: Date) {
  const year = date.getFullYear()
  const month = date.getMonth()
  return {
    start: `${year}-${pad(month + 1)}-01`,
    end: `${year}-${pad(month + 1)}-${pad(new Date(year, month + 1, 0).getDate())}`,
  }
}

async function load() {
  loading.value = true
  try {
    const range = monthRange(calendarDate.value)
    const month = range.start.slice(0, 7)
    const [attendance, companyUsers, currentDayAttendance, detail] = await Promise.all([
      bizApi.attendance(range),
      bizApi.attendanceUsers(),
      bizApi.attendance({ start: todayKey, end: todayKey }),
      bizApi.attendanceMonthlyDetail(month),
    ])
    records.value = attendance || []
    users.value = companyUsers || []
    todayAbsences.value = currentDayAttendance || []
    monthlyDetail.value = detail || { workdayCount: 0, employees: [] }
  } finally {
    loading.value = false
  }
}

function openDay(day: string) {
  selectedDate.value = day
  selectedUserIds.value = (recordsByDay.value[day] || []).map((row) => Number(row.userId))
  dialogVisible.value = true
}

function onDayContextMenu(event: MouseEvent, day: string) {
  contextMenuDate.value = day
  contextMenuX.value = Math.min(event.clientX, window.innerWidth - 150)
  contextMenuY.value = Math.min(event.clientY, window.innerHeight - 60)
  contextMenuVisible.value = true
}

function openLeaveSetting() {
  contextMenuVisible.value = false
  openDay(contextMenuDate.value)
}

function openDayDetail() {
  contextMenuVisible.value = false
  selectedDate.value = contextMenuDate.value
  detailVisible.value = true
}

function dayAttendanceRate(day: string) {
  if (!users.value.length) return '--'
  const absent = (recordsByDay.value[day] || []).length
  return `${Math.max(0, (users.value.length - absent) / users.value.length * 100).toFixed(0)}%`
}

function showActiveDayDetail() {
  selectedDate.value = activeDateKey.value
  detailVisible.value = true
}

function showEmployeeDetail(employee: any) {
  selectedEmployee.value = employee
  employeeDetailVisible.value = true
}

function dayStatus(day: any) {
  if (day.status === 'ABSENT') return '未出勤'
  return '出勤'
}

function dayStatusType(day: any) {
  if (day.status === 'ABSENT') return 'warning'
  return 'success'
}

function closeContextMenu() {
  contextMenuVisible.value = false
}

async function saveDay() {
  if (!selectedDate.value) return
  saving.value = true
  try {
    await bizApi.setAttendanceDay({ date: selectedDate.value, userIds: selectedUserIds.value })
    ElMessage.success(selectedUserIds.value.length ? '未出勤人员已保存' : '该日已恢复全勤')
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

watch(() => `${calendarDate.value.getFullYear()}-${calendarDate.value.getMonth()}`, load)

onMounted(async () => {
  document.addEventListener('click', closeContextMenu)
  window.addEventListener('blur', closeContextMenu)
  await load()
})

onUnmounted(() => {
  document.removeEventListener('click', closeContextMenu)
  window.removeEventListener('blur', closeContextMenu)
})
</script>

<template>
  <div class="page-stack">
    <div class="summary-grid">
      <div class="summary-card summary-card-primary">
        <span>全体员工</span><strong>{{ users.length }}</strong><small>全局统计人数</small>
      </div>
      <div class="summary-card">
        <span>今日实际出勤</span><strong>{{ Math.max(0, users.length - todayRecords.length) }}</strong><small>默认全勤口径</small>
      </div>
      <div class="summary-card summary-card-warning">
        <span>今日未出勤</span><strong>{{ todayRecords.length }}</strong><small>{{ todayRecords.length ? '请及时核对' : '今日暂无异常' }}</small>
      </div>
      <div class="summary-card">
        <span>{{ monthLabel }}出勤率</span><strong>{{ attendanceRate }}</strong><small>按自然日累计</small>
      </div>
      <div class="summary-card">
        <span>所选月缺勤人次</span><strong>{{ records.length }}</strong><small>实际 {{ actualAttendance }} / 应出勤 {{ expectedAttendance }}</small>
      </div>
    </div>

    <div class="page-card today-panel">
      <div class="section-heading">
        <div><h3>{{ activeDateTitle }}</h3><p>{{ activeDateKey }} · 共 {{ activeDateRecords.length }} 人</p></div>
        <el-button v-if="activeDateRecords.length" text type="primary" @click="showActiveDayDetail">查看明细</el-button>
      </div>
      <div v-if="activeDateRecords.length" class="absence-users">
        <div v-for="row in activeDateRecords" :key="row.id" class="absence-user">
          <span class="avatar">{{ (row.userName || '?').slice(0, 1) }}</span>
          <div><strong>{{ row.userName || `员工 ${row.userId}` }}</strong><small>未出勤</small></div>
        </div>
      </div>
      <el-empty v-else :description="activeDateKey === todayKey ? '今日全员出勤，暂无异常' : '当日全员出勤，暂无异常'" :image-size="58" />
    </div>

    <div class="page-card">
      <div class="toolbar">
        <strong class="scope-title">全员考勤日历</strong>
        <div class="legend"><i />存在未出勤员工</div>
      </div>
      <p class="tip">所有员工默认全勤。右键点击日期，可设置当天未出勤的员工；清空选择即可恢复全勤。</p>
      <el-calendar v-loading="loading" v-model="calendarDate">
        <template #date-cell="{ data }">
          <div
            class="calendar-day"
            :class="{
              'has-absence': (recordsByDay[data.day] || []).length > 0,
              'is-today': data.day === todayKey,
            }"
            @contextmenu.prevent.stop="onDayContextMenu($event, data.day)"
          >
            <div class="day-heading">
              <span class="day-number">{{ Number(data.day.slice(-2)) }}</span>
              <span v-if="data.day === todayKey" class="today-label">今天</span>
            </div>
            <div class="day-metrics">
              <span v-if="(recordsByDay[data.day] || []).length" class="absence-count">未出勤 {{ recordsByDay[data.day].length }} 人</span>
              <span v-if="data.type === 'current-month'" class="attendance-rate">出勤率 {{ dayAttendanceRate(data.day) }}</span>
            </div>
          </div>
        </template>
      </el-calendar>
    </div>

    <div class="page-card monthly-detail-card">
      <div class="section-heading monthly-heading">
        <div>
          <h3>员工月度明细</h3>
          <p>{{ monthLabel }} · 按自然日统计，共 {{ monthlyDetail.workdayCount || 0 }} 个应出勤日</p>
        </div>
        <div class="monthly-filters">
          <el-input v-model="employeeKeyword" clearable placeholder="搜索员工姓名或账号" style="width: 220px" />
          <el-checkbox v-model="abnormalOnly">仅看异常员工</el-checkbox>
        </div>
      </div>
      <el-table v-loading="loading" :data="filteredMonthlyEmployees" stripe class="monthly-table">
        <el-table-column label="员工" min-width="150" fixed="left">
          <template #default="{ row }">
            <div class="employee-cell"><strong>{{ row.employeeName }}</strong><small>{{ row.username }}</small></div>
          </template>
        </el-table-column>
        <el-table-column prop="expectedDays" label="应出勤" width="100" align="center" />
        <el-table-column prop="presentDays" label="实际出勤" width="110" align="center" />
        <el-table-column prop="absentDays" label="未出勤" width="100" align="center">
          <template #default="{ row }"><span :class="{ 'warning-text': row.absentDays > 0 }">{{ row.absentDays }}</span></template>
        </el-table-column>
        <el-table-column label="出勤率" width="110" align="center">
          <template #default="{ row }"><el-tag :type="row.attendanceRate < 100 ? 'warning' : 'success'">{{ Number(row.attendanceRate).toFixed(1) }}%</el-tag></template>
        </el-table-column>
        <el-table-column label="未出勤日期" min-width="260">
          <template #default="{ row }">
            <div v-if="row.absentDates?.length" class="date-tags">
              <el-tag v-for="date in row.absentDates" :key="date" type="warning" effect="plain" size="small">{{ date.slice(5) }}</el-tag>
            </div>
            <span v-else class="normal-text">全勤</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }"><el-button link type="primary" @click="showEmployeeDetail(row)">查看明细</el-button></template>
        </el-table-column>
        <template #empty><el-empty description="暂无匹配员工" :image-size="64" /></template>
      </el-table>
    </div>

    <Teleport to="body">
      <div
        v-if="contextMenuVisible"
        class="attendance-context-menu"
        :style="{ left: `${contextMenuX}px`, top: `${contextMenuY}px` }"
        @click.stop
        @contextmenu.prevent
      >
        <button type="button" @click="openDayDetail">查看当日明细</button>
        <button type="button" @click="openLeaveSetting">请假设置</button>
      </div>
    </Teleport>

    <el-dialog v-model="dialogVisible" :title="`${selectedDate} · 请假设置`" width="520px">
      <p class="dialog-tip">勾选当天未出勤的员工；未勾选员工均视为全勤。</p>
      <el-checkbox-group v-model="selectedUserIds" class="employee-list">
        <el-checkbox v-for="user in users" :key="user.id" :value="Number(user.id)">
          {{ user.name }}<small v-if="user.username">（{{ user.username }}）</small>
        </el-checkbox>
      </el-checkbox-group>
      <el-empty v-if="!users.length" description="该公司暂无员工" :image-size="64" />
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveDay">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="detailVisible" :title="`${selectedDate} · 考勤明细`" width="600px">
      <div class="detail-summary">
        <div><span>应出勤</span><strong>{{ users.length }}</strong></div>
        <div><span>实际出勤</span><strong>{{ Math.max(0, users.length - selectedDayRecords.length) }}</strong></div>
        <div><span>未出勤</span><strong class="warning-text">{{ selectedDayRecords.length }}</strong></div>
        <div><span>出勤率</span><strong>{{ dayAttendanceRate(selectedDate) }}</strong></div>
      </div>
      <el-table v-if="selectedDayRecords.length" :data="selectedDayRecords" stripe>
        <el-table-column prop="userName" label="员工" min-width="150">
          <template #default="{ row }">{{ row.userName || `员工 ${row.userId}` }}</template>
        </el-table-column>
        <el-table-column label="考勤状态" width="120"><el-tag type="warning">未出勤</el-tag></el-table-column>
        <el-table-column prop="createTime" label="登记时间" min-width="180" />
      </el-table>
      <el-empty v-else description="当日全员出勤" :image-size="72" />
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button type="primary" @click="detailVisible = false; openDay(selectedDate)">修改请假设置</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="employeeDetailVisible" :title="`${selectedEmployee?.employeeName || ''} · ${monthLabel}考勤明细`" size="680px">
      <div v-if="selectedEmployee" class="employee-month-summary">
        <div><span>应出勤</span><strong>{{ selectedEmployee.expectedDays }}</strong></div>
        <div><span>实际出勤</span><strong>{{ selectedEmployee.presentDays }}</strong></div>
        <div><span>未出勤</span><strong class="warning-text">{{ selectedEmployee.absentDays }}</strong></div>
        <div><span>出勤率</span><strong>{{ Number(selectedEmployee.attendanceRate).toFixed(1) }}%</strong></div>
      </div>
      <el-table v-if="selectedEmployee" :data="selectedEmployee.days" stripe>
        <el-table-column prop="date" label="日期" width="120" />
        <el-table-column prop="weekday" label="星期" width="85" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }"><el-tag :type="dayStatusType(row)">{{ dayStatus(row) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="reason" label="说明" min-width="170">
          <template #default="{ row }">{{ row.reason || '—' }}</template>
        </el-table-column>
        <el-table-column prop="operatorName" label="登记人" width="100">
          <template #default="{ row }">{{ row.operatorName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="registeredAt" label="登记时间" width="170">
          <template #default="{ row }">{{ row.registeredAt || '—' }}</template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>

<style scoped>
.summary-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 14px; }
.summary-card { min-width: 0; padding: 18px 20px; border: 1px solid #e5e7eb; border-radius: 12px; background: #fff; box-shadow: 0 2px 8px rgba(15, 23, 42, .04); }
.summary-card span, .summary-card small { display: block; color: #64748b; font-size: 13px; }
.summary-card strong { display: block; margin: 8px 0 5px; color: #0f172a; font-size: 28px; line-height: 1; }
.summary-card-primary { border-top: 3px solid #2563eb; }
.summary-card-warning { border-top: 3px solid #f59e0b; }
.summary-card-warning strong, .warning-text { color: #d97706 !important; }
.section-heading { display: flex; align-items: center; justify-content: space-between; }
.section-heading h3 { margin: 0; color: #1e293b; font-size: 16px; }
.section-heading p { margin: 5px 0 0; color: #94a3b8; font-size: 12px; }
.absence-users { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 18px; }
.absence-user { display: flex; align-items: center; gap: 10px; min-width: 180px; padding: 10px 14px; border-radius: 9px; background: #fff7ed; }
.absence-user .avatar { display: grid; width: 34px; height: 34px; place-items: center; border-radius: 50%; background: #f59e0b; color: white; font-weight: 600; }
.absence-user strong, .absence-user small { display: block; }
.absence-user strong { color: #334155; font-size: 14px; }.absence-user small { margin-top: 3px; color: #d97706; font-size: 12px; }
.toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.scope-title { color: #334155; font-size: 15px; }
.tip, .dialog-tip { color: #64748b; font-size: 13px; line-height: 1.6; }
.legend { display: flex; align-items: center; gap: 7px; color: #64748b; font-size: 13px; }
.legend i { width: 14px; height: 14px; border-radius: 4px; background: #fef3c7; border: 1px solid #f59e0b; }
:deep(.el-calendar-day) { padding: 4px; }
.calendar-day { height: 100%; min-height: 72px; padding: 8px; border-radius: 8px; box-sizing: border-box; cursor: context-menu; }
.calendar-day.has-absence { background: #fef3c7; color: #92400e; }
.calendar-day.is-today { border: 2px solid #2563eb; background: #eff6ff; box-shadow: inset 0 0 0 1px rgba(37, 99, 235, 0.12); }
.calendar-day.is-today.has-absence { background: #fef3c7; border-color: #2563eb; }
.day-heading { display: flex; align-items: center; justify-content: space-between; gap: 6px; }
.day-number { display: block; font-weight: 600; }
.today-label { padding: 2px 7px; border-radius: 999px; background: #2563eb; color: #fff; font-size: 11px; font-weight: 600; line-height: 18px; }
.day-metrics { display: flex; flex-direction: column; gap: 3px; margin-top: 8px; }
.absence-count, .attendance-rate { display: block; font-size: 12px; }
.attendance-rate { color: #64748b; }
.detail-summary { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-bottom: 18px; }
.detail-summary div { padding: 12px; border-radius: 8px; background: #f8fafc; text-align: center; }
.detail-summary span, .detail-summary strong { display: block; }.detail-summary span { color: #64748b; font-size: 12px; }.detail-summary strong { margin-top: 5px; color: #1e293b; font-size: 20px; }
.employee-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px 16px; max-height: 360px; overflow: auto; }
.employee-list :deep(.el-checkbox) { margin-right: 0; }
.employee-list small { color: #94a3b8; }
.monthly-detail-card { overflow: hidden; }
.monthly-heading { gap: 20px; }
.monthly-filters { display: flex; align-items: center; gap: 18px; }
.monthly-table { margin-top: 18px; }
.employee-cell strong, .employee-cell small { display: block; }
.employee-cell small { margin-top: 3px; color: #94a3b8; font-size: 12px; }
.date-tags { display: flex; flex-wrap: wrap; gap: 5px; }
.normal-text { color: #16a34a; }
.employee-month-summary { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-bottom: 18px; }
.employee-month-summary div { padding: 14px; border-radius: 9px; background: #f8fafc; text-align: center; }
.employee-month-summary span, .employee-month-summary strong { display: block; }
.employee-month-summary span { color: #64748b; font-size: 12px; }
.employee-month-summary strong { margin-top: 6px; color: #1e293b; font-size: 20px; }
@media (max-width: 640px) {
  .summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .detail-summary { grid-template-columns: repeat(2, 1fr); }
  .employee-list { grid-template-columns: 1fr; }
  .calendar-day { min-height: 54px; padding: 5px; }
  .absence-count { margin-top: 4px; font-size: 10px; }
  .monthly-heading, .monthly-filters { align-items: flex-start; flex-direction: column; }
  .employee-month-summary { grid-template-columns: repeat(2, 1fr); }
}
@media (min-width: 641px) and (max-width: 1100px) { .summary-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
</style>

<style>
.attendance-context-menu {
  position: fixed;
  z-index: 4000;
  min-width: 140px;
  padding: 6px;
  border: 1px solid #e2e8f0;
  border-radius: 9px;
  background: #fff;
  box-shadow: 0 12px 30px rgba(15, 23, 42, 0.16);
}
.attendance-context-menu button {
  width: 100%;
  padding: 9px 12px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #334155;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.attendance-context-menu button:hover {
  background: #f1f5f9;
  color: #2563eb;
}
</style>
