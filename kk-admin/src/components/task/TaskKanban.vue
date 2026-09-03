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
            <div class="kanban-card__title">{{ task.title }}</div>
            <div class="kanban-card__meta">
              <span class="prio" :class="`prio--${task.priority}`">{{ priorityMap[task.priority] || '中' }}</span>
              <span class="assignee">{{ task.assigneeName || '未分配' }}</span>
              <span v-if="task.dueDate" class="due" :class="{ overdue: task.overdue }">{{ task.dueDate }}</span>
            </div>
            <div v-if="task.participantNames?.length" class="kanban-card__people">
              {{ task.participantNames.join('、') }}
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
  color: #94a3b8;
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
  background: #f8fafc;
  border: 1px solid #e8edf4;
  border-radius: 12px;
  min-height: 420px;
  transition: border-color 0.15s, background 0.15s;
}

.kanban-col.is-over {
  border-color: #a5b4fc;
  background: #f5f7ff;
}

.kanban-col__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border-bottom: 1px solid #eef2f7;
}

.kanban-col__title {
  font-size: 13px;
  font-weight: 600;
  color: #334155;
}

.kanban-col__count {
  min-width: 22px;
  height: 22px;
  padding: 0 6px;
  border-radius: 999px;
  background: #fff;
  border: 1px solid #e2e8f0;
  font-size: 12px;
  color: #64748b;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-variant-numeric: tabular-nums;
}

.kanban-col--todo .kanban-col__title { color: #64748b; }
.kanban-col--doing .kanban-col__title { color: #b45309; }
.kanban-col--done .kanban-col__title { color: #047857; }

.kanban-col__body {
  flex: 1;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  overflow-y: auto;
}

.kanban-card {
  background: #fff;
  border: 1px solid #e8edf4;
  border-radius: 10px;
  padding: 12px;
  cursor: grab;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
  transition: box-shadow 0.15s, transform 0.15s, opacity 0.15s;
}

.kanban-card:hover {
  box-shadow: 0 6px 16px rgba(15, 23, 42, 0.08);
}

.kanban-card.dragging {
  opacity: 0.55;
}

.kanban-card__title {
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
  line-height: 1.4;
  margin-bottom: 8px;
}

.kanban-card__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #64748b;
}

.prio {
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 500;
}

.prio--1 { background: #fee2e2; color: #b91c1c; }
.prio--2 { background: #fef3c7; color: #b45309; }
.prio--3 { background: #f1f5f9; color: #64748b; }

.due.overdue,
.kanban-card.overdue .due {
  color: #ef4444;
  font-weight: 500;
}

.kanban-card__people {
  margin-top: 6px;
  font-size: 11px;
  color: #94a3b8;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kanban-empty {
  padding: 24px 8px;
  text-align: center;
  font-size: 12px;
  color: #cbd5e1;
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
