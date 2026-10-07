<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Delete, Plus, Setting, User, Wallet } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { bizApi } from '@/api/biz'
import { sysApi } from '@/api/system'
import { workflowApi } from '@/api/workflow'
import { approvalFlowTip } from '@/utils/approvalTip'
import ProjectCascadeSelect from '@/components/project/ProjectCascadeSelect.vue'

const route = useRoute()
const router = useRouter()

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
  settlePercent: 100,
  reservePercent: 0,
  members: [] as Array<{ userId?: number; layer: string; percent: number; remark: string }>,
})
let syncingFundPercent = false

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
  syncingFundPercent = true
  shareForm.settlePercent = Number(detail.value.settlePercent ?? 100)
  shareForm.reservePercent = Number(detail.value.reservePercent ?? (100 - shareForm.settlePercent))
  syncingFundPercent = false
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

watch(() => shareForm.settlePercent, (value) => {
  if (syncingFundPercent) return
  syncingFundPercent = true
  shareForm.reservePercent = Number((100 - Number(value || 0)).toFixed(2))
  syncingFundPercent = false
})

watch(() => shareForm.reservePercent, (value) => {
  if (syncingFundPercent) return
  syncingFundPercent = true
  shareForm.settlePercent = Number((100 - Number(value || 0)).toFixed(2))
  syncingFundPercent = false
})

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
    const approval = await workflowApi.submit({
      type: 'SHARE_CONFIG',
      title: `分成配置 · ${detail.value?.name || projectId.value}`,
      projectId: projectId.value,
      poolId: shareForm.poolId,
      payload: {
        poolId: shareForm.poolId,
        budget: shareForm.budget,
        settlePercent: shareForm.settlePercent,
        reservePercent: shareForm.reservePercent,
        members: shareForm.members,
      },
      remark: '项目分层配置审批',
    })
    ElMessage.success(approvalFlowTip(approval, '已提交分成配置审批'))
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
    const approval = await workflowApi.submit({
      type: 'PROJECT_SETTLE',
      title: `项目分钱 · ${detail.value?.name || ''}`,
      projectId: projectId.value,
      poolId: shareForm.poolId,
      amount: settleForm.amount,
      remark: settleForm.remark,
      payload: { items },
    })
    ElMessage.success(approvalFlowTip(approval, '已提交分钱审批'))
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

function backToProjectAccount() {
  void router.push('/finance/project-account')
}

watch(projectId, () => {
  void loadProject()
})

onMounted(async () => {
  await loadBase()
  const initialProjectId = Number(Array.isArray(route.query.projectId) ? route.query.projectId[0] : route.query.projectId)
  if (Number.isInteger(initialProjectId) && initialProjectId > 0) projectId.value = initialProjectId
})
</script>

<template>
  <div class="config-page">
    <header class="config-header">
      <el-button class="back-button" text @click="backToProjectAccount">
        <el-icon aria-hidden="true"><ArrowLeft /></el-icon>
        返回项目账款
      </el-button>
      <div class="config-heading">
        <div class="heading-icon"><el-icon aria-hidden="true"><Setting /></el-icon></div>
        <div>
          <h2>资金配置</h2>
          <p>配置项目资金来源、分配规则和参与人比例</p>
        </div>
      </div>
    </header>

    <section class="project-picker" aria-labelledby="project-picker-title">
      <div class="section-heading section-heading--compact">
        <div>
          <h3 id="project-picker-title">选择项目</h3>
          <p>选择后加载该项目当前生效的资金配置</p>
        </div>
        <span class="required-note">必选</span>
      </div>
      <ProjectCascadeSelect
        v-model="projectId"
        :projects="projects"
        mode="pick"
        top-placeholder="重点或重大"
        child-placeholder="请选择小项目"
        top-width="360px"
        child-width="280px"
      />
    </section>

    <el-empty v-if="!detail" class="config-empty" description="请先选择一个项目开始配置" />

    <template v-else>
      <section class="summary-grid" aria-label="项目资金概览">
        <div class="summary-card summary-card--project">
          <span>当前项目</span>
          <strong>{{ detail.name || '—' }}</strong>
          <small>负责人：{{ detail.ownerName || '—' }}</small>
        </div>
        <div class="summary-card">
          <span>项目预算</span>
          <strong>¥ {{ fmtMoney(detail.budget) }}</strong>
          <small>{{ Number(detail.budget || 0) > 0 ? '已设置预算上限' : '当前不限制预算' }}</small>
        </div>
        <div class="summary-card">
          <span>已结算</span>
          <strong>¥ {{ fmtMoney(detail.settledAmount) }}</strong>
          <small>累计完成结算金额</small>
        </div>
        <div class="summary-card summary-card--accent">
          <span>剩余可分</span>
          <strong>{{ remainBudget == null ? '不限额' : `¥ ${fmtMoney(remainBudget)}` }}</strong>
          <small>按当前预算计算</small>
        </div>
      </section>

      <section class="config-workspace">
        <el-tabs v-model="activeTab" class="config-tabs">
          <el-tab-pane label="配置规则" name="share">
            <el-form label-position="top" class="config-form">
              <div class="form-section">
                <div class="section-heading">
                  <div class="section-icon"><el-icon aria-hidden="true"><Wallet /></el-icon></div>
                  <div>
                    <h3>资金基础设置</h3>
                    <p>指定资金来源并设置项目预算上限</p>
                  </div>
                </div>
                <div class="field-grid">
                  <el-form-item label="关联资金池">
                    <el-select v-model="shareForm.poolId" clearable placeholder="请选择资金池">
                      <el-option v-for="p in pools" :key="p.id" :label="p.name" :value="p.id" />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="预算金额">
                    <el-input-number v-model="shareForm.budget" :min="0" :precision="2" controls-position="right" />
                    <span class="field-help">填写 0 表示不限制预算</span>
                  </el-form-item>
                </div>
              </div>

              <div class="form-section">
                <div class="section-heading">
                  <div class="section-icon"><el-icon aria-hidden="true"><Setting /></el-icon></div>
                  <div>
                    <h3>资金分配比例</h3>
                    <p>调整任意一项后，另一项自动补足，合计始终为 100%</p>
                  </div>
                  <el-tag type="success" effect="plain">合计 100%</el-tag>
                </div>
                <div class="ratio-grid">
                  <div class="ratio-card ratio-card--primary">
                    <div><span>项目分成</span><small>用于参与人分配</small></div>
                    <div class="ratio-input">
                      <el-input-number v-model="shareForm.settlePercent" :min="0" :max="100" :precision="2" controls-position="right" />
                      <b>%</b>
                    </div>
                  </div>
                  <div class="ratio-card">
                    <div><span>资金预留</span><small>保留在项目账户</small></div>
                    <div class="ratio-input">
                      <el-input-number v-model="shareForm.reservePercent" :min="0" :max="100" :precision="2" controls-position="right" />
                      <b>%</b>
                    </div>
                  </div>
                </div>
              </div>

              <div class="form-section">
                <div class="section-heading members-heading">
                  <div class="section-icon"><el-icon aria-hidden="true"><User /></el-icon></div>
                  <div>
                    <h3>分成人员</h3>
                    <p>设置参与人、分层角色和各自分成比例</p>
                  </div>
                  <div class="member-total" :class="{ 'member-total--invalid': Math.abs(percentSum - 100) > 0.01 }">
                    人员比例合计 <strong>{{ percentSum.toFixed(2) }}%</strong>
                  </div>
                </div>

                <div class="member-list">
                  <div class="member-list__head" aria-hidden="true">
                    <span>参与人</span><span>分层角色</span><span>分成比例</span><span>备注</span><span></span>
                  </div>
                  <div v-for="(row, index) in shareForm.members" :key="index" class="member-row">
                    <el-select v-model="row.userId" filterable placeholder="选择人员" aria-label="参与人">
                      <el-option v-for="u in users" :key="u.id" :label="u.nickname || u.username" :value="u.id" />
                    </el-select>
                    <el-input v-model="row.layer" placeholder="如：主理人" aria-label="分层角色" />
                    <div class="member-percent">
                      <el-input-number v-model="row.percent" :min="0" :max="100" :precision="2" controls-position="right" aria-label="分成比例" />
                      <span>%</span>
                    </div>
                    <el-input v-model="row.remark" placeholder="选填" aria-label="备注" />
                    <el-button
                      class="delete-button"
                      text
                      type="danger"
                      :disabled="shareForm.members.length <= 1"
                      :aria-label="`删除第 ${index + 1} 位参与人`"
                      @click="removeMember(index)"
                    ><el-icon><Delete /></el-icon></el-button>
                  </div>
                </div>
                <el-button class="add-member" plain @click="addMember">
                  <el-icon aria-hidden="true"><Plus /></el-icon>
                  添加参与人
                </el-button>
              </div>

              <div class="form-actions">
                <div>
                  <strong>确认配置无误后提交审批</strong>
                  <span>审批通过后，新配置将应用于后续项目结算</span>
                </div>
                <div class="form-actions__buttons">
                  <el-button @click="backToProjectAccount">取消并返回</el-button>
                  <el-button type="primary" :loading="saving" @click="saveShare">提交配置审批</el-button>
                </div>
              </div>
            </el-form>
          </el-tab-pane>

          <el-tab-pane label="按预设分钱" name="settle">
            <div class="settle-panel">
              <el-alert type="info" :closable="false" show-icon title="按已保存的参与人比例，从关联资金池扣款并划入个人钱包" />
              <el-form label-position="top" class="settle-form">
                <el-form-item label="本次分钱金额">
                  <el-input-number v-model="settleForm.amount" :min="0.01" :precision="2" controls-position="right" />
                  <span v-if="remainBudget != null" class="field-help">当前最多可分 ¥ {{ fmtMoney(remainBudget) }}</span>
                </el-form-item>
                <el-form-item label="备注">
                  <el-input v-model="settleForm.remark" placeholder="填写本次分钱说明（选填）" />
                </el-form-item>
                <div v-if="settlePreview.length" class="preview">
                  <div class="preview-title">分钱预览</div>
                  <div v-for="item in settlePreview" :key="String(item.name) + item.layer" class="preview-row">
                    <span>{{ item.name }}（{{ item.layer || '未分层' }} · {{ item.percent }}%）</span>
                    <b>+ ¥ {{ fmtMoney(item.share) }}</b>
                  </div>
                </div>
                <div class="settle-actions">
                  <el-button type="primary" :loading="settling" @click="settleByPreset">确认按比例分钱</el-button>
                  <el-button @click="$router.push('/finance/distribute')">切换到手动分钱</el-button>
                </div>
              </el-form>
            </div>
          </el-tab-pane>
        </el-tabs>
      </section>
    </template>
  </div>
</template>

<style scoped>
.config-page { width: min(1180px, 100%); margin: 0 auto; padding-bottom: 32px; }
.config-header { margin-bottom: 20px; }
.back-button { min-height: 40px; margin: 0 0 12px -12px; color: var(--kk-text-secondary); }
.back-button:hover { color: var(--kk-text); background: rgba(255, 255, 255, 0.58); }
.config-heading { display: flex; align-items: center; gap: 14px; }
.heading-icon, .section-icon { display: grid; place-items: center; flex: 0 0 auto; color: var(--kk-primary); background: #fff; border: 1px solid var(--kk-card-border); }
.heading-icon { width: 46px; height: 46px; border-radius: 14px; font-size: 22px; box-shadow: var(--kk-card-shadow); }
.config-heading h2 { margin: 0; color: var(--kk-text); font-size: 24px; line-height: 1.3; }
.config-heading p, .section-heading p { margin: 4px 0 0; color: var(--kk-text-secondary); font-size: 13px; line-height: 1.5; }
.project-picker, .config-workspace, .config-empty { background: var(--kk-card-bg); border: 1px solid var(--kk-card-border); border-radius: var(--kk-radius); box-shadow: var(--kk-card-shadow); }
.project-picker { display: flex; align-items: center; justify-content: space-between; gap: 24px; padding: 20px 24px; }
.required-note { padding: 3px 8px; color: #b45309; background: #fff7ed; border-radius: 99px; font-size: 12px; }
.config-empty { margin-top: 16px; padding: 54px 20px; }
.summary-grid { display: grid; grid-template-columns: 1.35fr repeat(3, 1fr); gap: 12px; margin: 16px 0; }
.summary-card { min-width: 0; padding: 18px 20px; background: rgba(255, 255, 255, 0.72); border: 1px solid var(--kk-card-border); border-radius: var(--kk-radius-sm); }
.summary-card span, .summary-card small { display: block; color: var(--kk-text-secondary); font-size: 12px; }
.summary-card strong { display: block; overflow: hidden; margin: 7px 0 5px; color: var(--kk-text); font-size: 20px; font-variant-numeric: tabular-nums; text-overflow: ellipsis; white-space: nowrap; }
.summary-card--project strong { font-size: 17px; }
.summary-card--accent { background: #f0fdf4; border-color: #bbf7d0; }
.summary-card--accent strong { color: #15803d; }
.config-workspace { overflow: hidden; }
.config-tabs :deep(.el-tabs__header) { margin: 0; padding: 0 24px; background: rgba(248, 250, 252, 0.7); }
.config-tabs :deep(.el-tabs__nav-wrap::after) { height: 1px; background: var(--kk-card-border); }
.config-tabs :deep(.el-tabs__item) { height: 54px; font-weight: 500; }
.config-tabs :deep(.el-tabs__content) { padding: 0; }
.config-form { padding: 0 28px 28px; }
.form-section { padding: 28px 0; border-bottom: 1px solid var(--kk-card-border); }
.section-heading { display: flex; align-items: center; gap: 12px; margin-bottom: 20px; }
.section-heading--compact { margin: 0; }
.section-heading h3 { margin: 0; color: var(--kk-text); font-size: 16px; line-height: 1.4; }
.section-icon { width: 38px; height: 38px; border-radius: 11px; font-size: 18px; background: #f8fafc; }
.section-heading > .el-tag, .member-total { margin-left: auto; }
.field-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; max-width: 780px; }
.field-grid :deep(.el-select), .field-grid :deep(.el-input-number), .settle-form :deep(.el-input-number) { width: 100%; }
.config-form :deep(.el-form-item) { margin-bottom: 0; }
.config-form :deep(.el-form-item__label), .settle-form :deep(.el-form-item__label) { color: var(--kk-text); font-weight: 500; }
.field-help { display: block; margin-top: 7px; color: var(--kk-text-muted); font-size: 12px; line-height: 1.4; }
.ratio-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.ratio-card { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 18px 20px; background: #f8fafc; border: 1px solid var(--kk-card-border); border-radius: var(--kk-radius-sm); }
.ratio-card--primary { background: #f5f7ff; border-color: #dce3ff; }
.ratio-card span, .ratio-card small { display: block; }
.ratio-card span { color: var(--kk-text); font-size: 14px; font-weight: 600; }
.ratio-card small { margin-top: 4px; color: var(--kk-text-muted); font-size: 12px; }
.ratio-input { display: flex; align-items: center; gap: 8px; }
.ratio-input :deep(.el-input-number) { width: 150px; }
.ratio-input b { color: var(--kk-text-secondary); }
.members-heading { margin-bottom: 16px; }
.member-total { color: var(--kk-text-secondary); font-size: 13px; }
.member-total strong { color: #15803d; font-variant-numeric: tabular-nums; }
.member-total--invalid, .member-total--invalid strong { color: var(--kk-danger); }
.member-list { overflow: hidden; border: 1px solid var(--kk-card-border); border-radius: var(--kk-radius-sm); }
.member-list__head, .member-row { display: grid; grid-template-columns: minmax(170px, 1.25fr) minmax(120px, .8fr) minmax(150px, .75fr) minmax(150px, 1fr) 44px; gap: 12px; align-items: center; }
.member-list__head { padding: 11px 14px; color: var(--kk-text-secondary); background: #f8fafc; font-size: 12px; font-weight: 600; }
.member-row { padding: 12px 14px; border-top: 1px solid var(--kk-card-border); }
.member-row :deep(.el-select), .member-row :deep(.el-input-number) { width: 100%; }
.member-percent { display: flex; align-items: center; gap: 6px; }
.member-percent > span { color: var(--kk-text-secondary); font-size: 13px; }
.delete-button { width: 40px; height: 40px; font-size: 17px; }
.add-member { min-height: 40px; margin-top: 14px; }
.form-actions { display: flex; align-items: center; justify-content: space-between; gap: 24px; padding-top: 24px; }
.form-actions strong, .form-actions span { display: block; }
.form-actions strong { color: var(--kk-text); font-size: 14px; }
.form-actions span { margin-top: 4px; color: var(--kk-text-secondary); font-size: 12px; }
.form-actions__buttons { display: flex; gap: 8px; }
.form-actions__buttons .el-button { min-height: 40px; }
.settle-panel { padding: 28px; }
.settle-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; max-width: 820px; margin-top: 24px; }
.settle-form :deep(.el-form-item) { margin-bottom: 0; }
.preview {
  grid-column: 1 / -1;
  width: auto;
  background: #f8fafc;
  border: 1px solid var(--kk-card-border);
  border-radius: var(--kk-radius-sm);
  padding: 14px 16px;
}
.preview-title { margin-bottom: 8px; color: var(--kk-text); font-size: 13px; font-weight: 600; }
.preview-row {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 7px 0;
  font-size: 13px;
  color: var(--kk-text-secondary);
}
.preview-row b { color: #15803d; font-variant-numeric: tabular-nums; }
.settle-actions { grid-column: 1 / -1; display: flex; gap: 8px; }
@media (max-width: 900px) {
  .summary-grid { grid-template-columns: repeat(2, 1fr); }
  .member-list { border: 0; overflow: visible; }
  .member-list__head { display: none; }
  .member-row { grid-template-columns: 1fr 1fr; padding: 16px; margin-bottom: 12px; background: #f8fafc; border: 1px solid var(--kk-card-border); border-radius: var(--kk-radius-sm); }
  .delete-button { justify-self: end; }
}
@media (max-width: 640px) {
  .config-page { padding-bottom: 20px; }
  .project-picker { align-items: stretch; flex-direction: column; padding: 18px; }
  .project-picker :deep(.project-cascade-select) { width: 100%; }
  .summary-grid, .field-grid, .ratio-grid, .settle-form { grid-template-columns: 1fr; }
  .summary-grid { gap: 8px; }
  .config-tabs :deep(.el-tabs__header) { padding: 0 18px; }
  .config-form, .settle-panel { padding-left: 18px; padding-right: 18px; }
  .section-heading { align-items: flex-start; flex-wrap: wrap; }
  .section-heading > .el-tag, .member-total { width: 100%; margin-left: 50px; }
  .ratio-card { align-items: flex-start; flex-direction: column; }
  .ratio-input, .ratio-input :deep(.el-input-number) { width: 100%; }
  .member-row { grid-template-columns: 1fr; }
  .delete-button { justify-self: start; }
  .form-actions { align-items: stretch; flex-direction: column; }
  .form-actions__buttons { display: grid; grid-template-columns: 1fr 1fr; }
  .settle-form .preview, .settle-actions { grid-column: auto; }
  .settle-actions { flex-direction: column; }
}
</style>
