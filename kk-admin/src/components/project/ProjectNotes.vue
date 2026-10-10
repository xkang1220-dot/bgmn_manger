<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Document,
  Link as LinkIcon,
  Monitor,
  FolderOpened,
  Plus,
  Search,
} from '@element-plus/icons-vue'
import { bizApi } from '@/api/biz'
import RichTextEditor from '@/components/common/RichTextEditor.vue'

interface LinkItem {
  id: string
  title: string
  url: string
  remark?: string
}

interface LocalFileItem {
  id: string
  name: string
  size: number
  createTime: string
  objectUrl?: string
  dataUrl?: string
  mimeType?: string
}

const MAX_LOCAL_DATA_URL_BYTES = 2 * 1024 * 1024

interface ResourceStore {
  repos: LinkItem[]
  websites: LinkItem[]
  files: LocalFileItem[]
  hiddenFileIds: number[]
  descriptionDraft?: string
  seeded?: boolean
}

type FileRow = {
  key: string
  id: string | number
  name: string
  format: string
  source: 'note' | 'local'
  sourceLabel: string
  authorName: string
  createTime: string
  size?: number
  href?: string
  previewHref?: string
  noteId?: number
}

const props = defineProps<{
  projectId: number
  description?: string
  websiteUrl?: string
  repositoryUrl?: string
}>()

const emit = defineEmits<{
  'save-description': [value: string]
}>()

const loading = ref(false)
const notes = ref<any[]>([])
const noteText = ref('')
const noteAttachments = ref<any[]>([])
const publishing = ref(false)
const uploading = ref(false)
const savingDesc = ref(false)
const descriptionText = ref('')
const editingDesc = ref(false)
const editingNote = ref(false)

const store = reactive<ResourceStore>({
  repos: [],
  websites: [],
  files: [],
  hiddenFileIds: [],
  seeded: false,
})

const fileKeyword = ref('')
const fileRange = ref<[string, string] | null>(null)
const fileSort = ref<'desc' | 'asc'>('desc')

const linkDialog = ref(false)
const linkKind = ref<'repos' | 'websites'>('repos')
const linkEditingId = ref<string | null>(null)
const linkForm = reactive({ title: '', url: '', remark: '' })

function storageKey(projectId: number) {
  return `bgmn:project-resources:${projectId}`
}

function fmtTime(t?: string) {
  if (!t) return ''
  return String(t).replace('T', ' ').slice(0, 16)
}

function fmtSize(size?: number) {
  if (size == null || Number.isNaN(size)) return '—'
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / (1024 * 1024)).toFixed(1)} MB`
}

function fileFormat(name?: string) {
  const text = String(name || '').trim()
  const index = text.lastIndexOf('.')
  if (index <= 0 || index === text.length - 1) return '—'
  return text.slice(index + 1).toUpperCase()
}

function attachmentUrl(file: any, preview = false) {
  return `/api/file/${preview ? 'preview' : 'download'}/${file.id}`
}

function isHttpUrl(url: string) {
  try {
    const u = new URL(url)
    return u.protocol === 'http:' || u.protocol === 'https:'
  } catch {
    return false
  }
}

function uid(prefix: string) {
  return `${prefix}_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`
}

function readStore(projectId: number): ResourceStore {
  try {
    const raw = JSON.parse(localStorage.getItem(storageKey(projectId)) || 'null')
    if (!raw || typeof raw !== 'object') {
      return { repos: [], websites: [], files: [], hiddenFileIds: [], seeded: false }
    }
    return {
      repos: Array.isArray(raw.repos) ? raw.repos : [],
      websites: Array.isArray(raw.websites) ? raw.websites : [],
      files: Array.isArray(raw.files) ? raw.files : [],
      hiddenFileIds: Array.isArray(raw.hiddenFileIds) ? raw.hiddenFileIds.map(Number) : [],
      descriptionDraft: typeof raw.descriptionDraft === 'string' ? raw.descriptionDraft : undefined,
      seeded: !!raw.seeded,
    }
  } catch {
    return { repos: [], websites: [], files: [], hiddenFileIds: [], seeded: false }
  }
}

function persistStore() {
  if (!props.projectId) return
  const payload: ResourceStore = {
    repos: store.repos,
    websites: store.websites,
    files: store.files.map(({ id, name, size, createTime, mimeType, dataUrl }) => ({
      id, name, size, createTime, mimeType, dataUrl,
    })),
    hiddenFileIds: store.hiddenFileIds,
    descriptionDraft: store.descriptionDraft,
    seeded: store.seeded,
  }
  localStorage.setItem(storageKey(props.projectId), JSON.stringify(payload))
}

function readFileAsDataUrl(file: File) {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result || ''))
    reader.onerror = () => reject(reader.error || new Error('读取文件失败'))
    reader.readAsDataURL(file)
  })
}

function applyStore(data: ResourceStore) {
  store.repos = data.repos
  store.websites = data.websites
  store.files = data.files
  store.hiddenFileIds = data.hiddenFileIds
  store.descriptionDraft = data.descriptionDraft
  store.seeded = data.seeded
}

function seedLinksFromProps() {
  if (store.seeded) return
  if (!store.repos.length && props.repositoryUrl?.trim()) {
    store.repos = [{
      id: uid('repo'),
      title: '代码仓库',
      url: props.repositoryUrl.trim(),
      remark: '',
    }]
  }
  if (!store.websites.length && props.websiteUrl?.trim()) {
    store.websites = [{
      id: uid('web'),
      title: '项目网站',
      url: props.websiteUrl.trim(),
      remark: '',
    }]
  }
  store.seeded = true
  persistStore()
}

function plainText(html?: string) {
  return String(html || '').replace(/<[^>]*>/g, '').replace(/&nbsp;/g, ' ').trim()
}

const displayDescription = computed(() => store.descriptionDraft ?? props.description ?? '')
const hasDescription = computed(() => !!plainText(displayDescription.value))

function syncDescription() {
  descriptionText.value = store.descriptionDraft ?? props.description ?? ''
}

function openDescEditor() {
  syncDescription()
  editingDesc.value = true
}

function cancelDescEditor() {
  editingDesc.value = false
  syncDescription()
}

const fileRows = computed<FileRow[]>(() => {
  const hidden = new Set(store.hiddenFileIds)
  const fromNotes: FileRow[] = []
  for (const note of notes.value) {
    for (const file of note.attachments || []) {
      if (hidden.has(Number(file.id))) continue
      const name = file.originalName || `附件 ${file.id}`
      fromNotes.push({
        key: `note-${file.id}`,
        id: file.id,
        name,
        format: fileFormat(name),
        source: 'note',
        sourceLabel: '备注附件',
        authorName: note.authorName || '用户',
        createTime: file.createTime || note.createTime || '',
        size: file.size,
        href: attachmentUrl(file),
        previewHref: attachmentUrl(file, true),
        noteId: note.id,
      })
    }
  }
  const fromLocal: FileRow[] = store.files.map((file) => {
    const href = file.objectUrl || file.dataUrl
    return {
      key: `local-${file.id}`,
      id: file.id,
      name: file.name,
      format: fileFormat(file.name),
      source: 'local' as const,
      sourceLabel: '本地演示',
      authorName: '本机',
      createTime: file.createTime,
      size: file.size,
      href,
      previewHref: href,
    }
  })
  return [...fromNotes, ...fromLocal]
})

const filteredFiles = computed(() => {
  const keyword = fileKeyword.value.trim().toLowerCase()
  const [from, to] = fileRange.value || []
  const fromTs = from ? new Date(`${from}T00:00:00`).getTime() : null
  const toTs = to ? new Date(`${to}T23:59:59`).getTime() : null
  const rows = fileRows.value.filter((row) => {
    if (keyword && !row.name.toLowerCase().includes(keyword)
      && !row.format.toLowerCase().includes(keyword)
      && !row.authorName.toLowerCase().includes(keyword)
      && !row.sourceLabel.toLowerCase().includes(keyword)) {
      return false
    }
    if (fromTs != null || toTs != null) {
      const ts = row.createTime ? new Date(row.createTime.replace(' ', 'T')).getTime() : NaN
      if (Number.isNaN(ts)) return false
      if (fromTs != null && ts < fromTs) return false
      if (toTs != null && ts > toTs) return false
    }
    return true
  })
  rows.sort((a, b) => {
    const at = a.createTime ? new Date(a.createTime.replace(' ', 'T')).getTime() : 0
    const bt = b.createTime ? new Date(b.createTime.replace(' ', 'T')).getTime() : 0
    return fileSort.value === 'desc' ? bt - at : at - bt
  })
  return rows
})

const stats = computed(() => ({
  repos: store.repos.length,
  websites: store.websites.length,
  files: fileRows.value.length,
  notes: notes.value.length,
}))

async function loadNotes() {
  if (!props.projectId) return
  loading.value = true
  try {
    notes.value = (await bizApi.projectNotes(props.projectId)) || []
  } finally {
    loading.value = false
  }
}

function loadResources() {
  applyStore(readStore(props.projectId))
  seedLinksFromProps()
  syncDescription()
}

async function saveDescription() {
  const value = descriptionText.value.trim()
  savingDesc.value = true
  try {
    emit('save-description', value)
    store.descriptionDraft = value
    persistStore()
    editingDesc.value = false
    ElMessage.success('项目说明已保存（接口不全时已同步到本地）')
  } finally {
    savingDesc.value = false
  }
}

function openNoteEditor() {
  editingNote.value = true
}

function cancelNoteEditor() {
  editingNote.value = false
  noteText.value = ''
  noteAttachments.value = []
}

function openLinkDialog(kind: 'repos' | 'websites', item?: LinkItem) {
  linkKind.value = kind
  linkEditingId.value = item?.id ?? null
  linkForm.title = item?.title || (kind === 'repos' ? '代码仓库' : '项目网站')
  linkForm.url = item?.url || ''
  linkForm.remark = item?.remark || ''
  linkDialog.value = true
}

function saveLink() {
  const title = linkForm.title.trim()
  const url = linkForm.url.trim()
  const remark = linkForm.remark.trim()
  if (!title) {
    ElMessage.warning('请填写名称')
    return
  }
  if (!isHttpUrl(url)) {
    ElMessage.warning('请填写有效的 http(s) 地址')
    return
  }
  const list = store[linkKind.value]
  if (linkEditingId.value) {
    const target = list.find((item) => item.id === linkEditingId.value)
    if (target) {
      target.title = title
      target.url = url
      target.remark = remark
    }
  } else {
    list.push({ id: uid(linkKind.value === 'repos' ? 'repo' : 'web'), title, url, remark })
  }
  persistStore()
  linkDialog.value = false
  ElMessage.success('已保存到本地演示数据')
}

async function removeLink(kind: 'repos' | 'websites', id: string) {
  try {
    await ElMessageBox.confirm('确认删除该链接？', '提示', { type: 'warning' })
  } catch {
    return
  }
  store[kind] = store[kind].filter((item) => item.id !== id)
  persistStore()
}

function openUrl(url: string) {
  window.open(url, '_blank', 'noopener,noreferrer')
}

async function uploadLocalFile(options: any) {
  const file: File = options.file
  if (!file) {
    options.onError?.(new Error('无效文件'))
    return
  }
  try {
    const item: LocalFileItem = {
      id: uid('file'),
      name: file.name,
      size: file.size,
      createTime: new Date().toISOString().slice(0, 19).replace('T', ' '),
      objectUrl: URL.createObjectURL(file),
      mimeType: file.type,
    }
    if (file.size <= MAX_LOCAL_DATA_URL_BYTES) {
      item.dataUrl = await readFileAsDataUrl(file)
    }
    store.files.unshift(item)
    persistStore()
    options.onSuccess?.(item)
    ElMessage.success(
      file.size <= MAX_LOCAL_DATA_URL_BYTES
        ? '已添加到本地文件库（演示）'
        : '已添加（超过 2MB，刷新后仅保留文件名，需重新上传才能打开）',
    )
  } catch (e: any) {
    options.onError?.(e)
  }
}

async function removeFile(row: FileRow) {
  try {
    await ElMessageBox.confirm(
      row.source === 'note'
        ? '后端暂不支持删除已绑定附件，将在本机隐藏该文件。'
        : '确认从本地文件库删除？',
      '提示',
      { type: 'warning' },
    )
  } catch {
    return
  }
  if (row.source === 'local') {
    const target = store.files.find((item) => item.id === row.id)
    if (target?.objectUrl) URL.revokeObjectURL(target.objectUrl)
    store.files = store.files.filter((item) => item.id !== row.id)
  } else {
    const id = Number(row.id)
    if (!store.hiddenFileIds.includes(id)) store.hiddenFileIds.push(id)
  }
  persistStore()
  ElMessage.success('已更新')
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
    editingNote.value = false
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

watch(
  () => [props.projectId, props.description, props.websiteUrl, props.repositoryUrl] as const,
  () => {
    noteText.value = ''
    noteAttachments.value = []
    editingDesc.value = false
    editingNote.value = false
    fileKeyword.value = ''
    fileRange.value = null
    fileSort.value = 'desc'
    loadResources()
    void loadNotes()
  },
  { immediate: true },
)

defineExpose({ load: loadNotes })
</script>

<template>
  <div v-loading="loading" class="project-resources">
    <div class="resource-stats" aria-label="资料概览">
      <div>
        <span>代码仓库</span>
        <b>{{ stats.repos }}</b>
      </div>
      <div>
        <span>项目网站</span>
        <b>{{ stats.websites }}</b>
      </div>
      <div>
        <span>文件</span>
        <b>{{ stats.files }}</b>
      </div>
      <div>
        <span>备注</span>
        <b>{{ stats.notes }}</b>
      </div>
    </div>

    <p class="resource-tip">
      多地址与独立文件库因后端接口不全，当前用本机演示数据保存（刷新不丢，不同步到其他设备）。
    </p>

    <section class="glass-panel resource-section resource-section--full">
      <div class="section-head section-head--compact">
        <h3><el-icon><Document /></el-icon>项目说明</h3>
        <el-button v-if="!editingDesc" size="small" @click="openDescEditor">编辑说明</el-button>
      </div>
      <template v-if="editingDesc">
        <RichTextEditor
          v-model="descriptionText"
          :min-height="160"
          placeholder="填写项目背景、范围与交付约定…"
        />
        <div class="inline-edit-actions">
          <el-button size="small" @click="cancelDescEditor">取消</el-button>
          <el-button type="primary" size="small" :loading="savingDesc" @click="saveDescription">保存</el-button>
        </div>
      </template>
      <template v-else>
        <div v-if="hasDescription" class="desc-display" v-html="displayDescription" />
        <p v-else class="desc-empty">暂无项目说明，点击右上角编辑</p>
      </template>
    </section>

    <div class="resource-row">
      <div class="resource-row__left">
        <section class="glass-panel resource-section resource-section--compact">
          <div class="section-head section-head--compact">
            <h3><el-icon><FolderOpened /></el-icon>代码仓库 <em>{{ stats.repos }}</em></h3>
            <el-button size="small" :icon="Plus" @click="openLinkDialog('repos')">添加</el-button>
          </div>
          <div v-if="store.repos.length" class="link-list">
            <article v-for="item in store.repos" :key="item.id" class="link-row">
              <div class="link-row__main">
                <b>{{ item.title }}</b>
                <a :href="item.url" target="_blank" rel="noopener noreferrer" :title="item.url">{{ item.url }}</a>
              </div>
              <div class="link-row__ops">
                <el-button link type="primary" size="small" @click="openUrl(item.url)">打开</el-button>
                <el-button link size="small" @click="openLinkDialog('repos', item)">编辑</el-button>
                <el-button link type="danger" size="small" @click="removeLink('repos', item.id)">删除</el-button>
              </div>
            </article>
          </div>
          <p v-else class="inline-empty">暂无仓库</p>
        </section>

        <section class="glass-panel resource-section resource-section--compact">
          <div class="section-head section-head--compact">
            <h3><el-icon><Monitor /></el-icon>项目网站 <em>{{ stats.websites }}</em></h3>
            <el-button size="small" :icon="Plus" @click="openLinkDialog('websites')">添加</el-button>
          </div>
          <div v-if="store.websites.length" class="link-list">
            <article v-for="item in store.websites" :key="item.id" class="link-row">
              <div class="link-row__main">
                <b>{{ item.title }}</b>
                <a :href="item.url" target="_blank" rel="noopener noreferrer" :title="item.url">{{ item.url }}</a>
              </div>
              <div class="link-row__ops">
                <el-button link type="primary" size="small" @click="openUrl(item.url)">打开</el-button>
                <el-button link size="small" @click="openLinkDialog('websites', item)">编辑</el-button>
                <el-button link type="danger" size="small" @click="removeLink('websites', item.id)">删除</el-button>
              </div>
            </article>
          </div>
          <p v-else class="inline-empty">暂无网站</p>
        </section>
      </div>

      <section class="glass-panel resource-section resource-section--compact resource-files">
        <div class="section-head section-head--compact">
          <h3><el-icon><LinkIcon /></el-icon>文件库</h3>
          <el-upload :show-file-list="false" :http-request="uploadLocalFile" multiple>
            <el-button size="small" type="primary">上传</el-button>
          </el-upload>
        </div>

        <div class="file-toolbar">
          <el-input
            v-model="fileKeyword"
            class="file-toolbar__field"
            clearable
            size="small"
            :prefix-icon="Search"
            placeholder="搜索文件"
            style="width: 220px"
          />
          <el-date-picker
            v-model="fileRange"
            class="file-toolbar__field"
            type="daterange"
            size="small"
            value-format="YYYY-MM-DD"
            start-placeholder="开始"
            end-placeholder="结束"
            clearable
            style="width: 220px"
          />
          <el-radio-group v-model="fileSort" size="small">
            <el-radio-button value="desc">新→旧</el-radio-button>
            <el-radio-button value="asc">旧→新</el-radio-button>
          </el-radio-group>
        </div>

        <div class="file-table-wrap">
          <el-table :data="filteredFiles" stripe size="small" empty-text="暂无匹配文件" height="100%">
            <el-table-column prop="name" label="文件名" min-width="140" show-overflow-tooltip />
            <el-table-column prop="format" label="格式" width="72" />
            <el-table-column prop="sourceLabel" label="来源" width="78" />
            <el-table-column label="时间" width="120">
              <template #default="{ row }">{{ fmtTime(row.createTime) || '—' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="row.previewHref || row.href"
                  link
                  type="primary"
                  size="small"
                  :href="row.previewHref || row.href"
                  tag="a"
                  target="_blank"
                >打开</el-button>
                <el-button link type="danger" size="small" @click="removeFile(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </section>
    </div>

    <section class="glass-panel resource-section resource-section--full">
      <div class="section-head section-head--compact">
        <h3>项目备注</h3>
        <el-button v-if="!editingNote" size="small" type="primary" @click="openNoteEditor">写备注</el-button>
      </div>

      <div v-if="editingNote" class="note-composer">
        <RichTextEditor v-model="noteText" :min-height="120" placeholder="写下项目问题、风险或备注…" />
        <div v-if="noteAttachments.length" class="pending-attachments">
          <div v-for="file in noteAttachments" :key="file.id" class="pending-attachment">
            <a :href="attachmentUrl(file, true)" target="_blank">{{ file.originalName }}</a>
            <el-button link type="danger" size="small" @click="removePendingAttachment(file)">移除</el-button>
          </div>
        </div>
        <div class="note-actions">
          <el-upload :show-file-list="false" :http-request="uploadAttachment" :disabled="uploading" multiple>
            <el-button :loading="uploading" plain size="small">上传附件</el-button>
          </el-upload>
          <span class="attachment-tip">最多 9 个附件</span>
          <el-button size="small" @click="cancelNoteEditor">取消</el-button>
          <el-button type="primary" size="small" :loading="publishing" @click="publish">保存</el-button>
        </div>
      </div>

      <div class="note-list" :class="{ 'note-list--spaced': editingNote }">
        <div v-for="note in notes" :key="note.id" class="note-item">
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
              <el-icon><LinkIcon /></el-icon>
              <span>{{ file.originalName || `附件 ${file.id}` }}</span>
            </a>
          </div>
        </div>
        <el-empty v-if="!notes.length && !loading && !editingNote" description="暂无项目备注" :image-size="56" />
      </div>
    </section>

    <el-dialog
      v-model="linkDialog"
      :title="linkEditingId ? '编辑链接' : (linkKind === 'repos' ? '添加代码仓库' : '添加项目网站')"
      width="480px"
      :close-on-click-modal="false"
    >
      <el-form label-width="72px">
        <el-form-item label="名称" required>
          <el-input v-model="linkForm.title" maxlength="40" show-word-limit placeholder="如：前端仓 / 管理后台" />
        </el-form-item>
        <el-form-item label="地址" required>
          <el-input v-model="linkForm.url" placeholder="https://…" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="linkForm.remark" maxlength="80" show-word-limit placeholder="可选说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="linkDialog = false">取消</el-button>
        <el-button type="primary" @click="saveLink">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.project-resources {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.resource-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.resource-stats > div {
  padding: 8px 12px;
  border: 1px solid var(--kk-glass-border, rgba(255, 255, 255, 0.72));
  border-radius: 10px;
  background: var(--kk-glass-bg, rgba(255, 255, 255, 0.46));
  backdrop-filter: var(--kk-glass-blur, saturate(180%) blur(22px));
  box-shadow: var(--kk-glass-shadow, 0 1px 2px rgba(0, 0, 0, 0.03), 0 10px 28px rgba(0, 0, 0, 0.04));
}

.resource-stats span,
.resource-stats b { display: block; }
.resource-stats span { font-size: 11px; color: var(--kk-text-muted); }
.resource-stats b {
  margin-top: 2px;
  font-size: 16px;
  color: var(--kk-text);
  font-variant-numeric: tabular-nums;
}

.resource-tip {
  margin: 0;
  font-size: 12px;
  color: var(--kk-text-muted);
  line-height: 1.45;
}

.resource-row {
  display: grid;
  grid-template-columns: minmax(240px, 1fr) minmax(0, 2fr);
  gap: 10px;
  align-items: stretch;
  min-height: 380px;
}

.resource-row__left {
  display: grid;
  grid-template-rows: 1fr 1fr;
  gap: 10px;
  min-width: 0;
  min-height: 100%;
}

.resource-row__left > .resource-section {
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.resource-row__left .link-list,
.resource-row__left .inline-empty {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.resource-section--full {
  width: 100%;
}

.resource-files {
  min-width: 0;
  min-height: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.file-table-wrap {
  flex: 1 1 auto;
  height: 0;
  min-height: 220px;
}

.glass-panel,
.resource-section {
  background: var(--kk-glass-bg);
  border: 1px solid var(--kk-glass-border);
  border-radius: var(--kk-radius);
  box-shadow: var(--kk-glass-shadow);
  backdrop-filter: var(--kk-glass-blur);
  -webkit-backdrop-filter: var(--kk-glass-blur);
  padding: 14px 16px;
}

.resource-section--compact {
  padding: 12px 14px;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}

.section-head--compact {
  margin-bottom: 8px;
}

.section-head h3 {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--kk-text);
}

.section-head h3 em {
  margin-left: 2px;
  font-style: normal;
  font-weight: 600;
  font-size: 12px;
  color: var(--kk-text-muted);
  font-variant-numeric: tabular-nums;
}

.desc-display {
  margin: 0;
  font-size: 13px;
  line-height: 1.65;
  color: var(--kk-text);
  overflow-wrap: anywhere;
  max-height: 180px;
  overflow: auto;
}

.desc-display :deep(p) { margin: 0 0 6px; }
.desc-display :deep(ul),
.desc-display :deep(ol) { margin: 4px 0; padding-left: 22px; }
.desc-display :deep(a) { color: #409eff; }

.desc-empty,
.inline-empty {
  margin: 0;
  font-size: 12px;
  color: var(--kk-text-muted);
}

.link-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.link-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 34px;
  padding: 4px 8px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.38);
}

.link-row__main {
  min-width: 0;
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex: 1;
}

.link-row__main b {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--kk-text);
}

.link-row__main a {
  min-width: 0;
  font-size: 12px;
  color: #2563eb;
  text-decoration: none;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.link-row__ops {
  flex-shrink: 0;
  display: flex;
  align-items: center;
}

.file-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-start;
  gap: 8px;
  margin-bottom: 10px;
  width: fit-content;
  max-width: 100%;
}

.file-toolbar__field {
  width: 220px !important;
  max-width: 220px !important;
  flex: 0 0 220px !important;
}

.file-toolbar :deep(.file-toolbar__field.el-input),
.file-toolbar :deep(.file-toolbar__field.el-date-editor),
.file-toolbar :deep(.el-date-editor.file-toolbar__field) {
  width: 220px !important;
  max-width: 220px !important;
}

.inline-edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 10px;
}

.note-composer {
  margin-bottom: 4px;
}

.note-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}

.note-actions > .el-button:nth-last-child(2) {
  margin-left: auto;
}

.note-list--spaced {
  margin-top: 14px;
}

.attachment-tip {
  font-size: 12px;
  color: var(--kk-text-muted);
}

.note-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 0;
}

.note-item {
  padding: 10px 12px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.4);
  border: 1px solid rgba(255, 255, 255, 0.62);
}

.note-head {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: var(--kk-text-muted);
  margin-bottom: 6px;
}

.note-head b {
  color: var(--kk-text);
  font-size: 13px;
}

.note-body {
  font-size: 13px;
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
  margin-top: 8px;
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

@media (max-width: 900px) {
  .resource-stats { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .resource-row { grid-template-columns: 1fr; }
  .link-row {
    flex-direction: column;
    align-items: flex-start;
    gap: 2px;
    padding: 8px;
  }
  .file-toolbar__field,
  .file-toolbar :deep(.file-toolbar__field.el-input),
  .file-toolbar :deep(.file-toolbar__field.el-date-editor),
  .file-toolbar :deep(.el-date-editor.file-toolbar__field) {
    width: 200px !important;
    max-width: 200px !important;
    flex: 0 0 200px !important;
  }
}

@media (prefers-reduced-transparency: reduce) {
  .resource-stats > div,
  .glass-panel,
  .resource-section,
  .link-row,
  .note-item {
    background: #fff;
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}
</style>
