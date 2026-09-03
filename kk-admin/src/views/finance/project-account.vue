<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { bizApi } from '@/api/biz'
import { sysApi } from '@/api/system'
import { workflowApi } from '@/api/workflow'

const list = ref<any[]>([])
const activeId = ref<number | null>(null)
const account = ref<any>(null)
const shareDetail = ref<any>(null)
const users = ref<any[]>([])
const ledgers = ref<any[]>([])
const ledgerTotal = ref(0)
const ledgerQuery = reactive({ page: 1, pageSize: 20 })
const tab = ref('overview')

const savingShare = ref(false)
const settling = ref(false)
const advanceDialog = ref(false)
const reimburseDialog = ref(false)
const salaryDialog = ref(false)
const ledgerDetailVisible = ref(false)
const ledgerDetail = ref<any>(null)
const ledgerRelated = ref<any[]>([])
const ledgerDetailLoading = ref(false)

const form = reactive({ amount: 0, remark: '' })
const voucherFiles = ref<any[]>([])
const uploadingVoucher = ref(false)
const settleForm = reactive({ amount: 0, remark: '' })
const shareForm = reactive({
  budget: 0,
  reservePercent: 0,
  settlePercent: 100,
  members: [] as Array<{ userId?: number; layer: string; percent: number; remark: string }>,
})

const percentSum = computed(() =>
  shareForm.members.reduce((s, m) => s + Number(m.percent || 0), 0),
)

/** 分成% + 预留% 须为 100%；支出不再配置，直接从项目结余扣 */
const fundSplitOk = computed(() => {
  const settle = Number(shareForm.settlePercent || 0)
  const reserve = Number(shareForm.reservePercent || 0)
  return settle >= 0 && reserve >= 0 && Math.abs(settle + reserve - 100) <= 0.01
})

/** 比例计算基数：优先预算，否则已转入 */
const fundBase = computed(() => {
  const budget = Number(shareForm.budget || shareDetail.value?.budget || 0)
  if (budget > 0) return budget
  return Number(account.value?.advanceAmount || 0)
})

const reserveQuota = computed(() => Number(((fundBase.value * Number(shareForm.reservePercent || 0)) / 100).toFixed(2)))
const settleQuota = computed(() => Number(((fundBase.value * Number(shareForm.settlePercent || 0)) / 100).toFixed(2)))
const settleRemain = computed(() => Math.max(0, settleQuota.value - Number(account.value?.settleAmount || 0)))
/** 本次还能分：不能超过「分成额度剩余」和「项目结余」 */
const canSettleAmount = computed(() =>
  Math.max(0, Math.min(settleRemain.value, Number(account.value?.balance || 0))),
)

const settlePreview = computed(() => {
  if (!shareForm.members.length || !settleForm.amount) return []
  const amount = Number(settleForm.amount)
  let allocated = 0
  return shareForm.members.map((m, i) => {
    let share = 0
    if (i === shareForm.members.length - 1) share = Number((amount - allocated).toFixed(2))
    else {
      share = Number(((amount * Number(m.percent || 0)) / 100).toFixed(2))
      allocated += share
    }
    const user = users.value.find((u) => u.id === m.userId)
    return {
      name: user?.nickname || user?.username || `用户${m.userId}`,
      layer: m.layer,
      percent: m.percent,
      share,
    }
  })
})

function fmt(n?: number) {
  return Number(n || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function fmtTime(t?: string) {
  if (!t) return '—'
  return t.replace('T', ' ').slice(0, 16)
}

function bizLabel(v?: string) {
  return ({
    ADVANCE: '预支入账',
    EXPENSE: '项目支出',
    SETTLE: '项目分钱',
    RESERVE: '预留',
    ROLLBACK: '回退',
    REIMBURSE: '报销',
    SALARY: '工资',
  } as any)[v || ''] || v || '—'
}

async function loadList() {
  list.value = await bizApi.projectAccountList()
}

async function ensureUsers() {
  if (!users.value.length) users.value = await sysApi.userList()
}

async function enter(row: any) {
  activeId.value = row.projectId
  tab.value = 'overview'
  await loadDetail()
}

async function loadDetail() {
  if (!activeId.value) return
  await ensureUsers()
  account.value = await bizApi.projectAccountDetail(activeId.value)
  try {
    shareDetail.value = await bizApi.projectShareDetail(activeId.value)
    shareForm.budget = Number(shareDetail.value?.budget || 0)
    shareForm.reservePercent = Number(shareDetail.value?.reservePercent ?? 0)
    shareForm.settlePercent = Number(shareDetail.value?.settlePercent ?? 100)
    const members = shareDetail.value?.members || []
    shareForm.members = members.length
      ? members.map((m: any) => ({
          userId: m.userId,
          layer: m.layer || '',
          percent: Number(m.percent || 0),
          remark: m.remark || '',
        }))
      : [{ userId: undefined, layer: '执行', percent: 100, remark: '' }]
  } catch {
    shareDetail.value = null
  }
  const res = await bizApi.projectAccountLedger(activeId.value, ledgerQuery)
  ledgers.value = res.list
  ledgerTotal.value = res.total
}

function back() {
  activeId.value = null
  account.value = null
  shareDetail.value = null
}

function addMember() {
  shareForm.members.push({ userId: undefined, layer: '协助', percent: 0, remark: '' })
}

function removeMember(index: number) {
  if (shareForm.members.length <= 1) return
  shareForm.members.splice(index, 1)
}

async function saveShare() {
  if (!activeId.value) return
  if (shareForm.members.some((m) => !m.userId)) {
    ElMessage.warning('请选择全部参与人')
    return
  }
  if (!fundSplitOk.value) {
    ElMessage.warning('分成% + 预留% 须为 100%')
    return
  }
  if (Math.abs(percentSum.value - 100) > 0.01) {
    ElMessage.warning(`分成人员合计必须为 100%，当前 ${percentSum.value.toFixed(2)}%`)
    return
  }
  savingShare.value = true
  try {
    await workflowApi.submit({
      type: 'SHARE_CONFIG',
      title: `资金配置 · ${account.value?.projectName || ''}`,
      projectId: activeId.value,
      poolId: shareDetail.value?.poolId,
      payload: {
        poolId: shareDetail.value?.poolId,
        budget: shareForm.budget,
        expensePercent: 0,
        reservePercent: shareForm.reservePercent,
        settlePercent: shareForm.settlePercent,
        members: shareForm.members,
      },
      remark: '项目资金配置：分成/预留合计 100%；支出直接从项目结余扣（只改规则）',
    })
    ElMessage.success('已提交资金配置审批（全体股东会签）')
    await loadDetail()
  } finally {
    savingShare.value = false
  }
}

async function settleByPreset() {
  if (!activeId.value) return
  if (!settleForm.amount || settleForm.amount <= 0) {
    ElMessage.warning('请输入分钱金额')
    return
  }
  if (settleForm.amount > canSettleAmount.value) {
    ElMessage.warning(`本次最多可分 ¥${fmt(canSettleAmount.value)}（分成额度剩余与项目结余取较小值）`)
    return
  }
  if (!shareForm.members.length || shareForm.members.some((m) => !m.userId)) {
    ElMessage.warning('请先配置分成人员及比例')
    tab.value = 'share'
    return
  }
  settling.value = true
  try {
    const amount = Number(settleForm.amount)
    let allocated = 0
    const items = shareForm.members.map((m, i) => {
      let share = 0
      if (i === shareForm.members.length - 1) share = Number((amount - allocated).toFixed(2))
      else {
        share = Number(((amount * Number(m.percent || 0)) / 100).toFixed(2))
        allocated += share
      }
      return { userId: m.userId, amount: share, layer: m.layer }
    }).filter((x) => x.amount > 0)
    await workflowApi.submit({
      type: 'PROJECT_SETTLE',
      title: `项目分钱 · ${account.value?.projectName || ''}`,
      projectId: activeId.value,
      poolId: shareDetail.value?.poolId,
      amount: settleForm.amount,
      remark: settleForm.remark,
      payload: { items },
    })
    ElMessage.success('已提交分钱审批')
    settleForm.amount = 0
    settleForm.remark = ''
    await loadDetail()
  } finally {
    settling.value = false
  }
}

async function returnRemainder() {
  if (!activeId.value) return
  const bal = Number(account.value?.balance || 0)
  if (bal <= 0) {
    ElMessage.warning('当前没有可回公司的预留/结余')
    return
  }
  await workflowApi.submit({
    type: 'RESERVE_RETURN',
    title: `预留回公司 · ${account.value?.projectName || ''}`,
    projectId: activeId.value,
    poolId: shareDetail.value?.poolId,
    amount: bal,
    remark: '项目结束，预留/结余退回公司总账',
  })
  ElMessage.success('已提交预留回公司审批')
  await loadDetail()
}

function accountLabel(row: any) {
  if (row.accountType === 'PROJECT') return '项目'
  if (row.accountType === 'POOL') return '公司'
  const who = row.userName || (row.userId ? `用户${row.userId}` : '')
  return who ? `个人 · ${who}` : '个人'
}

async function loadRelatedLedgers(row: any) {
  if (!activeId.value) return []
  const all = ledgers.value.length >= ledgerTotal.value
    ? ledgers.value
    : ((await bizApi.projectAccountLedger(activeId.value, { page: 1, pageSize: 200 })).list || [])

  // 项目分成扣款 → 找个人入账；个人入账 → 找同批其他个人 + 项目扣款
  if (row.bizType === 'SETTLE' && row.accountType === 'PROJECT') {
    return all.filter((x: any) => x.relatedId === row.id && x.accountType === 'WALLET')
  }
  if (row.bizType === 'SETTLE' && row.accountType === 'WALLET' && row.relatedId) {
    return all.filter((x: any) =>
      (x.id === row.relatedId && x.accountType === 'PROJECT')
      || (x.relatedId === row.relatedId && x.accountType === 'WALLET' && x.id !== row.id),
    )
  }
  if (row.relatedId) {
    return all.filter((x: any) => x.id === row.relatedId || (x.relatedId === row.relatedId && x.id !== row.id))
  }
  return all.filter((x: any) => x.relatedId === row.id)
}

async function openLedgerDetail(row: any) {
  ledgerDetail.value = row
  ledgerRelated.value = []
  ledgerDetailVisible.value = true
  ledgerDetailLoading.value = true
  try {
    ledgerRelated.value = await loadRelatedLedgers(row)
  } finally {
    ledgerDetailLoading.value = false
  }
}

async function submitAdvance() {
  if (!activeId.value || form.amount <= 0) {
    ElMessage.warning('请填写金额')
    return
  }
  await workflowApi.submit({
    type: 'PROJECT_ADVANCE',
    title: `项目预支 · ${account.value?.projectName || ''}`,
    projectId: activeId.value,
    amount: form.amount,
    remark: form.remark,
  })
  ElMessage.success('已提交：等审批通过后，钱从公司转到本项目')
  advanceDialog.value = false
  form.amount = 0
  form.remark = ''
}

async function openPayDialog(kind: 'reimburse' | 'salary') {
  form.amount = 0
  form.remark = ''
  voucherFiles.value = []
  if (kind === 'reimburse') reimburseDialog.value = true
  else salaryDialog.value = true
}

function fileUrl(file: any) {
  return file?.url || `/api/file/preview/${file?.id}`
}

async function onUploadPayVoucher(options: any) {
  uploadingVoucher.value = true
  try {
    const file = await workflowApi.uploadVoucher(options.file)
    voucherFiles.value.push(file)
    ElMessage.success('凭证已上传')
    options.onSuccess?.(file)
  } catch (e: any) {
    ElMessage.error(e.message || '上传失败')
    options.onError?.(e)
  } finally {
    uploadingVoucher.value = false
  }
}

function removePayVoucher(index: number) {
  voucherFiles.value.splice(index, 1)
}

async function submitPay(type: 'REIMBURSE_PROJECT' | 'SALARY_APPLY') {
  if (!activeId.value || form.amount <= 0) {
    ElMessage.warning('请填写金额')
    return
  }
  if (!voucherFiles.value.length) {
    ElMessage.warning('请上传发票/凭证')
    return
  }
  if (Number(account.value?.balance || 0) <= 0) {
    ElMessage.warning('项目余额为 0，请先「从公司转入」')
    return
  }
  if (form.amount > Number(account.value?.balance || 0)) {
    ElMessage.warning(`不能超过项目余额 ¥${fmt(account.value?.balance)}`)
    return
  }
  await workflowApi.submit({
    type,
    title: `${type === 'SALARY_APPLY' ? '发工资' : '项目报销'} · ${account.value?.projectName || ''}`,
    projectId: activeId.value,
    amount: form.amount,
    remark: form.remark,
    voucherFileIds: voucherFiles.value.map((f) => f.id),
  })
  ElMessage.success(type === 'SALARY_APPLY'
    ? '已提交发工资：审批通过并确认到账后，从本项目余额扣除'
    : '已提交报销：审批通过并确认到账后，从本项目余额扣除')
  reimburseDialog.value = false
  salaryDialog.value = false
  form.amount = 0
  form.remark = ''
  voucherFiles.value = []
}

onMounted(loadList)
</script>

<template>
  <div class="page-stack">
    <template v-if="!activeId">
      <div class="page-card">
        <div class="page-header">
          <div>
            <h3 class="page-title">项目账款</h3>
            <p class="page-desc">分成与预留合计 100%；工资/报销直接从项目结余扣，不用再配支出比例</p>
          </div>
        </div>
        <div class="grid">
          <article v-for="row in list" :key="row.projectId" class="card" @click="enter(row)">
            <h4>{{ row.projectName || `项目#${row.projectId}` }}</h4>
            <div class="meta">负责人 {{ row.ownerName || '—' }}</div>
            <div class="stats">
              <div class="stat-main">
                <span>项目结余</span>
                <b>¥ {{ fmt(row.balance) }}</b>
              </div>
              <div><span>公司已转入</span><b>¥ {{ fmt(row.advanceAmount) }}</b></div>
              <div><span>已报销/发工资</span><b>¥ {{ fmt(row.expenseAmount) }}</b></div>
              <div><span>已分给个人</span><b>¥ {{ fmt(row.settleAmount) }}</b></div>
            </div>
          </article>
        </div>
        <el-empty v-if="!list.length" description="暂无项目账款" />
      </div>
    </template>

    <template v-else>
      <div class="page-card" v-if="account">
        <div class="page-header">
          <div>
            <el-button link type="primary" @click="back">← 返回列表</el-button>
            <h3 class="page-title">{{ account.projectName }}</h3>
            <p class="page-desc">负责人 {{ account.ownerName || shareDetail?.ownerName || '—' }}</p>
          </div>
          <div class="actions">
            <el-button type="primary" @click="() => { form.amount = 0; form.remark = ''; advanceDialog = true }">从公司转入</el-button>
            <el-button @click="openPayDialog('reimburse')">申请报销</el-button>
            <el-button @click="openPayDialog('salary')">申请发工资</el-button>
            <el-button v-if="Number(account.balance) > 0" @click="returnRemainder">预留回公司</el-button>
          </div>
        </div>

        <div class="explain">
          <div class="explain-title">项目钱怎么分？（配置规则）</div>
          <ol class="steps">
            <li><b>分成</b>：规划给个人的一块，再按人员比例拆开分出去（可配）。</li>
            <li><b>预留</b>：项目结束时，结余回公司总账（可配）；与分成合计 100%。</li>
            <li><b>支出</b>：工资 / 报销不占比例，审批确认后直接从项目结余扣除。</li>
          </ol>
          <p class="formula">
            实账结余 = 已转入 ¥{{ fmt(account.advanceAmount) }}
            − 已支出 ¥{{ fmt(account.expenseAmount) }}
            − 已分成 ¥{{ fmt(account.settleAmount) }}
            ＝ <b>¥{{ fmt(account.balance) }}</b>
            <span class="hint">（改配置不会改已分成/已支出）</span>
          </p>
        </div>

        <div class="metric">
          <div class="metric-main">
            <span>项目结余</span>
            <b>¥ {{ fmt(account.balance) }}</b>
          </div>
          <div><span>公司已转入</span><b>¥ {{ fmt(account.advanceAmount) }}</b></div>
          <div>
            <span>已支出 · 工资/报销</span>
            <b>¥ {{ fmt(account.expenseAmount) }}（从结余扣）</b>
          </div>
          <div>
            <span>分成（{{ shareForm.settlePercent }}%）</span>
            <b>已分 ¥{{ fmt(account.settleAmount) }} / 额度 ¥{{ fmt(settleQuota) }}</b>
          </div>
          <div>
            <span>预留（{{ shareForm.reservePercent }}%）</span>
            <b>规划 ¥{{ fmt(reserveQuota) }} · 结束回公司</b>
          </div>
        </div>

        <el-tabs v-model="tab" class="tabs">
          <el-tab-pane label="项目流水" name="overview">
            <el-table :data="ledgers" stripe>
              <el-table-column label="时间" width="150">
                <template #default="{ row }">{{ fmtTime(row.occurTime) }}</template>
              </el-table-column>
              <el-table-column label="编号" prop="bizNo" width="170" show-overflow-tooltip />
              <el-table-column label="类型" width="100">
                <template #default="{ row }">{{ bizLabel(row.bizType) }}</template>
              </el-table-column>
              <el-table-column prop="title" label="摘要" min-width="160" show-overflow-tooltip />
              <el-table-column label="金额" width="120" align="right">
                <template #default="{ row }">
                  <span :class="Number(row.amount) >= 0 ? 'in' : 'out'">
                    {{ Number(row.amount) >= 0 ? '+' : '' }}{{ fmt(row.amount) }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="账户" width="140" show-overflow-tooltip>
                <template #default="{ row }">
                  {{ accountLabel(row) }}
                </template>
              </el-table-column>
              <el-table-column label="操作" width="80" align="center" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" @click="openLedgerDetail(row)">详情</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-pagination
              style="margin-top: 12px"
              v-model:current-page="ledgerQuery.page"
              :page-size="ledgerQuery.pageSize"
              :total="ledgerTotal"
              layout="total, prev, pager, next"
              @current-change="loadDetail"
            />
          </el-tab-pane>

          <el-tab-pane label="分成与分钱" name="share">
            <div class="share-layout">
              <section class="share-panel">
                <header class="share-panel-head">
                  <h4>① 资金配置</h4>
                  <p>只配「分成」和「预留」（合计 100%）。工资/报销不占比例，直接从项目结余扣。只改规则，已分/已花不变。</p>
                </header>

                <div class="budget-line">
                  <span class="field-label">预算基数</span>
                  <el-input-number v-model="shareForm.budget" :min="0" :precision="2" controls-position="right" />
                  <span class="hint">用来算分成/预留额度；为 0 则按已转入 · 当前 ¥{{ fmt(fundBase) }}</span>
                </div>

                <div class="percent-row two">
                  <div class="percent-item">
                    <span class="field-label">分成 %</span>
                    <el-input-number v-model="shareForm.settlePercent" :min="0" :max="100" :precision="2" controls-position="right" />
                    <span class="sub">额度 ¥{{ fmt(settleQuota) }} · 已分 ¥{{ fmt(account.settleAmount) }}</span>
                  </div>
                  <div class="percent-item">
                    <span class="field-label">预留 %</span>
                    <el-input-number v-model="shareForm.reservePercent" :min="0" :max="100" :precision="2" controls-position="right" />
                    <span class="sub">规划 ¥{{ fmt(reserveQuota) }} · 项目结束回公司</span>
                  </div>
                </div>
                <div class="sum-line" :class="{ bad: !fundSplitOk }">
                  分成 {{ Number(shareForm.settlePercent || 0).toFixed(2) }}%
                  + 预留 {{ Number(shareForm.reservePercent || 0).toFixed(2) }}%
                  = {{ (Number(shareForm.settlePercent || 0) + Number(shareForm.reservePercent || 0)).toFixed(2) }}%
                  （须为 100%；支出不占比例）
                </div>

                <div class="members-head">
                  <span class="field-label">分成人员比例</span>
                  <span class="hint">只拆「分成」那一块；以后每次分钱按此比例</span>
                </div>
                <el-table :data="shareForm.members" class="members-table">
                  <el-table-column label="人员" min-width="150">
                    <template #default="{ row }">
                      <el-select v-model="row.userId" filterable placeholder="选择人员" style="width: 100%">
                        <el-option v-for="u in users" :key="u.id" :label="u.nickname || u.username" :value="u.id" />
                      </el-select>
                    </template>
                  </el-table-column>
                  <el-table-column label="角色" width="120">
                    <template #default="{ row }">
                      <el-input v-model="row.layer" placeholder="如 主理人" />
                    </template>
                  </el-table-column>
                  <el-table-column label="分成 %" width="130">
                    <template #default="{ row }">
                      <el-input-number v-model="row.percent" :min="0" :max="100" :precision="2" controls-position="right" style="width: 110px" />
                    </template>
                  </el-table-column>
                  <el-table-column label="备注" min-width="100">
                    <template #default="{ row }">
                      <el-input v-model="row.remark" />
                    </template>
                  </el-table-column>
                  <el-table-column width="56" align="center">
                    <template #default="{ $index }">
                      <el-button link type="danger" @click="removeMember($index)">删</el-button>
                    </template>
                  </el-table-column>
                </el-table>
                <div class="panel-foot">
                  <div class="foot-left">
                    <el-button @click="addMember">添加参与人</el-button>
                    <span class="sum-line inline" :class="{ bad: Math.abs(percentSum - 100) > 0.01 }">
                      人员合计 {{ percentSum.toFixed(2) }}%
                    </span>
                  </div>
                  <el-button type="primary" :loading="savingShare" @click="saveShare">提交配置审批</el-button>
                </div>
              </section>

              <section class="share-panel settle-panel">
                <header class="share-panel-head">
                  <h4>② 按比例分钱</h4>
                  <p>从「分成」额度里分到个人钱包。本次最多 ¥{{ fmt(canSettleAmount) }}；已分 ¥{{ fmt(account.settleAmount) }} 不会因改配置而变。</p>
                </header>

                <div class="settle-form">
                  <div class="settle-field">
                    <span class="field-label">本次分多少</span>
                    <el-input-number v-model="settleForm.amount" :min="0.01" :precision="2" controls-position="right" style="width: 100%" />
                    <span class="sub">最多 ¥{{ fmt(canSettleAmount) }}</span>
                  </div>
                  <div class="settle-field">
                    <span class="field-label">备注</span>
                    <el-input v-model="settleForm.remark" placeholder="可选" />
                  </div>
                </div>

                <div v-if="settlePreview.length" class="preview-block">
                  <div class="field-label">每人预览</div>
                  <div class="preview">
                    <div v-for="item in settlePreview" :key="String(item.name) + item.layer" class="preview-row">
                      <span>{{ item.name }}<em>{{ item.layer || '成员' }} · {{ item.percent }}%</em></span>
                      <b>+¥ {{ fmt(item.share) }}</b>
                    </div>
                  </div>
                </div>
                <div v-else class="preview-empty">先在左侧配好分成人员，再填金额可预览</div>

                <div class="panel-foot end">
                  <el-button type="primary" :loading="settling" @click="settleByPreset">提交分钱审批</el-button>
                </div>
              </section>
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>
    </template>

    <el-drawer v-model="ledgerDetailVisible" title="流水详情" size="480px">
      <template v-if="ledgerDetail">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="时间">{{ fmtTime(ledgerDetail.occurTime) }}</el-descriptions-item>
          <el-descriptions-item label="编号">{{ ledgerDetail.bizNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="类型">{{ bizLabel(ledgerDetail.bizType) }}</el-descriptions-item>
          <el-descriptions-item label="摘要">{{ ledgerDetail.title || '—' }}</el-descriptions-item>
          <el-descriptions-item label="账户">{{ accountLabel(ledgerDetail) }}</el-descriptions-item>
          <el-descriptions-item label="金额">
            <span :class="Number(ledgerDetail.amount) >= 0 ? 'in' : 'out'">
              {{ Number(ledgerDetail.amount) >= 0 ? '+' : '' }}{{ fmt(ledgerDetail.amount) }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="变动前">
            {{ ledgerDetail.beforeBalance != null ? `¥ ${fmt(ledgerDetail.beforeBalance)}` : '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="变动后">
            {{ ledgerDetail.afterBalance != null ? `¥ ${fmt(ledgerDetail.afterBalance)}` : '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="备注">{{ ledgerDetail.remark || '—' }}</el-descriptions-item>
          <el-descriptions-item v-if="ledgerDetail.approvalId" label="审批单">
            #{{ ledgerDetail.approvalId }}
          </el-descriptions-item>
        </el-descriptions>

        <div v-loading="ledgerDetailLoading" class="related-block">
          <h4 class="related-title">关联流水</h4>
          <p class="related-tip">
            <template v-if="ledgerDetail.bizType === 'SETTLE' && ledgerDetail.accountType === 'PROJECT'">
              本笔从项目扣出后，分到以下个人钱包：
            </template>
            <template v-else-if="ledgerDetail.bizType === 'SETTLE' && ledgerDetail.accountType === 'WALLET'">
              同一次分钱的其它流水：
            </template>
            <template v-else>
              与本笔成对的进出账：
            </template>
          </p>
          <div v-if="ledgerRelated.length" class="related-list">
            <div v-for="r in ledgerRelated" :key="r.id" class="related-row">
              <div class="related-main">
                <span class="related-who">{{ accountLabel(r) }}</span>
                <span class="related-sub">{{ bizLabel(r.bizType) }} · {{ r.bizNo || r.title || '' }}</span>
              </div>
              <b :class="Number(r.amount) >= 0 ? 'in' : 'out'">
                {{ Number(r.amount) >= 0 ? '+' : '' }}{{ fmt(r.amount) }}
              </b>
            </div>
          </div>
          <div v-else class="related-empty">没有关联流水</div>
        </div>
      </template>
    </el-drawer>

    <el-dialog v-model="advanceDialog" title="从公司转入本项目" width="480px">
      <div class="dialog-box">
        <p>把公司总账的钱拨到这个项目里，之后才能报销、发工资、分钱。</p>
        <ul>
          <li>现在项目余额：<b>¥{{ fmt(account?.balance) }}</b></li>
          <li>审批通过后：公司总账减少，本项目余额增加。</li>
        </ul>
      </div>
      <el-form label-width="90px">
        <el-form-item label="转入金额" required>
          <el-input-number v-model="form.amount" :min="0.01" :precision="2" style="width: 200px" />
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="form.remark" placeholder="例如：项目启动拨款" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="advanceDialog = false">取消</el-button>
        <el-button type="primary" @click="submitAdvance">提交审批</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reimburseDialog" title="申请项目报销" width="480px">
      <div class="dialog-box">
        <p>用本项目的钱报销项目开支（如采购、差旅）。</p>
        <ul>
          <li>项目余额：<b>¥{{ fmt(account?.balance) }}</b></li>
          <li>流程：上传发票提交 → 审批 → 财务回执 → 你确认到账 → <b>从项目结余扣款</b>（无支出比例限制）</li>
        </ul>
      </div>
      <el-form label-width="90px">
        <el-form-item label="报销金额" required>
          <el-input-number v-model="form.amount" :min="0.01" :precision="2" :max="Number(account?.balance || 0) || undefined" style="width: 200px" />
        </el-form-item>
        <el-form-item label="发票" required>
          <div class="voucher-box">
            <el-upload :show-file-list="false" :http-request="onUploadPayVoucher" accept="image/*,.pdf">
              <el-button :loading="uploadingVoucher" size="small">上传发票/凭证</el-button>
            </el-upload>
            <div v-for="(file, index) in voucherFiles" :key="file.id" class="voucher-item">
              <a :href="fileUrl(file)" target="_blank" rel="noopener">{{ file.originalName || `文件#${file.id}` }}</a>
              <el-button link type="danger" @click="removePayVoucher(index)">移除</el-button>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="form.remark" placeholder="例如：买服务器" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reimburseDialog = false">取消</el-button>
        <el-button type="primary" @click="submitPay('REIMBURSE_PROJECT')">提交报销审批</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="salaryDialog" title="申请发工资" width="480px">
      <div class="dialog-box">
        <p>用本项目的钱发项目相关工资/劳务。</p>
        <ul>
          <li>项目余额：<b>¥{{ fmt(account?.balance) }}</b></li>
          <li>流程：上传凭证提交 → 审批 → 财务回执 → 确认到账 → <b>从项目结余扣款</b>（无支出比例限制）</li>
        </ul>
      </div>
      <el-form label-width="90px">
        <el-form-item label="工资金额" required>
          <el-input-number v-model="form.amount" :min="0.01" :precision="2" :max="Number(account?.balance || 0) || undefined" style="width: 200px" />
        </el-form-item>
        <el-form-item label="凭证" required>
          <div class="voucher-box">
            <el-upload :show-file-list="false" :http-request="onUploadPayVoucher" accept="image/*,.pdf">
              <el-button :loading="uploadingVoucher" size="small">上传凭证</el-button>
            </el-upload>
            <div v-for="(file, index) in voucherFiles" :key="file.id" class="voucher-item">
              <a :href="fileUrl(file)" target="_blank" rel="noopener">{{ file.originalName || `文件#${file.id}` }}</a>
              <el-button link type="danger" @click="removePayVoucher(index)">移除</el-button>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="form.remark" placeholder="例如：3 月外包劳务" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="salaryDialog = false">取消</el-button>
        <el-button type="primary" @click="submitPay('SALARY_APPLY')">提交发工资审批</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-desc { margin: 4px 0 0; color: #64748b; font-size: 13px; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
.card {
  border: 1px solid #e8edf4; border-radius: 12px; padding: 16px; cursor: pointer; background: #fff;
}
.card:hover { box-shadow: 0 6px 18px rgba(15,23,42,.06); }
.card h4 { margin: 0 0 6px; }
.meta { color: #94a3b8; font-size: 12px; margin-bottom: 12px; }
.stats { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; font-size: 12px; color: #64748b; }
.stats b { display: block; color: #0f172a; margin-top: 2px; font-size: 14px; }
.stat-main { grid-column: 1 / -1; padding: 8px 10px; background: #f8fafc; border-radius: 8px; }
.stat-main b { font-size: 20px; color: #1d4ed8; }
.explain {
  margin: 8px 0 14px;
  padding: 12px 14px;
  background: #fffbeb;
  border: 1px solid #fde68a;
  border-radius: 10px;
  color: #78716c;
  font-size: 13px;
  line-height: 1.7;
}
.explain-title { font-weight: 600; color: #92400e; margin-bottom: 6px; }
.explain p { margin: 0 0 6px; }
.steps {
  margin: 0 0 8px;
  padding-left: 18px;
  color: #78716c;
}
.steps li { margin: 4px 0; }
.dialog-box {
  margin: 0 0 14px;
  padding: 12px 14px;
  background: #f8fafc;
  border-radius: 8px;
  color: #475569;
  font-size: 13px;
  line-height: 1.6;
}
.dialog-box p { margin: 0 0 8px; }
.dialog-box ul { margin: 0; padding-left: 18px; }
.dialog-box li { margin: 4px 0; }
.dialog-box b { color: #0f172a; }
.voucher-box { width: 100%; }
.voucher-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 6px;
  padding: 6px 8px;
  background: #f8fafc;
  border-radius: 6px;
  font-size: 13px;
}
.voucher-item a { color: #1d4ed8; word-break: break-all; }
.formula { color: #57534e; }
.formula b { color: #1d4ed8; font-size: 15px; }
.metric {
  display: grid; grid-template-columns: 1.3fr repeat(4, 1fr); gap: 10px;
  background: #f8fafc; border-radius: 10px; padding: 14px; margin-bottom: 8px;
}
.metric span { display: block; font-size: 12px; color: #94a3b8; }
.metric b { font-size: 15px; color: #0f172a; }
.metric-main b { color: #1d4ed8; font-size: 22px; }
.share-fields {
  display: flex; flex-wrap: wrap; align-items: center; gap: 14px;
  color: #475569; font-size: 13px;
}
.share-fields label { display: inline-flex; align-items: center; }
.actions { display: flex; gap: 8px; flex-wrap: wrap; }
.tabs { margin-top: 8px; }
.hint { color: #94a3b8; font-size: 12px; }
.field-label {
  display: block;
  font-size: 13px;
  color: #334155;
  font-weight: 500;
  margin-bottom: 6px;
}
.share-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(280px, 0.9fr);
  gap: 16px;
  align-items: start;
}
.share-panel {
  background: #fff;
  border: 1px solid #e8edf4;
  border-radius: 12px;
  padding: 16px 18px 14px;
}
.share-panel-head {
  margin-bottom: 14px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f1f5f9;
}
.share-panel-head h4 {
  margin: 0 0 4px;
  font-size: 15px;
  font-weight: 600;
  color: #0f172a;
}
.share-panel-head p {
  margin: 0;
  font-size: 12px;
  color: #94a3b8;
  line-height: 1.5;
}
.budget-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}
.budget-line .field-label {
  margin: 0;
  white-space: nowrap;
}
.percent-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.percent-row.two {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.percent-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px;
  background: #f8fafc;
  border-radius: 8px;
}
.percent-item .field-label { margin-bottom: 0; }
.percent-item :deep(.el-input-number) { width: 100%; }
.percent-item.readonly {
  background: #f1f5f9;
  border: 1px dashed #cbd5e1;
}
.readonly-value {
  height: 32px;
  display: flex;
  align-items: center;
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
}
.sub {
  font-size: 12px;
  color: #94a3b8;
  line-height: 1.4;
}
.sum-line {
  margin: 10px 0 14px;
  font-size: 12px;
  color: #64748b;
}
.sum-line.inline { margin: 0; }
.sum-line.bad { color: #dc2626; font-weight: 600; }
.members-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 8px;
}
.members-head .field-label { margin: 0; }
.members-table {
  width: 100%;
  --el-table-header-bg-color: #f8fafc;
}
.panel-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid #f1f5f9;
}
.panel-foot.end { justify-content: flex-end; }
.foot-left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.settle-panel {
  position: sticky;
  top: 12px;
}
.settle-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.settle-field .sub { display: block; margin-top: 6px; }
.preview-block { margin-top: 16px; }
.preview {
  margin-top: 8px;
  border: 1px solid #eef2f7;
  border-radius: 8px;
  overflow: hidden;
}
.preview-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  font-size: 13px;
  color: #334155;
  border-bottom: 1px solid #f1f5f9;
}
.preview-row:last-child { border-bottom: 0; }
.preview-row em {
  margin-left: 6px;
  font-style: normal;
  color: #94a3b8;
  font-size: 12px;
}
.preview-row b { color: #16a34a; font-weight: 600; white-space: nowrap; }
.preview-empty {
  margin-top: 16px;
  padding: 16px;
  text-align: center;
  color: #94a3b8;
  font-size: 13px;
  background: #f8fafc;
  border-radius: 8px;
}
.in { color: #16a34a; font-weight: 600; }
.out { color: #dc2626; font-weight: 600; }
.related-block { margin-top: 20px; }
.related-title { margin: 0 0 6px; font-size: 15px; color: #0f172a; }
.related-tip { margin: 0 0 10px; font-size: 12px; color: #94a3b8; line-height: 1.5; }
.related-list {
  border: 1px solid #eef2f7;
  border-radius: 10px;
  overflow: hidden;
}
.related-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-bottom: 1px solid #f1f5f9;
}
.related-row:last-child { border-bottom: 0; }
.related-main { min-width: 0; }
.related-who { display: block; font-size: 13px; color: #0f172a; font-weight: 500; }
.related-sub { display: block; margin-top: 2px; font-size: 12px; color: #94a3b8; }
.related-empty {
  padding: 16px;
  text-align: center;
  color: #94a3b8;
  font-size: 13px;
  background: #f8fafc;
  border-radius: 8px;
}
@media (max-width: 1100px) {
  .metric { grid-template-columns: 1fr 1fr; }
  .share-layout { grid-template-columns: 1fr; }
  .settle-panel { position: static; }
  .percent-row { grid-template-columns: 1fr; }
}
</style>
