<script setup lang="ts">
import { computed } from 'vue'

type SubtaskItem = {
  id: number
  title: string
  startDate?: string | null
  dueDate?: string | null
  completedAt?: string | null
  status?: number
  assigneeName?: string | null
  holderName?: string | null
  overdue?: boolean
}

const props = defineProps<{
  tasks: SubtaskItem[]
  /** 父任务计划开始 / 实际开始 */
  parentStartDate?: string | null
  parentStartedAt?: string | null
  /** 父任务实际完成时间 */
  parentCompletedAt?: string | null
  parentStatus?: number | null
}>()
const emit = defineEmits<{ open: [task: SubtaskItem] }>()

const DAY_MS = 24 * 60 * 60 * 1000
const DAY_WIDTH = 48

const statusMap: Record<number, string> = { 0: '待办', 1: '进行中', 2: '已完成', 3: '已关闭', 4: '待确认完成' }
const statusLegend = [
  { status: 0, label: '待办' },
  { status: 1, label: '进行中' },
  { status: 2, label: '已完成' },
  { status: 4, label: '待确认完成' },
  { status: 3, label: '已关闭' },
] as const

function parseDate(value?: string | null) {
  if (!value) return null
  const match = String(value).match(/^(\d{4})-(\d{1,2})-(\d{1,2})/)
  if (!match) return null
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]))
  return Number.isNaN(date.getTime()) ? null : date
}

function formatDate(value?: string | null) {
  const date = parseDate(value)
  if (!date) return '未设置'
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${m}-${d}`
}

function formatShort(value?: string | null) {
  const date = parseDate(value)
  if (!date) return '--'
  return `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

function dayMs(date: Date) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
}

function currentDate() {
  const today = new Date()
  const month = String(today.getMonth() + 1).padStart(2, '0')
  const day = String(today.getDate()).padStart(2, '0')
  return `${today.getFullYear()}-${month}-${day}`
}

function displayEndDate(task: SubtaskItem) {
  return task.completedAt || currentDate()
}

const datedTasks = computed(() => {
  return props.tasks
    .map((task) => {
      const start = parseDate(task.startDate) || parseDate(task.dueDate)
      const endDate = displayEndDate(task)
      const end = parseDate(endDate)
      if (!start || !end) return null
      const startMs = dayMs(start)
      const endMs = Math.max(dayMs(end), startMs)
      return { ...task, endDate, startMs, endMs }
    })
    .filter(Boolean) as Array<SubtaskItem & { endDate: string; startMs: number; endMs: number }>
})

const undatedTasks = computed(() =>
  props.tasks.filter((task) => !parseDate(task.startDate) && !parseDate(task.dueDate)),
)

const range = computed(() => {
  const today = dayMs(new Date())
  const parentStart =
    parseDate(props.parentStartDate)
    || parseDate(props.parentStartedAt)
    || (datedTasks.value.length ? new Date(Math.min(...datedTasks.value.map((t) => t.startMs))) : null)

  if (!parentStart && !datedTasks.value.length) return null

  let min = parentStart ? dayMs(parentStart) : Math.min(...datedTasks.value.map((t) => t.startMs))

  // 默认到今天；若父任务在今天之前已完成，则到完成日
  let max = today
  const completedMs = parseDate(props.parentCompletedAt)
  const finished =
    props.parentStatus === 2
    || props.parentStatus === 3
    || completedMs != null
  if (finished && completedMs && dayMs(completedMs) < today) {
    max = dayMs(completedMs)
  }

  // 子任务缺少完成时间时会延伸到今天，时间轴范围也必须完整容纳该日期。
  if (datedTasks.value.length) {
    max = Math.max(max, ...datedTasks.value.map((task) => task.endMs))
  }

  if (max < min) max = min
  const days = Math.max(1, Math.round((max - min) / DAY_MS) + 1)
  return { min, max, days }
})

const trackWidth = computed(() => {
  if (!range.value) return 640
  return Math.max(range.value.days * DAY_WIDTH, 640)
})

const rows = computed(() => datedTasks.value)

const tickStep = computed(() => {
  const days = range.value?.days ?? 0
  if (days <= 16) return 1
  if (days <= 45) return 2
  if (days <= 90) return 7
  return 14
})

const ticks = computed(() => {
  if (!range.value) return [] as Array<{ label: string; left: number; width: number }>
  const { min, days } = range.value
  const step = tickStep.value
  const out: Array<{ label: string; left: number; width: number }> = []
  for (let i = 0; i < days; i += step) {
    const date = new Date(min + i * DAY_MS)
    const width = Math.min(step, days - i) * DAY_WIDTH
    out.push({
      label: `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`,
      left: i * DAY_WIDTH,
      width,
    })
  }
  return out
})

function barStyle(task: { startMs: number; endMs: number }) {
  if (!range.value) return {}
  const { min } = range.value
  const left = ((task.startMs - min) / DAY_MS) * DAY_WIDTH
  const width = Math.max(((task.endMs - task.startMs) / DAY_MS + 1) * DAY_WIDTH - 6, 100)
  return { left: `${left}px`, width: `${width}px` }
}
</script>

<template>
  <div class="subtask-timeline">
    <div v-if="!tasks.length" class="subtask-empty">暂无子任务</div>

    <template v-else>
      <div v-if="rows.length" class="gantt">
        <div class="gantt-legend" aria-label="状态颜色说明">
          <span
            v-for="item in statusLegend"
            :key="item.status"
            class="gantt-legend__item"
          >
            <i class="gantt-legend__swatch" :class="`is-status-${item.status}`" aria-hidden="true" />
            <em>{{ item.label }}</em>
          </span>
        </div>
        <div class="gantt-scroll" tabindex="0" aria-label="子任务时间线，可滚动查看">
          <div
            class="gantt-canvas"
            :style="{ width: `${trackWidth}px`, '--day-width': `${DAY_WIDTH}px` }"
          >
            <div class="gantt-axis" aria-hidden="true">
              <div
                v-for="tick in ticks"
                :key="tick.label + tick.left"
                class="gantt-tick"
                :style="{ left: `${tick.left}px`, width: `${tick.width}px` }"
              >
                <i class="gantt-tick__mark" />
                <span class="gantt-tick__label">{{ tick.label }}</span>
              </div>
            </div>

            <div class="gantt-rows">
              <article v-for="task in rows" :key="task.id" class="gantt-card">
                <button type="button" class="gantt-intro" @click="emit('open', task)">
                  <strong :title="task.title">{{ task.title }}</strong>
                  <span class="gantt-meta">
                    <em>{{ task.assigneeName || '未指定' }} · 持有人 {{ task.holderName || '未指定' }}</em>
                    <i>{{ statusMap[task.status ?? 0] }}</i>
                    <i class="is-date">{{ formatDate(task.startDate) }} — {{ formatDate(task.endDate) }}</i>
                  </span>
                </button>
                <div class="gantt-track">
                  <button
                    type="button"
                    class="gantt-bar"
                    :class="[`is-status-${task.status ?? 0}`, { 'is-overdue': task.overdue }]"
                    :style="barStyle(task)"
                    :title="`${task.title}\n开始 ${formatDate(task.startDate)}\n结束 ${formatDate(task.endDate)}`"
                    @click="emit('open', task)"
                  >
                    <span class="gantt-bar__range">
                      <i>{{ formatShort(task.startDate) }}</i>
                      <em>—</em>
                      <i>{{ formatShort(task.endDate) }}</i>
                    </span>
                  </button>
                </div>
              </article>
            </div>
          </div>
        </div>
      </div>

      <div v-if="undatedTasks.length" class="undated-block">
        <div class="undated-title">未设置计划时间（{{ undatedTasks.length }}）</div>
        <button
          v-for="task in undatedTasks"
          :key="task.id"
          type="button"
          class="undated-row"
          @click="emit('open', task)"
        >
          <span>
            <b>{{ task.title }}</b>
            <small>{{ task.assigneeName || '未指定' }} · 持有人 {{ task.holderName || '未指定' }}</small>
          </span>
          <el-tag size="small" effect="plain">{{ statusMap[task.status ?? 0] }}</el-tag>
        </button>
      </div>

      <div v-if="!rows.length && undatedTasks.length" class="subtask-empty is-soft">
        子任务尚未设置开始 / 结束时间，无法绘制时间线
      </div>
    </template>
  </div>
</template>

<style scoped>
.subtask-timeline {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}
.subtask-empty {
  display: grid;
  place-items: center;
  min-height: 96px;
  border: 1px dashed rgba(24, 24, 27, 0.12);
  border-radius: 12px;
  color: var(--kk-text-muted);
  font-size: 13px;
  background: rgba(255, 255, 255, 0.35);
}
.subtask-empty.is-soft {
  min-height: 64px;
  border-style: solid;
  border-color: rgba(24, 24, 27, 0.06);
}
.gantt {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}
.gantt-legend {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 14px;
  padding: 2px 2px 0;
}
.gantt-legend__item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--kk-text-secondary);
}
.gantt-legend__item em {
  font-style: normal;
}
.gantt-legend__swatch {
  width: 18px;
  height: 10px;
  border-radius: 999px;
  border: 1px solid transparent;
  box-sizing: border-box;
}
.gantt-legend__swatch.is-status-0 {
  background: rgba(99, 102, 241, 0.16);
  border-color: rgba(99, 102, 241, 0.28);
}
.gantt-legend__swatch.is-status-1 {
  background: rgba(59, 130, 246, 0.16);
  border-color: rgba(59, 130, 246, 0.28);
}
.gantt-legend__swatch.is-status-2 {
  background: rgba(16, 185, 129, 0.16);
  border-color: rgba(16, 185, 129, 0.28);
}
.gantt-legend__swatch.is-status-3 {
  background: rgba(24, 24, 27, 0.06);
  border-color: rgba(24, 24, 27, 0.12);
}
.gantt-legend__swatch.is-status-4 {
  background: rgba(245, 158, 11, 0.16);
  border-color: rgba(245, 158, 11, 0.28);
}
.gantt-scroll {
  max-height: min(58vh, 520px);
  overflow: auto;
  overscroll-behavior: contain;
  border: 1px solid rgba(24, 24, 27, 0.08);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.42);
  scrollbar-width: thin;
  scrollbar-color: rgba(24, 24, 27, 0.28) transparent;
}
.gantt-scroll:focus-visible {
  outline: 2px solid var(--kk-primary);
  outline-offset: 2px;
}
.gantt-canvas {
  --day-width: 48px;
  min-width: 100%;
  padding: 12px 16px 16px;
}
.gantt-axis {
  position: sticky;
  top: 0;
  z-index: 2;
  height: 36px;
  margin: 0 0 12px;
  border-bottom: 1px solid rgba(24, 24, 27, 0.12);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.98), rgba(255, 255, 255, 0.9));
}
.gantt-tick {
  position: absolute;
  top: 0;
  bottom: 0;
  box-sizing: border-box;
}
.gantt-tick__mark {
  position: absolute;
  left: 0;
  bottom: 0;
  width: 1px;
  height: 10px;
  background: rgba(24, 24, 27, 0.28);
}
.gantt-tick__label {
  position: absolute;
  left: 0;
  right: 0;
  top: 6px;
  overflow: hidden;
  text-align: center;
  font-size: 11px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  line-height: 1;
  color: var(--kk-text-secondary);
  white-space: nowrap;
}
.gantt-rows {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.gantt-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}
.gantt-intro {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  width: 100%;
  max-width: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  text-align: left;
  cursor: pointer;
  font: inherit;
  color: inherit;
}
.gantt-intro strong {
  max-width: 100%;
  font-size: 14px;
  font-weight: 650;
  line-height: 1.35;
  color: var(--kk-text);
  overflow-wrap: anywhere;
}
.gantt-intro:hover strong { color: var(--kk-primary); }
.gantt-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 10px;
  font-size: 12px;
  color: var(--kk-text-muted);
}
.gantt-meta em,
.gantt-meta i {
  font-style: normal;
}
.gantt-meta .is-date {
  font-variant-numeric: tabular-nums;
  color: var(--kk-text-secondary);
}
.gantt-track {
  position: relative;
  width: 100%;
  height: 38px;
  border-radius: 12px;
  border: 1px solid rgba(24, 24, 27, 0.06);
  background-color: rgba(255, 255, 255, 0.4);
  background-image: repeating-linear-gradient(
    to right,
    rgba(24, 24, 27, 0.08) 0,
    rgba(24, 24, 27, 0.08) 1px,
    transparent 1px,
    transparent var(--day-width)
  );
  background-position: 0 0;
  -webkit-backdrop-filter: blur(8px);
  backdrop-filter: blur(8px);
}
.gantt-bar {
  position: absolute;
  top: 5px;
  bottom: 5px;
  display: flex;
  align-items: center;
  padding: 0 12px;
  border: 1px solid transparent;
  border-radius: 10px;
  box-shadow: 0 1px 2px rgba(24, 24, 27, 0.04);
  cursor: pointer;
  font: inherit;
  transition: filter .15s var(--kk-ease), box-shadow .15s var(--kk-ease);
}
.gantt-bar:hover {
  filter: brightness(0.97);
  box-shadow: 0 2px 8px rgba(24, 24, 27, 0.08);
  z-index: 1;
}
/* 待办 */
.gantt-bar.is-status-0 {
  color: #4338ca;
  background: rgba(99, 102, 241, 0.16);
  border-color: rgba(99, 102, 241, 0.28);
}
/* 进行中 */
.gantt-bar.is-status-1 {
  color: #1d4ed8;
  background: rgba(59, 130, 246, 0.16);
  border-color: rgba(59, 130, 246, 0.28);
}
/* 已完成 */
.gantt-bar.is-status-2 {
  color: #047857;
  background: rgba(16, 185, 129, 0.16);
  border-color: rgba(16, 185, 129, 0.28);
}
/* 已关闭 */
.gantt-bar.is-status-3 {
  color: var(--kk-text-muted);
  background: rgba(24, 24, 27, 0.05);
  border-color: rgba(24, 24, 27, 0.1);
  opacity: 0.72;
}
/* 待确认完成 */
.gantt-bar.is-status-4 {
  color: #b45309;
  background: rgba(245, 158, 11, 0.16);
  border-color: rgba(245, 158, 11, 0.28);
}
.gantt-bar.is-overdue {
  color: #b91c1c;
  background: rgba(239, 68, 68, 0.12);
  border-color: rgba(239, 68, 68, 0.28);
}
.gantt-bar__range {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  font-weight: 600;
  white-space: nowrap;
}
.gantt-bar__range em {
  font-style: normal;
  opacity: 0.55;
}
.undated-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.undated-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--kk-text-muted);
}
.undated-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  padding: 10px 12px;
  border: 1px solid rgba(24, 24, 27, 0.07);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.5);
  text-align: left;
  cursor: pointer;
  font: inherit;
  color: inherit;
}
.undated-row:hover { background: rgba(255, 255, 255, 0.78); }
.undated-row span {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.undated-row b {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  color: var(--kk-text);
}
.undated-row small {
  font-size: 12px;
  color: var(--kk-text-muted);
}
</style>
