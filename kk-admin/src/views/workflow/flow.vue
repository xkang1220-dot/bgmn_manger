<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { workflowApi } from '@/api/workflow'
import { sysApi } from '@/api/system'

/** 系统内置审批类型（与后端 ApprovalTypes 对齐） */
const BUILTIN_TYPES = [
  { type: 'PROJECT_CREATE', name: '创建项目' },
  { type: 'PROJECT_DELETE', name: '删除项目' },
  { type: 'SHARE_CONFIG', name: '资金配置' },
  { type: 'ROLLBACK', name: '资金回退' },
  { type: 'REIMBURSE_PERSONAL', name: '个人报销' },
  { type: 'REIMBURSE_PROJECT', name: '项目报销' },
  { type: 'SALARY_APPLY', name: '工资申请' },
  { type: 'PROJECT_ADVANCE', name: '项目预支' },
  { type: 'PROJECT_SETTLE', name: '项目分钱' },
  { type: 'RESERVE_RETURN', name: '预留回公司' },
  { type: 'LEDGER_REGISTER', name: '总账登记' },
]

const list = ref<any[]>([])
const roles = ref<any[]>([])
const users = ref<any[]>([])
const dialog = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const customType = ref(false)
const form = reactive({
  id: undefined as number | undefined,
  type: '',
  name: '',
  passMode: 'ALL',
  roleCodeList: [] as string[],
  userIdList: [] as number[],
  timeoutHours: 0,
  status: 1,
  sort: 0,
  remark: '',
})

const existingTypes = computed(() => new Set(list.value.map((x) => x.type)))

const availableBuiltin = computed(() =>
  BUILTIN_TYPES.filter((t) => !existingTypes.value.has(t.type)),
)

async function load() {
  list.value = await workflowApi.flowList()
}

function resetForm() {
  Object.assign(form, {
    id: undefined,
    type: '',
    name: '',
    passMode: 'ANY',
    roleCodeList: ['finance'],
    userIdList: [],
    timeoutHours: 0,
    status: 1,
    sort: (list.value.length + 1) * 10,
    remark: '',
  })
}

function openCreate() {
  isEdit.value = false
  customType.value = availableBuiltin.value.length === 0
  resetForm()
  if (availableBuiltin.value.length) {
    pickBuiltin(availableBuiltin.value[0].type)
  }
  dialog.value = true
}

function open(row: any) {
  isEdit.value = true
  customType.value = false
  Object.assign(form, {
    id: row.id,
    type: row.type,
    name: row.name,
    passMode: row.passMode || 'ALL',
    roleCodeList: [...(row.roleCodeList || [])],
    userIdList: [...(row.userIdList || [])],
    timeoutHours: Number(row.timeoutHours || 0),
    status: row.status ?? 1,
    sort: Number(row.sort || 0),
    remark: row.remark || '',
  })
  dialog.value = true
}

function pickBuiltin(type: string) {
  const hit = BUILTIN_TYPES.find((t) => t.type === type)
  form.type = type
  form.name = hit?.name || type
  if (['PROJECT_CREATE', 'PROJECT_DELETE', 'SHARE_CONFIG', 'ROLLBACK'].includes(type)) {
    form.passMode = 'ALL'
    form.roleCodeList = ['shareholder']
    form.timeoutHours = 72
  } else {
    form.passMode = 'ANY'
    form.roleCodeList = ['finance']
    form.timeoutHours = 0
  }
}

async function save() {
  if (!form.type?.trim()) {
    ElMessage.warning('请填写审批类型编码')
    return
  }
  if (!/^[A-Z][A-Z0-9_]*$/.test(form.type.trim())) {
    ElMessage.warning('编码需大写字母开头，仅含大写字母/数字/下划线')
    return
  }
  if (!form.name?.trim()) {
    ElMessage.warning('请填写显示名称')
    return
  }
  if (!form.roleCodeList.length && !form.userIdList.length) {
    ElMessage.warning('请至少选择一个审批角色或指定审批人')
    return
  }
  if (!isEdit.value && existingTypes.value.has(form.type.trim())) {
    ElMessage.warning('该类型编码已存在，请直接点「配置」编辑')
    return
  }
  saving.value = true
  try {
    await workflowApi.saveFlow({
      ...form,
      type: form.type.trim(),
      name: form.name.trim(),
      id: isEdit.value ? form.id : undefined,
    })
    ElMessage.success(isEdit.value ? '已保存审批配置' : '已新增审批配置')
    dialog.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(row: any) {
  await ElMessageBox.confirm(`确认删除「${row.name}」配置？删除后该类型将按系统默认规则解析审批人。`, '删除确认', {
    type: 'warning',
  })
  await workflowApi.deleteFlow(row.id)
  ElMessage.success('已删除')
  await load()
}

function roleNames(codes?: string[]) {
  if (!codes?.length) return '—'
  return codes.map((c) => roles.value.find((r) => r.code === c)?.name || c).join('、')
}

function userNames(ids?: number[]) {
  if (!ids?.length) return '—'
  return ids.map((id) => {
    const u = users.value.find((x) => x.id === id)
    return u ? (u.nickname || u.username) : `#${id}`
  }).join('、')
}

onMounted(async () => {
  roles.value = await sysApi.roleList()
  users.value = await sysApi.userList()
  await load()
})
</script>

<template>
  <div class="page-card">
    <div class="page-header">
      <div>
        <h3 class="page-title">审批配置</h3>
        <p class="page-desc">可新增/编辑各业务审批：审批角色、指定人、会签或签、超时自动通过</p>
      </div>
      <div class="header-actions">
        <el-button @click="load">刷新</el-button>
        <el-button type="primary" @click="openCreate">新增配置</el-button>
      </div>
    </div>

    <el-table :data="list" stripe>
      <el-table-column prop="name" label="审批类型" width="120" />
      <el-table-column prop="type" label="编码" width="160" show-overflow-tooltip />
      <el-table-column label="通过方式" width="140">
        <template #default="{ row }">{{ row.passModeLabel || row.passMode }}</template>
      </el-table-column>
      <el-table-column label="审批角色" min-width="140">
        <template #default="{ row }">{{ roleNames(row.roleCodeList) }}</template>
      </el-table-column>
      <el-table-column label="指定审批人" min-width="160">
        <template #default="{ row }">{{ userNames(row.userIdList) }}</template>
      </el-table-column>
      <el-table-column label="超时(小时)" width="100" align="center">
        <template #default="{ row }">{{ row.timeoutHours > 0 ? row.timeoutHours : '关' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row)">配置</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" :title="isEdit ? `配置 · ${form.name}` : '新增审批配置'" width="560px">
      <el-form label-width="110px">
        <template v-if="!isEdit">
          <el-form-item label="类型来源">
            <el-radio-group v-model="customType" :disabled="!availableBuiltin.length">
              <el-radio :value="false" :disabled="!availableBuiltin.length">系统类型</el-radio>
              <el-radio :value="true">自定义编码</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item v-if="!customType" label="审批类型" required>
            <el-select
              :model-value="form.type"
              filterable
              placeholder="选择尚未配置的系统类型"
              style="width: 100%"
              @change="pickBuiltin"
            >
              <el-option
                v-for="t in availableBuiltin"
                :key="t.type"
                :label="`${t.name}（${t.type}）`"
                :value="t.type"
              />
            </el-select>
          </el-form-item>
          <template v-else>
            <el-form-item label="类型编码" required>
              <el-input v-model="form.type" placeholder="如 CUSTOM_APPLY，需与提交审批的 type 一致" />
            </el-form-item>
            <el-form-item label="显示名称" required>
              <el-input v-model="form.name" placeholder="列表中显示的名称" />
            </el-form-item>
          </template>
        </template>
        <el-form-item v-else label="类型编码">
          <el-input :model-value="form.type" disabled />
        </el-form-item>

        <el-form-item v-if="isEdit || !customType" label="显示名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="通过方式" required>
          <el-radio-group v-model="form.passMode">
            <el-radio value="ALL">会签（全部通过）</el-radio>
            <el-radio value="ANY">或签（一人通过）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="审批角色">
          <el-select v-model="form.roleCodeList" multiple filterable clearable placeholder="按角色找人" style="width: 100%">
            <el-option v-for="r in roles" :key="r.code" :label="`${r.name}（${r.code}）`" :value="r.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="指定审批人">
          <el-select v-model="form.userIdList" multiple filterable clearable placeholder="可额外指定具体账号" style="width: 100%">
            <el-option
              v-for="u in users"
              :key="u.id"
              :label="`${u.nickname || u.username}`"
              :value="u.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="超时自动通过">
          <el-input-number v-model="form.timeoutHours" :min="0" :max="720" />
          <span class="hint">小时，填 0 表示关闭</span>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-desc { margin: 4px 0 0; color: #64748b; font-size: 13px; }
.header-actions { display: flex; gap: 8px; }
.hint { margin-left: 10px; color: #94a3b8; font-size: 12px; }
</style>
