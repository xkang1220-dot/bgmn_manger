<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { bizApi } from '@/api/biz'
import { sysApi } from '@/api/system'
import { workflowApi } from '@/api/workflow'

const users = ref<any[]>([])
const projects = ref<any[]>([])
const saving = ref(false)

const form = reactive({
  projectId: undefined as number | undefined,
  remark: '',
  fillTotal: 0,
})

const items = ref<Array<{ userId?: number; layer: string; amount: number }>>([])
const projectDetail = ref<any>(null)

const totalAmount = computed(() =>
  items.value.reduce((sum, item) => sum + Number(item.amount || 0), 0),
)

const remainBudget = computed(() => {
  if (!projectDetail.value) return null
  const budget = Number(projectDetail.value.budget || 0)
  if (budget <= 0) return null
  const settled = Number(projectDetail.value.settledAmount || 0)
  return Math.max(0, budget - settled)
})

async function loadBase() {
  users.value = await sysApi.userList()
  projects.value = await bizApi.projectList()
}

async function loadProject() {
  items.value = []
  projectDetail.value = null
  if (!form.projectId) return
  projectDetail.value = await bizApi.projectShareDetail(form.projectId)
  loadPresetMembers()
}

function loadPresetMembers() {
  const members = projectDetail.value?.members || []
  items.value = members.map((m: any) => ({
    userId: m.userId,
    layer: m.layer || '',
    amount: 0,
  }))
  if (!items.value.length) {
    items.value = [{ userId: undefined, layer: '', amount: 0 }]
  }
}

function fillByPresetPercent() {
  if (!form.fillTotal || form.fillTotal <= 0) {
    ElMessage.warning('请输入要分配的总额')
    return
  }
  const members = projectDetail.value?.members || []
  if (!members.length) {
    ElMessage.warning('该项目未配置预设分成')
    return
  }
  const percentSum = members.reduce((s: number, m: any) => s + Number(m.percent || 0), 0)
  if (Math.abs(percentSum - 100) > 0.01) {
    ElMessage.warning('项目预设分成合计必须为 100%')
    return
  }
  let allocated = 0
  items.value = members.map((m: any, i: number) => {
    let amount = 0
    if (i === members.length - 1) {
      amount = Number((form.fillTotal - allocated).toFixed(2))
    } else {
      amount = Number(((form.fillTotal * Number(m.percent || 0)) / 100).toFixed(2))
      allocated += amount
    }
    return { userId: m.userId, layer: m.layer || '', amount }
  })
}

function addRow() {
  items.value.push({ userId: undefined, layer: '', amount: 0 })
}

function removeRow(index: number) {
  if (items.value.length <= 1) return
  items.value.splice(index, 1)
}

async function submit() {
  if (!form.projectId) {
    ElMessage.warning('请选择项目')
    return
  }
  const payloadItems = items.value
    .filter((item) => item.userId && item.amount > 0)
    .map((item) => ({
      userId: item.userId,
      layer: item.layer || undefined,
      amount: item.amount,
    }))
  if (!payloadItems.length) {
    ElMessage.warning('请至少填写一名参与人和金额')
    return
  }
  if (remainBudget.value != null && totalAmount.value > remainBudget.value) {
    ElMessage.warning(`分钱总额超过剩余可分金额 ${remainBudget.value}`)
    return
  }
  saving.value = true
  try {
    await workflowApi.submit({
      type: 'PROJECT_SETTLE',
      title: `手动分钱 · ${projectDetail.value?.name || ''}`,
      projectId: form.projectId,
      amount: totalAmount.value,
      remark: form.remark,
      payload: { items: payloadItems },
    })
    ElMessage.success('已提交手动分钱审批')
    form.remark = ''
    form.fillTotal = 0
    await loadProject()
  } finally {
    saving.value = false
  }
}

watch(
  () => form.projectId,
  () => {
    void loadProject()
  },
)

onMounted(loadBase)
</script>

<template>
  <div class="page-card">
    <div class="page-header">
      <div>
        <h3 class="page-title">项目分钱</h3>
        <p class="page-desc">
          财务可手动指定每人金额；也可载入「项目分层」中的预设参与人后，按比例一键填充再微调
        </p>
      </div>
    </div>

    <el-form label-width="100px" style="max-width: 920px">
      <el-form-item label="项目">
        <el-select v-model="form.projectId" filterable placeholder="选择项目" style="width: 360px">
          <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
      </el-form-item>

      <el-form-item v-if="projectDetail" label="项目信息">
        <div class="info-box">
          <div>关联资金池：{{ projectDetail.poolName || '默认资金池' }}</div>
          <div v-if="Number(projectDetail.budget) > 0">
            预算 {{ projectDetail.budget }}，已分 {{ projectDetail.settledAmount || 0 }}，剩余可分 {{ remainBudget }}
          </div>
          <div v-else>未设预算，分钱不受预算限制</div>
        </div>
      </el-form-item>

      <el-form-item label="按预设填充">
        <div class="fill-row">
          <el-input-number v-model="form.fillTotal" :min="0.01" :precision="2" placeholder="输入总额" />
          <el-button @click="fillByPresetPercent">按预设比例填充到下方表格</el-button>
          <el-button @click="loadPresetMembers">重新载入预设参与人</el-button>
        </div>
      </el-form-item>

      <el-form-item label="分钱明细">
        <div class="table-wrap">
          <el-table :data="items">
            <el-table-column label="人员" min-width="180">
              <template #default="{ row }">
                <el-select v-model="row.userId" filterable placeholder="选择人员">
                  <el-option v-for="u in users" :key="u.id" :label="u.nickname || u.username" :value="u.id" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="分层" width="140">
              <template #default="{ row }">
                <el-input v-model="row.layer" placeholder="如 主理人" />
              </template>
            </el-table-column>
            <el-table-column label="金额" width="160">
              <template #default="{ row }">
                <el-input-number v-model="row.amount" :min="0" :precision="2" style="width: 130px" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="80">
              <template #default="{ $index }">
                <el-button link type="danger" @click="removeRow($index)">删</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-button style="margin-top: 10px" @click="addRow">添加参与人</el-button>
        </div>
      </el-form-item>

      <el-form-item label="合计">
        <b class="total">¥ {{ totalAmount.toFixed(2) }}</b>
      </el-form-item>

      <el-form-item label="备注">
        <el-input v-model="form.remark" type="textarea" :rows="2" style="max-width: 520px" />
      </el-form-item>

      <el-form-item>
        <el-button type="primary" :loading="saving" @click="submit">确认手动分钱</el-button>
      </el-form-item>
    </el-form>
  </div>
</template>

<style scoped>
.page-desc {
  margin: 4px 0 0;
  color: #64748b;
  font-size: 13px;
}
.info-box {
  padding: 10px 12px;
  background: #f8fafc;
  border-radius: 8px;
  color: #475569;
  font-size: 13px;
  line-height: 1.8;
}
.fill-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}
.table-wrap {
  width: 100%;
}
.total {
  font-size: 20px;
  color: #2563eb;
}
</style>
