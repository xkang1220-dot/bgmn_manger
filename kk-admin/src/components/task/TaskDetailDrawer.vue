<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Link } from '@element-plus/icons-vue'
import type { UploadFile, UploadProps } from 'element-plus'
import { bizApi } from '@/api/biz'
import { useUserStore } from '@/stores/user'
import ProjectCascadeSelect from '@/components/project/ProjectCascadeSelect.vue'
import RichTextEditor from '@/components/common/RichTextEditor.vue'
import SubtaskTimeline from '@/components/task/SubtaskTimeline.vue'

const props = defineProps<{
  modelValue: boolean
  taskId?: number | null
  /** 从列表操作栏打开时要直接执行的动作 */
  initialAction?: 'view' | 'edit' | 'transfer' | 'close' | 'subtask'
  /** 新建时默认项目 */
  defaultProjectId?: number | null
  taskScope?: 'mine' | 'all'
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
const closing = ref(false)
const reviewing = ref(false)
const editing = ref(false)
const isNew = ref(false)
const isTaskManager = computed(() => userStore.hasPermission('project:task:add'))
const projects = ref<any[]>([])
const creatableProjects = computed(() => {
  if (!isNew.value) return projects.value
  // /project/task-options 已按“负责人或参与人”收敛了可创建任务的项目。
  // 前端只排除不能直接挂任务的重大项目外壳，避免把参与人可用的小项目
  // 再次过滤掉，导致默认选中值只能显示为项目 ID。
  return projects.value.filter((project) => {
    const scale = String(project.scale || 'NORMAL').toUpperCase()
    return !(scale === 'MAJOR' && !project.parentId)
  })
})

const selectedProject = computed(() =>
  projects.value.find((project) => Number(project.id) === Number(form.projectId)),
)

/** 任务报酬仅对重点 / 重大（含重大下的小项目）展示 */
const showTaskReward = computed(() => {
  if (!isTaskManager.value) return false
  const project = selectedProject.value
  if (!project) return false
  const scale = String(project.scale || '').toUpperCase()
  if (scale === 'KEY' || scale === 'MAJOR') return true
  if (project.parentId == null) return false
  const parent = projects.value.find((item) => Number(item.id) === Number(project.parentId))
  const parentScale = String(parent?.scale || '').toUpperCase()
  return parentScale === 'MAJOR' || parentScale === 'KEY'
})
const candidateUsers = ref<any[]>([])
const candidateProjectId = ref<number | undefined>(undefined)
const detail = ref<any>(null)
const comments = ref<any[]>([])
const flows = ref<any[]>([])
const commentText = ref('')
const commenting = ref(false)
const commentUploading = ref(false)
const commentAttachments = ref<any[]>([])
const uploading = ref(false)
const imageFileList = ref<UploadFile[]>([])
const previewVisible = ref(false)
const previewUrl = ref('')
const previewIsVideo = ref(false)
const transferDialog = ref(false)
const transferring = ref(false)
const transferForm = reactive({
  holderId: undefined as number | undefined,
  remark: '',
  imageFileIds: [] as number[],
})
const transferImages = ref<any[]>([])
const transferUploading = ref(false)
const detailPane = ref('comments')
const subtaskParentTitle = ref('')
const pendingAction = ref<'view' | 'edit' | 'transfer' | 'close' | 'subtask'>('view')

const form = reactive<any>({
  id: undefined,
  title: '',
  projectId: undefined,
  parentTaskId: undefined,
  assigneeId: undefined,
  holderId: undefined,
  participantIds: [] as number[],
  status: 0,
  priority: 2,
  taskReward: undefined as number | undefined,
  startDate: '',
  dueDate: '',
  riskLevel: 'NORMAL',
  content: '',
  imageFileIds: [] as number[],
})

const statusMap: Record<number, string> = { 0: '待办', 1: '进行中', 2: '已完成', 3: '已关闭', 4: '待确认完成' }
const priorityMap: Record<number, string> = { 1: '高', 2: '中', 3: '低' }
const statusType: Record<number, '' | 'success' | 'warning' | 'info' | 'danger'> = {
  0: 'info',
  1: 'warning',
  2: 'success',
  3: 'info',
  4: 'warning',
}

function hasUnfinishedChildren(task: any = detail.value) {
  return (task?.children || []).some((child: any) => child.status !== 2)
}

function emptyForm() {
  const selfId = userStore.user?.id as number | undefined
  return {
    id: undefined,
    title: '',
    projectId: props.defaultProjectId != null ? Number(props.defaultProjectId) : undefined,
    parentTaskId: undefined,
    assigneeId: selfId,
    holderId: selfId,
    participantIds: selfId != null ? [selfId] : ([] as number[]),
    status: 0,
    priority: 2,
    taskReward: undefined,
    startDate: '',
    dueDate: '',
    riskLevel: 'NORMAL',
    content: '',
    imageFileIds: [] as number[],
  }
}

function imageUrl(file: { id?: number; url?: string }) {
  if (file.id != null) return `/api/file/preview/${file.id}`
  const url = String(file.url || '')
  if (url.includes('/api/file/download/')) {
    const id = url.split('/').pop()
    if (id) return `/api/file/preview/${id}`
  }
  return url || ''
}

function isVideoFile(file: { contentType?: string; type?: string; name?: string; originalName?: string; url?: string }) {
  const type = file.contentType || file.type || ''
  if (type.startsWith('video/')) return true
  const name = file.originalName || file.name || file.url || ''
  return /\.(mp4|webm|mov|m4v|avi|mkv)(\?|$)/i.test(name)
}

function isImageFile(file: { contentType?: string; type?: string; name?: string; originalName?: string; url?: string }) {
  const type = file.contentType || file.type || ''
  if (type.startsWith('image/')) return true
  const name = file.originalName || file.name || file.url || ''
  return /\.(png|jpe?g|gif|webp|bmp|svg|ico|heic|avif)(\?|$)/i.test(name)
}

function toUploadFile(file: any): UploadFile {
  const raw = {
    name: file.originalName || `file-${file.id}`,
    url: imageUrl(file),
    uid: file.id,
    status: 'success' as const,
  }
  const item = raw as UploadFile & { contentType?: string }
  item.contentType = file.contentType
  return item
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

async function loadDetail(id: number) {
  loading.value = true
  syncingDetail.value = true
  try {
    if (!projects.value.length) {
      projects.value = (await bizApi.taskManagementProjects()) || []
    }
    const [full, commentList, flowList] = await Promise.all([
      bizApi.taskDetail(id, props.taskScope),
      bizApi.taskComments(id),
      bizApi.taskFlows(id),
    ])
    detail.value = full
    Object.assign(form, {
      ...full,
      participantIds: full.participantIds || [],
      imageFileIds: (full.images || []).map((f: any) => f.id),
    })
    await loadProjectCandidates(full.projectId)
    imageFileList.value = (full.images || []).map(toUploadFile)
    comments.value = commentList
    flows.value = flowList
    editing.value = false
    isNew.value = false
    detailPane.value = 'comments'
  } finally {
    syncingDetail.value = false
    loading.value = false
  }

  const action = pendingAction.value
  pendingAction.value = 'view'
  if (action === 'edit') editing.value = true
  else if (action === 'transfer') await openTransfer()
  else if (action === 'close') await closeTask()
  else if (action === 'subtask') await startSubtask()
}

async function openCreate() {
  subtaskParentTitle.value = ''
  detail.value = null
  Object.assign(form, emptyForm())
  imageFileList.value = []
  comments.value = []
  flows.value = []
  commentText.value = ''
  commentAttachments.value = []
  editing.value = true
  isNew.value = true
  await ensureOptions()
  const allowed = new Set(
    candidateUsers.value.filter(isEligibleCandidate).map((u) => Number(u.id)),
  )
  form.participantIds = (form.participantIds || []).filter((id: number) => allowed.has(Number(id)))
}

const syncingDetail = ref(false)

function candidateLabel(u: any) {
  const name = u.nickname || u.username || `用户${u.id}`
  return u.layer ? `${name}（${u.layer}）` : name
}

function isEligibleCandidate(u: any) {
  return u?.layer !== '需从参与人移除'
}

async function loadProjectCandidates(projectId?: number) {
  if (projectId == null) {
    candidateUsers.value = []
    candidateProjectId.value = undefined
    return
  }
  const d = await bizApi.projectDetail(projectId)
  upsertProjectOption(d)
  const map = new Map<number, any>()
  if (d.ownerId != null) {
    map.set(Number(d.ownerId), {
      id: Number(d.ownerId),
      nickname: d.ownerName || `用户${d.ownerId}`,
      layer: '负责人',
    })
  }
  for (const m of d.members || []) {
    if (m.userId == null) continue
    const id = Number(m.userId)
    const prev = map.get(id)
    const duty = m.layer != null ? String(m.layer).trim() : ''
    map.set(id, {
      id,
      nickname: m.nickname || m.userName || prev?.nickname || `用户${id}`,
      layer: duty || prev?.layer,
    })
  }
  candidateUsers.value = [...map.values()]
  if (form.assigneeId == null && d.ownerId != null) form.assigneeId = Number(d.ownerId)
  if (form.holderId == null && d.ownerId != null) form.holderId = Number(d.ownerId)
  candidateProjectId.value = projectId
  const have = new Set(candidateUsers.value.map((u) => Number(u.id)))
  const ids = form.participantIds || []
  const names = detail.value?.participantNames || []
  const detailIds = detail.value?.participantIds || []
  for (const raw of ids) {
    const id = Number(raw)
    if (have.has(id)) continue
    const idx = detailIds.findIndex((x: number) => Number(x) === id)
    candidateUsers.value.push({
      id,
      nickname: names[idx] || `用户${id}`,
      layer: '需从参与人移除',
    })
    have.add(id)
  }
}

/** 保证级联下拉能回显：把当前项目（及重大父项）并入 options，避免列表缓存缺项显示空白 */
function upsertProjectOption(d: any) {
  if (!d?.id) return
  const patch = (row: any) => {
    const id = Number(row.id)
    const next = {
      id,
      name: row.name,
      scale: row.scale,
      status: row.status,
      parentId: row.parentId ?? null,
      companyId: row.companyId,
    }
    const idx = projects.value.findIndex((p) => Number(p.id) === id)
    if (idx >= 0) {
      projects.value[idx] = { ...projects.value[idx], ...next }
    } else {
      projects.value = [...projects.value, next]
    }
  }
  patch(d)
  if (d.parentId != null && !projects.value.some((p) => Number(p.id) === Number(d.parentId))) {
    patch({
      id: d.parentId,
      name: d.parentName || `项目${d.parentId}`,
      scale: 'MAJOR',
      status: 1,
      parentId: null,
      companyId: d.companyId,
    })
  }
}

async function ensureOptions() {
  // 每次打开都刷新，避免新建项目后抽屉仍用旧列表导致默认项目空白
  projects.value = (await bizApi.taskManagementProjects()) || []
  await loadProjectCandidates(form.projectId)
}

async function startSubtask() {
  if (!detail.value?.id) return
  const parent = detail.value.parentTaskId
    ? { id: detail.value.parentTaskId, title: detail.value.parentTaskTitle, projectId: detail.value.projectId }
    : detail.value
  Object.assign(form, emptyForm(), {
    projectId: parent.projectId,
    parentTaskId: parent.id,
  })
  subtaskParentTitle.value = parent.title || `任务 ${parent.id}`
  detail.value = null
  comments.value = []
  flows.value = []
  imageFileList.value = []
  editing.value = true
  isNew.value = true
  await ensureOptions()
}

function openChildTask(task: { id: number }) {
  void loadDetail(Number(task.id))
}

watch(
  () => form.projectId,
  async (pid) => {
    if (!visible.value || syncingDetail.value) return
    await loadProjectCandidates(pid)
    if (pid !== detail.value?.projectId) {
      const allowed = new Set(
        candidateUsers.value.filter(isEligibleCandidate).map((u) => Number(u.id)),
      )
      form.participantIds = (form.participantIds || []).filter((id: number) => allowed.has(Number(id)))
      if (form.holderId != null && !allowed.has(Number(form.holderId))) form.holderId = undefined
      const selfId = userStore.user?.id
      if (selfId != null && allowed.has(Number(selfId)) && !form.participantIds.includes(selfId)) {
        form.participantIds = [...form.participantIds, selfId]
      }
    }
    if (!showTaskReward.value) form.taskReward = undefined
  },
)

watch(
  () => [props.modelValue, props.taskId, props.initialAction] as const,
  async ([open, id, action]) => {
    if (!open) return
    pendingAction.value = action || 'view'
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
  if (!form.participantIds?.length) {
    ElMessage.warning('请至少选择一名参与人')
    return
  }
  if (!form.assigneeId) {
    ElMessage.warning('请选择任务负责人')
    return
  }
  if (!form.holderId) {
    ElMessage.warning('请选择任务持有人')
    return
  }
  if (showTaskReward.value && form.taskReward != null && Number(form.taskReward) < 0) {
    ElMessage.warning('任务报酬不能小于 0')
    return
  }
  const eligibleIds = new Set(
    candidateUsers.value.filter(isEligibleCandidate).map((u) => Number(u.id)),
  )
  const invalid = (form.participantIds || []).filter((id: number) => !eligibleIds.has(Number(id)))
  if (invalid.length) {
    ElMessage.warning('参与人须为项目负责人或项目参与人，请先移除无效人员')
    return
  }
  if (!eligibleIds.has(Number(form.holderId))) {
    ElMessage.warning('持有人须为项目负责人或项目参与人')
    return
  }
  syncImageFileIds()
  saving.value = true
  try {
    const payload = { ...form }
    // 新建任务统一从“待办”开始；后续状态流转从列表“更多”操作进入并二次确认。
    if (isNew.value) payload.status = 0
    // 去掉仅展示用字段，避免污染请求体
    delete payload.assigneeName
    delete payload.holderName
    delete payload.canTransfer
    delete payload.canEdit
    delete payload.participantNames
    delete payload.projectName
    delete payload.parentTaskTitle
    delete payload.children
    delete payload.overdue
    delete payload.images
    if (!isTaskManager.value) delete payload.taskReward
    else if (!showTaskReward.value) payload.taskReward = null
    await bizApi.saveTask(payload, !isNew.value && !!form.id)
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

async function closeTask() {
  if (!form.id || closing.value) return
  if (detail.value?.status === 3) return
  if (hasUnfinishedChildren()) {
    ElMessage.warning('请先完成所有子任务，再关闭父任务')
    return
  }
  let reason = ''
  try {
    const { value } = await ElMessageBox.prompt('关闭后状态为「已关闭」，任务不会删除。请填写关闭原因。', '关闭任务', {
      confirmButtonText: '确认关闭',
      cancelButtonText: '取消',
      inputType: 'textarea',
      inputPlaceholder: '说明关闭原因（必填）',
      inputValidator: (v) => {
        const t = (v || '').trim()
        if (!t) return '请填写关闭原因'
        if (t.length > 500) return '关闭原因不能超过 500 字'
        return true
      },
    })
    reason = (value || '').trim()
  } catch {
    return
  }
  closing.value = true
  try {
    await bizApi.updateTaskStatus(form.id, 3, undefined, reason)
    ElMessage.success('已关闭')
    visible.value = false
    emit('saved')
  } finally {
    closing.value = false
  }
}

async function reviewCompletion(approved: boolean) {
  if (!detail.value?.id || reviewing.value) return
  if (approved && hasUnfinishedChildren()) {
    ElMessage.warning('请先完成所有子任务，再确认完成父任务')
    return
  }
  let remark = ''
  try {
    if (approved) {
      await ElMessageBox.confirm(`确认任务「${detail.value.title}」已经完成？`, '确认完成', {
        confirmButtonText: '确认完成', cancelButtonText: '取消', type: 'success',
      })
    } else {
      const result = await ElMessageBox.prompt(`请输入驳回「${detail.value.title}」完成申请的原因`, '驳回完成申请', {
        confirmButtonText: '确认驳回', cancelButtonText: '取消', inputType: 'textarea',
        inputValidator: (value) => !!String(value || '').trim() || '请填写驳回原因',
      })
      remark = result.value.trim()
    }
  } catch {
    return
  }

  reviewing.value = true
  try {
    await bizApi.reviewTaskCompletion(Number(detail.value.id), approved, remark)
    ElMessage.success(approved ? '任务已确认完成' : '完成申请已驳回')
    await loadDetail(Number(detail.value.id))
    emit('saved')
  } finally {
    reviewing.value = false
  }
}

async function openTransfer() {
  await loadProjectCandidates(form.projectId || detail.value?.projectId)
  transferForm.holderId = undefined
  transferForm.remark = ''
  transferForm.imageFileIds = []
  transferImages.value = []
  transferDialog.value = true
}

function cancelTransfer() {
  transferDialog.value = false
  visible.value = false
}

function onTransferDialogClose() {
  visible.value = false
}

const transferCandidates = computed(() => {
  const currentHolderId = Number(detail.value?.holderId)
  return candidateUsers.value.filter((u) => isEligibleCandidate(u) && Number(u.id) !== currentHolderId)
})

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
  if (!transferForm.holderId) {
    ElMessage.warning('请选择移交对象')
    return
  }
  transferring.value = true
  try {
    await bizApi.transferTask(form.id, {
      holderId: transferForm.holderId,
      remark: transferForm.remark || undefined,
      imageFileIds: transferForm.imageFileIds.length ? transferForm.imageFileIds : undefined,
    })
    ElMessage.success('已移交')
    transferDialog.value = false
    visible.value = false
    emit('saved')
  } finally {
    transferring.value = false
  }
}

async function onUploadImage(options: any) {
  if (imageFileList.value.length >= 9) {
    ElMessage.warning('最多上传 9 个附件')
    options.onError?.(new Error('附件数量超限'))
    return
  }
  uploading.value = true
  try {
    const file = await bizApi.uploadTaskImage(options.file)
    imageFileList.value.push(toUploadFile(file))
    syncImageFileIds()
    options.onSuccess?.(file)
    ElMessage.success('附件上传成功')
  } catch (e: any) {
    ElMessage.error(e.message || '附件上传失败')
    options.onError?.(e)
  } finally {
    uploading.value = false
  }
}

const MAX_ATTACH_MB = 500

const beforeAttachUpload: UploadProps['beforeUpload'] = (file) => {
  if (file.size / 1024 / 1024 > MAX_ATTACH_MB) {
    ElMessage.warning(`单个附件不能超过 ${MAX_ATTACH_MB}MB`)
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
      ElMessage.error(e.message || '附件删除失败')
      return false
    }
  }
  syncImageFileIds()
  return true
}

function onPreviewImage(uploadFile: UploadFile) {
  const meta = {
    contentType: (uploadFile as any).contentType,
    name: uploadFile.name,
    url: uploadFile.url,
  }
  if (!isImageFile(meta) && !isVideoFile(meta)) {
    const href = uploadFile.url || (uploadFile.uid != null ? `/api/file/download/${uploadFile.uid}` : '')
    if (href) window.open(href, '_blank', 'noopener')
    else ElMessage.info('该文件类型暂不支持预览，请下载后查看')
    return
  }
  previewUrl.value = uploadFile.url || ''
  previewIsVideo.value = isVideoFile(meta)
  previewVisible.value = true
}

async function handleAttachRemove(uploadFile: UploadFile) {
  const result = await onRemoveImage(uploadFile)
  if (result === false) return
  imageFileList.value = imageFileList.value.filter((f) => f.uid !== uploadFile.uid)
  syncImageFileIds()
}

async function submitComment() {
  if (!form.id) return
  const plainText = commentText.value.replace(/<[^>]*>/g, '').replace(/&nbsp;/g, ' ').trim()
  if (!plainText && !commentAttachments.value.length) {
    ElMessage.warning('请输入评论内容或上传附件')
    return
  }
  commenting.value = true
  try {
    const c = await bizApi.addTaskComment(
      form.id,
      commentText.value.trim(),
      commentAttachments.value.map((file) => Number(file.id)),
    )
    comments.value.push(c)
    commentText.value = ''
    commentAttachments.value = []
    ElMessage.success('已发表')
  } finally {
    commenting.value = false
  }
}

async function uploadCommentAttachment(options: any) {
  if (commentAttachments.value.length >= 9) {
    ElMessage.warning('每条评论最多上传 9 个附件')
    options.onError?.(new Error('附件数量超限'))
    return
  }
  commentUploading.value = true
  try {
    const file = await bizApi.uploadTaskCommentAttachment(options.file)
    commentAttachments.value.push(file)
    options.onSuccess?.(file)
  } catch (e: any) {
    options.onError?.(e)
  } finally {
    commentUploading.value = false
  }
}

async function removePendingCommentAttachment(file: any) {
  await bizApi.deleteTaskCommentAttachment(file.id)
  commentAttachments.value = commentAttachments.value.filter((item) => item.id !== file.id)
}

function commentAttachmentUrl(file: any, preview = false) {
  return `/api/file/${preview ? 'preview' : 'download'}/${file.id}`
}

</script>

<template>
  <el-drawer
    v-model="visible"
    :title="isNew ? '新建任务' : editing ? '编辑任务' : '任务详情'"
    size="min(820px, 100vw)"
    class="task-drawer"
    destroy-on-close
    append-to-body
  >
    <template #header>
      <div class="drawer-heading">
        <h2>{{ isNew ? '新建任务' : editing ? '编辑任务' : '任务详情' }}</h2>
      </div>
    </template>
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
          <el-descriptions-item v-if="detail.parentTaskId" label="父任务">{{ detail.parentTaskTitle || `任务 ${detail.parentTaskId}` }}</el-descriptions-item>
          <el-descriptions-item label="负责人">{{ detail.assigneeName || '未指定' }}</el-descriptions-item>
          <el-descriptions-item label="持有人">{{ detail.holderName || '未指定' }}</el-descriptions-item>
          <el-descriptions-item label="参与人员">
            {{ detail.participantNames?.length ? detail.participantNames.join('、') : '无' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.taskReward != null" label="任务报酬">
            ¥{{ Number(detail.taskReward).toFixed(2) }}
          </el-descriptions-item>
          <el-descriptions-item label="周期">
            {{ detail.startDate || '—' }} ~ {{ detail.dueDate || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="交付数据">
            实际开始 {{ fmtTime(detail.startedAt) }} · 实际完成 {{ fmtTime(detail.completedAt) }} · 最后推进 {{ fmtTime(detail.lastActivityAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="描述">
            <div v-if="detail.content" class="content-text" v-html="detail.content" />
            <div v-else class="content-text content-text--empty">暂无描述</div>
          </el-descriptions-item>
        </el-descriptions>

        <div v-if="detail.images?.length" class="section">
          <div class="section-title">附件</div>
          <div class="image-gallery">
            <template v-for="file in detail.images" :key="file.id">
              <video
                v-if="isVideoFile(file)"
                class="image-item media-video"
                :src="imageUrl(file)"
                controls
                preload="metadata"
              />
              <el-image
                v-else
                :src="imageUrl(file)"
                :preview-src-list="detail.images.filter((f: any) => !isVideoFile(f)).map(imageUrl)"
                fit="cover"
                class="image-item"
              />
            </template>
          </div>
        </div>

        <div v-if="userStore.hasPermission('project:task:confirm') && detail.status === 4" class="detail-actions">
          <el-button type="success" :loading="reviewing" @click="reviewCompletion(true)">确认完成</el-button>
          <el-button type="danger" plain :disabled="reviewing" @click="reviewCompletion(false)">驳回</el-button>
        </div>

        <div class="section comments">
          <el-tabs v-model="detailPane" class="detail-tabs">
            <el-tab-pane :label="`评论${comments.length ? ` (${comments.length})` : ''}`" name="comments">
              <div class="comment-list">
                <div v-for="c in comments" :key="c.id" class="comment-item">
                  <div class="comment-head">
                    <b>{{ c.authorName || '用户' }}</b>
                    <span>{{ fmtTime(c.createTime) }}</span>
                  </div>
                  <div class="comment-body" v-html="c.content" />
                  <div v-if="c.attachments?.length" class="comment-attachments">
                    <a
                      v-for="file in c.attachments"
                      :key="file.id"
                      :href="commentAttachmentUrl(file)"
                      class="comment-attachment"
                      target="_blank"
                    >
                      <el-icon><Link /></el-icon>
                      <span>{{ file.originalName || `附件 ${file.id}` }}</span>
                    </a>
                  </div>
                </div>
                <div v-if="!comments.length" class="comment-empty">还没有评论，来说两句</div>
              </div>
              <div class="comment-form">
                <RichTextEditor v-model="commentText" :min-height="100" placeholder="写下你的评论…" />
                <div v-if="commentAttachments.length" class="pending-attachments">
                  <div v-for="file in commentAttachments" :key="file.id" class="pending-attachment">
                    <a :href="commentAttachmentUrl(file, true)" target="_blank">{{ file.originalName }}</a>
                    <el-button link type="danger" size="small" @click="removePendingCommentAttachment(file)">移除</el-button>
                  </div>
                </div>
                <div class="comment-actions">
                  <el-upload :show-file-list="false" :http-request="uploadCommentAttachment" :disabled="commentUploading">
                    <el-button :loading="commentUploading" plain>上传附件</el-button>
                  </el-upload>
                  <span class="attachment-tip">最多 9 个附件</span>
                  <el-button type="primary" :loading="commenting" @click="submitComment">发表评论</el-button>
                </div>
              </div>
            </el-tab-pane>

            <el-tab-pane
              v-if="!detail.parentTaskId"
              :label="`子任务${detail.children?.length ? ` (${detail.children.length})` : ''}`"
              name="subtasks"
            >
              <SubtaskTimeline
                :tasks="detail.children || []"
                :parent-start-date="detail.startDate"
                :parent-started-at="detail.startedAt"
                :parent-completed-at="detail.completedAt"
                :parent-status="detail.status"
                @open="openChildTask"
              />
            </el-tab-pane>

            <el-tab-pane :label="`操作记录${flows.length ? ` (${flows.length})` : ''}`" name="flows">
              <ol v-if="flows.length" class="flow-timeline" aria-label="任务操作记录">
                <li v-for="f in flows" :key="f.id" class="flow-entry">
                  <span class="flow-dot" aria-hidden="true" />
                  <article class="flow-card">
                    <header class="flow-meta">
                      <div class="flow-actor">
                        <span class="flow-avatar" aria-hidden="true">{{ (f.operatorName || '系').slice(0, 1) }}</span>
                        <span class="flow-operator">{{ f.operatorName || '系统' }}</span>
                        <el-tag class="flow-action" size="small" effect="light">{{ f.actionLabel || f.action }}</el-tag>
                      </div>
                      <time class="flow-time" :datetime="f.createTime">{{ fmtTime(f.createTime) }}</time>
                    </header>
                    <div class="flow-summary">{{ f.summary }}</div>
                    <div v-if="f.taskTitle && f.taskId !== detail.id" class="flow-task-name">关联任务：{{ f.taskTitle }}</div>
                    <div v-if="f.remark" class="flow-remark">
                      <span class="flow-remark-label">说明</span>
                      <span>{{ f.remark }}</span>
                    </div>
                    <div v-if="f.images?.length" class="flow-images">
                      <template v-for="img in f.images" :key="img.id">
                        <video v-if="isVideoFile(img)" class="flow-img media-video" :src="imageUrl(img)" controls preload="metadata" />
                        <el-image v-else :src="imageUrl(img)" :preview-src-list="f.images.filter((x: any) => !isVideoFile(x)).map(imageUrl)" fit="cover" class="flow-img" />
                      </template>
                    </div>
                  </article>
                </li>
              </ol>
              <div v-else class="comment-empty">暂无操作记录</div>
            </el-tab-pane>
          </el-tabs>
        </div>
      </template>

      <!-- 编辑 / 新建 -->
      <template v-else>
        <el-form label-position="top" class="task-form">
          <section class="form-section form-section--primary">
            <div class="form-section__head">
              <span class="form-section__index">01</span>
              <h3>基本信息</h3>
            </div>
            <el-form-item label="任务标题" required class="title-field">
              <el-input v-model="form.title" size="large" placeholder="请输入任务标题" maxlength="128" show-word-limit />
            </el-form-item>
            <el-form-item v-if="form.parentTaskId" label="父任务">
              <el-input :model-value="subtaskParentTitle" disabled />
            </el-form-item>
            <el-form-item label="所属项目" required>
              <ProjectCascadeSelect
                v-model="form.projectId"
                :projects="creatableProjects"
                mode="task"
                top-placeholder="进行中的项目"
                child-placeholder="请选择小项目"
                top-width="100%"
                child-width="100%"
                :clearable="false"
                :disabled="!!defaultProjectId && isNew"
              />
            </el-form-item>
          </section>

          <section class="form-section">
            <div class="form-section__head">
              <span class="form-section__index">02</span>
              <h3>协作与进度</h3>
            </div>
            <div class="form-grid">
              <el-form-item label="负责人" required>
                <el-select v-model="form.assigneeId" filterable placeholder="选择唯一交付责任人" style="width: 100%">
                  <el-option v-for="u in candidateUsers.filter(isEligibleCandidate)" :key="u.id" :label="candidateLabel(u)" :value="u.id" />
                </el-select>
              </el-form-item>
              <el-form-item label="持有人" required>
                <el-select v-model="form.holderId" filterable placeholder="选择唯一持有人" style="width: 100%">
                  <el-option v-for="u in candidateUsers.filter(isEligibleCandidate)" :key="u.id" :label="candidateLabel(u)" :value="u.id" />
                </el-select>
              </el-form-item>
              <el-form-item label="参与人员">
                <el-select v-model="form.participantIds" multiple filterable clearable collapse-tags collapse-tags-tooltip placeholder="选择协作成员" style="width: 100%" :disabled="!form.projectId || (!isNew && !userStore.hasPermission('project:task:add'))">
                  <el-option v-for="u in candidateUsers" :key="u.id" :label="candidateLabel(u)" :value="u.id" />
                </el-select>
              </el-form-item>
              <el-form-item label="优先级">
                <el-radio-group v-model="form.priority" class="option-cards">
                  <el-radio-button :value="1">高</el-radio-button>
                  <el-radio-button :value="2">中</el-radio-button>
                  <el-radio-button :value="3">低</el-radio-button>
                </el-radio-group>
              </el-form-item>
              <el-form-item label="任务状态">
                <el-tag :type="statusType[isNew ? 0 : form.status]" effect="light">
                  {{ statusMap[isNew ? 0 : form.status] || '待办' }}
                </el-tag>
                <span v-if="isNew" class="field-help">新建任务默认保存为待办</span>
              </el-form-item>
            </div>
          </section>

          <section class="form-section">
            <div class="form-section__head">
              <span class="form-section__index">03</span>
              <h3>计划与交付</h3>
            </div>
            <div class="form-grid">
              <el-form-item label="开始日期">
                <el-date-picker v-model="form.startDate" value-format="YYYY-MM-DD" placeholder="选择开始日期" style="width: 100%" :disabled-date="disableStartDate" />
              </el-form-item>
              <el-form-item label="截止日期">
                <el-date-picker v-model="form.dueDate" value-format="YYYY-MM-DD" placeholder="选择截止日期" style="width: 100%" :disabled-date="disableDueDate" />
              </el-form-item>
            </div>
            <el-form-item v-if="showTaskReward" label="任务报酬">
              <el-input-number v-model="form.taskReward" :min="0" :max="999999999999.99" :precision="2" :step="100" controls-position="right" placeholder="请输入任务报酬" style="width: 100%" />
            </el-form-item>
            <el-form-item label="任务描述">
              <RichTextEditor v-model="form.content" :min-height="150" placeholder="请输入任务描述" />
            </el-form-item>
            <el-form-item label="相关附件" class="attachment-field">
              <div v-if="imageFileList.length" class="pending-attachments">
                <div v-for="file in imageFileList" :key="file.uid" class="pending-attachment">
                  <a href="javascript:;" @click.prevent="onPreviewImage(file)">
                    <el-icon><Link /></el-icon>
                    <span>{{ file.name || `附件 ${file.uid}` }}</span>
                  </a>
                  <el-button link type="danger" size="small" @click="handleAttachRemove(file)">移除</el-button>
                </div>
              </div>
              <div class="form-attach-actions">
                <el-upload
                  :show-file-list="false"
                  :http-request="onUploadImage"
                  :before-upload="beforeAttachUpload"
                  :disabled="uploading || imageFileList.length >= 9"
                >
                  <el-button :loading="uploading" plain>上传附件</el-button>
                </el-upload>
                <span class="attachment-tip">不限格式，最多 9 个，单个不超过 {{ MAX_ATTACH_MB }}MB</span>
              </div>
            </el-form-item>
          </section>
        </el-form>
        <div class="form-actions">
          <el-button @click="cancelEdit">取消</el-button>
          <el-button type="primary" :loading="saving" @click="save">{{ isNew ? '创建任务' : '保存修改' }}</el-button>
        </div>
      </template>
    </div>

    <el-dialog v-model="previewVisible" :title="previewIsVideo ? '视频预览' : '图片预览'" width="720px" append-to-body @closed="previewUrl = ''">
      <video
        v-if="previewIsVideo"
        :src="previewUrl"
        controls
        style="display: block; width: 100%; max-height: 70vh; background: #000"
      />
      <img v-else :src="previewUrl" alt="preview" style="display: block; max-width: 100%; margin: 0 auto" />
    </el-dialog>

    <el-dialog v-model="transferDialog" title="移交任务" width="420px" append-to-body @close="onTransferDialogClose">
      <el-form label-width="84px">
        <el-form-item label="移交给" required>
          <el-select v-model="transferForm.holderId" filterable placeholder="选择新的任务持有人" style="width: 100%">
            <el-option
              v-for="u in transferCandidates"
              :key="u.id"
              :label="candidateLabel(u)"
              :value="u.id"
            />
          </el-select>
          <div v-if="!transferCandidates.length" class="transfer-empty-hint">暂无其他可选持有人</div>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="transferForm.remark" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="可选，写明移交原因" />
        </el-form-item>
        <el-form-item label="附件">
          <el-upload :show-file-list="false" :http-request="onUploadTransferImage" :before-upload="beforeAttachUpload">
            <el-button :loading="transferUploading">上传附件</el-button>
          </el-upload>
          <div v-if="transferImages.length" class="flow-images" style="margin-top: 8px">
            <template v-for="(img, i) in transferImages" :key="img.id || i">
              <video
                v-if="isVideoFile(img)"
                class="flow-img media-video"
                :src="imageUrl(img)"
                controls
                preload="metadata"
              />
              <el-image
                v-else
                :src="imageUrl(img)"
                fit="cover"
                class="flow-img"
              />
            </template>
            <el-button link type="danger" @click="transferImages = []; transferForm.imageFileIds = []">清空</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cancelTransfer">取消</el-button>
        <el-button type="primary" :loading="transferring" @click="submitTransfer">确认移交</el-button>
      </template>
    </el-dialog>
  </el-drawer>
</template>

<style scoped>
:global(.task-drawer.el-drawer) {
  --task-accent: var(--kk-primary);
  --task-border: rgba(24, 24, 27, 0.08);
  --task-muted: var(--kk-text-muted);
  background: var(--kk-glass-overlay-bg);
  -webkit-backdrop-filter: var(--kk-glass-overlay-blur);
  backdrop-filter: var(--kk-glass-overlay-blur);
  border-radius: var(--kk-radius-lg) 0 0 var(--kk-radius-lg);
  box-shadow:
    0 0 0 100vmax rgba(24, 24, 27, 0.12),
    var(--kk-glass-shadow);
}

:global(.el-overlay:has(.task-drawer)) {
  background: transparent !important;
}

:global(.task-drawer .el-drawer__header) {
  margin: 0;
  padding: 20px 24px 16px;
  border-bottom: 1px solid var(--task-border);
  background: rgba(255, 255, 255, 0.35);
}

:global(.task-drawer .el-drawer__body) {
  padding: 0;
}

:global(.task-drawer .el-drawer__close-btn) {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  color: var(--kk-text-muted);
  transition: background-color 0.15s var(--kk-ease), color 0.15s var(--kk-ease);
}

:global(.task-drawer .el-drawer__close-btn:hover) {
  color: var(--kk-text);
  background: rgba(24, 24, 27, 0.06);
}

.drawer-heading h2 {
  margin: 0;
  color: var(--kk-text);
  font-size: 20px;
  font-weight: 700;
  line-height: 1.35;
}

.task-detail {
  min-height: 200px;
  padding: 20px 24px 0;
}

.task-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.form-section {
  padding: 18px 20px;
  border: 1px solid var(--kk-glass-border);
  border-radius: var(--kk-radius);
  background: var(--kk-glass-bg);
  box-shadow: var(--kk-glass-shadow);
  -webkit-backdrop-filter: var(--kk-glass-blur);
  backdrop-filter: var(--kk-glass-blur);
}

.form-section--primary {
  border-top: 3px solid var(--kk-primary);
}

.form-section__head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}

.form-section__index {
  display: inline-flex;
  flex: 0 0 32px;
  align-items: center;
  justify-content: center;
  height: 32px;
  border-radius: 9px;
  color: var(--kk-text);
  background: rgba(24, 24, 27, 0.06);
  font-size: 11px;
  font-weight: 700;
}

.form-section__head h3 {
  margin: 0;
  color: var(--kk-text);
  font-size: 15px;
  font-weight: 650;
}

.task-form :deep(.el-form-item) {
  margin-bottom: 16px;
}

.task-form :deep(.el-form-item:last-child) {
  margin-bottom: 0;
}

.task-form :deep(.el-form-item__content) {
  min-width: 0;
}

.task-form :deep(.el-form-item__label) {
  height: auto;
  margin-bottom: 7px;
  padding: 0;
  color: var(--kk-text-secondary);
  font-size: 13px;
  font-weight: 600;
  line-height: 1.4;
}

.task-form :deep(.el-input__wrapper),
.task-form :deep(.el-select__wrapper) {
  min-height: 40px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.72);
  box-shadow: 0 0 0 1px rgba(24, 24, 27, 0.1) inset;
  transition: box-shadow 0.15s var(--kk-ease);
}

.task-form :deep(.el-input__wrapper:hover),
.task-form :deep(.el-select__wrapper:hover) {
  box-shadow: 0 0 0 1px rgba(24, 24, 27, 0.18) inset;
}

.task-form :deep(.el-input__wrapper.is-focus),
.task-form :deep(.el-select__wrapper.is-focused) {
  box-shadow: 0 0 0 2px rgba(24, 24, 27, 0.22) inset;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 14px;
}

.field-help {
  margin-left: 8px;
  color: var(--kk-text-muted);
  font-size: 12px;
}

.option-cards {
  display: grid;
  width: 100%;
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.option-cards :deep(.el-radio-button__inner) {
  width: 100%;
  min-height: 40px;
  padding: 11px 8px;
  border-radius: 0;
}

.option-cards :deep(.el-radio-button:first-child .el-radio-button__inner) {
  border-radius: 10px 0 0 10px;
}

.option-cards :deep(.el-radio-button:last-child .el-radio-button__inner) {
  border-radius: 0 10px 10px 0;
}

.attachment-field :deep(.el-form-item__content) {
  display: block;
}

.attachment-field .pending-attachments {
  margin: 0 0 10px;
}

.form-attach-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 12px;
}

.task-form :deep(.project-cascade),
.task-form :deep(.rich-editor) {
  width: 100%;
  min-width: 0;
}

.task-form :deep(.project-cascade) {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.task-form :deep(.project-cascade .el-select:only-child) {
  grid-column: 1 / -1;
}

.task-form :deep(.project-cascade .el-select) {
  width: 100% !important;
}

.form-actions {
  position: sticky;
  z-index: 5;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  margin: 18px -24px 0;
  padding: 14px 24px;
  border-top: 1px solid var(--task-border);
  background: rgba(255, 255, 255, 0.55);
  box-shadow: 0 -8px 24px rgba(24, 24, 27, 0.04);
  -webkit-backdrop-filter: saturate(180%) blur(16px);
  backdrop-filter: saturate(180%) blur(16px);
}

.form-actions :deep(.el-button) {
  min-width: 92px;
  min-height: 40px;
  border-radius: 10px;
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

.transfer-empty-hint {
  margin-top: 6px;
  font-size: 12px;
  color: #94a3b8;
}

.detail-desc {
  margin-bottom: 16px;
}

.content-text {
  white-space: pre-wrap;
  line-height: 1.6;
  color: #334155;
  overflow-wrap: anywhere;
}

.content-text--empty {
  color: #94a3b8;
}

.content-text :deep(p) {
  margin: 0 0 6px;
}

.content-text :deep(p:last-child) {
  margin-bottom: 0;
}

.content-text :deep(ul),
.content-text :deep(ol) {
  margin: 4px 0;
  padding-left: 22px;
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

.media-video {
  object-fit: cover;
  background: #0f172a;
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
  overflow-wrap: anywhere;
}

.comment-body :deep(p) { margin: 0 0 6px; }
.comment-body :deep(ul), .comment-body :deep(ol) { margin: 4px 0; padding-left: 22px; }
.comment-body :deep(a) { color: #409eff; }

.comment-attachments, .pending-attachments { display: flex; flex-direction: column; gap: 6px; margin-top: 8px; }
.comment-attachment, .pending-attachment { display: flex; align-items: center; gap: 6px; min-width: 0; }
.comment-attachment { width: fit-content; max-width: 100%; color: var(--kk-text); text-decoration: none; }
.comment-attachment span, .pending-attachment a span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pending-attachment {
  justify-content: space-between;
  padding: 8px 10px;
  border-radius: 10px;
  border: 1px solid rgba(24, 24, 27, 0.06);
  background: rgba(255, 255, 255, 0.55);
  font-size: 13px;
}
.pending-attachment a {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  color: var(--kk-text-secondary);
  text-decoration: none;
}
.pending-attachment a:hover { color: var(--kk-text); }

.comment-empty {
  font-size: 13px;
  color: #cbd5e1;
  padding: 12px 0;
}

.comment-form {
  margin-top: 4px;
}

.comment-actions { display: flex; align-items: center; gap: 8px; margin-top: 8px; }
.comment-actions > .el-button:last-child { margin-left: auto; }
.attachment-tip { font-size: 12px; color: #94a3b8; }

.flow-timeline {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 14px;
  margin: 0;
  padding: 4px 4px 4px 28px;
  list-style: none;
  max-height: 420px;
  overflow-y: auto;
  scrollbar-gutter: stable;
}

.flow-timeline::before {
  content: '';
  position: absolute;
  top: 14px;
  bottom: 14px;
  left: 9px;
  width: 2px;
  border-radius: 999px;
  background: #dbeafe;
}

.flow-entry {
  position: relative;
  min-width: 0;
}

.flow-dot {
  position: absolute;
  z-index: 1;
  top: 19px;
  left: -25px;
  width: 10px;
  height: 10px;
  box-sizing: border-box;
  border: 2px solid #fff;
  border-radius: 50%;
  background: #3b82f6;
  box-shadow: 0 0 0 3px #dbeafe;
}

.flow-entry:not(:first-child) .flow-dot {
  background: #94a3b8;
  box-shadow: 0 0 0 3px #f1f5f9;
}

.flow-card {
  padding: 14px 16px;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.flow-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.flow-actor {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.flow-avatar {
  display: inline-flex;
  flex: 0 0 28px;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  color: #1d4ed8;
  background: #eff6ff;
  font-size: 12px;
  font-weight: 700;
}

.flow-operator {
  overflow: hidden;
  color: #334155;
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.flow-action {
  flex: 0 0 auto;
}

.flow-time {
  flex: 0 0 auto;
  color: #94a3b8;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.flow-summary {
  color: #0f172a;
  font-size: 14px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.flow-task-name { margin-top: 6px; color: var(--kk-text-muted); font-size: 12px; }
.flow-remark {
  display: flex;
  gap: 8px;
  margin-top: 10px;
  padding: 8px 10px;
  border-radius: 8px;
  color: #475569;
  background: #f8fafc;
  font-size: 13px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.flow-remark-label {
  flex: 0 0 auto;
  color: #64748b;
  font-weight: 600;
}

.flow-images {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
  align-items: center;
}

.flow-img {
  width: 64px;
  height: 64px;
  overflow: hidden;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
}

@media (max-width: 560px) {
  :global(.task-drawer .el-drawer__header) {
    padding: 18px 18px 15px;
  }

  .task-detail {
    padding: 16px 14px 0;
  }

  .form-section {
    padding: 16px 14px;
    border-radius: 12px;
  }

  .form-grid {
    grid-template-columns: 1fr;
  }

  .task-form :deep(.project-cascade) {
    grid-template-columns: 1fr;
  }

  .form-actions {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: 10px;
    margin-right: -14px;
    margin-left: -14px;
    padding: 12px 14px max(12px, env(safe-area-inset-bottom));
  }

  .form-actions :deep(.el-button) {
    width: 100%;
    margin: 0;
  }

  .flow-timeline {
    padding-left: 24px;
  }

  .flow-dot {
    left: -21px;
  }

  .flow-timeline::before {
    left: 7px;
  }

  .flow-card {
    padding: 12px;
  }

  .flow-meta {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
  }

  .flow-time {
    padding-left: 36px;
  }
}
</style>
