<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { bizApi } from '@/api/biz'

const today = new Date()
const attendanceCycleDay = ref(20)
const calendarDate = ref(calendarMonthContaining(today, attendanceCycleDay.value))
const records = ref<any[]>([])
const dutyRecords = ref<any[]>([])
const todayAbsences = ref<any[]>([])
const users = ref<any[]>([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const dutyDialogVisible = ref(false)
const detailVisible = ref(false)
const selectedDate = ref('')
const selectedUserIds = ref<number[]>([])
const selectedDutyUserIds = ref<number[]>([])
const contextMenuVisible = ref(false)
const contextMenuDate = ref('')
const contextMenuX = ref(0)
const contextMenuY = ref(0)
const monthlyDetail = ref<any>({ workdayCount: 0, employees: [] })
const employeeKeyword = ref('')
const abnormalOnly = ref(false)
const employeeDetailVisible = ref(false)
const selectedEmployee = ref<any>(null)
const selectedEmployeeId = ref<number | null>(null)
const employeeDetailMonth = ref(new Date())
const employeeDetailLoading = ref(false)
const todayKey = `${today.getFullYear()}-${pad(today.getMonth() + 1)}-${pad(today.getDate())}`

const selectedDayRecords = computed(() => recordsByDay.value[selectedDate.value] || [])
const selectedDayDutyRecords = computed(() => dutyRecordsByDay.value[selectedDate.value] || [])
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
const attendancePeriod = computed(() => {
  const year = calendarDate.value.getFullYear()
  const month = calendarDate.value.getMonth()
  const endDay = Math.min(attendanceCycleDay.value, new Date(year, month + 1, 0).getDate())
  const previousMonthEnd = new Date(year, month, 0)
  const previousCycleDay = Math.min(attendanceCycleDay.value, previousMonthEnd.getDate())
  const start = new Date(previousMonthEnd.getFullYear(), previousMonthEnd.getMonth(), previousCycleDay + 1)
  const end = new Date(year, month, endDay)
  return { start, end, startKey: dateKey(start), endKey: dateKey(end) }
})
const attendancePeriodLabel = computed(() => `${attendancePeriod.value.startKey} 至 ${attendancePeriod.value.endKey}`)
const isCurrentAttendancePeriod = computed(() => {
  const currentPeriodMonth = calendarMonthContaining(today, attendanceCycleDay.value)
  return calendarDate.value.getFullYear() === currentPeriodMonth.getFullYear()
    && calendarDate.value.getMonth() === currentPeriodMonth.getMonth()
})
const employeeDetailMonthLabel = computed(() => `${employeeDetailMonth.value.getFullYear()}年${employeeDetailMonth.value.getMonth() + 1}月`)
const employeeDetailPeriodLabel = computed(() => {
  const start = selectedEmployee.value?.periodStart
  const end = selectedEmployee.value?.periodEnd
  return start && end ? `${start} 至 ${end}` : employeeDetailMonthLabel.value
})
const holidayByDay = computed<Record<string, any>>(() => {
  const result: Record<string, any> = {}
  for (const day of monthlyDetail.value?.calendar || []) result[String(day.date)] = day
  return result
})
const attendanceDays = computed(() => Math.round(
  (attendancePeriod.value.end.getTime() - attendancePeriod.value.start.getTime()) / 86400000,
) + 1)
const attendanceCalendarDays = computed<string[]>(() => {
  const first = new Date(attendancePeriod.value.start)
  first.setDate(first.getDate() - first.getDay())
  const last = new Date(attendancePeriod.value.end)
  last.setDate(last.getDate() + (6 - last.getDay()))
  const days: string[] = []
  for (let date = first; date <= last; date.setDate(date.getDate() + 1)) {
    days.push(dateKey(date))
  }
  return days
})
const expectedAttendance = computed(() => users.value.length * attendanceDays.value)
const workdayAbsenceCount = computed(() => records.value.length)
const actualAttendance = computed(() => Math.max(0, expectedAttendance.value - workdayAbsenceCount.value))
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
const employeeCalendarDays = computed<(any | null)[]>(() => {
  const days = selectedEmployee.value?.days || []
  if (!days.length) return []
  const firstDate = new Date(`${days[0].date}T00:00:00`)
  return [...Array(firstDate.getDay()).fill(null), ...days]
})

const recordsByDay = computed<Record<string, any[]>>(() => {
  const result: Record<string, any[]> = {}
  for (const row of records.value) {
    const day = String(row.leaveDate || '')
    if (day) (result[day] ||= []).push(row)
  }
  return result
})
const dutyRecordsByDay = computed<Record<string, any[]>>(() => {
  const result: Record<string, any[]> = {}
  for (const row of dutyRecords.value) {
    const day = String(row.dutyDate || '')
    if (day) (result[day] ||= []).push(row)
  }
  return result
})

function pad(value: number) { return String(value).padStart(2, '0') }

function dateKey(date: Date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

function calendarMonthContaining(date: Date, cycleDay: number) {
  const currentMonthCycleDay = Math.min(cycleDay, new Date(date.getFullYear(), date.getMonth() + 1, 0).getDate())
  const monthOffset = date.getDate() > currentMonthCycleDay ? 1 : 0
  return new Date(date.getFullYear(), date.getMonth() + monthOffset, 1)
}

function isAttendancePeriodDay(day: string) {
  return day >= attendancePeriod.value.startKey && day <= attendancePeriod.value.endKey
}

function onCalendarDayContextMenu(event: MouseEvent, day: string) {
  if (isAttendancePeriodDay(day)) onDayContextMenu(event, day)
}

async function load() {
  loading.value = true
  try {
    const range = { start: attendancePeriod.value.startKey, end: attendancePeriod.value.endKey }
    const month = `${calendarDate.value.getFullYear()}-${pad(calendarDate.value.getMonth() + 1)}`
    const [attendance, companyUsers, currentDayAttendance, detail, duty] = await Promise.all([
      bizApi.attendance(range),
      bizApi.attendanceUsers(),
      bizApi.attendance({ start: todayKey, end: todayKey }),
      bizApi.attendanceMonthlyDetail(month),
      bizApi.attendanceDuty(range),
    ])
    records.value = attendance || []
    users.value = companyUsers || []
    todayAbsences.value = currentDayAttendance || []
    monthlyDetail.value = detail || { workdayCount: 0, employees: [] }
    dutyRecords.value = duty || []
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
  contextMenuY.value = Math.min(event.clientY, window.innerHeight - 130)
  contextMenuVisible.value = true
}

function openLeaveSetting() {
  contextMenuVisible.value = false
  openDay(contextMenuDate.value)
}

function openDutySetting() {
  contextMenuVisible.value = false
  selectedDate.value = contextMenuDate.value
  selectedDutyUserIds.value = (dutyRecordsByDay.value[selectedDate.value] || [])
    .map(row => Number(row.userId))
  dutyDialogVisible.value = true
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

async function loadEmployeeDetail() {
  if (selectedEmployeeId.value == null) return
  employeeDetailLoading.value = true
  try {
    const month = `${employeeDetailMonth.value.getFullYear()}-${pad(employeeDetailMonth.value.getMonth() + 1)}`
    const detail = await bizApi.attendanceMonthlyDetail(month)
    const employee = (detail?.employees || []).find((row: any) => Number(row.userId) === selectedEmployeeId.value)
    if (!employee) {
      selectedEmployee.value = null
      return
    }
    const range = { start: employee.periodStart, end: employee.periodEnd }
    const duty = await bizApi.attendanceDuty(range)
    const dutyDates = new Set((duty || [])
      .filter((row: any) => Number(row.userId) === selectedEmployeeId.value)
      .map((row: any) => String(row.dutyDate)))
    selectedEmployee.value = {
      ...employee,
      days: (employee.days || []).map((day: any) => ({ ...day, duty: dutyDates.has(String(day.date)) })),
    }
  } finally {
    employeeDetailLoading.value = false
  }
}

async function showEmployeeDetail(employee: any) {
  selectedEmployeeId.value = Number(employee.userId)
  employeeDetailMonth.value = new Date(calendarDate.value.getFullYear(), calendarDate.value.getMonth(), 1)
  selectedEmployee.value = employee
  employeeDetailVisible.value = true
  await loadEmployeeDetail()
}

async function changeEmployeeDetailMonth(offset: number) {
  const date = employeeDetailMonth.value
  employeeDetailMonth.value = new Date(date.getFullYear(), date.getMonth() + offset, 1)
  await loadEmployeeDetail()
}

async function showCurrentEmployeeMonth() {
  employeeDetailMonth.value = new Date(today.getFullYear(), today.getMonth(), 1)
  await loadEmployeeDetail()
}

async function onEmployeeDetailMonthChange(value: Date | null) {
  if (!value) return
  employeeDetailMonth.value = new Date(value.getFullYear(), value.getMonth(), 1)
  await loadEmployeeDetail()
}

function changeCalendarMonth(offset: number) {
  const date = calendarDate.value
  calendarDate.value = new Date(date.getFullYear(), date.getMonth() + offset, 1)
}

function showCurrentCalendarMonth() {
  calendarDate.value = calendarMonthContaining(today, attendanceCycleDay.value)
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

async function saveDutyDay() {
  if (!selectedDate.value) return
  saving.value = true
  try {
    await bizApi.setAttendanceDutyDay({ date: selectedDate.value, userIds: selectedDutyUserIds.value })
    ElMessage.success(selectedDutyUserIds.value.length ? '值班人员已保存' : '该日值班人员已清空')
    dutyDialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

watch(() => `${calendarDate.value.getFullYear()}-${calendarDate.value.getMonth()}`, load)
watch(attendanceCycleDay, load)

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
        <span>本周期出勤率</span><strong>{{ attendanceRate }}</strong><small>{{ attendancePeriodLabel }}</small>
      </div>
      <div class="summary-card">
        <span>本周期缺勤人次</span><strong>{{ workdayAbsenceCount }}</strong><small>实际 {{ actualAttendance }} / 应出勤 {{ expectedAttendance }}</small>
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
        <div>
          <strong class="scope-title">全员考勤日历</strong>
          <p class="period-label">{{ attendancePeriodLabel }}</p>
        </div>
        <div class="attendance-period-controls">
          <span>考勤周期</span>
          <el-select v-model="attendanceCycleDay" style="width: 130px">
            <el-option v-for="day in 31" :key="day" :label="`每月 ${day} 日`" :value="day" />
          </el-select>
          <el-button-group class="calendar-month-actions">
            <el-button :disabled="loading" @click="changeCalendarMonth(-1)">上一月</el-button>
            <el-button :disabled="loading" @click="showCurrentCalendarMonth">本月</el-button>
            <el-button :disabled="loading" @click="changeCalendarMonth(1)">下一月</el-button>
          </el-button-group>
          <el-date-picker v-model="calendarDate" type="month" format="YYYY年MM月" :clearable="false" style="width: 150px" />
          <el-button
            v-if="!isCurrentAttendancePeriod"
            type="primary"
            plain
            :disabled="loading"
            @click="showCurrentCalendarMonth"
          >今天</el-button>
          <div class="legend"><i />存在未出勤员工</div>
        </div>
      </div>
      <p class="tip">周期从上月周期日的次日开始，到本月周期日结束。所有员工默认全勤；右键点击日期可设置未出勤员工。</p>
      <div v-loading="loading" class="attendance-calendar">
        <div v-for="weekday in ['日', '一', '二', '三', '四', '五', '六']" :key="weekday" class="attendance-calendar-weekday">{{ weekday }}</div>
        <template v-for="day in attendanceCalendarDays" :key="day">
          <div
            class="calendar-day"
            :class="{
              'is-outside-period': !isAttendancePeriodDay(day),
              'has-absence': (recordsByDay[day] || []).length > 0,
              'is-today': day === todayKey,
              'is-holiday': holidayByDay[day]?.type === 'HOLIDAY',
              'is-adjusted-workday': holidayByDay[day]?.type === 'ADJUSTED_WORKDAY',
            }"
            @contextmenu.prevent.stop="onCalendarDayContextMenu($event, day)"
          >
            <div class="day-heading">
              <span class="day-number">{{ Number(day.slice(5, 7)) }}/{{ Number(day.slice(-2)) }}</span>
              <span class="day-badges">
                <span v-if="day === todayKey" class="today-label">今天</span>
                <span v-if="holidayByDay[day]?.type === 'HOLIDAY'" class="holiday-label">休</span>
                <span v-else-if="holidayByDay[day]?.type === 'ADJUSTED_WORKDAY'" class="workday-label">班</span>
                <span v-if="(dutyRecordsByDay[day] || []).length" class="duty-label">值</span>
              </span>
            </div>
            <div class="day-metrics">
              <span v-if="holidayByDay[day]?.type === 'HOLIDAY'" class="holiday-name">{{ holidayByDay[day].name }}</span>
              <span v-else-if="holidayByDay[day]?.type === 'WEEKEND'" class="holiday-name">周末</span>
              <span v-else-if="holidayByDay[day]?.type === 'ADJUSTED_WORKDAY'" class="holiday-name">{{ holidayByDay[day].name }}补班</span>
              <span v-if="(recordsByDay[day] || []).length" class="absence-count">未出勤 {{ recordsByDay[day].length }} 人</span>
              <span v-if="(dutyRecordsByDay[day] || []).length" class="duty-count">值班 {{ dutyRecordsByDay[day].length }} 人</span>
              <span v-if="isAttendancePeriodDay(day)" class="attendance-rate">出勤率 {{ dayAttendanceRate(day) }}</span>
            </div>
          </div>
        </template>
      </div>
    </div>

    <div class="page-card monthly-detail-card">
      <div class="section-heading monthly-heading">
        <div>
          <h3>员工月度明细</h3>
          <p>{{ monthLabel }} · 按每位员工设置的考勤周期统计</p>
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
        <el-table-column prop="expectedDays" label="周期应出勤" width="110" align="center" />
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
        <button type="button" @click="openDutySetting">值班设置</button>
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

    <el-dialog v-model="dutyDialogVisible" :title="`${selectedDate} · 值班设置`" width="520px">
      <p class="dialog-tip">可多选当天值班员工；清空选择表示当天无人值班。</p>
      <el-checkbox-group v-model="selectedDutyUserIds" class="employee-list">
        <el-checkbox v-for="user in users" :key="user.id" :value="Number(user.id)">
          {{ user.name }}<small v-if="user.username">（{{ user.username }}）</small>
        </el-checkbox>
      </el-checkbox-group>
      <el-empty v-if="!users.length" description="暂无启用考勤的员工" :image-size="64" />
      <template #footer>
        <el-button @click="dutyDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveDutyDay">保存</el-button>
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
      <div v-if="selectedDayDutyRecords.length" class="duty-detail">
        <strong>值班人员</strong>
        <el-tag v-for="row in selectedDayDutyRecords" :key="row.id" type="primary" effect="plain">
          {{ row.userName || `员工 ${row.userId}` }}
        </el-tag>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button type="primary" @click="detailVisible = false; openDay(selectedDate)">修改请假设置</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="employeeDetailVisible"
      :title="`${selectedEmployee?.employeeName || ''} · ${employeeDetailPeriodLabel}考勤日历`"
      width="min(1100px, 94vw)"
      top="5vh"
      class="employee-calendar-dialog"
    >
      <div class="employee-calendar-toolbar">
        <el-button-group>
          <el-button :disabled="employeeDetailLoading" @click="changeEmployeeDetailMonth(-1)">上一月</el-button>
          <el-button :disabled="employeeDetailLoading" @click="showCurrentEmployeeMonth">本月</el-button>
          <el-button :disabled="employeeDetailLoading" @click="changeEmployeeDetailMonth(1)">下一月</el-button>
        </el-button-group>
        <el-date-picker
          :model-value="employeeDetailMonth"
          :disabled="employeeDetailLoading"
          type="month"
          format="YYYY年MM月"
          placeholder="选择月份"
          :clearable="false"
          style="width: 150px"
          @change="onEmployeeDetailMonthChange"
        />
      </div>
      <div v-loading="employeeDetailLoading" class="employee-calendar-content">
      <div v-if="selectedEmployee" class="employee-month-summary">
        <div><span>应出勤</span><strong>{{ selectedEmployee.expectedDays }}</strong></div>
        <div><span>实际出勤</span><strong>{{ selectedEmployee.presentDays }}</strong></div>
        <div><span>未出勤</span><strong class="warning-text">{{ selectedEmployee.absentDays }}</strong></div>
        <div><span>出勤率</span><strong>{{ Number(selectedEmployee.attendanceRate).toFixed(1) }}%</strong></div>
      </div>
      <div v-if="selectedEmployee" class="employee-calendar-legend">
        <span class="calendar-default-tip">考勤周期：每月 {{ selectedEmployee.attendanceCycleDay }} 日；无标记日期均为正常出勤</span>
        <span><i class="legend-dot absent" />未出勤</span>
        <span><i class="legend-dot holiday" />节假日</span>
        <span><i class="legend-dot adjusted" />调班</span>
        <span><i class="legend-dot duty" />值班</span>
      </div>
      <div v-if="selectedEmployee" class="employee-calendar">
        <div v-for="weekday in ['日', '一', '二', '三', '四', '五', '六']" :key="weekday" class="employee-calendar-weekday">
          {{ weekday }}
        </div>
        <div
          v-for="(day, index) in employeeCalendarDays"
          :key="day?.date || `empty-${index}`"
          class="employee-calendar-cell"
          :class="{
            'is-empty': !day,
            'is-absent': day?.status === 'ABSENT',
            'is-holiday': day?.dayType === 'HOLIDAY',
            'is-adjusted': day?.dayType === 'ADJUSTED_WORKDAY',
            'is-weekend': day?.dayType === 'WEEKEND',
            'is-today': day?.date === todayKey,
          }"
        >
          <template v-if="day">
            <div class="employee-calendar-date">
              <strong>{{ Number(day.date.slice(5, 7)) }}/{{ Number(day.date.slice(-2)) }}</strong>
              <span class="day-badges">
                <span v-if="day.date === todayKey" class="employee-today-badge">今</span>
                <span v-if="day.dayType === 'HOLIDAY'" class="employee-special-badge holiday">休</span>
                <span v-else-if="day.dayType === 'ADJUSTED_WORKDAY'" class="employee-special-badge adjusted">班</span>
                <span v-if="day.duty" class="employee-special-badge duty">值</span>
              </span>
            </div>
            <span
              v-if="day.holidayName"
              class="employee-calendar-holiday"
              :class="{ weekend: day.dayType === 'WEEKEND' }"
            >{{ day.dayType === 'ADJUSTED_WORKDAY' ? `${day.holidayName}调班` : day.holidayName }}</span>
            <div class="employee-day-status" :class="{ absent: day.status === 'ABSENT' }">
              <i />
              <span>{{ day.status === 'ABSENT' ? '未出勤' : '正常出勤' }}</span>
            </div>
          </template>
        </div>
      </div>
      <el-empty v-else-if="!employeeDetailLoading" description="该员工在所选月份暂无考勤数据" :image-size="72" />
      </div>
    </el-dialog>
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
.period-label { margin: 5px 0 0; color: #94a3b8; font-size: 12px; }
.attendance-period-controls { display: flex; align-items: center; gap: 10px; color: #64748b; font-size: 13px; }
.attendance-calendar { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); overflow: hidden; border: 1px solid #e5e7eb; border-radius: 10px; background: #e5e7eb; gap: 1px; }
.attendance-calendar-weekday { padding: 11px 4px; background: #f8fafc; color: #64748b; font-size: 12px; font-weight: 600; text-align: center; }
.tip, .dialog-tip { color: #64748b; font-size: 13px; line-height: 1.6; }
.legend { display: flex; align-items: center; gap: 7px; color: #64748b; font-size: 13px; }
.legend i { width: 14px; height: 14px; border-radius: 4px; background: #fef3c7; border: 1px solid #f59e0b; }
:deep(.el-calendar-day) { height: 104px; padding: 4px; box-sizing: border-box; }
.calendar-day { min-height: 104px; padding: 8px; background: #fff; box-sizing: border-box; cursor: context-menu; }
.calendar-day.is-outside-period { background: #f8fafc; color: #a8b0bd; cursor: default; }
.calendar-day.is-outside-period .holiday-name { color: #b6bec9; }
.calendar-day.has-absence { background: #fef3c7; color: #92400e; }
.calendar-day.is-holiday { background: #f8fafc; }
.calendar-day.is-holiday.has-absence { background: #fef3c7; color: #92400e; }
.calendar-day.is-adjusted-workday { box-shadow: inset 0 0 0 1px #fb923c; }
.calendar-day.is-today { border: 2px solid #2563eb; background: #eff6ff; box-shadow: inset 0 0 0 1px rgba(37, 99, 235, 0.12); }
.calendar-day.is-today.has-absence { background: #fef3c7; border-color: #2563eb; }
.day-heading { display: flex; align-items: center; justify-content: space-between; gap: 6px; }
.day-number { display: block; font-weight: 600; }
.today-label { padding: 2px 7px; border-radius: 999px; background: #2563eb; color: #fff; font-size: 11px; font-weight: 600; line-height: 18px; }
.day-metrics { display: flex; flex-flow: row wrap; align-items: center; gap: 4px 10px; margin-top: 8px; }
.day-metrics > span { flex: 0 0 auto; white-space: nowrap; }
.absence-count, .attendance-rate { display: block; font-size: 12px; }
.attendance-rate { color: #64748b; }
.holiday-label, .workday-label { display: inline-flex; align-items: center; justify-content: center; width: 20px; height: 20px; border-radius: 50%; font-size: 11px; color: #fff; }
.day-badges { display: inline-flex; align-items: center; gap: 4px; }
.holiday-label { background: #ef4444; }
.workday-label { background: #f97316; }
.duty-label { display: inline-flex; align-items: center; justify-content: center; width: 20px; height: 20px; border-radius: 50%; background: #2563eb; color: #fff; font-size: 11px; font-weight: 600; }
.duty-count { color: #2563eb; font-size: 12px; }
.holiday-name { display: block; color: #64748b; font-size: 12px; }
.duty-detail { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-top: 16px; padding-top: 16px; border-top: 1px solid #e5e7eb; }
.duty-detail strong { margin-right: 4px; color: #334155; font-size: 14px; }
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
.employee-calendar-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 18px; }
.employee-calendar-content { min-height: 320px; }
.employee-calendar-legend { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 14px; margin: 0 0 12px; color: #64748b; font-size: 12px; }
.employee-calendar-legend span { display: inline-flex; align-items: center; gap: 5px; }
.employee-calendar-legend .calendar-default-tip { margin-right: auto; color: #94a3b8; }
.legend-dot { width: 8px; height: 8px; border-radius: 50%; }
.legend-dot.absent { background: #f59e0b; }
.legend-dot.holiday { background: #fca5a5; }
.legend-dot.adjusted { background: #fdba74; }
.legend-dot.duty { background: #93c5fd; }
.employee-calendar { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); overflow: hidden; border: 1px solid #e5e7eb; border-radius: 12px; background: #eef0f3; gap: 1px; }
.employee-calendar-weekday { padding: 10px 4px; background: #f8fafc; color: #64748b; font-size: 12px; font-weight: 600; text-align: center; }
.employee-calendar-cell { min-height: 104px; padding: 10px; background: #fff; box-sizing: border-box; }
.employee-calendar-cell.is-empty { background: #f8fafc; }
.employee-calendar-cell.is-holiday { background: #fff; }
.employee-calendar-cell.is-weekend { background: #fcfcfd; }
.employee-calendar-cell.is-adjusted { box-shadow: inset 0 2px 0 #fed7aa; }
.employee-calendar-cell.is-absent { background: #fffbeb; box-shadow: inset 0 2px 0 #f59e0b; }
.employee-calendar-cell.is-today { box-shadow: inset 0 0 0 1px #93c5fd; }
.employee-calendar-cell.is-today.is-absent { box-shadow: inset 0 2px 0 #f59e0b, inset 0 0 0 1px #93c5fd; }
.employee-calendar-date { display: flex; align-items: center; justify-content: space-between; min-height: 22px; color: #334155; }
.employee-calendar-date strong { font-size: 15px; }
.employee-special-badge { display: inline-flex; align-items: center; justify-content: center; min-width: 20px; height: 20px; padding: 0 5px; border-radius: 6px; font-size: 11px; font-weight: 600; }
.employee-special-badge.holiday { background: #fef2f2; color: #dc2626; }
.employee-special-badge.adjusted { background: #fff7ed; color: #ea580c; }
.employee-special-badge.duty { background: #eff6ff; color: #2563eb; }
.employee-today-badge { display: inline-flex; align-items: center; justify-content: center; min-width: 20px; height: 20px; border-radius: 6px; background: #eff6ff; color: #2563eb; font-size: 11px; font-weight: 600; }
.employee-calendar-holiday { display: block; overflow: hidden; margin-top: 6px; color: #dc2626; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.employee-calendar-holiday.weekend { color: #a1a1aa; }
.employee-day-status { display: flex; align-items: center; gap: 5px; margin-top: 8px; color: #64748b; font-size: 11px; }
.employee-day-status i { width: 6px; height: 6px; border-radius: 50%; background: #86c97a; }
.employee-day-status.absent { color: #b45309; font-weight: 600; }
.employee-day-status.absent i { background: #f59e0b; }
@media (max-width: 640px) {
  .summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .detail-summary { grid-template-columns: repeat(2, 1fr); }
  .employee-list { grid-template-columns: 1fr; }
  :deep(.el-calendar-day) { height: 88px; }
  .calendar-day { padding: 5px; }
  .day-metrics { gap: 2px 6px; margin-top: 4px; }
  .absence-count { font-size: 10px; }
  .monthly-heading, .monthly-filters { align-items: flex-start; flex-direction: column; }
  .toolbar, .attendance-period-controls { align-items: flex-start; flex-direction: column; }
  .employee-month-summary { grid-template-columns: repeat(2, 1fr); }
  .employee-calendar-toolbar { align-items: stretch; flex-direction: column; }
  .employee-calendar-cell { min-height: 82px; padding: 5px; }
  .employee-calendar-holiday { display: none; }
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
