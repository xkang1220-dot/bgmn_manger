<script setup lang="ts">
import type { TaskListItem } from '@/api/taskAi'

defineProps<{
  title?: string
  items: TaskListItem[]
  emptyText?: string
}>()
</script>

<template>
  <div class="task-list">
    <div v-if="title" class="task-list-title">{{ title }}</div>
    <div v-if="!items?.length" class="task-list-empty">{{ emptyText || '暂无任务' }}</div>
    <ul v-else class="task-list-ul">
      <li v-for="(item, idx) in items" :key="item.id ?? idx" class="task-list-li">
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
