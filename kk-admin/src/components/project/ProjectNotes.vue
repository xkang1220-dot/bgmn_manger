<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Link } from '@element-plus/icons-vue'
import { bizApi } from '@/api/biz'
import RichTextEditor from '@/components/common/RichTextEditor.vue'

const props = defineProps<{
  projectId: number
}>()

const loading = ref(false)
const notes = ref<any[]>([])
const noteText = ref('')
const noteAttachments = ref<any[]>([])
const publishing = ref(false)
const uploading = ref(false)

function fmtTime(t?: string) {
  if (!t) return ''
  return String(t).replace('T', ' ').slice(0, 16)
}

function attachmentUrl(file: any, preview = false) {
  return `/api/file/${preview ? 'preview' : 'download'}/${file.id}`
}

async function load() {
  if (!props.projectId) return
  loading.value = true
  try {
    notes.value = (await bizApi.projectNotes(props.projectId)) || []
  } finally {
    loading.value = false
  }
}

async function publish() {
  const plainText = noteText.value.replace(/<[^>]*>/g, '').replace(/&nbsp;/g, ' ').trim()
  if (!plainText && !noteAttachments.value.length) {
    ElMessage.warning('请输入备注内容或上传附件')
    return
  }
  publishing.value = true
  try {
    const note = await bizApi.addProjectNote(
      props.projectId,
      noteText.value.trim(),
      noteAttachments.value.map((file) => Number(file.id)),
    )
    notes.value.unshift(note)
    noteText.value = ''
    noteAttachments.value = []
    ElMessage.success('已发布')
  } finally {
    publishing.value = false
  }
}

async function uploadAttachment(options: any) {
  if (noteAttachments.value.length >= 9) {
    ElMessage.warning('每条备注最多上传 9 个附件')
    options.onError?.(new Error('附件数量超限'))
    return
  }
  uploading.value = true
  try {
    const file = await bizApi.uploadProjectNoteAttachment(options.file)
    noteAttachments.value.push(file)
    options.onSuccess?.(file)
  } catch (e: any) {
    options.onError?.(e)
  } finally {
    uploading.value = false
  }
}

async function removePendingAttachment(file: any) {
  await bizApi.deleteProjectNoteAttachment(file.id)
  noteAttachments.value = noteAttachments.value.filter((item) => item.id !== file.id)
}

watch(() => props.projectId, () => {
  noteText.value = ''
  noteAttachments.value = []
  void load()
}, { immediate: true })

defineExpose({ load })
</script>

<template>
  <div v-loading="loading" class="project-notes">
    <div class="note-form glass-panel">
      <div class="note-form__title">发布备注</div>
      <p class="note-form__hint">记录项目中的问题与进展</p>
      <RichTextEditor v-model="noteText" :min-height="120" placeholder="写下项目问题、风险或备注…" />
      <div v-if="noteAttachments.length" class="pending-attachments">
        <div v-for="file in noteAttachments" :key="file.id" class="pending-attachment">
          <a :href="attachmentUrl(file, true)" target="_blank">{{ file.originalName }}</a>
          <el-button link type="danger" size="small" @click="removePendingAttachment(file)">移除</el-button>
        </div>
      </div>
      <div class="note-actions">
        <el-upload :show-file-list="false" :http-request="uploadAttachment" :disabled="uploading" multiple>
          <el-button :loading="uploading" plain>上传附件</el-button>
        </el-upload>
        <span class="attachment-tip">最多 9 个附件</span>
        <el-button type="primary" :loading="publishing" @click="publish">发布</el-button>
      </div>
    </div>

    <div class="note-list">
      <div v-for="note in notes" :key="note.id" class="note-item glass-panel">
        <div class="note-head">
          <b>{{ note.authorName || '用户' }}</b>
          <span>{{ fmtTime(note.createTime) }}</span>
        </div>
        <div class="note-body" v-html="note.content" />
        <div v-if="note.attachments?.length" class="note-attachments">
          <a
            v-for="file in note.attachments"
            :key="file.id"
            :href="attachmentUrl(file)"
            class="note-attachment"
            target="_blank"
          >
            <el-icon><Link /></el-icon>
            <span>{{ file.originalName || `附件 ${file.id}` }}</span>
          </a>
        </div>
      </div>
      <el-empty v-if="!notes.length && !loading" description="暂无项目备注" />
    </div>
  </div>
</template>

<style scoped>
.project-notes {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.note-form,
.note-item {
  background: var(--kk-glass-bg);
  border: 1px solid var(--kk-glass-border);
  border-radius: var(--kk-radius);
  box-shadow: var(--kk-glass-shadow);
  backdrop-filter: var(--kk-glass-blur);
  -webkit-backdrop-filter: var(--kk-glass-blur);
  padding: 16px 18px;
}

.note-form__title {
  font-size: 14px;
  font-weight: 600;
  color: var(--kk-text);
  margin-bottom: 4px;
}

.note-form__hint {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--kk-text-muted);
}

.note-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}

.note-actions > .el-button:last-child {
  margin-left: auto;
}

.attachment-tip {
  font-size: 12px;
  color: var(--kk-text-muted);
}

.note-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.note-head {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: var(--kk-text-muted);
  margin-bottom: 8px;
}

.note-head b {
  color: var(--kk-text);
  font-size: 13px;
}

.note-body {
  font-size: 14px;
  line-height: 1.6;
  color: var(--kk-text);
  overflow-wrap: anywhere;
}

.note-body :deep(p) { margin: 0 0 6px; }
.note-body :deep(ul),
.note-body :deep(ol) { margin: 4px 0; padding-left: 22px; }
.note-body :deep(a) { color: #409eff; }

.note-attachments,
.pending-attachments {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 10px;
}

.note-attachment,
.pending-attachment {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.note-attachment {
  width: fit-content;
  max-width: 100%;
  color: #409eff;
  text-decoration: none;
  font-size: 13px;
}

.note-attachment span,
.pending-attachment a {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pending-attachment {
  justify-content: space-between;
  padding: 5px 8px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.45);
  font-size: 13px;
}

.pending-attachment a {
  color: var(--kk-text-secondary);
}

@media (prefers-reduced-transparency: reduce) {
  .note-form,
  .note-item {
    background: #fff;
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}
</style>
