<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bizApi } from '@/api/biz'
import { sysApi } from '@/api/system'

const query = reactive({ page: 1, pageSize: 12, realName: '', employeeNo: '' })
const list = ref<any[]>([])
const total = ref(0)
const users = ref<any[]>([])
const dialog = ref(false)
const detailDrawer = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const detail = ref<any>(null)
const form = reactive<any>({
  userId: undefined,
  realName: '',
  employeeNo: '',
  position: '',
  education: '',
  entryDate: '',
  idCard: '',
  address: '',
  emergencyContact: '',
  emergencyPhone: '',
  remark: '',
  payMethods: [] as any[],
})

const METHOD_TYPES = [
  { value: 'BANK', label: '银行卡' },
  { value: 'ALIPAY', label: '支付宝' },
]

function methodLabel(type?: string) {
  if (type === 'WECHAT') return '微信'
  return METHOD_TYPES.find((x) => x.value === type)?.label || type || '—'
}

function emptyPayMethod() {
  return {
    id: undefined,
    methodType: 'BANK',
    accountName: '',
    accountNo: '',
    bankName: '',
    isDefault: 0,
    remark: '',
  }
}

function addPayMethod() {
  if (!form.payMethods) form.payMethods = []
  const row = emptyPayMethod()
  if (!form.payMethods.length) row.isDefault = 1
  form.payMethods.push(row)
}

function removePayMethod(index: number) {
  form.payMethods.splice(index, 1)
  if (form.payMethods.length && !form.payMethods.some((m: any) => Number(m.isDefault) === 1)) {
    form.payMethods[0].isDefault = 1
  }
}

function setDefaultPayMethod(index: number) {
  form.payMethods.forEach((m: any, i: number) => {
    m.isDefault = i === index ? 1 : 0
  })
}

const AVATAR_TONES = ['indigo', 'cyan', 'violet', 'amber'] as const

const filteredEmpty = computed(() => !list.value.length && (!!query.realName.trim() || !!query.employeeNo.trim()))

function emptyForm() {
  return {
    id: undefined,
    userId: undefined,
    realName: '',
    employeeNo: '',
    position: '',
    education: '',
    entryDate: '',
    idCard: '',
    address: '',
    emergencyContact: '',
    emergencyPhone: '',
    remark: '',
    payMethods: [] as any[],
  }
}

function initial(row: any) {
  const name = String(row.realName || row.username || '').replace(/\s/g, '')
  return name.slice(0, 1) || '?'
}

function avatarTone(row: any) {
  const name = String(row.realName || row.username || '')
  let hash = 0
  for (let i = 0; i < name.length; i++) hash = (hash * 31 + name.charCodeAt(i)) >>> 0
  return AVATAR_TONES[hash % AVATAR_TONES.length]
}

async function load() {
  const res = await bizApi.archivePage(query)
  list.value = res.list
  total.value = res.total
}

function onFilter() {
  query.page = 1
  load()
}

function resetFilter() {
  query.realName = ''
  query.employeeNo = ''
  query.page = 1
  load()
}

async function open(row?: any) {
  isEdit.value = !!row
  if (row?.id) {
    const full = await bizApi.archiveDetail(row.id)
    Object.assign(form, {
      ...full,
      payMethods: (full.payMethods || []).map((m: any) => ({ ...m })),
    })
  } else {
    Object.assign(form, emptyForm())
  }
  dialog.value = true
}

async function showDetail(row: any) {
  detail.value = await bizApi.archiveDetail(row.id)
  detailDrawer.value = true
}

async function save() {
  if (!form.userId) {
    ElMessage.warning('请绑定系统账号')
    return
  }
  if (!form.realName?.trim()) {
    ElMessage.warning('请填写姓名')
    return
  }
  for (const m of form.payMethods || []) {
    if (!m.accountNo?.trim()) {
      ElMessage.warning('请填写收款账号')
      return
    }
    if (m.methodType === 'BANK' && !m.bankName?.trim()) {
      ElMessage.warning('银行卡请填写开户行')
      return
    }
  }
  saving.value = true
  try {
    await bizApi.saveArchive({
      ...form,
      payMethods: (form.payMethods || []).map((m: any, i: number) => ({
        ...m,
        sort: i,
        isDefault: Number(m.isDefault) === 1 ? 1 : 0,
      })),
    }, isEdit.value)
    ElMessage.success('保存成功')
    dialog.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(id: number) {
  await ElMessageBox.confirm('确认删除档案？')
  await bizApi.deleteArchive(id)
  ElMessage.success('已删除')
  await load()
}

onMounted(async () => {
  users.value = await sysApi.userList()
  await load()
})
</script>

<template>
  <div class="page-stack">
    <div class="page-top">
      <div class="page-top__main" />
      <div class="page-actions">
        <el-button v-permission="'hr:archive:add'" type="primary" @click="open()">新建档案</el-button>
      </div>
    </div>

    <el-form class="filter-bar" @submit.prevent="onFilter">
      <el-form-item label="姓名">
        <el-input
          v-model="query.realName"
          clearable
          placeholder="搜索姓名"
          class="filter-keyword"
          @keyup.enter="onFilter"
        />
      </el-form-item>
      <el-form-item label="工号">
        <el-input
          v-model="query.employeeNo"
          clearable
          placeholder="搜索工号"
          class="filter-keyword"
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
        class="person"
        :class="'person--' + avatarTone(row)"
        role="button"
        tabindex="0"
        @click="showDetail(row)"
        @keyup.enter="showDetail(row)"
      >
        <div class="person-top">
          <span class="avatar" :class="'avatar--' + avatarTone(row)">{{ initial(row) }}</span>
          <div class="person-id">
            <div class="name">{{ row.realName || '—' }}</div>
          </div>
        </div>
        <div class="person-meta">
          <div><span>工号</span><b>{{ row.employeeNo || '—' }}</b></div>
          <div><span>账号</span><b>{{ row.username || '—' }}</b></div>
          <div><span>入职</span><b>{{ row.entryDate || '—' }}</b></div>
          <div><span>电话</span><b>{{ row.phone || '—' }}</b></div>
        </div>
        <div class="person-ops" @click.stop>
          <el-button
            v-permission="'hr:archive:edit'"
            class="icon-btn"
            text
            aria-label="编辑"
            title="编辑"
            @click="open(row)"
          >
            <el-icon :size="16"><EditPen /></el-icon>
          </el-button>
          <el-button
            v-permission="'hr:archive:remove'"
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
    <el-empty v-else :description="filteredEmpty ? '没有匹配的人' : '暂无档案'" />

    <div v-if="total > query.pageSize" class="page-footer">
      <el-pagination
        v-model:current-page="query.page"
        :page-size="query.pageSize"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="load"
      />
    </div>

    <el-dialog
      v-model="dialog"
      :title="isEdit ? '编辑档案' : '新建档案'"
      width="720px"
      :close-on-click-modal="false"
    >
      <el-form label-width="100px">
        <el-form-item label="绑定账号" required>
          <el-select v-model="form.userId" filterable :disabled="isEdit" style="width: 100%">
            <el-option v-for="u in users" :key="u.id" :label="`${u.nickname || u.username}`" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="姓名" required><el-input v-model="form.realName" /></el-form-item>
        <el-form-item label="工号"><el-input v-model="form.employeeNo" /></el-form-item>
        <el-form-item label="岗位"><el-input v-model="form.position" /></el-form-item>
        <el-form-item label="学历"><el-input v-model="form.education" /></el-form-item>
        <el-form-item label="入职日期"><el-date-picker v-model="form.entryDate" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item>
        <el-form-item label="身份证"><el-input v-model="form.idCard" /></el-form-item>
        <el-form-item label="地址"><el-input v-model="form.address" /></el-form-item>
        <el-form-item label="紧急联系人"><el-input v-model="form.emergencyContact" /></el-form-item>
        <el-form-item label="紧急电话"><el-input v-model="form.emergencyPhone" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="收款方式">
          <div class="pay-box">
            <div v-for="(m, index) in form.payMethods" :key="index" class="pay-row">
              <el-select v-model="m.methodType" style="width: 110px">
                <el-option v-for="t in METHOD_TYPES" :key="t.value" :label="t.label" :value="t.value" />
              </el-select>
              <el-input v-model="m.accountName" placeholder="户名" style="width: 110px" />
              <el-input v-model="m.accountNo" placeholder="账号/卡号" style="flex: 1" />
              <el-input v-if="m.methodType === 'BANK'" v-model="m.bankName" placeholder="开户行" style="width: 140px" />
              <el-checkbox
                :model-value="Number(m.isDefault) === 1"
                @change="(checked: boolean | string | number) => { if (checked) setDefaultPayMethod(Number(index)) }"
              >默认</el-checkbox>
              <el-button link type="danger" @click="removePayMethod(Number(index))">删除</el-button>
            </div>
            <el-button type="primary" link @click="addPayMethod">添加收款方式</el-button>
            <div class="pay-tip">支持银行卡 / 支付宝，可多条；默认用于发工资与报销申请</div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailDrawer" title="档案详情" size="420px" append-to-body>
      <template v-if="detail">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="姓名">{{ detail.realName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="工号">{{ detail.employeeNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="账号">{{ detail.username || '—' }}</el-descriptions-item>
          <el-descriptions-item label="部门">{{ detail.deptName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="岗位">{{ detail.position || '—' }}</el-descriptions-item>
          <el-descriptions-item label="学历">{{ detail.education || '—' }}</el-descriptions-item>
          <el-descriptions-item label="入职日期">{{ detail.entryDate || '—' }}</el-descriptions-item>
          <el-descriptions-item label="电话">{{ detail.phone || '—' }}</el-descriptions-item>
          <el-descriptions-item label="身份证">{{ detail.idCard || '—' }}</el-descriptions-item>
          <el-descriptions-item label="地址">{{ detail.address || '—' }}</el-descriptions-item>
          <el-descriptions-item label="紧急联系人">{{ detail.emergencyContact || '—' }}</el-descriptions-item>
          <el-descriptions-item label="紧急电话">{{ detail.emergencyPhone || '—' }}</el-descriptions-item>
          <el-descriptions-item label="备注">{{ detail.remark || '—' }}</el-descriptions-item>
          <el-descriptions-item label="收款方式">
            <div v-if="detail.payMethods?.length" class="pay-detail">
              <div v-for="m in detail.payMethods" :key="m.id" class="pay-detail-row">
                <el-tag size="small" effect="plain">{{ m.methodTypeLabel || methodLabel(m.methodType) }}</el-tag>
                <span>{{ m.accountName || '—' }} · {{ m.accountNo }}</span>
                <span v-if="m.methodType === 'BANK' && m.bankName" class="muted">（{{ m.bankName }}）</span>
                <el-tag v-if="Number(m.isDefault) === 1" size="small" type="success">默认</el-tag>
              </div>
            </div>
            <span v-else>—</span>
          </el-descriptions-item>
        </el-descriptions>
        <div class="drawer-actions">
          <el-button v-permission="'hr:archive:edit'" type="primary" @click="open(detail); detailDrawer = false">编辑档案</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.board {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 14px;
}
.person {
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px;
  cursor: pointer;
  background: var(--kk-glass-bg);
  border: 1px solid var(--kk-glass-border);
  border-radius: var(--kk-radius);
  box-shadow: var(--kk-glass-shadow);
  backdrop-filter: var(--kk-glass-blur);
  -webkit-backdrop-filter: var(--kk-glass-blur);
  transition: box-shadow 0.15s var(--kk-ease);
}
.person::before {
  content: "";
  position: absolute;
  right: -28px;
  bottom: -36px;
  width: 120px;
  height: 120px;
  border-radius: 50%;
  filter: blur(28px);
  opacity: 0.22;
  pointer-events: none;
}
.person--indigo::before { background: #d4d4d8; }
.person--cyan::before { background: #a5f3fc; }
.person--violet::before { background: #ddd6fe; }
.person--amber::before { background: #fde68a; }
.person:hover { box-shadow: 0 8px 28px rgba(0, 0, 0, 0.08); }
.person:focus-visible {
  outline: 2px solid var(--kk-primary);
  outline-offset: 2px;
}
.person-top {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.avatar {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 16px;
  font-weight: 700;
  line-height: 1;
}
.avatar--indigo { background: #f4f4f5; color: #18181b; }
.avatar--cyan { background: #ecfeff; color: #0891b2; }
.avatar--violet { background: #f5f3ff; color: #7c3aed; }
.avatar--amber { background: #fffbeb; color: #d97706; }
.person-id { min-width: 0; flex: 1; }
.name {
  font-weight: 600;
  font-size: 15px;
  color: var(--kk-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.person-ops {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 2px;
  margin-top: auto;
  padding-top: 10px;
  border-top: 1px solid rgba(0, 0, 0, 0.06);
}
.icon-btn {
  width: 32px;
  height: 32px;
  padding: 0;
  color: var(--kk-text-secondary);
}
.icon-btn:hover { color: var(--kk-primary); }
.icon-btn.is-danger:hover { color: var(--kk-danger); }
.person-meta {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px 12px;
  padding-top: 12px;
  border-top: 1px solid rgba(0, 0, 0, 0.06);
  font-size: 12px;
  color: var(--kk-text-muted);
}
.person-meta b {
  display: block;
  margin-top: 2px;
  font-size: 13px;
  font-weight: 600;
  color: var(--kk-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.drawer-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
.pay-box {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.pay-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.pay-tip {
  font-size: 12px;
  color: var(--kk-text-muted);
}
.pay-detail {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.pay-detail-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}
.pay-detail-row .muted {
  color: var(--kk-text-muted);
  font-size: 12px;
}

@media (prefers-reduced-transparency: reduce) {
  .person {
    background: #fff;
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}
</style>
