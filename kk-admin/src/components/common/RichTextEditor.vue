<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  modelValue: string
  placeholder?: string
  minHeight?: number
}>(), {
  placeholder: '请输入内容…',
  minHeight: 110,
})

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const editorRef = ref<HTMLDivElement | null>(null)
let syncing = false

onMounted(() => {
  if (editorRef.value) editorRef.value.innerHTML = props.modelValue || ''
})

watch(() => props.modelValue, (value) => {
  if (!editorRef.value || syncing) return
  if (editorRef.value.innerHTML !== (value || '')) editorRef.value.innerHTML = value || ''
})

function emitValue() {
  if (!editorRef.value) return
  syncing = true
  emit('update:modelValue', editorRef.value.innerHTML)
  nextTick(() => { syncing = false })
}

function command(name: string, value?: string) {
  editorRef.value?.focus()
  document.execCommand(name, false, value)
  emitValue()
}

function addLink() {
  const url = window.prompt('请输入链接地址')?.trim()
  if (!url) return
  command('createLink', /^(https?:\/\/|mailto:)/i.test(url) ? url : `https://${url}`)
}
</script>

<template>
  <div class="rich-editor">
    <div class="rich-toolbar" @mousedown.prevent>
      <button type="button" title="加粗" @click="command('bold')"><b>B</b></button>
      <button type="button" title="斜体" @click="command('italic')"><i>I</i></button>
      <button type="button" title="下划线" @click="command('underline')"><u>U</u></button>
      <span class="divider" />
      <button type="button" title="无序列表" @click="command('insertUnorderedList')">• 列表</button>
      <button type="button" title="有序列表" @click="command('insertOrderedList')">1. 列表</button>
      <button type="button" title="插入链接" @click="addLink">链接</button>
      <button type="button" title="清除格式" @click="command('removeFormat')">清除格式</button>
    </div>
    <div
      ref="editorRef"
      class="rich-body"
      contenteditable="true"
      :style="{ minHeight: `${minHeight}px` }"
      :data-placeholder="placeholder"
      @input="emitValue"
    />
  </div>
</template>

<style scoped>
.rich-editor { border: 1px solid #dcdfe6; border-radius: 8px; overflow: hidden; background: #fff; }
.rich-editor:focus-within { border-color: #409eff; box-shadow: 0 0 0 1px #409eff inset; }
.rich-toolbar { display: flex; flex-wrap: wrap; gap: 3px; padding: 6px 8px; border-bottom: 1px solid #ebeef5; background: #f8fafc; }
.rich-toolbar button { height: 26px; padding: 0 7px; border: 0; border-radius: 4px; background: transparent; color: #475569; cursor: pointer; }
.rich-toolbar button:hover { background: #e2e8f0; color: #0f172a; }
.divider { width: 1px; margin: 3px 4px; background: #d8dee8; }
.rich-body { padding: 10px 12px; outline: none; overflow-wrap: anywhere; line-height: 1.6; font-size: 14px; }
.rich-body:empty::before { content: attr(data-placeholder); color: #a8abb2; pointer-events: none; }
.rich-body :deep(p) { margin: 0 0 6px; }
.rich-body :deep(ul), .rich-body :deep(ol) { margin: 4px 0; padding-left: 22px; }
</style>
