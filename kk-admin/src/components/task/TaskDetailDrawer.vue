<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import type { UploadFile, UploadProps } from 'element-plus'
import { bizApi } from '@/api/biz'
import { sysApi } from '@/api/system'
import { useUserStore } from '@/stores/user'

const props = defineProps<{
  modelValue: boolean
  taskId?: number | null
  /** 新建时默认项目 */
  defaultProjectId?: number | null
}>()

const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  saved: []
  deleted: []
}>()

const userStore = useUserStore()
const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

const loading = ref(false)
const saving = ref(false)
const editing = ref(false)
const isNew = ref(false)
const projects = ref<any[]>([])
const users = ref<any[]>([])
const detail = ref<any>(null)
const comments = ref<any[]>([])
const flows = ref<any[]>([])
const commentText = ref('')
const commenting = ref(false)
const uploading = ref(false)
const imageFileList = ref<UploadFile[]>([])
const previewVisible = ref(false)
const previewUrl = ref('')
const transferDialog = ref(false)
const transferring = ref(false)
const transferForm = reactive({
  assigneeId: undefined as number | undefined,
  remark: '',
  imageFileIds: [] as number[],
})
const transferImages = ref<any[]>([])
const transferUploading = ref(false)
const detailPane = ref('comments')

const form = reactive<any>({
  id: undefined,
  title: '',
  projectId: undefined,
  assigneeId: undefined,
  participantIds: [] as number[],
  status: 0,
  priority: 2,
  startDate: '',
  dueDate: '',
  progress: 0,
  content: '',
  imageFileIds: [] as number[],
})

const statusMap: Record<number, string> = { 0: '待办', 1: '进行中', 2: '已完成', 3: '已取消' }
const priorityMap: Record<number, string> = { 1: '高', 2: '中', 3: '低' }
const statusType: Record<number, '' | 'success' | 'warning' | 'info' | 'danger'> = {
  0: 'info',
  1: 'warning',
  2: 'success',
  3: 'info',
}

function emptyForm() {
  return {
    id: undefined,
    title: '',
    projectId: props.defaultProjectId || undefined,
    assigneeId: undefined,
    participantIds: [] as number[],
    status: 0,
    priority: 2,
    startDate: '',
    dueDate: '',
    progress: 0,
    content: '',
    imageFileIds: [] as number[],
  }
}

function imageUrl(file: { id?: number; url?: string }) {
  return file.url || `/api/file/preview/${file.id}`
}

function toUploadFile(file: any): UploadFile {
  return {
    name: file.originalName || `image-${file.id}`,
    url: imageUrl(file),
    uid: file.id,
    status: 'success',
  }
}

function syncImageFileIds() {
  form.imageFileIds = imageFileList.value
    .map((item) => Number(item.uid))
    .filter((id) => !Number.isNaN(id))
}

function fmtTime(t?: string) {
  if (!t) return '—'
  return t.replace('T', ' ').slice(0, 16)
}

function disableStartDate(date: Date) {
  if (!form.dueDate) return false
  return date.getTime() > new Date(form.dueDate).getTime()
}

function disableDueDate(date: Date) {
  if (!form.startDate) return false
  const start = new Date(form.startDate)
  start.setHours(0, 0, 0, 0)
  return date.getTime() < start.getTime()
}

function onStatusChange() {
  if (form.status === 2) form.progress = 100
  else if (form.status === 0) form.progress = 0
}

async function loadDetail(id: number) {
  loading.value = true
  try {
    await ensureOptions()
    const [full, commentList, flowList] = await Promise.all([
      bizApi.taskDetail(id),
      bizApi.taskComments(id),
      bizApi.taskFlows(id),
    ])
    detail.value = full
    Object.assign(form, {
      ...full,
      participantIds: full.participantIds || [],
      imageFileIds: (full.images || []).map((f: any) => f.id),
    })
    imageFileList.value = (full.images || []).map(toUploadFile)
    comments.value = commentList
    flows.value = flowList
    editing.value = false
    isNew.value = false
    detailPane.value = 'comments'
  } finally {
    loading.value = false
  }
}

async function openCreate() {
  await ensureOptions()
  detail.value = null
  Object.assign(form, emptyForm())
  imageFileList.value = []
  comments.value = []
  flows.value = []
  commentText.value = ''
  editing.value = true
  isNew.value = true
}

async function ensureOptions() {
  const jobs: Promise<any>[] = []
  if (!projects.value.length) jobs.push(bizApi.projectList().then((r) => { projects.value = r }))
  if (!users.value.length) jobs.push(sysApi.userList().then((r) => { users.value = r }))
  if (jobs.length) await Promise.all(jobs)
}

watch(
  () => [props.modelValue, props.taskId] as const,
  async ([open, id]) => {
    if (!open) return
    if (id) await loadDetail(Number(id))
    else await openCreate()
  },
)

async function cancelEdit() {
  if (isNew.value) {
    visible.value = false
    return
  }
  if (form.id) await loadDetail(form.id)
  else editing.value = false
}

async function save() {
  if (!form.title?.trim()) {
    ElMessage.warning('请填写任务标题')
    return
  }
  if (!form.projectId) {
    ElMessage.warning('请选择所属项目')
    return
  }
  if (form.startDate && form.dueDate && form.dueDate < form.startDate) {
    ElMessage.warning('截止日期不能早于开始日期')
    return
  }
  syncImageFileIds()
  saving.value = true
  try {
    await bizApi.saveTask(form, !isNew.value && !!form.id)
    ElMessage.success('保存成功')
    emit('saved')
    if (isNew.value) {
      visible.value = false
    } else if (form.id) {
      await loadDetail(form.id)
    }
  } finally {
    saving.value = false
  }
}

async function remove() {
  if (!form.id) return
  await ElMessageBox.confirm('确认删除该任务？')
  await bizApi.deleteTask(form.id)
  ElMessage.success('已删除')
  visible.value = false
  emit('deleted')
}

function openTransfer() {
  transferForm.assigneeId = undefined
  transferForm.remark = ''
  transferForm.imageFileIds = []
  transferImages.value = []
  transferDialog.value = true
}

async function onUploadTransferImage(options: any) {
  transferUploading.value = true
  try {
    const file = await bizApi.uploadTaskImage(options.file)
    transferImages.value.push(file)
    transferForm.imageFileIds.push(file.id)
    options.onSuccess?.(file)
  } catch (e: any) {
    ElMessage.error(e.message || '上传失败')
    options.onError?.(e)
  } finally {
    transferUploading.value = false
  }
}

async function submitTransfer() {
  if (!form.id) return
  if (!transferForm.assigneeId) {
    ElMessage.warning('请选择转交对象')
    return
  }
  transferring.value = true
  try {
    await bizApi.transferTask(form.id, {
      assigneeId: transferForm.assigneeId,
      remark: transferForm.remark || undefined,
      imageFileIds: transferForm.imageFileIds.length ? transferForm.imageFileIds : undefined,
    })
    ElMessage.success('已转交')
    transferDialog.value = false
    emit('saved')
    await loadDetail(form.id)
    detailPane.value = 'flows'
  } finally {
    transferring.value = false
  }
}

async function onUploadImage(options: any) {
  uploading.value = true
  try {
    const file = await bizApi.uploadTaskImage(options.file)
    options.onSuccess?.(file)
    await nextTick()
    const item = imageFileList.value.find((f) => f.uid === options.file.uid)
    if (item) {
      item.url = imageUrl(file)
      item.uid = file.id
      item.name = file.originalName || item.name
    }
    syncImageFileIds()
    ElMessage.success('图片上传成功')
  } catch (e: any) {
    ElMessage.error(e.message || '图片上传失败')
    options.onError?.(e)
  } finally {
    uploading.value = false
  }
}

const beforeImageUpload: UploadProps['beforeUpload'] = (file) => {
  if (!file.type.startsWith('image/')) {
    ElMessage.warning('仅支持上传图片')
    return false
  }
  if (file.size / 1024 / 1024 > 10) {
    ElMessage.warning('单张图片不能超过 10MB')
    return false
  }
  return true
}

async function onRemoveImage(uploadFile: UploadFile) {
  const fileId = Number(uploadFile.uid)
  if (!Number.isNaN(fileId)) {
    try {
      await bizApi.deleteTaskImage(fileId)
    } catch (e: any) {
      ElMessage.error(e.message || '图片删除失败')
      return false
    }
  }
  syncImageFileIds()
  return true
}

function onPreviewImage(uploadFile: UploadFile) {
  previewUrl.value = uploadFile.url || ''
  previewVisible.value = true
}

async function submitComment() {
  if (!form.id) return
  if (!commentText.value.trim()) {
    ElMessage.warning('请输入评论内容')
    return
  }
  commenting.value = true
  try {
    const c = await bizApi.addTaskComment(form.id, commentText.value.trim())
    comments.value.push(c)
    commentText.value = ''
    ElMessage.success('已发表')
  } finally {
    commenting.value = false
  }
}

async function removeComment(c: any) {
  await ElMessageBox.confirm('删除这条评论？')
  await bizApi.deleteTaskComment(c.id)
  comments.value = comments.value.filter((x) => x.id !== c.id)
  ElMessage.success('已删除')
}

function canDeleteComment(c: any) {
  return c.createBy && userStore.user?.id && Number(c.createBy) === Number(userStore.user.id)
}
</script>

<template>
  <el-drawer
    v-model="visible"
    :title="isNew ? '新建任务' : editing ? '编辑任务' : '任务详情'"
    size="520px"
    destroy-on-close
    append-to-body
  >
    <div v-loading="loading" class="task-detail">
      <!-- 查看模式 -->
      <template v-if="!editing && detail">
        <div class="detail-head">
          <h3 class="detail-title">{{ detail.title }}</h3>
          <div class="detail-tags">
            <el-tag :type="statusType[detail.status]" size="small" effect="light">{{ statusMap[detail.status] }}</el-tag>
            <el-tag size="small" effect="plain">{{ priorityMap[detail.priority] || '中' }}</el-tag>
            <el-tag v-if="detail.overdue" type="danger" size="small">已逾期</el-tag>
          </div>
        </div>

        <el-descriptions :column="1" border class="detail-desc">
          <el-descriptions-item label="项目">{{ detail.projectName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="负责人">{{ detail.assigneeName || '未分配' }}</el-descriptions-item>
          <el-descriptions-item label="参与人员">
            {{ detail.participantNames?.length ? detail.participantNames.join('、') : '无' }}
          </el-descriptions-item>
          <el-descriptions-item label="进度">
            <el-progress :percentage="detail.progress ?? 0" :stroke-width="8" style="width: 180px" />
          </el-descriptions-item>
          <el-descriptions-item label="周期">
            {{ detail.startDate || '—' }} ~ {{ detail.dueDate || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="描述">
            <div class="content-text">{{ detail.content || '暂无描述' }}</div>
          </el-descriptions-item>
        </el-descriptions>

        <div v-if="detail.images?.length" class="section">
          <div class="section-title">图片</div>
          <div class="image-gallery">
            <el-image
              v-for="img in detail.images"
              :key="img.id"
              :src="imageUrl(img)"
              :preview-src-list="detail.images.map(imageUrl)"
              fit="cover"
              class="image-item"
            />
          </div>
        </div>

        <div class="detail-actions">
          <el-button type="primary" @click="editing = true">编辑</el-button>
          <el-button @click="openTransfer">转交</el-button>
          <el-button type="danger" plain @click="remove">删除</el-button>
        </div>

        <div class="section comments">
          <el-tabs v-model="detailPane" class="detail-tabs">
            <el-tab-pane :label="`评论${comments.length ? ` (${comments.length})` : ''}`" name="comments">
              <div class="comment-list">
                <div v-for="c in comments" :key="c.id" class="comment-item">
                  <div class="comment-head">
                    <b>{{ c.authorName || '用户' }}</b>
                    <span>{{ fmtTime(c.createTime) }}</span>
                    <el-button v-if="canDeleteComment(c)" link type="danger" size="small" @click="removeComment(c)">删除</el-button>
                  </div>
                  <div class="comment-body">{{ c.content }}</div>
                </div>
                <div v-if="!comments.length" class="comment-empty">还没有评论，来说两句</div>
              </div>
              <div class="comment-form">
                <el-input
                  v-model="commentText"
                  type="textarea"
                  :rows="3"
                  maxlength="2000"
                  show-word-limit
                  placeholder="写下你的评论…"
                />
                <el-button type="primary" :loading="commenting" style="margin-top: 8px" @click="submitComment">发表评论</el-button>
              </div>
            </el-tab-pane>

            <el-tab-pane :label="`流转${flows.length ? ` (${flows.length})` : ''}`" name="flows">
              <el-timeline v-if="flows.length" class="flow-timeline">
                <el-timeline-item
                  v-for="f in flows"
                  :key="f.id"
                  :timestamp="fmtTime(f.createTime)"
                  placement="top"
                >
                  <div class="flow-item">
                    <div class="flow-title">
                      <el-tag size="small" effect="plain">{{ f.actionLabel || f.action }}</el-tag>
                      <span>{{ f.operatorName || '系统' }}</span>
                    </div>
                    <div class="flow-summary">{{ f.summary }}</div>
                    <div v-if="f.remark && f.action === 'TRANSFER'" class="flow-remark">说明：{{ f.remark }}</div>
                    <div v-if="f.images?.length" class="flow-images">
                      <el-image
                        v-for="img in f.images"
                        :key="img.id"
                        :src="imageUrl(img)"
                        :preview-src-list="f.images.map(imageUrl)"
                        fit="cover"
                        class="flow-img"
                      />
                    </div>
                  </div>
                </el-timeline-item>
              </el-timeline>
              <div v-else class="comment-empty">暂无流转记录</div>
            </el-tab-pane>
          </el-tabs>
        </div>
      </template>

      <!-- 编辑 / 新建 -->
      <template v-else>
        <el-form label-width="84px">
          <el-form-item label="标题" required>
            <el-input v-model="form.title" placeholder="任务标题" maxlength="128" show-word-limit />
          </el-form-item>
          <el-form-item label="项目" required>
            <el-select v-model="form.projectId" filterable placeholder="选择项目" style="width: 100%" :disabled="!!defaultProjectId && isNew">
              <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="负责人">
            <el-select v-model="form.assigneeId" filterable clearable placeholder="选择负责人" style="width: 100%">
              <el-option v-for="u in users" :key="u.id" :label="u.nickname || u.username" :value="u.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="参与人员">
            <el-select v-model="form.participantIds" multiple filterable clearable collapse-tags collapse-tags-tooltip placeholder="可多选" style="width: 100%">
              <el-option v-for="u in users" :key="u.id" :label="u.nickname || u.username" :value="u.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="优先级">
            <el-select v-model="form.priority" style="width: 100%">
              <el-option :value="1" label="高" />
              <el-option :value="2" label="中" />
              <el-option :value="3" label="低" />
            </el-select>
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="form.status" style="width: 100%" @change="onStatusChange">
              <el-option :value="0" label="待办" />
              <el-option :value="1" label="进行中" />
              <el-option :value="2" label="已完成" />
              <el-option :value="3" label="已取消" />
            </el-select>
          </el-form-item>
          <el-form-item label="开始日期">
            <el-date-picker v-model="form.startDate" value-format="YYYY-MM-DD" style="width: 100%" :disabled-date="disableStartDate" />
          </el-form-item>
          <el-form-item label="截止日期">
            <el-date-picker v-model="form.dueDate" value-format="YYYY-MM-DD" style="width: 100%" :disabled-date="disableDueDate" />
          </el-form-item>
          <el-form-item label="进度">
            <div style="width: 100%; display: flex; align-items: center; gap: 12px">
              <el-slider v-model="form.progress" :min="0" :max="100" :disabled="form.status === 2" style="flex: 1" />
              <span style="width: 40px; text-align: right">{{ form.progress }}%</span>
            </div>
          </el-form-item>
          <el-form-item label="描述">
            <el-input v-model="form.content" type="textarea" :rows="4" maxlength="1000" show-word-limit />
          </el-form-item>
          <el-form-item label="图片">
            <el-upload
              v-model:file-list="imageFileList"
              list-type="picture-card"
              accept="image/*"
              :limit="9"
              :http-request="onUploadImage"
              :before-upload="beforeImageUpload"
              :on-remove="onRemoveImage"
              :on-preview="onPreviewImage"
            >
              <el-icon><Plus /></el-icon>
            </el-upload>
          </el-form-item>
        </el-form>
        <div class="detail-actions">
          <el-button v-if="!isNew" @click="cancelEdit">取消</el-button>
          <el-button type="primary" :loading="saving" @click="save">保存</el-button>
        </div>
      </template>
    </div>

    <el-dialog v-model="previewVisible" title="图片预览" width="720px" append-to-body>
      <img :src="previewUrl" alt="preview" style="display: block; max-width: 100%; margin: 0 auto" />
    </el-dialog>

    <el-dialog v-model="transferDialog" title="转交任务" width="420px" append-to-body>
      <el-form label-width="84px">
        <el-form-item label="当前负责人">
          <span>{{ detail?.assigneeName || '未分配' }}</span>
        </el-form-item>
        <el-form-item label="转交给" required>
          <el-select v-model="transferForm.assigneeId" filterable placeholder="选择人员" style="width: 100%">
            <el-option
              v-for="u in users.filter((x) => x.id !== detail?.assigneeId)"
              :key="u.id"
              :label="u.nickname || u.username"
              :value="u.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="transferForm.remark" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="可选，写明转交原因" />
        </el-form-item>
        <el-form-item label="图片">
          <el-upload :show-file-list="false" :http-request="onUploadTransferImage" accept="image/*">
            <el-button :loading="transferUploading">上传图片</el-button>
          </el-upload>
          <div v-if="transferImages.length" class="flow-images" style="margin-top: 8px">
            <el-image
              v-for="(img, i) in transferImages"
              :key="img.id"
              :src="imageUrl(img)"
              fit="cover"
              class="flow-img"
            />
            <el-button link type="danger" @click="transferImages = []; transferForm.imageFileIds = []">清空</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="transferDialog = false">取消</el-button>
        <el-button type="primary" :loading="transferring" @click="submitTransfer">确认转交</el-button>
      </template>
    </el-dialog>
  </el-drawer>
</template>

<style scoped>
.task-detail {
  min-height: 200px;
}

.detail-head {
  margin-bottom: 14px;
}

.detail-title {
  margin: 0 0 8px;
  font-size: 18px;
  font-weight: 700;
  line-height: 1.4;
  color: #0f172a;
}

.detail-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.detail-desc {
  margin-bottom: 16px;
}

.content-text {
  white-space: pre-wrap;
  line-height: 1.6;
  color: #334155;
}

.section {
  margin-top: 20px;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
  margin-bottom: 10px;
}

.image-gallery {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.image-item {
  width: 80px;
  height: 80px;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
}

.detail-actions {
  display: flex;
  gap: 8px;
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid #f1f5f9;
}

.comments {
  padding-top: 8px;
  border-top: 1px solid #f1f5f9;
}

.detail-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}

.comment-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 14px;
  max-height: 320px;
  overflow-y: auto;
}

.comment-item {
  padding: 10px 12px;
  background: #f8fafc;
  border-radius: 8px;
}

.comment-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #94a3b8;
  margin-bottom: 6px;
}

.comment-head b {
  color: #334155;
  font-size: 13px;
}

.comment-body {
  font-size: 13px;
  line-height: 1.55;
  color: #0f172a;
  white-space: pre-wrap;
}

.comment-empty {
  font-size: 13px;
  color: #cbd5e1;
  padding: 12px 0;
}

.comment-form {
  margin-top: 4px;
}

.flow-timeline {
  padding-left: 4px;
  max-height: 420px;
  overflow-y: auto;
}

.flow-item {
  padding-bottom: 4px;
}

.flow-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #334155;
  margin-bottom: 4px;
}

.flow-summary {
  font-size: 13px;
  color: #0f172a;
  line-height: 1.5;
}

.flow-remark {
  margin-top: 4px;
  font-size: 12px;
  color: #64748b;
}

.flow-images {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
  align-items: center;
}

.flow-img {
  width: 56px;
  height: 56px;
  border-radius: 6px;
  border: 1px solid #e2e8f0;
}
</style>
