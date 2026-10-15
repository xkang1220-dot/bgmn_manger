<script setup lang="ts">
import type { ClarifyField, ClarifyOption } from '@/api/taskAi'

defineProps<{
  field: ClarifyField
  prompt: string
  options?: ClarifyOption[]
  disabled?: boolean
}>()

const emit = defineEmits<{
  select: [payload: { field: ClarifyField; optionId: string; label: string }]
}>()
</script>

<template>
  <div class="clarify">
    <p class="clarify-prompt">{{ prompt }}</p>
    <div v-if="options?.length" class="clarify-options">
      <button
        v-for="opt in options"
        :key="opt.id"
        type="button"
        class="clarify-chip"
        :disabled="disabled"
        @click="emit('select', { field, optionId: opt.id, label: opt.label })"
      >
        {{ opt.label }}
      </button>
    </div>
    <p v-if="field === 'user' && options?.length && !disabled" class="clarify-hint">
      都不是？可在下方输入框继续输入全名或账号
    </p>
  </div>
</template>

<style scoped>
.clarify {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.clarify-prompt {
  margin: 0;
  font-size: 14px;
  line-height: 1.5;
  color: var(--kk-text, #18181b);
}

.clarify-options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.clarify-chip {
  border: 1px solid rgba(255, 255, 255, 0.72);
  background: rgba(255, 255, 255, 0.55);
  border-radius: 999px;
  padding: 6px 12px;
  font-size: 13px;
  color: #27272a;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;
}

.clarify-chip:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.82);
  border-color: rgba(37, 99, 235, 0.35);
}

.clarify-chip:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.clarify-hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.4;
  color: #71717a;
}
</style>
