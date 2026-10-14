<script setup lang="ts">
import { computed } from 'vue'
import DOMPurify from 'dompurify'
import { marked } from 'marked'

const props = defineProps<{ content: string }>()

const html = computed(() => {
  const raw = marked.parse(props.content || '', { async: false }) as string
  return DOMPurify.sanitize(raw)
})
</script>

<template>
  <div class="ai-md" v-html="html" />
</template>

<style scoped>
.ai-md {
  color: var(--kk-text, #18181b);
  font-size: 14px;
  line-height: 1.65;
  word-break: break-word;
}

.ai-md :deep(p) {
  margin: 0 0 0.6em;
}

.ai-md :deep(p:last-child) {
  margin-bottom: 0;
}

.ai-md :deep(ul),
.ai-md :deep(ol) {
  margin: 0.4em 0;
  padding-left: 1.25em;
}

.ai-md :deep(code) {
  padding: 0.1em 0.35em;
  border-radius: 6px;
  background: rgba(24, 24, 27, 0.06);
  font-size: 0.92em;
}
</style>
