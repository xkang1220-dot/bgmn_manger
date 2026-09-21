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
const fileInputRef = ref<HTMLInputElement | null>(null)
const uploading = ref(false)
let syncing = false
/** 工具栏点选会先失焦，需在 mousedown 时把光标位置存下来 */
let savedRange: Range | null = null

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

function captureSelection() {
  const editor = editorRef.value
  const sel = window.getSelection()
  if (editor && sel && sel.rangeCount > 0) {
    const node = sel.anchorNode
    if (node && editor.contains(node)) {
      savedRange = sel.getRangeAt(0).cloneRange()
      return
    }
  }
  savedRange = null
}

async function onPaste(e: ClipboardEvent) {
  const items = e.clipboardData?.items
  if (!items) return
  for (const item of items) {
    if (item.type.startsWith('image/')) {
      e.preventDefault()
      captureSelection()
      const file = item.getAsFile()
      if (file) await insertImage(file)
      return
    }
  }
}

/** 在按钮抢走焦点前记下光标 */
function onPickMouseDown() {
  captureSelection()
}

function pickImage() {
  if (uploading.value) return
  const input = fileInputRef.value
  if (!input) return
  input.value = ''
  input.click()
}

async function onFilePicked(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (file) await insertImage(file)
}

function placeCaretAfter(node: Node) {
  const sel = window.getSelection()
  if (!sel) return
  const range = document.createRange()
  range.setStartAfter(node)
  range.collapse(true)
  sel.removeAllRanges()
  sel.addRange(range)
}

function insertImageNode(url: string) {
  const editor = editorRef.value
  if (!editor) return
  editor.focus()

  const img = document.createElement('img')
  img.src = url
  img.alt = ''
  img.style.maxWidth = '100%'

  const rangeStillValid =
    !!savedRange &&
    editor.contains(savedRange.startContainer) &&
    editor.contains(savedRange.endContainer)

  if (rangeStillValid && savedRange) {
    const sel = window.getSelection()
    sel?.removeAllRanges()
    sel?.addRange(savedRange)
    const range = savedRange
    range.deleteContents()
    range.insertNode(img)
    placeCaretAfter(img)
  } else {
    editor.appendChild(img)
    placeCaretAfter(img)
  }
  savedRange = null
  onInput()
}

async function insertImage(file: File) {
  if (uploading.value) return
  const looksLikeImage =
    file.type.startsWith('image/') || /\.(png|jpe?g|gif|webp|bmp|svg)$/i.test(file.name)
  if (!looksLikeImage) {
    ElMessage.warning('请选择图片文件')
    return
  }
  if (file.size > 8 * 1024 * 1024) {
    ElMessage.warning('图片不能超过 8MB')
    return
  }
  uploading.value = true
  try {
    const meta = await ticketApi.uploadImage(file)
    const url = meta?.id ? `/api/file/preview/${meta.id}` : (meta?.url || '')
    if (!url) {
      ElMessage.error('上传失败')
      return
    }
    insertImageNode(url)
  } catch {
    // 错误提示由 request 拦截器处理
  } finally {
    uploading.value = false
  }
}

onBeforeUnmount(() => {
  editorRef.value = null
  savedRange = null
})
</script>

<template>
  <div class="ticket-rich-editor">
    <div class="toolbar">
      <el-button
        size="small"
        :loading="uploading"
        :disabled="uploading"
        @mousedown.prevent="onPickMouseDown"
        @click="pickImage"
      >
        <el-icon><Picture /></el-icon>
        图片
      </el-button>
      <input
        ref="fileInputRef"
        class="file-input"
        type="file"
        accept="image/*"
        @change="onFilePicked"
      />
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
  position: relative;
  padding: 8px 10px;
  border-bottom: 1px solid rgba(24, 24, 27, 0.08);
  display: flex;
  gap: 8px;
  align-items: center;
}
.file-input {
  position: absolute;
  width: 0;
  height: 0;
  opacity: 0;
  overflow: hidden;
  pointer-events: none;
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
