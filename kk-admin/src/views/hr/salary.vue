<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bizApi } from '@/api/biz'
import { sysApi } from '@/api/system'
import ProjectCascadeSelect from '@/components/project/ProjectCascadeSelect.vue'

const WEEKDAY_OPTIONS = [
  { value: 1, label: '周一' },
  { value: 2, label: '周二' },
  { value: 3, label: '周三' },
  { value: 4, label: '周四' },
  { value: 5, label: '周五' },
  { value: 6, label: '周六' },
  { value: 7, label: '周日' },
]

const companies = ref<any[]>([])
const users = ref<any[]>([])
const projects = ref<any[]>([])
const companyId = ref<number | undefined>()
const cycleFilter = ref<'ALL' | 'MONTHLY' | 'WEEKLY'>('ALL')
const items = ref<any[]>([])
const schedule = reactive<any>({
  companyId: undefined,
  cycleType: 'BOTH',
  payDay: 20,
  weeklyPayDay: 1,
  payHour: 9,
  payMinute: 0,
  previewDays: 3,
  weeklyPreviewDays: 1,
  enabled: 1,
  previewEnabled: 0,
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
  cycleType: 'MONTHLY',
  amount: undefined,
  enabled: 1,
  remark: '',
})

const formAmountLabel = computed(() => (form.cycleType === 'WEEKLY' ? '周薪' : '月薪'))
const formCompanyName = computed(() => {
  const c = companies.value.find((x) => x.id === form.companyId)
  return c?.name || '—'
})
const filteredItems = computed(() => {
  if (cycleFilter.value === 'ALL') return items.value
  return items.value.filter((i) => (i.cycleType || 'MONTHLY') === cycleFilter.value)
})

function cycleLabel(type?: string) {
  return type === 'WEEKLY' ? '周薪' : '月薪'
}

async function loadMeta() {
  companies.value = await sysApi.myCompanies()
  projects.value = await bizApi.projectList()
  if (!companyId.value && companies.value.length) {
    companyId.value = companies.value[0].id
  }
}

async function loadUsers(forCompanyId?: number) {
  const cid = forCompanyId ?? companyId.value
  if (!cid) {
    users.value = []
    return
  }
  users.value = await sysApi.userList({ companyId: cid })
}

async function load() {
  if (!companyId.value) return
  await loadUsers()
  items.value = await bizApi.salaryItems({ companyId: companyId.value })
  const s = await bizApi.salarySchedule(companyId.value)
  Object.assign(schedule, s || {})
  schedule.companyId = companyId.value
  schedule.cycleType = 'BOTH'
  if (schedule.weeklyPayDay == null || schedule.weeklyPayDay < 1 || schedule.weeklyPayDay > 7) {
    schedule.weeklyPayDay = 1
  }
  if (schedule.weeklyPreviewDays == null || schedule.weeklyPreviewDays < 1) {
    schedule.weeklyPreviewDays = 1
  }
  if (schedule.payDay == null || schedule.payDay < 1 || schedule.payDay > 28) {
    schedule.payDay = 20
  }
  if (schedule.previewDays == null || schedule.previewDays < 1) {
    schedule.previewDays = 3
  }
  if (schedule.previewEnabled == null) {
    schedule.previewEnabled = 0
  }
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
    cycleType: 'MONTHLY',
    amount: undefined,
    enabled: 1,
    remark: '',
  })
  form.companyId = row?.companyId || companyId.value
  if (!form.cycleType) form.cycleType = 'MONTHLY'
  loadUsers(form.companyId)
  dialog.value = true
}

async function onFormCompanyChange(cid: number) {
  form.userId = undefined
  form.projectId = undefined
  await loadUsers(cid)
}

async function save() {
  if (!form.companyId || !form.userId || !form.projectId || !form.amount || !form.cycleType) {
    ElMessage.warning(`请完整填写公司、收款人、项目、周期与${formAmountLabel.value}`)
    return
  }
  saving.value = true
  try {
    await bizApi.saveSalaryItem({ ...form, companyId: form.companyId }, isEdit.value)
    ElMessage.success('已保存')
    dialog.value = false
    if (form.companyId !== companyId.value) {
      companyId.value = form.companyId
    }
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(row: any) {
  await ElMessageBox.confirm(
    `删除「${row.userName} / ${row.projectName}」的${cycleLabel(row.cycleType)}配置？`,
    '确认',
  )
  await bizApi.deleteSalaryItem(row.id)
  ElMessage.success('已删除')
  await load()
}

async function saveSchedule() {
  if (!companyId.value) return
  await bizApi.saveSalarySchedule({
    ...schedule,
    companyId: companyId.value,
    cycleType: 'BOTH',
  })
  ElMessage.success('日程已保存')
  await load()
}

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
        <el-radio-group v-model="cycleFilter" size="small">
          <el-radio-button value="ALL">全部</el-radio-button>
          <el-radio-button value="MONTHLY">月薪</el-radio-button>
          <el-radio-button value="WEEKLY">周薪</el-radio-button>
        </el-radio-group>
        <el-button v-permission="'hr:salary:edit'" type="primary" @click="open()">新增配置</el-button>
      </div>

      <el-table :data="filteredItems" stripe>
        <el-table-column prop="userName" label="收款人" min-width="120" />
        <el-table-column prop="projectName" label="项目" min-width="160" />
        <el-table-column label="周期" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.cycleType === 'WEEKLY' ? 'warning' : ''" effect="plain">
              {{ cycleLabel(row.cycleType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="amount" label="金额" width="120" />
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
      <h3 class="card-title">发薪日程（按公司）</h3>
      <p class="sec-tip">当前公司：月薪、周薪两套日程分开配置，互不影响；发薪时刻与代提交人共用。</p>

      <div class="schedule-grid">
        <div class="schedule-panel">
          <div class="schedule-panel__head">
            <span class="schedule-badge monthly">月薪</span>
            <span class="schedule-panel__title">月结日程</span>
          </div>
          <el-form label-width="100px">
            <el-form-item label="发薪日">
              <el-input-number v-model="schedule.payDay" :min="1" :max="28" />
              <span class="hint">每月第几天（1–28）</span>
            </el-form-item>
            <el-form-item label="预告提前天">
              <el-input-number v-model="schedule.previewDays" :min="1" :max="27" />
              <span class="hint">1–27 天</span>
            </el-form-item>
          </el-form>
        </div>

        <div class="schedule-panel">
          <div class="schedule-panel__head">
            <span class="schedule-badge weekly">周薪</span>
            <span class="schedule-panel__title">周结日程</span>
          </div>
          <el-form label-width="100px">
            <el-form-item label="发薪日">
              <el-select v-model="schedule.weeklyPayDay" style="width: 160px">
                <el-option
                  v-for="o in WEEKDAY_OPTIONS"
                  :key="o.value"
                  :label="o.label"
                  :value="o.value"
                />
              </el-select>
              <span class="hint">每周固定星期几</span>
            </el-form-item>
            <el-form-item label="确认提前天">
              <el-input-number v-model="schedule.weeklyPreviewDays" :min="1" :max="6" />
              <span class="hint">默认 1 天（可 1–6）</span>
            </el-form-item>
          </el-form>
        </div>
      </div>

      <div class="schedule-common">
        <div class="schedule-panel__head">
          <span class="schedule-panel__title">公共设置</span>
        </div>
        <el-form label-width="100px" style="max-width: 560px">
          <el-form-item label="发薪时刻">
            <el-input-number v-model="schedule.payHour" :min="0" :max="23" />
            <span>:</span>
            <el-input-number v-model="schedule.payMinute" :min="0" :max="59" />
            <span class="hint">月薪 / 周薪共用</span>
          </el-form-item>
          <el-form-item label="代提交人">
            <el-select v-model="schedule.submitterUserId" clearable filterable placeholder="默认取管理员" style="width: 100%">
              <el-option v-for="u in users" :key="u.id" :label="u.nickname || u.username" :value="u.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="启用定时">
            <el-switch v-model="schedule.enabled" :active-value="1" :inactive-value="0" />
            <span class="hint">控制定时发薪；定时预告默认关闭</span>
          </el-form-item>
          <el-form-item label="定时预告">
            <el-switch v-model="schedule.previewEnabled" :active-value="1" :inactive-value="0" />
            <span class="hint">关闭后仍可用「算薪并发确认 / 手动预告」</span>
          </el-form-item>
          <el-form-item>
            <el-button v-permission="'hr:salary:schedule'" type="primary" @click="saveSchedule">保存日程</el-button>
          </el-form-item>
        </el-form>
      </div>
    </div>

    <el-dialog v-model="dialog" :title="isEdit ? '编辑工资配置' : '新增工资配置'" width="500px">
      <el-form label-width="90px">
        <el-form-item label="所属公司" required>
          <el-select
            v-model="form.companyId"
            filterable
            style="width: 100%"
            :disabled="isEdit"
            @change="onFormCompanyChange"
          >
            <el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
          <div v-if="isEdit" class="hint">{{ formCompanyName }}</div>
        </el-form-item>
        <el-form-item label="收款人" required>
          <el-select v-model="form.userId" filterable style="width: 100%">
            <el-option v-for="u in users" :key="u.id" :label="u.nickname || u.username" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="项目" required>
          <ProjectCascadeSelect
            v-model="form.projectId"
            :projects="projects"
            :company-id="form.companyId"
            mode="pick"
            top-placeholder="重点或重大"
            child-placeholder="请选择小项目"
            top-width="100%"
            child-width="100%"
            :clearable="false"
          />
          <div class="hint">仅支持为重点项目配薪；选重大时请再选下属小项目。以往常规项目的配置会保留，只是不再发薪</div>
        </el-form-item>
        <el-form-item label="周期" required>
          <el-radio-group v-model="form.cycleType" :disabled="isEdit">
            <el-radio-button value="MONTHLY">月薪</el-radio-button>
            <el-radio-button value="WEEKLY">周薪</el-radio-button>
          </el-radio-group>
          <div class="hint">同一人同一项目可各配一条月薪、一条周薪</div>
        </el-form-item>
        <el-form-item :label="formAmountLabel" required>
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
  flex-wrap: wrap;
}
.card-title {
  margin: 0 0 8px;
  font-size: 16px;
}
.sec-tip {
  margin: 0 0 16px;
  color: #64748b;
  font-size: 13px;
}
.hint {
  margin-left: 8px;
  color: #94a3b8;
  font-size: 13px;
}
.schedule-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 16px;
}
.schedule-panel,
.schedule-common {
  border: 1px solid rgba(24, 24, 27, 0.08);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.35);
  padding: 16px 18px 8px;
}
.schedule-panel__head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.schedule-panel__title {
  font-size: 14px;
  font-weight: 600;
  color: #18181b;
}
.schedule-badge {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
}
.schedule-badge.monthly {
  color: #1d4ed8;
  background: rgba(37, 99, 235, 0.12);
}
.schedule-badge.weekly {
  color: #b45309;
  background: rgba(245, 158, 11, 0.16);
}
@media (max-width: 900px) {
  .schedule-grid {
    grid-template-columns: 1fr;
  }
}
</style>
