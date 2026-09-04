<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bizApi } from '@/api/biz'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const query = reactive({ page: 1, pageSize: 12, originalName: '' })
const list = ref<any[]>([])
const total = ref(0)
const uploading = ref(false)
const previewVisible = ref(false)
const previewRow = ref<any>(null)
const imageViewerVisible = ref(false)
const imageViewerIndex = ref(0)

const storageMap: Record<string, string> = {
  local: '本地磁盘',
  minio: 'MinIO',
  rustfs: 'RustFS',
}

const IMAGE_EXT = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg']

const filteredEmpty = computed(() => !list.value.length && !!query.originalName.trim())

function extOf(row: any) {
  return String(row?.originalName || '').split('.').pop()?.toLowerCase() || ''
}

function isImage(row: any) {
  const type = String(row?.contentType || '')
  return type.startsWith('image/') || IMAGE_EXT.includes(extOf(row))
}

function isPdf(row: any) {
  const type = String(row?.contentType || '')
  return type.includes('pdf') || extOf(row) === 'pdf'
}

function canPreview(row: any) {
  return isImage(row) || isPdf(row)
}

const imagePreviewList = computed(() =>
  list.value.filter(isImage).map((row) => previewUrl(row.id)),
)

function previewUrl(id: number) {
  return `/api/file/preview/${id}`
}

function downloadUrl(id: number) {
  return `/api/file/download/${id}`
}

function formatSize(size?: number) {
  if (!size) return '0 B'
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  if (size < 1024 * 1024 * 1024) return `${(size / 1024 / 1024).toFixed(1)} MB`
  return `${(size / 1024 / 1024 / 1024).toFixed(1)} GB`
}

function fmtTime(t?: string) {
  if (!t) return '—'
  return t.replace('T', ' ').slice(0, 16)
}

async function load() {
  const res = await bizApi.filePage(query)
  list.value = res.list
  total.value = res.total
}

function onFilter() {
  query.page = 1
  load()
}

function resetFilter() {
  query.originalName = ''
  query.page = 1
  load()
}

async function onUpload(options: any) {
  uploading.value = true
  try {
    const form = new FormData()
    form.append('file', options.file)
    form.append('bizType', 'common')
    const res = await fetch('/api/file/upload', {
      method: 'POST',
      headers: { Authorization: userStore.token || '' },
      body: form,
    })
    const json = await res.json()
    if (json.code !== 200) throw new Error(json.message)
    ElMessage.success('上传成功')
    await load()
  } catch (e: any) {
    ElMessage.error(e.message || '上传失败')
  } finally {
    uploading.value = false
  }
}

async function remove(id: number) {
  await ElMessageBox.confirm('确认删除文件？')
  await bizApi.deleteFile(id)
  ElMessage.success('已删除')
  await load()
}

function openPreview(row: any) {
  if (isImage(row)) {
    const url = previewUrl(row.id)
    const index = imagePreviewList.value.indexOf(url)
    imageViewerIndex.value = index >= 0 ? index : 0
    imageViewerVisible.value = true
    return
  }
  previewRow.value = row
  previewVisible.value = true
}

function download(row: any) {
  window.open(downloadUrl(row.id), '_blank')
}

function onCardClick(row: any) {
  if (canPreview(row)) openPreview(row)
  else download(row)
}

function copyUrl(url?: string) {
  if (!url) return
  navigator.clipboard.writeText(url).then(() => ElMessage.success('链接已复制'))
}

onMounted(load)
</script>

<template>
  <div class="page-stack">
    <div class="page-top">
      <div class="page-top__main">
        <p class="page-desc">图片和 PDF 点卡片直接预览，其他文件跳转下载</p>
      </div>
      <div class="page-actions">
        <el-upload :show-file-list="false" :http-request="onUpload">
          <el-button type="primary" :loading="uploading">上传文件</el-button>
        </el-upload>
      </div>
    </div>

    <el-form class="filter-bar" @submit.prevent="onFilter">
      <el-form-item label="文件名">
        <el-input
          v-model="query.originalName"
          clearable
          placeholder="搜索文件名"
          class="filter-keyword--wide"
          @keyup.enter="onFilter"
        />
      </el-form-item>
      <el-form-item class="filter-actions">
        <el-button type="primary" native-type="submit">查询</el-button>
        <el-button @click="resetFilter">重置</el-button>
      </el-form-item>
    </el-form>

    <div v-if="list.length" class="board">
      <article
        v-for="row in list"
        :key="row.id"
        class="file-card"
        :class="{ 'is-previewable': canPreview(row) }"
        role="button"
        tabindex="0"
        @click="onCardClick(row)"
        @keyup.enter="onCardClick(row)"
      >
        <div class="file-cover">
          <el-image
            v-if="isImage(row)"
            class="file-cover-image"
            :src="previewUrl(row.id)"
            :alt="row.originalName"
            fit="cover"
          />
          <el-icon v-else-if="isPdf(row)" class="file-glyph file-glyph--pdf" :size="40"><Document /></el-icon>
          <el-icon v-else class="file-glyph" :size="40"><Document /></el-icon>
          <span class="file-cover-tip">{{ canPreview(row) ? '预览' : '下载' }}</span>
        </div>
        <div class="file-body">
          <div class="file-name" :title="row.originalName">{{ row.originalName }}</div>
          <div class="file-meta">
            {{ formatSize(row.size) }} · {{ row.uploaderName || '—' }}
          </div>
          <div class="file-meta">{{ fmtTime(row.createTime) }}</div>
          <div class="file-tags">
            <el-tag size="small" :type="row.storageType === 'local' ? 'info' : 'success'">
              {{ storageMap[row.storageType] || row.storageType || '未知' }}
            </el-tag>
            <el-tag v-if="row.bizType" size="small" type="info">{{ row.bizType }}</el-tag>
          </div>
        </div>
        <div class="file-ops" @click.stop>
          <el-button
            v-if="canPreview(row)"
            class="icon-btn"
            text
            aria-label="预览"
            title="预览"
            @click="openPreview(row)"
          >
            <el-icon :size="16"><View /></el-icon>
          </el-button>
          <el-button
            class="icon-btn"
            text
            aria-label="下载"
            title="下载"
            @click="download(row)"
          >
            <el-icon :size="16"><Download /></el-icon>
          </el-button>
          <el-button
            v-if="row.url"
            class="icon-btn"
            text
            aria-label="复制链接"
            title="复制链接"
            @click="copyUrl(row.url)"
          >
            <el-icon :size="16"><CopyDocument /></el-icon>
          </el-button>
          <el-button
            class="icon-btn is-danger"
            text
            aria-label="删除"
            title="删除"
            @click="remove(row.id)"
          >
            <el-icon :size="16"><Delete /></el-icon>
          </el-button>
        </div>
      </article>
    </div>
    <el-empty v-else :description="filteredEmpty ? '没有匹配的文件' : '暂无文件'" />

    <div v-if="total > query.pageSize" class="page-footer">
      <el-pagination
        v-model:current-page="query.page"
        :page-size="query.pageSize"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="load"
      />
    </div>

    <el-image-viewer
      v-if="imageViewerVisible"
      :url-list="imagePreviewList"
      :initial-index="imageViewerIndex"
      teleported
      hide-on-click-modal
      @close="imageViewerVisible = false"
    />

    <el-dialog
      v-model="previewVisible"
      :title="previewRow?.originalName || '预览'"
      width="860px"
      align-center
      append-to-body
      destroy-on-close
      class="preview-dialog"
    >
      <iframe
        v-if="previewRow && isPdf(previewRow)"
        class="preview-frame"
        :src="previewUrl(previewRow.id)"
        title="PDF 预览"
      />
      <template #footer>
        <el-button @click="previewVisible = false">关闭</el-button>
        <el-button type="primary" @click="download(previewRow)">下载</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.board {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 14px;
}
.file-card {
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  cursor: pointer;
  background: var(--kk-glass-bg);
  border: 1px solid var(--kk-glass-border);
  border-radius: var(--kk-radius);
  box-shadow: var(--kk-glass-shadow);
  backdrop-filter: var(--kk-glass-blur);
  -webkit-backdrop-filter: var(--kk-glass-blur);
  transition: box-shadow 0.15s var(--kk-ease);
}
.file-card:hover { box-shadow: 0 8px 28px rgba(0, 0, 0, 0.08); }
.file-card:focus-visible {
  outline: 2px solid var(--kk-primary);
  outline-offset: 2px;
}
.file-cover {
  position: relative;
  height: 140px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.28);
  overflow: hidden;
}
.file-cover-image {
  width: 100%;
  height: 100%;
}
.file-cover-image :deep(.el-image__inner) {
  width: 100%;
  height: 100%;
}
.file-glyph { color: var(--kk-text-muted); }
.file-glyph--pdf { color: #dc2626; }
.file-cover-tip {
  position: absolute;
  right: 10px;
  bottom: 8px;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  color: var(--kk-text-secondary);
  background: rgba(255, 255, 255, 0.72);
}
.file-body {
  padding: 12px 14px 10px;
  min-width: 0;
}
.file-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--kk-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.file-meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--kk-text-muted);
}
.file-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}
.file-ops {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: 0 8px 8px;
}
.icon-btn {
  width: 32px;
  height: 32px;
  padding: 0;
  color: var(--kk-text-secondary);
}
.icon-btn:hover { color: var(--kk-primary); }
.icon-btn.is-danger:hover { color: var(--kk-danger); }

.preview-frame {
  width: 100%;
  height: min(52vh, calc(100vh - 280px));
  border: 0;
  border-radius: var(--kk-radius-sm);
  background: #fff;
}

@media (prefers-reduced-transparency: reduce) {
  .file-card {
    background: #fff;
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}
</style>
