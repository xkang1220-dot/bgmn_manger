<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bizApi } from '@/api/biz'
import { sysApi } from '@/api/system'

const companies = ref<any[]>([])
const users = ref<any[]>([])
const projects = ref<any[]>([])
const companyId = ref<number | undefined>()
const items = ref<any[]>([])
const schedule = reactive<any>({
  companyId: undefined,
  payDay: 20,
  payHour: 9,
  payMinute: 0,
  previewDays: 3,
  enabled: 1,
  submitterUserId: undefined,
})
const dialog = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const form = reactive<any>({
  id: undefined,
  companyId: undefined,
  userId: undefined,
  projectId: undefined,
  amount: undefined,
  enabled: 1,
  remark: '',
})

async function loadMeta() {
  companies.value = await sysApi.myCompanies()
  projects.value = await bizApi.projectList()
  if (!companyId.value && companies.value.length) {
    companyId.value = companies.value[0].id
  }
}

async function loadUsers() {
  if (!companyId.value) {
    users.value = []
    return
  }
  users.value = await sysApi.userList({ companyId: companyId.value })
}

async function load() {
  if (!companyId.value) return
  await loadUsers()
  items.value = await bizApi.salaryItems({ companyId: companyId.value })
  const s = await bizApi.salarySchedule(companyId.value)
  Object.assign(schedule, s || {})
  schedule.companyId = companyId.value
  if (
    schedule.submitterUserId &&
    !users.value.some((u: any) => u.id === schedule.submitterUserId)
  ) {
    schedule.submitterUserId = undefined
  }
}

function open(row?: any) {
  isEdit.value = !!row
  Object.assign(form, row || {
    id: undefined,
    companyId: companyId.value,
    userId: undefined,
    projectId: undefined,
    amount: undefined,
    enabled: 1,
    remark: '',
  })
  form.companyId = companyId.value
  dialog.value = true
}

async function save() {
  if (!form.userId || !form.projectId || !form.amount) {
    ElMessage.warning('请完整填写收款人、项目与金额')
    return
  }
  saving.value = true
  try {
    await bizApi.saveSalaryItem({ ...form, companyId: companyId.value }, isEdit.value)
    ElMessage.success('已保存')
    dialog.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(row: any) {
  await ElMessageBox.confirm(`删除「${row.userName} / ${row.projectName}」的工资配置？`, '确认')
  await bizApi.deleteSalaryItem(row.id)
  ElMessage.success('已删除')
  await load()
}

async function saveSchedule() {
  if (!companyId.value) return
  await bizApi.saveSalarySchedule({ ...schedule, companyId: companyId.value })
  ElMessage.success('日程已保存')
  await load()
}

const companyProjects = () =>
  (projects.value || []).filter((p: any) => !companyId.value || p.companyId === companyId.value)

watch(companyId, () => load())

onMounted(async () => {
  await loadMeta()
  await load()
})
</script>

<template>
  <div class="page-stack">
    <div class="page-card">
      <div class="toolbar">
        <el-select v-model="companyId" placeholder="公司" style="width: 220px">
          <el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-button v-permission="'hr:salary:edit'" type="primary" @click="open()">新增配置</el-button>
      </div>

      <el-table :data="items" stripe>
        <el-table-column prop="userName" label="收款人" min-width="120" />
        <el-table-column prop="projectName" label="项目" min-width="160" />
        <el-table-column prop="amount" label="月薪" width="120" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">{{ row.enabled === 1 ? '启用' : '停用' }}</template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="140" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'hr:salary:edit'" link type="primary" @click="open(row)">编辑</el-button>
            <el-button v-permission="'hr:salary:edit'" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="page-card">
      <h3 class="card-title">发薪日程</h3>
      <el-form label-width="120px" style="max-width: 520px">
        <el-form-item label="发薪日">
          <el-input-number v-model="schedule.payDay" :min="1" :max="28" />
          <span class="hint">每月第几天（1–28）</span>
        </el-form-item>
        <el-form-item label="发薪时刻">
          <el-input-number v-model="schedule.payHour" :min="0" :max="23" />
          <span>:</span>
          <el-input-number v-model="schedule.payMinute" :min="0" :max="59" />
        </el-form-item>
        <el-form-item label="预告提前天">
          <el-input-number v-model="schedule.previewDays" :min="1" :max="27" />
        </el-form-item>
        <el-form-item label="代提交人">
          <el-select v-model="schedule.submitterUserId" clearable filterable placeholder="默认取管理员" style="width: 100%">
            <el-option v-for="u in users" :key="u.id" :label="u.nickname || u.username" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用定时">
          <el-switch v-model="schedule.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item>
          <el-button v-permission="'hr:salary:schedule'" type="primary" @click="saveSchedule">保存日程</el-button>
        </el-form-item>
      </el-form>
    </div>

    <el-dialog v-model="dialog" :title="isEdit ? '编辑工资配置' : '新增工资配置'" width="480px">
      <el-form label-width="90px">
        <el-form-item label="收款人" required>
          <el-select v-model="form.userId" filterable style="width: 100%">
            <el-option v-for="u in users" :key="u.id" :label="u.nickname || u.username" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="项目" required>
          <el-select v-model="form.projectId" filterable style="width: 100%">
            <el-option v-for="p in companyProjects()" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="月薪" required>
          <el-input-number v-model="form.amount" :min="0.01" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" />
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
.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  align-items: center;
}
.card-title {
  margin: 0 0 16px;
  font-size: 16px;
}
.hint {
  margin-left: 8px;
  color: #94a3b8;
  font-size: 13px;
}
</style>
