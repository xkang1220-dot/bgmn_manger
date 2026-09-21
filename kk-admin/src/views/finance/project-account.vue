<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bizApi } from '@/api/biz'
import { sysApi } from '@/api/system'
import { workflowApi } from '@/api/workflow'
import { approvalFlowTip } from '@/utils/approvalTip'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const list = ref<any[]>([])
const companies = ref<any[]>([])
const filter = reactive({
  companyId: undefined as number | undefined,
  scale: '' as string,
})
const activeId = ref<number | null>(null)
/** 从重大外壳点进小项目后，返回时回到外壳 */
const shellParentId = ref<number | null>(null)
const childAccounts = ref<any[]>([])
const account = ref<any>(null)
const shareDetail = ref<any>(null)
const users = ref<any[]>([])
const ledgers = ref<any[]>([])
const ledgerTotal = ref(0)
const ledgerQuery = reactive({ page: 1, pageSize: 20 })
const tab = ref('overview')

const savingShare = ref(false)
const advanceDialog = ref(false)
const reimburseDialog = ref(false)
const salaryDialog = ref(false)
const ledgerDetailVisible = ref(false)
const ledgerDetail = ref<any>(null)
const ledgerRelated = ref<any[]>([])
const ledgerDetailLoading = ref(false)

const form = reactive({
  amount: 0,
  remark: '',
  payMethodId: undefined as number | undefined,
  fundType: 'SHARE_PENDING' as string,
})
const voucherFiles = ref<any[]>([])
const uploadingVoucher = ref(false)
const myPayMethods = ref<any[]>([])
const shareForm = reactive({
  reservePercent: 0,
  settlePercent: 100,
  members: [] as Array<{ userId?: number; layer: string; percent: number; remark: string }>,
})
const periodSharing = ref(false)
const periodShareDialog = ref(false)
const selectedPeriodMonth = ref('')
const reverseAdvanceDialog = ref(false)
const reverseForm = reactive({
  sharePendingAmount: 0,
  nonShareAmount: 0,
  remark: '',
})
const remainderDialog = ref(false)
const remainderForm = reactive({
  sharePendingAmount: 0,
  nonShareAmount: 0,
  reserveHeldAmount: 0,
  remark: '',
})
const remainderSubmitting = ref(false)

const percentSum = computed(() =>
  shareForm.members.reduce((s, m) => s + Number(m.percent || 0), 0),
)

/** 分成% + 预留% 须为 100%；支出不再配置，直接从项目结余扣 */
const fundSplitOk = computed(() => {
  const settle = Number(shareForm.settlePercent || 0)
  const reserve = Number(shareForm.reservePercent || 0)
  return settle >= 0 && reserve >= 0 && Math.abs(settle + reserve - 100) <= 0.01
})

/** 分成% + 预留% 联动，合计恒为 100% */
function clampPercent(v: unknown) {
  const n = Number(v)
  if (!Number.isFinite(n)) return 0
  return Math.min(100, Math.max(0, Number(n.toFixed(2))))
}

function setSettlePercent(v: unknown) {
  const settle = clampPercent(v)
  const reserve = Number((100 - settle).toFixed(2))
  if (shareForm.settlePercent !== settle) shareForm.settlePercent = settle
  if (shareForm.reservePercent !== reserve) shareForm.reservePercent = reserve
}

function setReservePercent(v: unknown) {
  const reserve = clampPercent(v)
  const settle = Number((100 - reserve).toFixed(2))
  if (shareForm.reservePercent !== reserve) shareForm.reservePercent = reserve
  if (shareForm.settlePercent !== settle) shareForm.settlePercent = settle
}

watch(
  () => shareForm.settlePercent,
  (v) => setSettlePercent(v),
)

watch(
  () => shareForm.reservePercent,
  (v) => setReservePercent(v),
)

/** 加载配置时：以分成%为准，预留%补到 100 */
function applyFundSplitPercents(settle?: unknown, reserve?: unknown) {
  const settleRaw = settle != null ? Number(settle) : NaN
  const reserveRaw = reserve != null ? Number(reserve) : NaN
  if (Number.isFinite(settleRaw)) {
    setSettlePercent(settleRaw)
  } else if (Number.isFinite(reserveRaw)) {
    setReservePercent(reserveRaw)
  } else {
    setSettlePercent(100)
  }
}

function formatPeriodMonth(d = new Date()) {
  const m = `${d.getMonth() + 1}`.padStart(2, '0')
  return `${d.getFullYear()}-${m}`
}

const periodSettlePreview = computed(() => {
  const pending = Number(account.value?.sharePendingBalance || 0)
  const settlePct = Number(shareForm.settlePercent || 0)
  const settleAmt = Number(((pending * settlePct) / 100).toFixed(2))
  const reserveAmt = Number((pending - settleAmt).toFixed(2))
  return { pending, settleAmt, reserveAmt }
})

const periodMemberPreview = computed(() => buildMemberShares(periodSettlePreview.value.settleAmt))

/** 从公司转入可用余额：打开弹窗后优先用资金池列表（与总账同口径） */
const companyPoolBalance = computed(() => {
  if (advancePoolSnapshot.value?.balance != null) {
    return Number(advancePoolSnapshot.value.balance)
  }
  if (account.value?.companyPoolBalance != null && account.value?.companyPoolBalance !== '') {
    return Number(account.value.companyPoolBalance)
  }
  if (shareDetail.value?.poolBalance != null && shareDetail.value?.poolBalance !== '') {
    return Number(shareDetail.value.poolBalance)
  }
  return 0
})
const companyPoolId = computed(() =>
  advancePoolSnapshot.value?.id
    ?? account.value?.companyPoolId
    ?? shareDetail.value?.poolId
    ?? undefined,
)
const companyPoolLabel = computed(() => {
  const poolName = advancePoolSnapshot.value?.name
    || account.value?.companyPoolName
    || shareDetail.value?.poolName
  const company = account.value?.companyName || shareDetail.value?.companyName
  if (poolName && company) return `${company} · ${poolName}`
  return poolName || company || '公司总账'
})

/** 退回公司：最多 min(预支未退回, 待分成+非分成) */
const returnableAdvance = computed(() => {
  const advance = Number(account.value?.advanceAmount || 0)
  const pending = Number(account.value?.sharePendingBalance || 0)
  const nonShare = Number(account.value?.nonShareBalance || 0)
  return Math.max(0, Number(Math.min(advance, pending + nonShare).toFixed(2)))
})

const reverseReturnTotal = computed(() =>
  Number((Number(reverseForm.sharePendingAmount || 0) + Number(reverseForm.nonShareAmount || 0)).toFixed(2)),
)

const remainderReturnTotal = computed(() =>
  Number((
    Number(remainderForm.sharePendingAmount || 0)
    + Number(remainderForm.nonShareAmount || 0)
    + Number(remainderForm.reserveHeldAmount || 0)
  ).toFixed(2)),
)

/** 打开转入弹窗时从资金池列表核对到的快照 */
const advancePoolSnapshot = ref<{ id?: number; name?: string; balance?: number } | null>(null)

function fmt(n?: number) {
  return Number(n || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function buildMemberShares(amount: number) {
  if (!shareForm.members.length || amount <= 0) return []
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
      name: user?.nickname || user?.username || (m.userId ? `用户${m.userId}` : '未选人员'),
      layer: m.layer,
      percent: Number(m.percent || 0),
      share,
    }
  })
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

function bizTagType(v?: string) {
  return ({
    ADVANCE: 'success',
    EXPENSE: 'danger',
    SETTLE: 'primary',
    RESERVE: 'info',
    ROLLBACK: 'danger',
    REIMBURSE: 'warning',
    SALARY: 'warning',
  } as Record<string, string>)[v || ''] || 'info'
}

function scaleLabel(v?: string) {
  return ({ NORMAL: '常规', KEY: '重点', MAJOR: '重大' } as Record<string, string>)[v || ''] || v || '—'
}

function scaleTone(v?: string) {
  return ({ KEY: 'primary', MAJOR: 'warning' } as Record<string, string>)[v || ''] || 'info'
}

async function loadList() {
  const params: { companyId?: number; scale?: string } = {}
  if (filter.companyId != null) params.companyId = filter.companyId
  if (filter.scale) params.scale = filter.scale
  list.value = await bizApi.projectAccountList(params)
}

async function loadCompanies() {
  if (companies.value.length) return
  companies.value = await sysApi.myCompanies()
}

function resetFilter() {
  filter.companyId = undefined
  filter.scale = ''
  loadList()
}

async function ensureUsers() {
  if (!users.value.length) users.value = await sysApi.userList()
}

async function enter(row: any) {
  activeId.value = row.projectId
  shellParentId.value = null
  tab.value = 'overview'
  childAccounts.value = []
  await loadDetail()
}

async function enterChild(row: any) {
  shellParentId.value = activeId.value
  activeId.value = row.projectId
  tab.value = 'overview'
  childAccounts.value = []
  await loadDetail()
}

function back() {
  if (shellParentId.value) {
    activeId.value = shellParentId.value
    shellParentId.value = null
    childAccounts.value = []
    void loadDetail()
    return
  }
  activeId.value = null
  account.value = null
  shareDetail.value = null
  childAccounts.value = []
}

async function loadDetail() {
  if (!activeId.value) return
  await ensureUsers()
  account.value = await bizApi.projectAccountDetail(activeId.value)
  if (account.value?.majorShell) {
    try {
      childAccounts.value = await bizApi.projectAccountChildren(activeId.value)
    } catch {
      childAccounts.value = []
    }
    shareDetail.value = null
    ledgers.value = []
    ledgerTotal.value = 0
    return
  }
  childAccounts.value = []
  try {
    shareDetail.value = await bizApi.projectShareDetail(activeId.value)
    applyFundSplitPercents(shareDetail.value?.settlePercent, shareDetail.value?.reservePercent)
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
    const approval = await workflowApi.submit({
      type: 'SHARE_CONFIG',
      title: `资金配置 · ${account.value?.projectName || ''}`,
      projectId: activeId.value,
      poolId: shareDetail.value?.poolId,
      payload: {
        poolId: shareDetail.value?.poolId,
        budget: 0,
        expensePercent: 0,
        reservePercent: shareForm.reservePercent,
        settlePercent: shareForm.settlePercent,
        members: shareForm.members,
      },
      remark: '项目资金配置：分成/预留合计 100%；支出直接从项目结余扣（只改规则）',
    })
    ElMessage.success(approvalFlowTip(approval, '已提交资金配置审批'))
    await loadDetail()
  } finally {
    savingShare.value = false
  }
}

async function openRemainderDialog() {
  if (!activeId.value) return
  await loadDetail()
  const pending = Number(account.value?.sharePendingBalance || 0)
  const nonShare = Number(account.value?.nonShareBalance || 0)
  const held = Number(account.value?.reserveHeld || 0)
  if (pending + nonShare + held <= 0) {
    ElMessage.warning('当前没有可回公司的结余/预留')
    return
  }
  // 默认全 0，避免误把全部金额带上；需要整笔时点「全部填入」
  remainderForm.sharePendingAmount = 0
  remainderForm.nonShareAmount = 0
  remainderForm.reserveHeldAmount = 0
  remainderForm.remark = ''
  remainderDialog.value = true
}

function fillRemainderAll() {
  remainderForm.sharePendingAmount = Number(Number(account.value?.sharePendingBalance || 0).toFixed(2))
  remainderForm.nonShareAmount = Number(Number(account.value?.nonShareBalance || 0).toFixed(2))
  remainderForm.reserveHeldAmount = Number(Number(account.value?.reserveHeld || 0).toFixed(2))
}

async function submitRemainderReturn() {
  if (!activeId.value) return
  // 先失焦，避免 InputNumber 编辑中未提交就点确认，仍带着旧值
  ;(document.activeElement as HTMLElement | null)?.blur?.()
  await new Promise<void>((resolve) => setTimeout(resolve, 0))

  const shareAmt = Number(Number(remainderForm.sharePendingAmount || 0).toFixed(2))
  const nonShareAmt = Number(Number(remainderForm.nonShareAmount || 0).toFixed(2))
  const heldAmt = Number(Number(remainderForm.reserveHeldAmount || 0).toFixed(2))
  const total = Number((shareAmt + nonShareAmt + heldAmt).toFixed(2))
  if (total <= 0) {
    ElMessage.warning('请至少填写一笔回公司金额')
    return
  }
  const pending = Number(Number(account.value?.sharePendingBalance || 0).toFixed(2))
  const nonShare = Number(Number(account.value?.nonShareBalance || 0).toFixed(2))
  const held = Number(Number(account.value?.reserveHeld || 0).toFixed(2))
  if (shareAmt < 0 || nonShareAmt < 0 || heldAmt < 0) {
    ElMessage.warning('回公司金额不能为负')
    return
  }
  if (shareAmt > pending) {
    ElMessage.warning(`待分成最多可回 ¥${fmt(pending)}`)
    return
  }
  if (nonShareAmt > nonShare) {
    ElMessage.warning(`非分成最多可回 ¥${fmt(nonShare)}`)
    return
  }
  if (heldAmt > held) {
    ElMessage.warning(`预留占用最多可回 ¥${fmt(held)}`)
    return
  }
  const poolId = companyPoolId.value || shareDetail.value?.poolId || account.value?.companyPoolId
  if (!poolId) {
    ElMessage.warning('未找到该公司资金池')
    return
  }
  try {
    await ElMessageBox.confirm(
      `<div style="line-height:1.7">即将提交回公司：<br/>待分成 <b>¥${fmt(shareAmt)}</b><br/>非分成 <b>¥${fmt(nonShareAmt)}</b><br/>预留占用 <b>¥${fmt(heldAmt)}</b><br/>合计 <b style="color:#b45309">¥${fmt(total)}</b><br/><br/>请核对合计，确认无误后再提交。</div>`,
      '确认结余回公司',
      {
        type: 'warning',
        dangerouslyUseHTMLString: true,
        confirmButtonText: '确认提交',
        cancelButtonText: '返回修改',
      },
    )
  } catch {
    return
  }
  remainderSubmitting.value = true
  try {
    const approval = await workflowApi.submit({
      type: 'RESERVE_RETURN',
      title: `项目结余回公司 ¥${fmt(total)} · ${account.value?.projectName || ''}`,
      projectId: activeId.value,
      poolId,
      amount: total,
      remark: remainderForm.remark || '项目结余退回公司总账',
      payload: {
        sharePendingAmount: shareAmt,
        nonShareAmount: nonShareAmt,
        reserveHeldAmount: heldAmt,
      },
    })
    ElMessage.success(approvalFlowTip(approval, '已提交结余回公司审批'))
    remainderDialog.value = false
    remainderForm.sharePendingAmount = 0
    remainderForm.nonShareAmount = 0
    remainderForm.reserveHeldAmount = 0
    remainderForm.remark = ''
    await loadDetail()
  } catch {
    // 错误提示由 request 拦截器统一弹出
  } finally {
    remainderSubmitting.value = false
  }
}

async function openPeriodShareDialog() {
  if (!activeId.value) return
  if (periodSettlePreview.value.pending <= 0) {
    ElMessage.warning('待分成余额为 0，无法分成')
    return
  }
  if (!fundSplitOk.value) {
    ElMessage.warning('请先配置分成% + 预留% = 100%')
    tab.value = 'share'
    return
  }
  if (!shareForm.members.length || shareForm.members.some((m) => !m.userId)) {
    ElMessage.warning('请先配置分成人员及比例')
    tab.value = 'share'
    return
  }
  selectedPeriodMonth.value = formatPeriodMonth()
  periodShareDialog.value = true
}

async function confirmPeriodShare() {
  if (!activeId.value) return
  const month = String(selectedPeriodMonth.value || '').trim()
  if (!/^\d{4}-\d{2}$/.test(month)) {
    ElMessage.warning('请选择自然月')
    return
  }
  const preview = periodSettlePreview.value
  periodSharing.value = true
  try {
    const approval = await workflowApi.submit({
      type: 'PROJECT_SHARE_PERIOD',
      title: `自然月分成 · ${account.value?.projectName || ''} · ${month}`,
      projectId: activeId.value,
      poolId: shareDetail.value?.poolId || account.value?.companyPoolId,
      amount: preview.pending,
      remark: `自然月 ${month}：待分成按配置分成/预留执行分成`,
      payload: {
        periodMonth: month,
        settlePercent: shareForm.settlePercent,
        reservePercent: shareForm.reservePercent,
      },
    })
    ElMessage.success(approvalFlowTip(approval, '已提交自然月分成审批'))
    periodShareDialog.value = false
    await loadDetail()
  } finally {
    periodSharing.value = false
  }
}

function fundBucketBalance(fundType?: string) {
  if (fundType === 'NON_SHARE') return Number(account.value?.nonShareBalance || 0)
  return Number(account.value?.sharePendingBalance || 0)
}

function fundTypeLabel(v?: string) {
  return v === 'NON_SHARE' ? '非分成资金' : '待分成资金'
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

  // 项目分成 / 工资 / 报销：项目扣款 → 个人入账；个人入账 → 同批项目扣款
  if (['SETTLE', 'SALARY', 'REIMBURSE'].includes(row.bizType) && row.accountType === 'PROJECT') {
    return all.filter((x: any) => x.relatedId === row.id && x.accountType === 'WALLET')
  }
  if (['SETTLE', 'SALARY', 'REIMBURSE'].includes(row.bizType) && row.accountType === 'WALLET' && row.relatedId) {
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

async function openAdvanceDialog() {
  form.amount = 0
  form.remark = ''
  advancePoolSnapshot.value = null
  if (activeId.value) {
    // 1) 刷新账款详情（含公司资金池余额）
    try {
      account.value = await bizApi.projectAccountDetail(activeId.value)
    } catch {
      // ignore
    }
    // 2) 刷新资金配置（兼容旧字段 poolBalance）
    try {
      const detail = await bizApi.projectShareDetail(activeId.value)
      shareDetail.value = detail
      applyFundSplitPercents(detail?.settlePercent, detail?.reservePercent)
      const members = detail?.members || []
      shareForm.members = members.length
        ? members.map((m: any) => ({
            userId: m.userId,
            layer: m.layer || '',
            percent: Number(m.percent || 0),
            remark: m.remark || '',
          }))
        : shareForm.members
    } catch {
      // 无资金配置权限时仍可打开
    }
    // 3) 有总账/资金池权限时再用列表核对（与总账同口径）；无权限则用账款详情里的公司池余额，避免误报 403
    const canReadPool = userStore.hasPermission('finance:pool:list')
      || userStore.hasPermission('finance:ledger:list')
      || userStore.hasPermission('finance:ledger:add')
    if (canReadPool) {
      try {
        const companyId = Number(account.value?.companyId || shareDetail.value?.companyId)
        if (Number.isFinite(companyId) && companyId > 0) {
          const pools = (await bizApi.poolList()) || []
          const matched = pools.filter((p: any) => {
            if (Number(p.companyId) !== companyId) return false
            // status 空/未返回视为启用；仅显式 0 为禁用（与后端一致）
            return p.status == null || p.status === '' || Number(p.status) !== 0
          })
          const preferredId = Number(account.value?.companyPoolId || shareDetail.value?.poolId || 0)
          let pool = preferredId > 0 ? matched.find((p: any) => Number(p.id) === preferredId) : undefined
          if (!pool) pool = matched.find((p: any) => Number(p.isDefault) === 1)
          if (!pool) pool = matched[0]
          if (pool) {
            advancePoolSnapshot.value = {
              id: Number(pool.id),
              name: pool.name,
              balance: Number(pool.balance || 0),
            }
          }
        }
      } catch {
        // 列表失败时仍依赖账款详情
      }
    }
  }
  advanceDialog.value = true
}

async function submitAdvance() {
  if (!activeId.value || form.amount <= 0) {
    ElMessage.warning('请填写金额')
    return
  }
  const amount = Number(Number(form.amount).toFixed(2))
  const available = Number(Number(companyPoolBalance.value).toFixed(2))
  if (!(available > 0)) {
    ElMessage.warning('公司余额为 0，无法转入')
    return
  }
  if (amount > available) {
    ElMessage.warning(`不能超过公司余额 ¥${fmt(available)}`)
    return
  }
  if (!companyPoolId.value) {
    ElMessage.warning('未找到该公司资金池，请先在总账确认资金池配置')
    return
  }
  try {
    const approval = await workflowApi.submit({
      type: 'PROJECT_ADVANCE',
      title: `项目预支 · ${account.value?.projectName || ''}`,
      projectId: activeId.value,
      poolId: companyPoolId.value,
      amount,
      remark: form.remark,
    })
    ElMessage.success(approvalFlowTip(approval, '已提交：等审批通过后，钱从公司转到本项目'))
    advanceDialog.value = false
    form.amount = 0
    form.remark = ''
    await loadDetail()
  } catch {
    // 错误提示由 request 拦截器统一弹出
  }
}

async function openReverseAdvanceDialog() {
  if (activeId.value) {
    await loadDetail()
  }
  const available = returnableAdvance.value
  if (available <= 0) {
    ElMessage.warning('当前没有可退回公司的预支余额（受公司预支未退回与项目可用余额限制）')
    return
  }
  const advance = Number(account.value?.advanceAmount || 0)
  const pending = Number(account.value?.sharePendingBalance || 0)
  const nonShare = Number(account.value?.nonShareBalance || 0)
  // 默认优先扣非分成，剩余额度再扣待分成
  let remain = Number(Math.min(advance, pending + nonShare).toFixed(2))
  const fromNonShare = Number(Math.min(nonShare, remain).toFixed(2))
  remain = Number((remain - fromNonShare).toFixed(2))
  const fromPending = Number(Math.min(pending, remain).toFixed(2))
  reverseForm.nonShareAmount = fromNonShare
  reverseForm.sharePendingAmount = fromPending
  reverseForm.remark = ''
  reverseAdvanceDialog.value = true
}

async function submitReverseAdvance() {
  if (!activeId.value) return
  const shareAmt = Number(Number(reverseForm.sharePendingAmount || 0).toFixed(2))
  const nonShareAmt = Number(Number(reverseForm.nonShareAmount || 0).toFixed(2))
  const total = Number((shareAmt + nonShareAmt).toFixed(2))
  if (total <= 0) {
    ElMessage.warning('请至少填写一笔退回金额')
    return
  }
  const pending = Number(Number(account.value?.sharePendingBalance || 0).toFixed(2))
  const nonShare = Number(Number(account.value?.nonShareBalance || 0).toFixed(2))
  const advance = Number(Number(account.value?.advanceAmount || 0).toFixed(2))
  if (shareAmt < 0 || nonShareAmt < 0) {
    ElMessage.warning('退回金额不能为负')
    return
  }
  if (shareAmt > pending) {
    ElMessage.warning(`待分成最多可退 ¥${fmt(pending)}`)
    return
  }
  if (nonShareAmt > nonShare) {
    ElMessage.warning(`非分成最多可退 ¥${fmt(nonShare)}`)
    return
  }
  if (total > advance) {
    ElMessage.warning(`合计不能超过公司预支未退回 ¥${fmt(advance)}`)
    return
  }
  const poolId = companyPoolId.value || shareDetail.value?.poolId || account.value?.companyPoolId
  if (!poolId) {
    ElMessage.warning('未找到该公司资金池')
    return
  }
  try {
    const approval = await workflowApi.submit({
      type: 'PROJECT_ADVANCE_RETURN',
      title: `退回公司 · ${account.value?.projectName || ''}`,
      projectId: activeId.value,
      poolId,
      amount: total,
      remark: reverseForm.remark || '公司预支资金退回公司总账',
      payload: {
        sharePendingAmount: shareAmt,
        nonShareAmount: nonShareAmt,
      },
    })
    ElMessage.success(approvalFlowTip(approval, '已提交：等审批通过后，资金退回公司总账'))
    reverseAdvanceDialog.value = false
    reverseForm.sharePendingAmount = 0
    reverseForm.nonShareAmount = 0
    reverseForm.remark = ''
    await loadDetail()
  } catch {
    // 错误提示由 request 拦截器统一弹出
  }
}

function payMethodLabel(m: any) {
  const type = m.methodTypeLabel || ({ BANK: '银行卡', ALIPAY: '支付宝', WECHAT: '微信' } as any)[m.methodType] || m.methodType
  const name = m.accountName ? `${m.accountName} · ` : ''
  const bank = m.methodType === 'BANK' && m.bankName ? `（${m.bankName}）` : ''
  return `${type} · ${name}${m.accountNo || ''}${bank}`
}

async function loadMyPayMethods() {
  try {
    myPayMethods.value = (await bizApi.myPayMethods()) || []
  } catch {
    myPayMethods.value = []
  }
}

async function openPayDialog(kind: 'reimburse' | 'salary') {
  form.amount = 0
  form.remark = ''
  // 默认待分成；若待分成为空且非分成有余额，自动切到非分成，避免预支后无法报销
  const pending = Number(account.value?.sharePendingBalance || 0)
  const nonShare = Number(account.value?.nonShareBalance || 0)
  form.fundType = pending > 0 || nonShare <= 0 ? 'SHARE_PENDING' : 'NON_SHARE'
  form.payMethodId = undefined
  voucherFiles.value = []
  await loadMyPayMethods()
  const def = myPayMethods.value.find((m) => Number(m.isDefault) === 1) || myPayMethods.value[0]
  form.payMethodId = def?.id != null ? Number(def.id) : undefined
  if (!myPayMethods.value.length) {
    ElMessage.warning('请先在员工档案中配置个人收款方式，否则无法提交')
  }
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
  if (!form.payMethodId) {
    ElMessage.warning('请选择收款方式，便于财务线下打款')
    return
  }
  if (type === 'REIMBURSE_PROJECT' && !voucherFiles.value.length) {
    ElMessage.warning('请上传发票/凭证')
    return
  }
  const bucket = fundBucketBalance(form.fundType)
  if (bucket <= 0) {
    ElMessage.warning(`${fundTypeLabel(form.fundType)}余额为 0，请换资金池或先入账`)
    return
  }
  if (form.amount > bucket) {
    ElMessage.warning(`不能超过${fundTypeLabel(form.fundType)} ¥${fmt(bucket)}（不可跨池拆扣）`)
    return
  }
  const approval = await workflowApi.submit({
    type,
    title: `${type === 'SALARY_APPLY' ? '发工资' : '项目报销'} · ${account.value?.projectName || ''}`,
    projectId: activeId.value,
    amount: form.amount,
    remark: form.remark,
    voucherFileIds: voucherFiles.value.length ? voucherFiles.value.map((f) => f.id) : undefined,
    payload: { payMethodId: form.payMethodId, fundType: form.fundType || 'SHARE_PENDING' },
  })
  const moneyHint = '审批通过并确认到账后：从所选资金池转入你的个人钱包，公司总账不变'
  ElMessage.success(`${approvalFlowTip(approval)}。${moneyHint}`)
  reimburseDialog.value = false
  salaryDialog.value = false
  form.amount = 0
  form.remark = ''
  form.payMethodId = undefined
  form.fundType = 'SHARE_PENDING'
  voucherFiles.value = []
}

onMounted(async () => {
  await loadCompanies()
  await loadList()
})
</script>

<template>
  <div class="page-stack">
    <template v-if="!activeId">
      <div class="page-top">
        <div class="page-top__main">
          <p class="page-desc">待分成 / 非分成双池；支出默认扣待分成；自然月走「自然月分成」审批。常规项目不进入本页。</p>
        </div>
      </div>
      <div class="page-card filter-card">
        <el-form :inline="true" class="filter-form" @submit.prevent>
          <el-form-item label="公司">
            <el-select v-model="filter.companyId" clearable filterable placeholder="全部公司" style="width: 200px">
              <el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="重要度">
            <el-select v-model="filter.scale" clearable placeholder="重点+重大" style="width: 140px">
              <el-option label="重点" value="KEY" />
              <el-option label="重大" value="MAJOR" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="loadList">查询</el-button>
            <el-button @click="resetFilter">重置</el-button>
          </el-form-item>
        </el-form>
      </div>
      <div v-if="list.length" class="acc-grid">
        <article
          v-for="row in list"
          :key="row.projectId"
          class="acc-card"
          role="button"
          tabindex="0"
          @click="enter(row)"
          @keyup.enter="enter(row)"
        >
          <div class="acc-card__head">
            <div class="acc-card__title-row">
              <h4>{{ row.projectName || `项目#${row.projectId}` }}</h4>
              <el-tag v-if="row.scale" :type="scaleTone(row.scale)" size="small" effect="plain">{{ scaleLabel(row.scale) }}</el-tag>
            </div>
            <p>{{ row.companyName || '—' }} · 负责人 {{ row.ownerName || '—' }}</p>
          </div>
          <div class="acc-card__balance">
            <div>
              <span>项目结余</span>
              <b>¥ {{ fmt(row.balance) }}</b>
            </div>
            <el-icon class="acc-card__icon" :size="44"><Wallet /></el-icon>
          </div>
          <div class="acc-card__meta">
            <div><span>待分成</span><b>¥ {{ fmt(row.sharePendingBalance) }}</b></div>
            <div><span>非分成</span><b>¥ {{ fmt(row.nonShareBalance) }}</b></div>
            <div><span>预留占用</span><b>¥ {{ fmt(row.reserveHeld) }}</b></div>
          </div>
        </article>
      </div>
      <el-empty v-else description="暂无项目账款" />
    </template>

    <template v-else>
      <template v-if="account">
        <div class="page-top">
          <div class="page-top__main">
            <el-button @click="back">返回列表</el-button>
            <h2 class="detail-name">{{ account.projectName }}</h2>
            <p class="page-desc">负责人 {{ account.ownerName || shareDetail?.ownerName || '—' }}</p>
          </div>
          <div class="page-actions">
            <template v-if="!account.majorShell">
              <div class="fund-swap">
                <el-button type="primary" @click="openAdvanceDialog">从公司转入</el-button>
                <el-button
                  :disabled="returnableAdvance <= 0"
                  @click="openReverseAdvanceDialog"
                >退回公司</el-button>
              </div>
              <el-button @click="openPayDialog('reimburse')">申请报销</el-button>
              <el-button @click="openPayDialog('salary')">申请发工资</el-button>
              <el-button
                type="success"
                :loading="periodSharing"
                :disabled="Number(account.sharePendingBalance || 0) <= 0"
                @click="openPeriodShareDialog"
              >自然月分成</el-button>
              <el-button
                v-if="Number(account.balance) > 0 || Number(account.reserveHeld || 0) > 0"
                @click="openRemainderDialog"
              >结余回公司</el-button>
            </template>
            <el-tag v-else type="warning" effect="plain">重大项目汇总（请进入小项目动账）</el-tag>
          </div>
        </div>

        <div class="metric-grid">
          <div class="metric-card metric-card--indigo">
            <div class="metric-body">
              <div class="metric-label">总可用</div>
              <div class="metric-value">¥ {{ fmt(account.balance) }}</div>
            </div>
            <el-icon class="metric-glyph" :size="48"><Wallet /></el-icon>
          </div>
          <div class="metric-card metric-card--violet">
            <div class="metric-body">
              <div class="metric-label">待分成</div>
              <div class="metric-value sm">¥ {{ fmt(account.sharePendingBalance) }}</div>
              <div class="metric-hint">月末分成基数</div>
            </div>
            <el-icon class="metric-glyph" :size="48"><Coin /></el-icon>
          </div>
          <div class="metric-card metric-card--cyan">
            <div class="metric-body">
              <div class="metric-label">非分成</div>
              <div class="metric-value sm">¥ {{ fmt(account.nonShareBalance) }}</div>
              <div class="metric-hint">不参与月度分成</div>
            </div>
            <el-icon class="metric-glyph" :size="48"><OfficeBuilding /></el-icon>
          </div>
          <div class="metric-card metric-card--amber">
            <div class="metric-body">
              <div class="metric-label">已支出 · 工资/报销</div>
              <div class="metric-value sm">¥ {{ fmt(account.expenseAmount) }}</div>
            </div>
            <el-icon class="metric-glyph" :size="48"><Ticket /></el-icon>
          </div>
          <div class="metric-card metric-card--slate">
            <div class="metric-body">
              <div class="metric-label">{{ account.majorShell ? '预留占用（汇总）' : '预留占用' }}</div>
              <div class="metric-value sm">¥ {{ fmt(account.reserveHeld) }}</div>
              <div v-if="!account.majorShell" class="metric-hint">结束时随结余回公司</div>
            </div>
            <el-icon class="metric-glyph" :size="48"><Box /></el-icon>
          </div>
        </div>

        <p class="rule-tip">
          <template v-if="account.majorShell">
            重大项目仅展示小项目汇总结余；请先创建小项目，再在小项目上转入/报销/发工资/分成。
          </template>
          <template v-else>
            总可用 = 待分成 + 非分成。支出默认扣待分成；对待分成按分成/预留配置走「自然月分成」审批（月份可自选），预留占用结束时随「结余回公司」退回。
          </template>
        </p>

        <div v-if="account.majorShell" class="page-card" style="margin-bottom: 16px">
          <h3 style="margin: 0 0 12px; font-size: 16px">小项目账款</h3>
          <div v-if="childAccounts.length" class="acc-grid">
            <article
              v-for="row in childAccounts"
              :key="row.projectId"
              class="acc-card"
              role="button"
              tabindex="0"
              @click="enterChild(row)"
              @keyup.enter="enterChild(row)"
            >
              <div class="acc-card__head">
                <h4>{{ row.projectName || `项目#${row.projectId}` }}</h4>
                <p>负责人 {{ row.ownerName || '—' }}</p>
              </div>
              <div class="acc-card__balance">
                <div>
                  <span>项目结余</span>
                  <b>¥ {{ fmt(row.balance) }}</b>
                </div>
              </div>
            </article>
          </div>
          <el-empty v-else description="暂无小项目，请先在项目管理中创建" />
        </div>

        <div v-if="!account.majorShell" class="page-card">
        <el-tabs v-model="tab" class="tabs">
          <el-tab-pane label="项目流水" name="overview">
            <el-table :data="ledgers" stripe empty-text="暂无流水">
              <el-table-column label="时间" width="150">
                <template #default="{ row }">{{ fmtTime(row.occurTime) }}</template>
              </el-table-column>
              <el-table-column label="编号" prop="bizNo" width="170" show-overflow-tooltip />
              <el-table-column label="类型" width="100">
                <template #default="{ row }">
                  <el-tag :type="bizTagType(row.bizType)" size="small">{{ bizLabel(row.bizType) }}</el-tag>
                </template>
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
            <div v-if="ledgerTotal > ledgerQuery.pageSize" class="page-footer">
              <el-pagination
                v-model:current-page="ledgerQuery.page"
                :page-size="ledgerQuery.pageSize"
                :total="ledgerTotal"
                layout="total, prev, pager, next"
                @current-change="loadDetail"
              />
            </div>
          </el-tab-pane>

          <el-tab-pane label="资金配置" name="share">
            <div class="share-layout">
              <section class="share-panel">
                <header class="share-panel-head">
                  <h4>资金配置</h4>
                  <p>只配「分成」和「预留」（合计 100%）。点「自然月分成」时按此比例拆待分成余额；工资/报销不占比例，默认从待分成扣。只改规则，已分/已花不变。</p>
                </header>

                <div class="percent-row two">
                  <div class="percent-item">
                    <span class="field-label">分成 %</span>
                    <el-input-number
                      v-model="shareForm.settlePercent"
                      :min="0"
                      :max="100"
                      :precision="2"
                      controls-position="right"
                    />
                    <span class="sub">已分 ¥{{ fmt(account.settleAmount) }}</span>
                  </div>
                  <div class="percent-item">
                    <span class="field-label">预留 %</span>
                    <el-input-number
                      v-model="shareForm.reservePercent"
                      :min="0"
                      :max="100"
                      :precision="2"
                      controls-position="right"
                    />
                    <span class="sub">规划额度 · 分成后进预留占用</span>
                  </div>
                </div>
                <div class="sum-line">
                  分成 {{ Number(shareForm.settlePercent || 0).toFixed(2) }}%
                  + 预留 {{ Number(shareForm.reservePercent || 0).toFixed(2) }}%
                  = 100%（联动互补；支出不占比例）
                </div>

                <div class="members-head">
                  <span class="field-label">分成人员比例</span>
                  <span class="hint">只拆「分成」那一块；自然月分成时按此比例进个人钱包</span>
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
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>
      </template>
    </template>

    <el-drawer v-model="ledgerDetailVisible" title="流水详情" size="480px" append-to-body>
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
            <template v-if="['SETTLE', 'SALARY', 'REIMBURSE'].includes(ledgerDetail.bizType) && ledgerDetail.accountType === 'PROJECT'">
              本笔从项目扣出后，进入以下个人钱包：
            </template>
            <template v-else-if="['SETTLE', 'SALARY', 'REIMBURSE'].includes(ledgerDetail.bizType) && ledgerDetail.accountType === 'WALLET'">
              同一次动账的其它流水：
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

    <el-dialog v-model="advanceDialog" title="从公司转入本项目" width="480px" :close-on-click-modal="false">
      <div class="dialog-box">
        <p>把公司总账的钱拨到这个项目的<strong>非分成资金</strong>，不参与自然月分成；之后可报销、发工资。</p>
        <ul>
          <li>公司余额：<b>¥{{ fmt(companyPoolBalance) }}</b>
            <span v-if="companyPoolLabel" class="hint">（{{ companyPoolLabel }}）</span>
          </li>
          <li>现在非分成：<b>¥{{ fmt(account?.nonShareBalance) }}</b> · 总可用：<b>¥{{ fmt(account?.balance) }}</b></li>
          <li>审批通过后：公司总账减少，本项目非分成增加。</li>
        </ul>
      </div>
      <el-form label-width="90px">
        <el-form-item label="转入金额" required>
          <el-input-number
            v-model="form.amount"
            :min="0.01"
            :precision="2"
            :max="companyPoolBalance > 0 ? companyPoolBalance : undefined"
            style="width: 200px"
          />
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="form.remark" placeholder="例如：项目启动拨款" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="advanceDialog = false">取消</el-button>
        <el-button type="primary" @click="submitAdvance">提交审批</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="remainderDialog" title="结余回公司" width="520px" :close-on-click-modal="false">
      <div class="dialog-box">
        <p>把项目结余退回公司总账；请分别填写金额（默认不填，避免误退全部）。也可点「全部填入」后改数。</p>
        <ul>
          <li>待分成：<b>¥{{ fmt(account?.sharePendingBalance) }}</b> · 非分成：<b>¥{{ fmt(account?.nonShareBalance) }}</b></li>
          <li>预留占用：<b>¥{{ fmt(account?.reserveHeld) }}</b></li>
          <li>审批通过后：按下方填写金额扣减，公司总账增加。</li>
        </ul>
      </div>
      <div style="margin-bottom: 12px">
        <el-button size="small" @click="fillRemainderAll">全部填入</el-button>
        <el-button size="small" @click="remainderForm.sharePendingAmount = 0; remainderForm.nonShareAmount = 0; remainderForm.reserveHeldAmount = 0">全部清零</el-button>
      </div>
      <el-form label-width="110px">
        <el-form-item label="待分成回公司">
          <el-input-number
            v-model="remainderForm.sharePendingAmount"
            :min="0"
            :precision="2"
            :value-on-clear="0"
            :controls="false"
            :max="Number(account?.sharePendingBalance || 0)"
            style="width: 200px"
          />
          <div class="hint">可用 ¥{{ fmt(account?.sharePendingBalance) }}</div>
        </el-form-item>
        <el-form-item label="非分成回公司">
          <el-input-number
            v-model="remainderForm.nonShareAmount"
            :min="0"
            :precision="2"
            :value-on-clear="0"
            :controls="false"
            :max="Number(account?.nonShareBalance || 0)"
            style="width: 200px"
          />
          <div class="hint">可用 ¥{{ fmt(account?.nonShareBalance) }}</div>
        </el-form-item>
        <el-form-item label="预留占用回公司">
          <el-input-number
            v-model="remainderForm.reserveHeldAmount"
            :min="0"
            :precision="2"
            :value-on-clear="0"
            :controls="false"
            :max="Number(account?.reserveHeld || 0)"
            style="width: 200px"
          />
          <div class="hint">可用 ¥{{ fmt(account?.reserveHeld) }}</div>
        </el-form-item>
        <el-form-item label="合计">
          <b>¥{{ fmt(remainderReturnTotal) }}</b>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="remainderForm.remark" placeholder="例如：项目结束结余回笼" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="remainderDialog = false">取消</el-button>
        <el-button type="primary" :loading="remainderSubmitting" @click="submitRemainderReturn">确认提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="periodShareDialog" title="自然月分成" width="560px" :close-on-click-modal="false">
      <div class="dialog-box">
        <p>按当前资金配置，把待分成余额拆成「分成进个人钱包」和「预留占用」。同一项目同一自然月只能提交一次。</p>
        <ul>
          <li>待分成总额：<b>¥{{ fmt(periodSettlePreview.pending) }}</b></li>
          <li>
            分成 {{ Number(shareForm.settlePercent || 0).toFixed(2) }}% →
            <b>¥{{ fmt(periodSettlePreview.settleAmt) }}</b>
            · 预留 {{ Number(shareForm.reservePercent || 0).toFixed(2) }}% →
            <b>¥{{ fmt(periodSettlePreview.reserveAmt) }}</b>
          </li>
        </ul>
      </div>
      <el-form label-width="90px">
        <el-form-item label="自然月" required>
          <el-date-picker
            v-model="selectedPeriodMonth"
            type="month"
            value-format="YYYY-MM"
            placeholder="选择月份"
            style="width: 200px"
          />
        </el-form-item>
      </el-form>
      <div v-if="periodMemberPreview.length" class="period-preview">
        <div class="field-label">分成明细预览</div>
        <div
          v-for="item in periodMemberPreview"
          :key="String(item.name) + item.layer"
          class="period-preview-row"
        >
          <span>{{ item.name }}<em>{{ item.layer || '成员' }} · {{ Number(item.percent).toFixed(2) }}%</em></span>
          <b>+¥ {{ fmt(item.share) }}</b>
        </div>
      </div>
      <template #footer>
        <el-button @click="periodShareDialog = false">取消</el-button>
        <el-button type="primary" :loading="periodSharing" @click="confirmPeriodShare">确认提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reverseAdvanceDialog" title="退回公司" width="520px" :close-on-click-modal="false">
      <div class="dialog-box">
        <p>把本项目<strong>公司预支未退回</strong>的资金退回公司总账；可从待分成、非分成分别填写，也可组合。</p>
        <ul>
          <li>公司预支未退回：<b>¥{{ fmt(account?.advanceAmount) }}</b></li>
          <li>待分成：<b>¥{{ fmt(account?.sharePendingBalance) }}</b> · 非分成：<b>¥{{ fmt(account?.nonShareBalance) }}</b></li>
          <li>本次最多可退：<b>¥{{ fmt(returnableAdvance) }}</b>（预支未退回与项目可用取较小值）</li>
          <li>审批通过后：所选资金池减少，公司总账增加。</li>
        </ul>
      </div>
      <el-form label-width="110px">
        <el-form-item label="待分成退回">
          <el-input-number
            v-model="reverseForm.sharePendingAmount"
            :min="0"
            :precision="2"
            :max="Number(account?.sharePendingBalance || 0)"
            style="width: 200px"
          />
          <div class="hint">可用 ¥{{ fmt(account?.sharePendingBalance) }}</div>
        </el-form-item>
        <el-form-item label="非分成退回">
          <el-input-number
            v-model="reverseForm.nonShareAmount"
            :min="0"
            :precision="2"
            :max="Number(account?.nonShareBalance || 0)"
            style="width: 200px"
          />
          <div class="hint">可用 ¥{{ fmt(account?.nonShareBalance) }}</div>
        </el-form-item>
        <el-form-item label="合计">
          <b>¥{{ fmt(reverseReturnTotal) }}</b>
          <span class="hint" style="margin-left: 8px">须 ≤ 预支未退回 ¥{{ fmt(account?.advanceAmount) }}</span>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="reverseForm.remark" placeholder="例如：多余预支退回" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reverseAdvanceDialog = false">取消</el-button>
        <el-button type="primary" @click="submitReverseAdvance">提交审批</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reimburseDialog" title="申请项目报销" width="480px" :close-on-click-modal="false">
      <div class="dialog-box">
        <p>用本项目的钱报销项目开支（如采购、差旅）。</p>
        <ul>
          <li>待分成：<b>¥{{ fmt(account?.sharePendingBalance) }}</b> · 非分成：<b>¥{{ fmt(account?.nonShareBalance) }}</b></li>
          <li>流程：上传发票提交 → 审批 → 财务回执 → 你确认到账 → <b>从所选资金池转入个人钱包</b>（公司总账不变）</li>
        </ul>
      </div>
      <el-form label-width="90px">
        <el-form-item label="扣款资金" required>
          <el-radio-group v-model="form.fundType">
            <el-radio value="SHARE_PENDING">待分成（默认）</el-radio>
            <el-radio value="NON_SHARE">非分成</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="报销金额" required>
          <el-input-number
            v-model="form.amount"
            :min="0.01"
            :precision="2"
            :max="fundBucketBalance(form.fundType) || undefined"
            style="width: 200px"
          />
          <div class="hint">当前可选余额 ¥{{ fmt(fundBucketBalance(form.fundType)) }}</div>
        </el-form-item>
        <el-form-item label="收款方式" required>
          <el-select
            v-model="form.payMethodId"
            filterable
            :placeholder="myPayMethods.length ? '选择收款方式' : '请先在员工档案配置'"
            style="width: 100%"
          >
            <el-option v-for="m in myPayMethods" :key="m.id" :label="payMethodLabel(m)" :value="Number(m.id)" />
          </el-select>
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

    <el-dialog v-model="salaryDialog" title="申请发工资" width="480px" :close-on-click-modal="false">
      <div class="dialog-box">
        <p>用本项目的钱发项目相关工资/劳务。</p>
        <ul>
          <li>待分成：<b>¥{{ fmt(account?.sharePendingBalance) }}</b> · 非分成：<b>¥{{ fmt(account?.nonShareBalance) }}</b></li>
          <li>流程：提交审批 → 财务回执 → 确认到账 → <b>从所选资金池转入个人钱包</b>（公司总账不变）</li>
        </ul>
      </div>
      <el-form label-width="90px">
        <el-form-item label="扣款资金" required>
          <el-radio-group v-model="form.fundType">
            <el-radio value="SHARE_PENDING">待分成（默认）</el-radio>
            <el-radio value="NON_SHARE">非分成</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="工资金额" required>
          <el-input-number
            v-model="form.amount"
            :min="0.01"
            :precision="2"
            :max="fundBucketBalance(form.fundType) || undefined"
            style="width: 200px"
          />
          <div class="hint">当前可选余额 ¥{{ fmt(fundBucketBalance(form.fundType)) }}</div>
        </el-form-item>
        <el-form-item label="收款方式" required>
          <el-select
            v-model="form.payMethodId"
            filterable
            :placeholder="myPayMethods.length ? '选择收款方式' : '请先在员工档案配置'"
            style="width: 100%"
          >
            <el-option v-for="m in myPayMethods" :key="m.id" :label="payMethodLabel(m)" :value="Number(m.id)" />
          </el-select>
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
.detail-name {
  margin: 10px 0 4px;
  font-size: 22px;
  font-weight: 600;
  letter-spacing: -0.03em;
  color: var(--kk-text);
}
.fund-swap {
  display: inline-flex;
  align-items: stretch;
  overflow: hidden;
  border-radius: var(--kk-radius-sm);
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.55) inset;
}
.fund-swap :deep(.el-button) {
  margin: 0;
  border: 0;
  border-radius: 0;
  height: 32px;
}
.fund-swap :deep(.el-button + .el-button) {
  margin-left: 0;
  box-shadow: inset 1px 0 0 rgba(15, 23, 42, 0.1);
}
.fund-swap :deep(.el-button.is-disabled) {
  opacity: 0.42;
}
.acc-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
}
.acc-card {
  position: relative;
  overflow: hidden;
  padding: 20px;
  cursor: pointer;
  background: var(--kk-glass-bg);
  border: 1px solid var(--kk-glass-border);
  border-radius: var(--kk-radius);
  box-shadow: var(--kk-glass-shadow);
  backdrop-filter: var(--kk-glass-blur);
  -webkit-backdrop-filter: var(--kk-glass-blur);
}
.acc-card:hover { box-shadow: 0 8px 28px rgba(0, 0, 0, 0.08); }
.acc-card:focus-visible {
  outline: 2px solid var(--kk-primary);
  outline-offset: 2px;
}
.acc-card__head h4 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--kk-text);
}
.acc-card__title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.filter-card {
  margin-bottom: 16px;
  padding: 14px 18px;
}
.filter-form {
  margin: 0;
}
.filter-form :deep(.el-form-item) {
  margin-bottom: 0;
}
.acc-card__head p {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--kk-text-muted);
}
.acc-card__balance {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 16px 0 14px;
}
.acc-card__balance span {
  display: block;
  font-size: 13px;
  color: var(--kk-text-secondary);
}
.acc-card__balance b {
  display: block;
  margin-top: 4px;
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
  color: var(--kk-text);
}
.acc-card__icon { color: var(--kk-primary); flex-shrink: 0; }
.acc-card__meta {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 10px;
  padding-top: 12px;
  border-top: 1px solid rgba(0, 0, 0, 0.06);
  font-size: 12px;
  color: var(--kk-text-secondary);
}
.acc-card__meta b {
  display: block;
  margin-top: 4px;
  font-size: 13px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--kk-text);
}
.metric-grid {
  display: grid;
  grid-template-columns: 1.25fr repeat(4, minmax(0, 1fr));
  gap: 14px;
}
.metric-card {
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-height: 96px;
  padding: 16px 14px 16px 18px;
  background: var(--kk-glass-bg);
  border: 1px solid var(--kk-glass-border);
  border-radius: var(--kk-radius);
  box-shadow: var(--kk-glass-shadow);
  backdrop-filter: var(--kk-glass-blur);
  -webkit-backdrop-filter: var(--kk-glass-blur);
}
.metric-card::before {
  content: "";
  position: absolute;
  right: -24px;
  top: 50%;
  width: 100px;
  height: 100px;
  border-radius: 50%;
  transform: translateY(-50%);
  filter: blur(32px);
  opacity: 0.22;
  pointer-events: none;
}
.metric-card--indigo::before { background: #d4d4d8; }
.metric-card--cyan::before { background: #a5f3fc; }
.metric-card--violet::before { background: #ddd6fe; }
.metric-card--amber::before { background: #fde68a; }
.metric-card--slate::before { background: #e2e8f0; }
.metric-card--indigo .metric-glyph { color: var(--kk-primary); }
.metric-card--cyan .metric-glyph { color: #0891b2; }
.metric-card--violet .metric-glyph { color: #7c3aed; }
.metric-card--amber .metric-glyph { color: #d97706; }
.metric-card--slate .metric-glyph { color: #64748b; }
.metric-body { position: relative; z-index: 1; min-width: 0; }
.metric-glyph { position: relative; z-index: 1; flex-shrink: 0; opacity: 1; }
.metric-label { font-size: 12px; font-weight: 500; color: var(--kk-text-secondary); }
.metric-value {
  margin-top: 6px;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
  color: var(--kk-text);
}
.metric-value.sm { font-size: 18px; }
.metric-hint { margin-top: 4px; font-size: 12px; color: var(--kk-text-muted); }
.rule-tip {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--kk-text-secondary);
}
.dialog-box {
  margin: 0 0 14px;
  padding: 12px 14px;
  background: rgba(255, 255, 255, 0.45);
  border-radius: var(--kk-radius-sm);
  color: var(--kk-text-secondary);
  font-size: 13px;
  line-height: 1.6;
}
.dialog-box p { margin: 0 0 8px; }
.dialog-box ul { margin: 0; padding-left: 18px; }
.dialog-box li { margin: 4px 0; }
.dialog-box b { color: var(--kk-text); }
.voucher-box { width: 100%; }
.voucher-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 6px;
  padding: 6px 8px;
  background: rgba(255, 255, 255, 0.45);
  border-radius: 8px;
  font-size: 13px;
}
.voucher-item a { color: var(--kk-primary); word-break: break-all; }
.tabs { margin-top: 0; }
.hint { color: var(--kk-text-muted); font-size: 12px; }
.period-preview {
  margin-top: 8px;
  border: 1px solid #eef2f7;
  border-radius: 10px;
  overflow: hidden;
}
.period-preview .field-label {
  padding: 10px 12px 0;
}
.period-preview-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  font-size: 13px;
  color: #334155;
  border-top: 1px solid #f1f5f9;
}
.period-preview-row em {
  margin-left: 6px;
  font-style: normal;
  color: #94a3b8;
  font-size: 12px;
}
.period-preview-row b {
  color: #16a34a;
  font-weight: 600;
  white-space: nowrap;
}
.field-label {
  display: block;
  font-size: 13px;
  color: var(--kk-text);
  font-weight: 500;
  margin-bottom: 6px;
}
.share-layout {
  display: block;
}
.share-panel {
  background: var(--kk-glass-bg);
  border: 1px solid var(--kk-glass-border);
  border-radius: var(--kk-radius);
  box-shadow: var(--kk-glass-shadow);
  backdrop-filter: var(--kk-glass-blur);
  -webkit-backdrop-filter: var(--kk-glass-blur);
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
  background: rgba(255, 255, 255, 0.35);
  border-radius: var(--kk-radius-sm);
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
.foot-left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.in { color: var(--kk-success); font-weight: 600; font-variant-numeric: tabular-nums; }
.out { color: var(--kk-danger); font-weight: 600; font-variant-numeric: tabular-nums; }
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
@media (max-width: 1280px) {
  .metric-grid { grid-template-columns: 1fr 1fr; }
  .acc-card__meta { grid-template-columns: 1fr 1fr; }
}
@media (max-width: 1100px) {
  .percent-row { grid-template-columns: 1fr; }
}
@media (max-width: 720px) {
  .metric-grid,
  .acc-grid { grid-template-columns: 1fr; }
}
@media (prefers-reduced-transparency: reduce) {
  .acc-card,
  .metric-card,
  .share-panel {
    background: #fff;
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}
</style>
