<script setup lang="ts">
import { useRouter } from 'vue-router'
import type { TaskListItem } from '@/api/taskAi'

defineProps<{
  title?: string
  items: TaskListItem[]
  emptyText?: string
}>()

const emit = defineEmits<{ openTask: [] }>()
const router = useRouter()

function openTask(item: TaskListItem) {
  if (item?.id == null) return
  const query: Record<string, string> = { taskId: String(item.id) }
  if (item.projectId != null) query.projectId = String(item.projectId)
  emit('openTask')
  router.push({ path: '/project/task', query })
}
</script>

<template>
  <div class="task-list">
    <div v-if="title" class="task-list-title">{{ title }}</div>
    <div v-if="!items?.length" class="task-list-empty">{{ emptyText || '暂无任务' }}</div>
    <ul v-else class="task-list-ul">
      <li
        v-for="(item, idx) in items"
        :key="item.id ?? idx"
        class="task-list-li"
        :class="{ 'is-clickable': item.id != null }"
        :role="item.id != null ? 'button' : undefined"
        :tabindex="item.id != null ? 0 : undefined"
        @click="openTask(item)"
        @keydown.enter.prevent="openTask(item)"
        @keydown.space.prevent="openTask(item)"
      >
        <div class="task-list-main">
          <span class="task-list-name">{{ item.title }}</span>
          <span v-if="item.statusLabel" class="task-list-status">{{ item.statusLabel }}</span>
        </div>
        <div class="task-list-meta">
          <span v-if="item.projectName">{{ item.projectName }}</span>
          <span v-if="item.userName">{{ item.userName }}</span>
          <span v-if="item.timeLabel">{{ item.timeLabel }}</span>
          <span v-if="item.durationLabel" class="task-list-duration">{{ item.durationLabel }}</span>
        </div>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.task-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.task-list-title {
  font-size: 13px;
  font-weight: 600;
  color: #3f3f46;
}

.task-list-empty {
  font-size: 13px;
  color: #a1a1aa;
  padding: 8px 0;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.task-list-ul {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.task-list-li {
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.4);
  border: 1px solid rgba(255, 255, 255, 0.65);
}

.task-list-li.is-clickable {
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;
}

.task-list-li.is-clickable:hover {
  background: rgba(255, 255, 255, 0.62);
  border-color: rgba(37, 99, 235, 0.28);
}

.task-list-li.is-clickable:focus-visible {
  outline: 2px solid rgba(37, 99, 235, 0.45);
  outline-offset: 1px;
}

.task-list-main {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.task-list-name {
  font-size: 14px;
  color: #18181b;
  font-weight: 500;
}

.task-list-li.is-clickable .task-list-name {
  color: #1d4ed8;
}

.task-list-status {
  flex-shrink: 0;
  font-size: 12px;
  color: #52525b;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(24, 24, 27, 0.05);
}

.task-list-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 4px;
  font-size: 12px;
  color: #71717a;
}

.task-list-duration {
  color: #3f3f46;
  font-weight: 550;
}
</style>
