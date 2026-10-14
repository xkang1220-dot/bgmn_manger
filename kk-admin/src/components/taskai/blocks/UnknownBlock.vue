<script setup lang="ts">
import { ref } from 'vue'

defineProps<{
  block: Record<string, unknown>
}>()

const open = ref(false)
</script>

<template>
  <div class="unknown">
    <button type="button" class="unknown-toggle" @click="open = !open">
      暂不支持的卡片类型：{{ String(block.type || 'unknown') }}
      <span>{{ open ? '收起' : '展开原始数据' }}</span>
    </button>
    <pre v-if="open" class="unknown-json">{{ JSON.stringify(block, null, 2) }}</pre>
  </div>
</template>

<style scoped>
.unknown {
  border-radius: 12px;
  border: 1px dashed rgba(24, 24, 27, 0.18);
  background: rgba(255, 255, 255, 0.35);
  overflow: hidden;
}

.unknown-toggle {
  width: 100%;
  display: flex;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 12px;
  border: 0;
  background: transparent;
  font-size: 12px;
  color: #71717a;
  cursor: pointer;
  text-align: left;
}

.unknown-json {
  margin: 0;
  padding: 0 12px 12px;
  font-size: 11px;
  line-height: 1.45;
  color: #52525b;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
