<script setup lang="ts">
defineProps<{
  items: { label: string; value: string | number; hint?: string }[]
}>()
</script>

<template>
  <div class="stat-row">
    <div v-for="(item, idx) in items" :key="idx" class="stat-item">
      <span class="stat-label">{{ item.label }}</span>
      <strong class="stat-value">{{ item.value }}</strong>
      <small v-if="item.hint" class="stat-hint">{{ item.hint }}</small>
    </div>
  </div>
</template>

<style scoped>
.stat-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(88px, 1fr));
  gap: 8px;
}

.stat-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.42);
  border: 1px solid rgba(255, 255, 255, 0.65);
}

.stat-label {
  font-size: 12px;
  color: #71717a;
}

.stat-value {
  font-size: 18px;
  font-weight: 650;
  color: #18181b;
  letter-spacing: -0.02em;
}

.stat-hint {
  font-size: 11px;
  color: #a1a1aa;
}
</style>
