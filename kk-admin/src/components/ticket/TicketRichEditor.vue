<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Picture } from '@element-plus/icons-vue'
import { ticketApi } from '@/api/ticket'

const props = defineProps<{
  modelValue: string
  placeholder?: string
  minHeight?: number
}>()

const emit = defineEmits<{
  'update:modelValue': [string]
}>()

const editorRef = ref<HTMLDivElement | null>(null)
const uploading = ref(false)
let syncing = false

onMounted(() => {
  if (editorRef.value) {
    editorRef.value.innerHTML = props.modelValue || ''
  }
})

watch(
  () => props.modelValue,
  (val) => {
    if (!editorRef.value || syncing) return
    if (editorRef.value.innerHTML !== (val || '')) {
      editorRef.value.innerHTML = val || ''
    }
  },
)

function onInput() {
  if (!editorRef.value) return
  syncing = true
  emit('update:modelValue', editorRef.value.innerHTML)
  nextTick(() => {
    syncing = false
  })
}

async function onPaste(e: ClipboardEvent) {
  const items = e.clipboardData?.items
  if (!items) return
  for (const item of items) {
    if (item.type.startsWith('image/')) {
      e.preventDefault()
      const file = item.getAsFile()
      if (file) await insertImage(file)
      return
    }
  }
}

async function pickImage() {
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = 'image/*'
  input.onchange = async () => {
    const file = input.files?.[0]
    if (file) await insertImage(file)
  }
  input.click()
}

async function insertImage(file: File) {
  if (file.size > 8 * 1024 * 1024) {
    ElMessage.warning('图片不能超过 8MB')
    return
  }
  uploading.value = true
  try {
    const meta = await ticketApi.uploadImage(file)
    // 本地存储 url 常为 download 链接，图片展示统一走 preview
    const url = meta?.id
      ? `/api/file/preview/${meta.id}`
      : (meta?.url || '')
    if (!url) {
      ElMessage.error('上传失败')
      return
    }
    document.execCommand('insertHTML', false, `<img src="${url}" alt="" style="max-width:100%" />`)
    onInput()
  } finally {
    uploading.value = false
  }
}

onBeforeUnmount(() => {
  editorRef.value = null
})
</script>

<template>
  <div class="ticket-rich-editor">
    <div class="toolbar">
      <el-button size="small" :loading="uploading" @click="pickImage">
        <el-icon><Picture /></el-icon>
        图片
      </el-button>
    </div>
    <div
      ref="editorRef"
      class="editor-body"
      contenteditable="true"
      :style="{ minHeight: (minHeight || 140) + 'px' }"
      :data-placeholder="placeholder || '请输入内容…'"
      @input="onInput"
      @paste="onPaste"
    />
  </div>
</template>

<style scoped>
.ticket-rich-editor {
  border: 1px solid rgba(24, 24, 27, 0.12);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.55);
  overflow: hidden;
}
.toolbar {
  padding: 8px 10px;
  border-bottom: 1px solid rgba(24, 24, 27, 0.08);
  display: flex;
  gap: 8px;
}
.editor-body {
  padding: 12px 14px;
  outline: none;
  line-height: 1.6;
  font-size: 14px;
  color: #18181b;
}
.editor-body:empty::before {
  content: attr(data-placeholder);
  color: #a1a1aa;
  pointer-events: none;
}
.editor-body :deep(img) {
  max-width: 100%;
  border-radius: 8px;
}
</style>
