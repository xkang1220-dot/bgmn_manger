<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { workflowApi } from '@/api/workflow'
import { sysApi } from '@/api/system'

/** 系统内置审批类型（与后端 ApprovalTypes 对齐） */
const BUILTIN_TYPES = [
  { type: 'PROJECT_CREATE', name: '创建项目' },
  { type: 'PROJECT_DELETE', name: '删除项目' },
  { type: 'PROJECT_SCALE_CHANGE', name: '变更项目规模' },
  { type: 'SHARE_CONFIG', name: '资金配置' },
  { type: 'ROLLBACK', name: '资金回退' },
  { type: 'REIMBURSE_PERSONAL', name: '个人报销' },
  { type: 'WALLET_WITHDRAW', name: '钱包提现' },
  { type: 'REIMBURSE_PROJECT', name: '项目报销' },
  { type: 'PROJECT_BALANCE_APPLY', name: '项目余额申请' },
  { type: 'SALARY_APPLY', name: '工资申请' },
  { type: 'SALARY_MONTHLY', name: '月度工资' },
  { type: 'PROJECT_ADVANCE', name: '项目预支' },
  { type: 'PROJECT_ADVANCE_RETURN', name: '退回公司' },
  { type: 'PROJECT_SETTLE', name: '项目分钱' },
  { type: 'PROJECT_SHARE_PERIOD', name: '自然月分成' },
  { type: 'RESERVE_RETURN', name: '预留回公司' },
  { type: 'LEDGER_REGISTER', name: '总账登记' },
  { type: 'MONTHLY_VERIFY', name: '月度核验' },
  { type: 'ASSET_BORROW', name: '资产领用' },
  { type: 'ASSET_RETURN', name: '资产归还' },
  { type: 'ASSET_TRANSFER', name: '资产转交' },
]

const companies = ref<any[]>([])
const filterCompanyId = ref<number | undefined>()
const list = ref<any[]>([])
const roles = ref<any[]>([])
const users = ref<any[]>([])
const dialog = ref(false)
const copyDialog = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const copying = ref(false)
const customType = ref(false)
const copyFromCompanyId = ref<number | undefined>()
const form = reactive({
  id: undefined as number | undefined,
  companyId: undefined as number | undefined,
  companyName: '',
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

const companyName = computed(() =>
  companies.value.find((c) => c.id === filterCompanyId.value)?.name || '',
)

async function loadCompanies() {
  companies.value = await sysApi.myCompanies()
  if (!filterCompanyId.value && companies.value.length) {
    filterCompanyId.value = companies.value[0].id
  }
}

async function load() {
  if (!filterCompanyId.value) {
    list.value = []
    users.value = []
    return
  }
  // 列表要解析指定审批人姓名，需与配置同公司加载用户
  const [flows, userRows] = await Promise.all([
    workflowApi.flowList(filterCompanyId.value),
    sysApi.userList({ companyId: filterCompanyId.value }),
  ])
  list.value = flows
  users.value = userRows || []
}

async function loadUsers() {
  const cid = form.companyId || filterCompanyId.value
  users.value = cid ? await sysApi.userList({ companyId: cid }) : await sysApi.userList()
}

function resetForm() {
  Object.assign(form, {
    id: undefined,
    companyId: filterCompanyId.value,
    companyName: companyName.value,
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

async function openCreate() {
  if (!filterCompanyId.value) {
    ElMessage.warning('请先选择公司')
    return
  }
  isEdit.value = false
  customType.value = availableBuiltin.value.length === 0
  resetForm()
  await loadUsers()
  if (availableBuiltin.value.length) {
    pickBuiltin(availableBuiltin.value[0].type)
  }
  dialog.value = true
}

async function open(row: any) {
  isEdit.value = true
  customType.value = false
  Object.assign(form, {
    id: row.id,
    companyId: row.companyId,
    companyName: row.companyName || companyName.value,
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
  await loadUsers()
  dialog.value = true
}

function pickBuiltin(type: string) {
  const hit = BUILTIN_TYPES.find((t) => t.type === type)
  form.type = type
  form.name = hit?.name || type
  if (['PROJECT_CREATE', 'PROJECT_DELETE', 'PROJECT_SCALE_CHANGE', 'SHARE_CONFIG', 'ROLLBACK'].includes(type)) {
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
  if (!form.companyId) {
    ElMessage.warning('请选择所属公司')
    return
  }
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
    ElMessage.warning('该公司已存在该类型，请直接点「配置」编辑')
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
  await ElMessageBox.confirm(
    `确认删除「${row.name}」配置？删除后该公司提交该类型审批将失败，需重新配置。`,
    '删除确认',
    { type: 'warning' },
  )
  await workflowApi.deleteFlow(row.id)
  ElMessage.success('已删除')
  await load()
}

function openCopy() {
  if (!filterCompanyId.value) {
    ElMessage.warning('请先选择目标公司')
    return
  }
  copyFromCompanyId.value = companies.value.find((c) => c.id !== filterCompanyId.value)?.id
  copyDialog.value = true
}

async function doCopy() {
  if (!copyFromCompanyId.value || !filterCompanyId.value) {
    ElMessage.warning('请选择源公司')
    return
  }
  copying.value = true
  try {
    const n = await workflowApi.copyFlows({
      fromCompanyId: copyFromCompanyId.value,
      toCompanyId: filterCompanyId.value,
    })
    ElMessage.success(n > 0 ? `已复制 ${n} 条缺失配置` : '目标公司已齐全，无需复制')
    copyDialog.value = false
    await load()
  } finally {
    copying.value = false
  }
}

function roleNames(codes?: string[]) {
  if (!codes?.length) return '—'
  return codes.map((c) => roles.value.find((r) => r.code === c)?.name || c).join('、')
}

function userNames(ids?: number[]) {
  if (!ids?.length) return '—'
  return ids.map((id) => {
    const u = users.value.find((x) => Number(x.id) === Number(id))
    return u ? (u.nickname || u.username) : `#${id}`
  }).join('、')
}

watch(filterCompanyId, () => {
  void load()
})

onMounted(async () => {
  roles.value = await sysApi.roleList()
  await loadCompanies()
  await load()
})
</script>

<template>
  <div class="page-card">
    <div class="page-header">
      <div>
        <h3 class="page-title">审批配置</h3>
        <p class="page-desc">按公司分别配置审批角色、指定人、会签/或签与超时；提交时走单据所属公司的配置</p>
      </div>
      <div class="header-actions">
        <el-select v-model="filterCompanyId" placeholder="选择公司" style="width: 200px" filterable>
          <el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-button @click="load">刷新</el-button>
        <el-button @click="openCopy" :disabled="!filterCompanyId || companies.length < 2">从其他公司复制</el-button>
        <el-button v-permission="'workflow:flow:edit'" type="primary" @click="openCreate" :disabled="!filterCompanyId">新增配置</el-button>
      </div>
    </div>

    <el-table :data="list" stripe>
      <el-table-column prop="companyName" label="所属公司" width="140" show-overflow-tooltip />
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
          <el-button v-permission="'workflow:flow:edit'" link type="primary" @click="open(row)">配置</el-button>
          <el-button v-permission="'workflow:flow:edit'" link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" :title="isEdit ? `配置 · ${form.name}` : '新增审批配置'" width="560px">
      <el-form label-width="110px">
        <el-form-item label="所属公司" required>
          <span>{{ form.companyName || companyName || '—' }}</span>
        </el-form-item>
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
            <el-option v-for="r in roles.filter((x) => x.code)" :key="r.code" :label="`${r.name}（${r.code}）`" :value="r.code" />
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

    <el-dialog v-model="copyDialog" title="从其他公司复制配置" width="420px">
      <el-form label-width="100px">
        <el-form-item label="目标公司">
          <span>{{ companyName }}</span>
        </el-form-item>
        <el-form-item label="源公司" required>
          <el-select v-model="copyFromCompanyId" filterable placeholder="选择源公司" style="width: 100%">
            <el-option
              v-for="c in companies.filter((x) => x.id !== filterCompanyId)"
              :key="c.id"
              :label="c.name"
              :value="c.id"
            />
          </el-select>
        </el-form-item>
        <p class="hint">仅复制目标公司还没有的审批类型；指定人不会复制，需按公司重配。</p>
      </el-form>
      <template #footer>
        <el-button @click="copyDialog = false">取消</el-button>
        <el-button type="primary" :loading="copying" @click="doCopy">复制</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-desc { margin: 4px 0 0; color: #64748b; font-size: 13px; }
.header-actions { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.hint { margin-left: 10px; color: #94a3b8; font-size: 12px; }
</style>
