<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bizApi } from '@/api/biz'

const props = defineProps<{
  projectId: number
}>()

const emit = defineEmits<{
  openTask: [task: any]
  createTask: []
  changed: []
}>()

const loading = ref(false)
const boardTasks = ref<any[]>([])
const draggingId = ref<number | null>(null)
const dragOverStatus = ref<number | null>(null)
const suppressClick = ref(false)

const columns = [
  { status: 0, label: '待办', tone: 'todo' },
  { status: 1, label: '进行中', tone: 'doing' },
  { status: 2, label: '已完成', tone: 'done' },
]

const priorityMap: Record<number, string> = { 1: '高', 2: '中', 3: '低' }
const AVATAR_TONES = ['indigo', 'cyan', 'violet', 'amber'] as const

function initial(name?: string) {
  const s = String(name || '').replace(/\s/g, '')
  return s.slice(0, 1) || '?'
}

function avatarTone(name?: string) {
  const s = String(name || '')
  let hash = 0
  for (let i = 0; i < s.length; i++) hash = (hash * 31 + s.charCodeAt(i)) >>> 0
  return AVATAR_TONES[hash % AVATAR_TONES.length]
}

function progressOf(task: any) {
  if (Number(task.status) === 2) return 100
  const n = Number(task.progress)
  return Number.isFinite(n) ? Math.min(100, Math.max(0, n)) : 0
}

function showProgress(task: any) {
  return Number(task.status) === 1 || Number(task.status) === 2 || progressOf(task) > 0
}

function others(task: any) {
  const assignee = String(task.assigneeName || '')
  return (task.participantNames || []).filter((n: string) => n && n !== assignee)
}

function extraPeople(task: any) {
  return Math.max(0, others(task).length - 3)
}

const grouped = computed(() => {
  const map: Record<number, any[]> = { 0: [], 1: [], 2: [] }
  for (const t of boardTasks.value) {
    const s = Number(t.status)
    if (s === 0 || s === 1 || s === 2) map[s].push(t)
  }
  return map
})

async function load() {
  if (!props.projectId) return
  loading.value = true
  try {
    boardTasks.value = await bizApi.taskBoard(props.projectId)
  } finally {
    loading.value = false
  }
}

function onDragStart(e: DragEvent, task: any) {
  draggingId.value = task.id
  suppressClick.value = false
  e.dataTransfer?.setData('text/plain', String(task.id))
  if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move'
}

function onDragEnd() {
  if (draggingId.value != null) {
    suppressClick.value = true
    setTimeout(() => {
      suppressClick.value = false
    }, 150)
  }
  draggingId.value = null
  dragOverStatus.value = null
}

function onDragOver(e: DragEvent, status: number) {
  e.preventDefault()
  dragOverStatus.value = status
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'move'
}

function onDragLeave(status: number) {
  if (dragOverStatus.value === status) dragOverStatus.value = null
}

async function onDrop(e: DragEvent, status: number) {
  e.preventDefault()
  dragOverStatus.value = null
  suppressClick.value = true
  setTimeout(() => {
    suppressClick.value = false
  }, 150)
  const id = Number(e.dataTransfer?.getData('text/plain') || draggingId.value)
  draggingId.value = null
  if (Number.isNaN(id)) return
  const task = boardTasks.value.find((t) => t.id === id)
  if (!task || task.status === status) return

  const fromLabel = columns.find((c) => c.status === task.status)?.label || ''
  const toLabel = columns.find((c) => c.status === status)?.label || ''
  try {
    await ElMessageBox.confirm(
      `确认将「${task.title}」从「${fromLabel}」移到「${toLabel}」？`,
      '确认流转',
      { confirmButtonText: '确认', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }

  const prev = task.status
  task.status = status
  if (status === 2) task.progress = 100
  else if (status === 0) task.progress = 0
  try {
    await bizApi.updateTaskStatus(id, status)
    ElMessage.success('状态已更新')
    emit('changed')
  } catch (err: any) {
    task.status = prev
    ElMessage.error(err.message || '更新失败')
    await load()
  }
}

function onCardClick(task: any) {
  if (suppressClick.value) return
  emit('openTask', task)
}

watch(() => props.projectId, load, { immediate: true })

defineExpose({ load })
</script>

<template>
  <div v-loading="loading" class="kanban">
    <div class="kanban-toolbar">
      <span class="kanban-hint">拖拽卡片切换状态，松开后需确认才会生效</span>
      <el-button type="primary" size="small" @click="emit('createTask')">新建任务</el-button>
    </div>
    <div class="kanban-board">
      <div
        v-for="col in columns"
        :key="col.status"
        class="kanban-col"
        :class="[`kanban-col--${col.tone}`, { 'is-over': dragOverStatus === col.status }]"
        @dragover="onDragOver($event, col.status)"
        @dragleave="onDragLeave(col.status)"
        @drop="onDrop($event, col.status)"
      >
        <div class="kanban-col__head">
          <span class="kanban-col__mark" />
          <span class="kanban-col__title">{{ col.label }}</span>
          <span class="kanban-col__count">{{ grouped[col.status].length }}</span>
        </div>
        <div class="kanban-col__body">
          <div
            v-for="task in grouped[col.status]"
            :key="task.id"
            class="kanban-card"
            :class="{ dragging: draggingId === task.id, overdue: task.overdue }"
            draggable="true"
            @dragstart="onDragStart($event, task)"
            @dragend="onDragEnd"
            @click="onCardClick(task)"
          >
            <div class="kanban-card__top">
              <span class="prio" :class="`prio--${task.priority}`">{{ priorityMap[task.priority] || '中' }}</span>
              <span v-if="task.overdue" class="overdue-flag">逾期</span>
            </div>
            <div class="kanban-card__title">{{ task.title }}</div>
            <div v-if="showProgress(task)" class="kanban-card__progress" :class="{ done: Number(task.status) === 2 }">
              <span :style="{ width: progressOf(task) + '%' }" />
            </div>
            <div class="kanban-card__foot">
              <span class="who">
                <span class="avatar" :class="'avatar--' + avatarTone(task.assigneeName)">{{ initial(task.assigneeName) }}</span>
                <em>{{ task.assigneeName || '未分配' }}</em>
                <span v-if="others(task).length" class="faces">
                  <span
                    v-for="(name, i) in others(task).slice(0, 3)"
                    :key="name + i"
                    class="face"
                    :class="'avatar--' + avatarTone(name)"
                    :title="name"
                  >{{ initial(name) }}</span>
                  <span v-if="extraPeople(task)" class="face face--more">+{{ extraPeople(task) }}</span>
                </span>
              </span>
              <span v-if="task.dueDate" class="due" :class="{ overdue: task.overdue }">
                <el-icon :size="13"><Calendar /></el-icon>
                {{ task.dueDate }}
              </span>
            </div>
          </div>
          <div v-if="!grouped[col.status].length" class="kanban-empty">暂无任务</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.kanban-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.kanban-hint {
  font-size: 12px;
  color: var(--kk-text-muted);
}

.kanban-board {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  min-height: 420px;
}

.kanban-col {
  display: flex;
  flex-direction: column;
  background: rgba(255, 255, 255, 0.28);
  border: 1px solid rgba(255, 255, 255, 0.5);
  border-radius: var(--kk-radius-sm);
  min-height: 420px;
  transition: border-color 0.15s, background 0.15s, box-shadow 0.15s;
}

.kanban-col.is-over {
  border-color: rgba(24, 24, 27, 0.22);
  background: rgba(255, 255, 255, 0.55);
  box-shadow: inset 0 0 0 1px rgba(24, 24, 27, 0.06);
}

.kanban-col__head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 14px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
}

.kanban-col__mark {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
  background: #a1a1aa;
}

.kanban-col--doing .kanban-col__mark { background: #d97706; }
.kanban-col--done .kanban-col__mark { background: #059669; }

.kanban-col__title {
  font-size: 13px;
  font-weight: 600;
  color: var(--kk-text);
}

.kanban-col__count {
  margin-left: auto;
  min-width: 22px;
  height: 22px;
  padding: 0 6px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.7);
  border: 1px solid rgba(0, 0, 0, 0.06);
  font-size: 12px;
  color: var(--kk-text-secondary);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-variant-numeric: tabular-nums;
}

.kanban-col__body {
  flex: 1;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  overflow-y: auto;
}

.kanban-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: var(--kk-glass-bg);
  border: 1px solid var(--kk-glass-border);
  border-radius: 12px;
  padding: 14px;
  cursor: grab;
  box-shadow: var(--kk-glass-shadow);
  backdrop-filter: var(--kk-glass-blur);
  -webkit-backdrop-filter: var(--kk-glass-blur);
  transition: box-shadow 0.18s var(--kk-ease), transform 0.18s var(--kk-ease), opacity 0.15s;
}

.kanban-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.08);
}

.kanban-card.dragging {
  opacity: 0.55;
  transform: none;
}

.kanban-card__top {
  display: flex;
  align-items: center;
  gap: 6px;
}

.kanban-card__title {
  font-size: 14px;
  font-weight: 600;
  letter-spacing: -0.02em;
  color: var(--kk-text);
  line-height: 1.4;
}

.kanban-card__progress {
  height: 3px;
  border-radius: 99px;
  background: rgba(0, 0, 0, 0.06);
  overflow: hidden;
}

.kanban-card__progress span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--kk-primary);
}

.kanban-card__progress.done span {
  background: #059669;
}

.kanban-card__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}

.who,
.due {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  font-size: 12px;
  color: var(--kk-text-secondary);
}

.who {
  min-width: 0;
  overflow: hidden;
}

.who em {
  font-style: normal;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.faces {
  display: inline-flex;
  align-items: center;
  margin-left: 2px;
}

.due.overdue,
.kanban-card.overdue .due {
  color: var(--kk-danger);
  font-weight: 500;
}

.prio,
.overdue-flag {
  padding: 1px 7px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 500;
  line-height: 18px;
}

.prio--1 { background: #fee2e2; color: #b91c1c; }
.prio--2 { background: #fef3c7; color: #b45309; }
.prio--3 { background: #f4f4f5; color: #71717a; }

.overdue-flag {
  background: #fee2e2;
  color: #b91c1c;
}

.avatar,
.face {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 10px;
  font-weight: 700;
  line-height: 1;
}

.avatar--indigo { background: #f4f4f5; color: #18181b; }
.avatar--cyan { background: #ecfeff; color: #0891b2; }
.avatar--violet { background: #f5f3ff; color: #7c3aed; }
.avatar--amber { background: #fffbeb; color: #d97706; }

.face {
  box-shadow: 0 0 0 2px rgba(255, 255, 255, 0.9);
}

.face + .face {
  margin-left: -6px;
}

.face--more {
  background: #f4f4f5;
  color: var(--kk-text-secondary);
  font-size: 9px;
  font-weight: 600;
}

.kanban-empty {
  padding: 36px 8px;
  text-align: center;
  font-size: 12px;
  color: var(--kk-text-muted);
}

@media (prefers-reduced-transparency: reduce) {
  .kanban-card {
    background: #fff;
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .kanban-card,
  .kanban-card:hover {
    transform: none;
  }
}

@media (max-width: 900px) {
  .kanban-board {
    grid-template-columns: 1fr;
  }
  .kanban-col {
    min-height: 200px;
  }
}
</style>
