<script setup lang="ts">
import { computed } from 'vue'

type TimelineTask = {
  id: number
  title: string
  projectName?: string | null
  assigneeName?: string | null
  startDate?: string | null
  dueDate?: string | null
  status?: number
  priority?: number
  overdue?: boolean
}

const props = defineProps<{ tasks: TimelineTask[]; loading?: boolean }>()
const emit = defineEmits<{ open: [task: TimelineTask] }>()

const statusLabel: Record<number, string> = { 0: '待办', 1: '进行中', 2: '已完成', 3: '已关闭', 4: '待确认完成' }
const priorityLabel: Record<number, string> = { 1: '高', 2: '中', 3: '低' }

function parseDate(value?: string | null) {
  if (!value) return null
  const match = value.match(/^(\d{4})-(\d{1,2})-(\d{1,2})/)
  if (!match) return null
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]))
  return Number.isNaN(date.getTime()) ? null : date
}

function dateText(value?: string | null) {
  const date = parseDate(value)
  return date ? `${date.getFullYear()}.${String(date.getMonth() + 1).padStart(2, '0')}.${String(date.getDate()).padStart(2, '0')}` : '--'
}

const datedTasks = computed(() => props.tasks
  .flatMap((task) => {
    const anchor = parseDate(task.startDate || task.dueDate)
    return anchor ? [{ ...task, anchor }] : []
  })
  .sort((a, b) => a.anchor.getTime() - b.anchor.getTime()))

const undatedCount = computed(() => props.tasks.length - datedTasks.value.length)

function scheduleText(task: TimelineTask) {
  if (task.startDate && task.dueDate) return `${task.startDate} — ${task.dueDate}`
  if (task.dueDate) return `截止 ${task.dueDate}`
  return `${task.startDate} 开始`
}
</script>

<template>
  <section class="task-timeline" :aria-busy="loading">
    <div class="timeline-heading">
      <div><h4>任务时间轴</h4><p>按计划日期从左到右排列，点击任务可查看详情</p></div>
      <span v-if="datedTasks.length" class="timeline-count">{{ datedTasks.length }} 项计划</span>
    </div>

    <div v-if="datedTasks.length" class="timeline-scroll" tabindex="0" aria-label="任务时间轴，可横向滚动查看更多任务">
      <ol class="timeline-list">
        <li v-for="(task, index) in datedTasks" :key="task.id" class="timeline-item" :class="{ 'is-below': index % 2 === 1 }">
          <span class="timeline-node" :class="[`is-status-${task.status}`, { 'is-overdue': task.overdue }]" aria-hidden="true" />
          <time :datetime="task.startDate || task.dueDate || undefined">{{ dateText(task.startDate || task.dueDate) }}</time>
          <button type="button" class="timeline-card" :aria-label="`查看任务：${task.title}，${scheduleText(task)}`" @click="emit('open', task)">
            <span class="card-topline">
              <span class="status-text" :class="`is-status-${task.status}`">{{ statusLabel[task.status ?? 0] || '任务' }}</span>
              <span class="priority-text" :class="`is-priority-${task.priority}`">{{ priorityLabel[task.priority ?? 2] || '中' }}优先级</span>
            </span>
            <strong :title="task.title">{{ task.title }}</strong>
            <span v-if="task.projectName" class="card-project" :title="task.projectName">{{ task.projectName }}</span>
            <span class="card-schedule">{{ scheduleText(task) }}</span>
            <span class="card-owner">负责人：{{ task.assigneeName || '未指定' }}</span>
            <span v-if="task.overdue" class="overdue-text">已逾期</span>
          </button>
        </li>
      </ol>
    </div>

    <div v-else class="timeline-empty">当前任务尚未设置计划时间</div>
    <p v-if="undatedCount" class="timeline-undated">另有 {{ undatedCount }} 项任务未设置计划时间，可切换到“我的任务明细”中补充。</p>
  </section>
</template>

<style scoped>
.task-timeline { padding: 4px 2px 2px; }
.timeline-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 14px; }
.timeline-heading h4 { margin: 0; font-size: 14px; color: var(--kk-text); }
.timeline-heading p, .timeline-undated { margin: 4px 0 0; font-size: 12px; color: var(--kk-text-muted); }
.timeline-count { flex: 0 0 auto; padding: 4px 9px; border-radius: 999px; font-size: 11px; color: var(--kk-text-secondary); background: var(--kk-bg-muted, #f5f7fa); }
.timeline-scroll { width: 100%; overflow-x: auto; padding: 3px 2px 10px; border-radius: 10px; scrollbar-width: thin; scrollbar-color: var(--kk-border, #dcdfe6) transparent; }
.timeline-scroll:focus-visible { outline: 2px solid var(--kk-primary); outline-offset: 2px; }
.timeline-list { display: flex; align-items: stretch; width: max-content; min-width: 100%; margin: 0; padding: 0; list-style: none; }
.timeline-item { position: relative; flex: 1 0 228px; height: 374px; padding: 0 10px; scroll-snap-align: start; }
.timeline-item::before { content: ''; position: absolute; top: 176px; left: 0; right: 0; height: 2px; background: var(--kk-border, #dcdfe6); }
.timeline-item:first-child::before { left: 50%; }
.timeline-item:last-child::before { right: 50%; }
.timeline-item time { position: absolute; z-index: 2; top: 193px; left: 50%; transform: translateX(-50%); white-space: nowrap; font-size: 12px; font-weight: 600; color: var(--kk-text-secondary); font-variant-numeric: tabular-nums; }
.timeline-item.is-below time { top: 149px; }
.timeline-node { position: absolute; z-index: 3; top: 170px; left: 50%; width: 13px; height: 13px; border: 3px solid var(--kk-bg, #fff); border-radius: 50%; box-shadow: 0 0 0 2px #64748b; background: #64748b; transform: translateX(-50%); }
.timeline-node.is-status-1 { box-shadow: 0 0 0 2px var(--kk-primary); background: var(--kk-primary); }
.timeline-node.is-status-2 { box-shadow: 0 0 0 2px #16a34a; background: #16a34a; }
.timeline-node.is-status-4 { box-shadow: 0 0 0 2px #d97706; background: #d97706; }
.timeline-node.is-overdue { box-shadow: 0 0 0 2px var(--kk-danger); background: var(--kk-danger); }
.timeline-card { position: absolute; top: 0; left: 50%; display: flex; flex-direction: column; align-items: flex-start; width: calc(100% - 20px); max-width: 280px; height: 150px; padding: 12px 13px; border: 1px solid var(--kk-border, #ebeef5); border-radius: 10px; text-align: left; color: inherit; background: var(--kk-bg, #fff); cursor: pointer; transform: translateX(-50%); transition: border-color .15s var(--kk-ease), box-shadow .15s var(--kk-ease), background-color .15s var(--kk-ease); }
.timeline-card::after { content: ''; position: absolute; top: 100%; left: 50%; width: 1px; height: 26px; background: var(--kk-border, #dcdfe6); transform: translateX(-50%); }
.timeline-item.is-below .timeline-card { top: 214px; }
.timeline-item.is-below .timeline-card::after { top: auto; bottom: 100%; height: 38px; }
.timeline-card:hover { border-color: color-mix(in srgb, var(--kk-primary) 38%, var(--kk-border, #ebeef5)); box-shadow: 0 6px 18px rgba(15, 23, 42, .07); background: color-mix(in srgb, var(--kk-primary) 2%, var(--kk-bg, #fff)); }
.timeline-card:focus-visible { outline: 2px solid var(--kk-primary); outline-offset: 2px; }
.card-topline { display: flex; align-items: center; justify-content: space-between; gap: 8px; width: 100%; margin-bottom: 8px; font-size: 10px; }
.status-text { color: #64748b; }
.status-text.is-status-1 { color: var(--kk-primary); }
.status-text.is-status-2 { color: #15803d; }
.status-text.is-status-4 { color: #b45309; }
.priority-text { color: var(--kk-text-muted); }
.priority-text.is-priority-1 { color: var(--kk-danger); font-weight: 600; }
.timeline-card strong { display: -webkit-box; width: 100%; overflow: hidden; font-size: 13px; line-height: 1.45; color: var(--kk-text); white-space: normal; overflow-wrap: anywhere; -webkit-box-orient: vertical; -webkit-line-clamp: 2; line-clamp: 2; }
.card-project, .card-schedule, .card-owner { display: block; width: 100%; margin-top: 7px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11px; color: var(--kk-text-muted); }
.card-schedule { font-variant-numeric: tabular-nums; }
.card-owner { margin-top: 4px; }
.overdue-text { margin-top: 7px; padding: 2px 6px; border-radius: 4px; font-size: 10px; color: var(--kk-danger); background: color-mix(in srgb, var(--kk-danger) 9%, transparent); }
.timeline-empty { display: grid; place-items: center; min-height: 88px; border: 1px dashed var(--kk-border, #dcdfe6); border-radius: 10px; font-size: 13px; color: var(--kk-text-muted); }
.timeline-undated { margin-top: 10px; }
@media (max-width: 640px) {
  .timeline-scroll { scroll-snap-type: x proximity; }
  .timeline-item { width: 210px; padding: 0 8px; }
  .timeline-card { height: 150px; }
}
@media (prefers-reduced-motion: reduce) { .timeline-card { transition: none; } }
</style>
