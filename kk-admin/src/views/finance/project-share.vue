<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { bizApi } from '@/api/biz'
import { sysApi } from '@/api/system'
import { workflowApi } from '@/api/workflow'

const users = ref<any[]>([])
const projects = ref<any[]>([])
const pools = ref<any[]>([])
const saving = ref(false)
const settling = ref(false)
const activeTab = ref('share')

const projectId = ref<number | undefined>()
const detail = ref<any>(null)

const shareForm = reactive({
  poolId: undefined as number | undefined,
  budget: 0,
  members: [] as Array<{ userId?: number; layer: string; percent: number; remark: string }>,
})

const settleForm = reactive({ amount: 0, remark: '' })

const remainBudget = computed(() => {
  if (!detail.value) return null
  const budget = Number(detail.value.budget || 0)
  if (budget <= 0) return null
  const settled = Number(detail.value.settledAmount || 0)
  return Math.max(0, budget - settled)
})

const percentSum = computed(() =>
  shareForm.members.reduce((s, m) => s + Number(m.percent || 0), 0),
)

const settlePreview = computed(() => {
  if (!shareForm.members.length || !settleForm.amount) return []
  const amount = Number(settleForm.amount)
  let allocated = 0
  return shareForm.members.map((m, i) => {
    let share = 0
    if (i === shareForm.members.length - 1) {
      share = Number((amount - allocated).toFixed(2))
    } else {
      share = Number(((amount * Number(m.percent || 0)) / 100).toFixed(2))
      allocated += share
    }
    const user = users.value.find((u) => u.id === m.userId)
    return {
      name: user?.nickname || user?.username || m.userId,
      layer: m.layer,
      percent: m.percent,
      share,
    }
  })
})

async function loadBase() {
  const [u, p, pool] = await Promise.all([sysApi.userList(), bizApi.projectList(), bizApi.poolList()])
  users.value = u
  projects.value = p
  pools.value = pool
}

async function loadProject() {
  detail.value = null
  shareForm.members = []
  settleForm.amount = 0
  settleForm.remark = ''
  if (!projectId.value) return
  detail.value = await bizApi.projectShareDetail(projectId.value)
  shareForm.poolId = detail.value.poolId
  shareForm.budget = Number(detail.value.budget || 0)
  const members = detail.value.members || []
  shareForm.members = members.length
    ? members.map((m: any) => ({
        userId: m.userId,
        layer: m.layer || '',
        percent: Number(m.percent || 0),
        remark: m.remark || '',
      }))
    : [{ userId: undefined, layer: '执行', percent: 100, remark: '' }]
}

function addMember() {
  shareForm.members.push({ userId: undefined, layer: '协助', percent: 0, remark: '' })
}

function removeMember(index: number) {
  if (shareForm.members.length <= 1) return
  shareForm.members.splice(index, 1)
}

async function saveShare() {
  if (!projectId.value) {
    ElMessage.warning('请选择项目')
    return
  }
  if (shareForm.members.some((m) => !m.userId)) {
    ElMessage.warning('请选择全部参与人')
    return
  }
  if (Math.abs(percentSum.value - 100) > 0.01) {
    ElMessage.warning(`分成合计必须为 100%，当前 ${percentSum.value.toFixed(2)}%`)
    return
  }
  saving.value = true
  try {
    await workflowApi.submit({
      type: 'SHARE_CONFIG',
      title: `分成配置 · ${detail.value?.name || projectId.value}`,
      projectId: projectId.value,
      poolId: shareForm.poolId,
      payload: {
        poolId: shareForm.poolId,
        budget: shareForm.budget,
        members: shareForm.members,
      },
      remark: '项目分层配置审批',
    })
    ElMessage.success('已提交分成配置审批（全体股东会签，3天超时自动通过）')
    await loadProject()
  } finally {
    saving.value = false
  }
}

async function settleByPreset() {
  if (!projectId.value) {
    ElMessage.warning('请选择项目')
    return
  }
  if (!settleForm.amount || settleForm.amount <= 0) {
    ElMessage.warning('请输入分钱金额')
    return
  }
  if (remainBudget.value != null && settleForm.amount > remainBudget.value) {
    ElMessage.warning(`超过剩余可分金额 ${remainBudget.value}`)
    return
  }
  settling.value = true
  try {
    // 按预设比例算出每人金额写入 payload
    const members = detail.value?.members || []
    const amount = Number(settleForm.amount)
    let allocated = 0
    const items = members.map((m: any, i: number) => {
      let share = 0
      if (i === members.length - 1) share = Number((amount - allocated).toFixed(2))
      else {
        share = Number(((amount * Number(m.percent || 0)) / 100).toFixed(2))
        allocated += share
      }
      return { userId: m.userId, amount: share, layer: m.layer }
    }).filter((x: any) => x.amount > 0)
    await workflowApi.submit({
      type: 'PROJECT_SETTLE',
      title: `项目分钱 · ${detail.value?.name || ''}`,
      projectId: projectId.value,
      poolId: shareForm.poolId,
      amount: settleForm.amount,
      remark: settleForm.remark,
      payload: { items },
    })
    ElMessage.success('已提交分钱审批')
    settleForm.amount = 0
    settleForm.remark = ''
    await loadProject()
  } finally {
    settling.value = false
  }
}

function fmtMoney(n?: number | string) {
  return Number(n || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

watch(projectId, () => {
  void loadProject()
})

onMounted(loadBase)
</script>

<template>
  <div class="page-card">
    <div class="page-header">
      <div>
        <h3 class="page-title">项目分层</h3>
        <p class="page-desc">在财务侧配置项目资金池、预算与分成比例；项目管理页不再展示这些信息</p>
      </div>
    </div>

    <el-form label-width="100px" style="max-width: 960px">
      <el-form-item label="项目" required>
        <el-select v-model="projectId" filterable placeholder="选择项目" style="width: 360px">
          <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
      </el-form-item>

      <template v-if="detail">
        <el-form-item label="概览">
          <div class="info-box">
            <div>负责人：{{ detail.ownerName || '—' }}</div>
            <div>
              预算 ¥ {{ fmtMoney(detail.budget) }} · 已结算 ¥ {{ fmtMoney(detail.settledAmount) }}
              <template v-if="remainBudget != null"> · 剩余可分 ¥ {{ fmtMoney(remainBudget) }}</template>
            </div>
          </div>
        </el-form-item>

        <el-tabs v-model="activeTab">
          <el-tab-pane label="分层配置" name="share">
            <el-form-item label="资金池">
              <el-select v-model="shareForm.poolId" clearable placeholder="关联资金池" style="width: 360px">
                <el-option v-for="p in pools" :key="p.id" :label="p.name" :value="p.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="预算">
              <el-input-number v-model="shareForm.budget" :min="0" :precision="2" style="width: 220px" />
              <span class="hint">0 表示不限制</span>
            </el-form-item>
            <el-form-item label="分成人员">
              <div class="table-wrap">
                <el-table :data="shareForm.members">
                  <el-table-column label="人员" min-width="160">
                    <template #default="{ row }">
                      <el-select v-model="row.userId" filterable placeholder="选择人员" style="width: 100%">
                        <el-option v-for="u in users" :key="u.id" :label="u.nickname || u.username" :value="u.id" />
                      </el-select>
                    </template>
                  </el-table-column>
                  <el-table-column label="分层角色" width="140">
                    <template #default="{ row }">
                      <el-input v-model="row.layer" placeholder="如 主理人" />
                    </template>
                  </el-table-column>
                  <el-table-column label="比例%" width="140">
                    <template #default="{ row }">
                      <el-input-number v-model="row.percent" :min="0" :max="100" :precision="2" style="width: 120px" />
                    </template>
                  </el-table-column>
                  <el-table-column label="备注" min-width="120">
                    <template #default="{ row }">
                      <el-input v-model="row.remark" />
                    </template>
                  </el-table-column>
                  <el-table-column label="" width="70">
                    <template #default="{ $index }">
                      <el-button link type="danger" @click="removeMember($index)">删</el-button>
                    </template>
                  </el-table-column>
                </el-table>
                <div class="member-actions">
                  <el-button @click="addMember">添加参与人</el-button>
                  <span :class="{ danger: Math.abs(percentSum - 100) > 0.01 }">合计 {{ percentSum.toFixed(2) }}%</span>
                </div>
              </div>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="saving" @click="saveShare">保存分层配置</el-button>
            </el-form-item>
          </el-tab-pane>

          <el-tab-pane label="按预设分钱" name="settle">
            <el-alert
              type="info"
              :closable="false"
              show-icon
              title="按上方已保存的分成比例，从关联资金池扣款并划入各人钱包"
              style="margin-bottom: 16px"
            />
            <el-form-item label="本次金额">
              <el-input-number v-model="settleForm.amount" :min="0.01" :precision="2" style="width: 220px" />
            </el-form-item>
            <el-form-item label="备注">
              <el-input v-model="settleForm.remark" style="max-width: 420px" />
            </el-form-item>
            <el-form-item v-if="settlePreview.length" label="预览">
              <div class="preview">
                <div v-for="item in settlePreview" :key="String(item.name) + item.layer" class="preview-row">
                  <span>{{ item.name }}（{{ item.layer || '未分层' }} {{ item.percent }}%）</span>
                  <b>+ {{ item.share }}</b>
                </div>
              </div>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="settling" @click="settleByPreset">确认按比例分钱</el-button>
              <el-button link type="primary" @click="$router.push('/finance/distribute')">去手动分钱</el-button>
            </el-form-item>
          </el-tab-pane>
        </el-tabs>
      </template>
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
.hint {
  margin-left: 10px;
  color: #94a3b8;
  font-size: 12px;
}
.table-wrap {
  width: 100%;
}
.member-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 10px;
  color: #64748b;
  font-size: 13px;
}
.member-actions .danger {
  color: #dc2626;
}
.preview {
  width: 100%;
  max-width: 420px;
  background: #f8fafc;
  border-radius: 8px;
  padding: 10px 12px;
}
.preview-row {
  display: flex;
  justify-content: space-between;
  padding: 4px 0;
  font-size: 13px;
  color: #334155;
}
</style>
