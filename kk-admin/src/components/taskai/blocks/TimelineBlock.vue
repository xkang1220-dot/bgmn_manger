<script setup lang="ts">
import type { TimelineEvent } from '@/api/taskAi'

defineProps<{
  title?: string
  events: TimelineEvent[]
}>()
</script>

<template>
  <div class="timeline">
    <div v-if="title" class="timeline-title">{{ title }}</div>
    <div v-if="!events?.length" class="timeline-empty">暂无事项</div>
    <ol v-else class="timeline-list">
      <li v-for="(ev, idx) in events" :key="ev.id ?? idx" class="timeline-item">
        <div class="timeline-rail" aria-hidden="true">
          <i class="timeline-dot" />
          <i v-if="idx < events.length - 1" class="timeline-line" />
        </div>
        <div class="timeline-body">
          <div class="timeline-time">{{ ev.time }}</div>
          <div class="timeline-event-title">{{ ev.title }}</div>
          <div class="timeline-meta">
            <span v-if="ev.projectName">{{ ev.projectName }}</span>
            <span v-if="ev.userName">{{ ev.userName }}</span>
            <span v-if="ev.statusLabel">{{ ev.statusLabel }}</span>
          </div>
        </div>
      </li>
    </ol>
  </div>
</template>

<style scoped>
.timeline {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.timeline-title {
  font-size: 13px;
  font-weight: 600;
  color: #3f3f46;
}

.timeline-empty {
  font-size: 13px;
  color: #a1a1aa;
}

.timeline-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.timeline-item {
  display: grid;
  grid-template-columns: 16px 1fr;
  gap: 10px;
  min-height: 52px;
}

.timeline-rail {
  position: relative;
  display: flex;
  justify-content: center;
}

.timeline-dot {
  width: 9px;
  height: 9px;
  margin-top: 5px;
  border-radius: 50%;
  background: #2563eb;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.15);
  z-index: 1;
}

.timeline-line {
  position: absolute;
  top: 16px;
  bottom: -8px;
  width: 2px;
  background: rgba(37, 99, 235, 0.18);
}

.timeline-time {
  font-size: 12px;
  color: #71717a;
}

.timeline-event-title {
  font-size: 14px;
  font-weight: 550;
  color: #18181b;
  margin-top: 2px;
}

.timeline-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 4px;
  font-size: 12px;
  color: #71717a;
}
</style>
