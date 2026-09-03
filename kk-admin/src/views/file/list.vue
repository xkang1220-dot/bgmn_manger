<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bizApi } from '@/api/biz'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const query = reactive({ page: 1, pageSize: 10, originalName: '' })
const list = ref<any[]>([])
const total = ref(0)
const uploading = ref(false)

const storageMap: Record<string, string> = {
  local: '本地磁盘',
  minio: 'MinIO',
  rustfs: 'RustFS 文件服务器',
}

function formatSize(size?: number) {
  if (!size) return '0 B'
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  if (size < 1024 * 1024 * 1024) return `${(size / 1024 / 1024).toFixed(1)} MB`
  return `${(size / 1024 / 1024 / 1024).toFixed(1)} GB`
}

async function load() {
  const res = await bizApi.filePage(query)
  list.value = res.list
  total.value = res.total
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

function download(id: number) {
  window.open(`/api/file/download/${id}`, '_blank')
}

function preview(id: number) {
  window.open(`/api/file/preview/${id}`, '_blank')
}

function openUrl(url?: string) {
  if (!url) return
  window.open(url, '_blank')
}

function copyUrl(url?: string) {
  if (!url) return
  navigator.clipboard.writeText(url).then(() => ElMessage.success('链接已复制'))
}

onMounted(load)
</script>

<template>
  <div class="page-card">
    <div class="page-header">
      <div>
        <h3 class="page-title">文件管理</h3>
        <p class="page-desc">文件上传到 RustFS 文件服务器，桶内路径前缀 kk-files</p>
      </div>
      <el-upload :show-file-list="false" :http-request="onUpload">
        <el-button type="primary" :loading="uploading">上传文件</el-button>
      </el-upload>
    </div>

    <div class="toolbar">
      <el-input v-model="query.originalName" placeholder="文件名" clearable style="width: 220px" @keyup.enter="load" />
      <el-button type="primary" @click="load">查询</el-button>
    </div>

    <el-table :data="list">
      <el-table-column prop="originalName" label="文件名" min-width="180" show-overflow-tooltip />
      <el-table-column label="大小" width="100">
        <template #default="{ row }">{{ formatSize(row.size) }}</template>
      </el-table-column>
      <el-table-column label="存储位置" width="130">
        <template #default="{ row }">
          <el-tag size="small" :type="row.storageType === 'local' ? 'info' : 'success'">
            {{ storageMap[row.storageType] || row.storageType || '未知' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="访问地址" min-width="240">
        <template #default="{ row }">
          <div v-if="row.url" class="file-url-cell">
            <el-link type="primary" :underline="false" @click="openUrl(row.url)">{{ row.url }}</el-link>
            <el-button link type="primary" @click="copyUrl(row.url)">复制</el-button>
          </div>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="bizType" label="业务" width="100" />
      <el-table-column prop="uploaderName" label="上传人" width="100" />
      <el-table-column prop="createTime" label="上传时间" width="170" />
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="preview(row.id)">预览</el-button>
          <el-button link type="primary" @click="download(row.id)">下载</el-button>
          <el-button link type="danger" @click="remove(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      style="margin-top: 12px"
      v-model:current-page="query.page"
      :total="total"
      layout="total, prev, pager, next"
      @current-change="load"
    />
  </div>
</template>

<style scoped>
.page-desc {
  margin: 4px 0 0;
  font-size: 13px;
  color: #64748b;
}

.file-url-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.file-url-cell :deep(.el-link) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 180px;
}

.muted {
  color: #94a3b8;
}
</style>
