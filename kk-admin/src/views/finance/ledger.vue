<script setup lang="ts">
import { onMounted, reactive, ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { bizApi } from '@/api/biz'
import { sysApi } from '@/api/system'
import { workflowApi } from '@/api/workflow'
import { approvalFlowTip } from '@/utils/approvalTip'
import ProjectCascadeSelect from '@/components/project/ProjectCascadeSelect.vue'

const query = reactive({
  page: 1,
  pageSize: 20,
  companyId: undefined as number | undefined,
  bizType: '',
  channelId: undefined as number | undefined,
  keyword: '',
  minAmount: undefined as number | undefined,
  maxAmount: undefined as number | undefined,
  dateRange: [] as string[],
})
const rawList = ref<any[]>([])
const summary = ref<any>({})
const pools = ref<any[]>([])
const channels = ref<any[]>([])
const projects = ref<any[]>([])
const dialog = ref(false)
const payoutDialog = ref(false)
const payoutSaving = ref(false)
const payoutUsers = ref<any[]>([])
const payoutProjectDetail = ref<any>(null)
const payoutForm = reactive({
  sourceType: 'COMPANY' as 'COMPANY' | 'PROJECT',
  poolId: undefined as number | undefined,
  projectId: undefined as number | undefined,
  fundType: 'SHARE_PENDING' as string,
  payeeUserId: undefined as number | undefined,
  amount: undefined as number | undefined,
  remark: '',
})
const uploading = ref(false)
const listLoading = ref(false)
const saving = ref(false)
const thresholdDialog = ref(false)
const thresholdSaving = ref(false)
const taxDialog = ref(false)
const taxSaving = ref(false)
const companies = ref<any[]>([])
const thresholdForm = reactive({
  companyId: undefined as number | undefined,
  enabled: false,
  notifyThreshold: 5000,
  approveThreshold: 30000,
})
const taxForm = reactive({
  companyId: undefined as number | undefined,
  defaultTaxRate: 0.2,
  taxMode: 'FLAT' as string,
  tiers: [] as Array<{ minAmount: number; maxAmount: number | undefined; taxRatePercent: number }>,
  trialAmount: 8000,
  trialTax: 0,
  trialNet: 0,
  trialBreakdown: [] as any[],
})
const activeThreshold = ref<any>({ enabled: 0, notifyThreshold: 5000, approveThreshold: 30000 })

const form = reactive<any>({
  bizType: 'INCOME',
  poolId: undefined,
  channelId: undefined,
  projectId: undefined,
  fundType: 'SHARE_PENDING',
  amount: 0,
  feeMode: '' as string,
  feeValue: undefined as number | undefined,
  title: '',
  remark: '',
  voucherFileIds: [] as number[],
})
const voucherFiles = ref<any[]>([])
const detailVisible = ref(false)
const detailRow = ref<LedgerRow | null>(null)
const companyBalanceVisible = ref(false)
const projectBalanceVisible = ref(false)
const walletBalanceVisible = ref(false)
const walletBalances = ref<any[]>([])
const walletBalancesLoading = ref(false)
const assetsDetailVisible = ref(false)

const companyBalances = computed(() => {
  const fromApi = summary.value?.companyBalances
  if (Array.isArray(fromApi) && fromApi.length) {
    return fromApi.filter((r: any) => Number(r.poolCount ?? 0) > 0 || Number(r.balance ?? r.poolBalance ?? 0) !== 0)
  }
  const poolSource = (Array.isArray(summary.value?.pools) && summary.value.pools.length)
    ? summary.value.pools
    : pools.value
  return aggregateCompanyPoolBalances(poolSource)
})

const projectBalances = computed(() => {
  const fromApi = summary.value?.projectBalances
  if (Array.isArray(fromApi)) return fromApi
  return []
})

const companyAssets = computed(() => {
  const fromApi = summary.value?.companyAssets
  if (Array.isArray(fromApi) && fromApi.length) {
    return fromApi.map((r: any) => ({
      ...r,
      poolBalance: Number(r.poolBalance ?? r.balance ?? 0),
      projectBalance: Number(r.projectBalance ?? 0),
      companyProjectTotal: Number(r.companyProjectTotal
        ?? (Number(r.poolBalance ?? r.balance ?? 0) + Number(r.projectBalance ?? 0))),
    }))
  }
  const balances = companyBalances.value
  if (!balances.length) return []
  return balances.map((r: any) => {
    const poolBalance = Number(r.poolBalance ?? r.balance ?? 0)
    const projectBalance = Number(r.projectBalance ?? 0)
    return {
      ...r,
      poolBalance,
      projectBalance,
      companyProjectTotal: poolBalance + projectBalance,
    }
  })
})

const assetsCompanyPoolSum = computed(() =>
  companyAssets.value.reduce((s: number, r: any) => s + Number(r.poolBalance ?? 0), 0),
)
const assetsCompanyProjectSum = computed(() =>
  companyAssets.value.reduce((s: number, r: any) => s + Number(r.projectBalance ?? 0), 0),
)

/** 从资金池列表按公司汇总（后端未下发明细时的兜底） */
function aggregateCompanyPoolBalances(poolList: any[] | undefined) {
  if (!Array.isArray(poolList) || !poolList.length) return []
  const map = new Map<number, { companyId: number; companyName?: string; balance: number; poolCount: number }>()
  for (const p of poolList) {
    const cid = p?.companyId
    if (cid == null) continue
    const id = Number(cid)
    const row = map.get(id) || {
      companyId: id,
      companyName: p.companyName,
      balance: 0,
      poolCount: 0,
    }
    row.balance += Number(p.balance ?? 0)
    row.poolCount += 1
    if (!row.companyName && p.companyName) row.companyName = p.companyName
    map.set(id, row)
  }
  return [...map.values()].sort((a, b) => b.balance - a.balance)
}

interface LedgerRow {
  key: string
  occurTime: string
  bizNo?: string
  bizType: string
  title: string
  projectName?: string
  channelName?: string
  companyId?: number
  companyName?: string
  amount: number
  grossAmount?: number
  feeAmount?: number
  afterBalance?: number
  counterpart: string
  vouchers: any[]
}

const TYPE_LABEL: Record<string, string> = {
  INCOME: '入账',
  EXPENSE: '出账',
  TRANSFER: '划拨',
  SETTLE: '项目分钱',
  ADVANCE: '项目预支',
  RESERVE: '预留',
  REIMBURSE: '报销',
  SALARY: '工资',
  ROLLBACK: '回退',
  FEE: '手续费',
  PAYOUT: '发钱入钱包',
}

const TYPE_TAG: Record<string, string> = {
  INCOME: 'success',
  EXPENSE: 'danger',
  TRANSFER: 'warning',
  SETTLE: 'primary',
  ADVANCE: '',
  RESERVE: 'info',
  REIMBURSE: 'warning',
  SALARY: 'success',
  PAYOUT: 'primary',
  ROLLBACK: 'danger',
}

function emptyForm() {
  const poolId = pools.value.find((p) => p.isDefault === 1)?.id ?? pools.value[0]?.id
  Object.assign(form, {
    bizType: 'INCOME',
    poolId,
    channelId: pickChannelForPool(poolId),
    projectId: undefined,
    fundType: 'SHARE_PENDING',
    amount: 0,
    feeMode: '',
    feeValue: undefined,
    title: '',
    remark: '',
    voucherFileIds: [],
  })
  voucherFiles.value = []
}

/** 登记弹窗：仅展示当前资金池下的收款渠道 */
const formChannels = computed(() => {
  if (form.poolId == null) return channels.value
  return channels.value.filter((c) => Number(c.poolId) === Number(form.poolId))
})

function pickChannelForPool(poolId?: number | null) {
  if (poolId == null) return undefined
  const matched = channels.value.filter((c) => Number(c.poolId) === Number(poolId))
  return matched[0]?.id
}

function syncFormChannel() {
  const ok = formChannels.value.some((c) => Number(c.id) === Number(form.channelId))
  if (!ok) {
    form.channelId = pickChannelForPool(form.poolId)
  }
}

function bizLabel(v: string) {
  return TYPE_LABEL[v] || v
}

function bizTagType(v: string) {
  return TYPE_TAG[v] || 'info'
}

function fmtTime(t?: string) {
  if (!t) return '—'
  return t.replace('T', ' ').slice(0, 16)
}

function fmtMoney(n?: number, signed = false) {
  const v = Number(n ?? 0)
  const abs = Math.abs(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  if (!signed) return `¥${abs}`
  if (v > 0) return `+¥${abs}`
  if (v < 0) return `-¥${abs}`
  return `¥${abs}`
}

function amountClass(n?: number) {
  const v = Number(n ?? 0)
  if (v > 0) return 'amt-in'
  if (v < 0) return 'amt-out'
  return ''
}

function cleanTitle(row: any) {
  const raw = String(row.title || '')
  if (row.bizType === 'SETTLE') {
    return row.projectName ? `项目分钱 · ${row.projectName}` : '项目分钱'
  }
  if (row.bizType === 'TRANSFER') {
    return raw.includes('个人') ? '划拨个人' : (raw || '资金划拨')
  }
  if (row.bizType === 'ADVANCE') {
    return row.projectName ? `预支到项目 · ${row.projectName}` : (raw || '项目预支')
  }
  return raw
    .replace(/^项目预设分钱扣款：/, '项目分钱 · ')
    .replace(/^财务手动分钱扣款：/, '项目分钱 · ')
    .replace(/^项目预支扣款$/, '项目预支')
    .replace(/^个人报销扣款$/, '个人报销')
    || '—'
}

function resolveLedgerCompany(row: any): { companyId?: number; companyName?: string } {
  const companyId = ledgerCompanyId(row)
  if (companyId == null) return {}
  const companyName = row.companyName
    || companies.value.find((c) => Number(c.id) === companyId)?.name
    || pools.value.find((p) => Number(p.companyId) === companyId)?.companyName
    || undefined
  return { companyId, companyName }
}

function counterpartLabel(row: any) {
  if (row?.userName) return `个人 · ${row.userName}`
  if (row?.projectName) return row.projectName
  if (row?.userId != null) return `个人#${row.userId}`
  return '—'
}

/** 只展示公司侧流水，同一笔业务合并成一行 */
function buildCompanyRows(rows: any[]): LedgerRow[] {
  const poolRows = rows.filter((r) => r.accountType === 'POOL')
  const byBatch = new Map<number, any[]>()
  const alone: any[] = []

  for (const row of poolRows) {
    if (row.relatedId) {
      const list = byBatch.get(row.relatedId) || []
      list.push(row)
      byBatch.set(row.relatedId, list)
    } else {
      alone.push(row)
    }
  }

  const result: LedgerRow[] = []

  for (const row of alone) {
    const company = resolveLedgerCompany(row)
    result.push({
      key: `s-${row.id}`,
      occurTime: row.occurTime,
      bizNo: row.bizNo,
      bizType: row.bizType,
      title: cleanTitle(row),
      projectName: row.projectName,
      companyId: company.companyId,
      companyName: company.companyName,
      amount: Number(row.amount),
      afterBalance: Number(row.afterBalance),
      counterpart: counterpartLabel(row),
      vouchers: row.vouchers || [],
    })
  }

  for (const [batchId, poolItems] of byBatch) {
    const head = [...poolItems].sort((a, b) => a.id - b.id)[0]
    // 关联钱包：relatedId 指向本批，或钱包行自身 id == 本批（提现先写钱包再挂公司）
    const relatedWallets = rows.filter(
      (r) =>
        r.accountType === 'WALLET'
        && Math.abs(Number(r.amount)) > 0
        && (Number(r.relatedId) === Number(batchId) || Number(r.id) === Number(batchId)),
    )
    let counterpart = counterpartLabel(head)
    if (relatedWallets.length) {
      counterpart = relatedWallets
        .map((w) => `${w.userName || '个人'}${Number(w.amount) > 0 ? '+' : ''}${fmtMoney(w.amount).replace('¥', '')}`)
        .join('、')
    } else if (head.bizType === 'ADVANCE' || head.bizType === 'RESERVE') {
      counterpart = head.projectName ? `项目 · ${head.projectName}` : '项目'
    }
    const company = resolveLedgerCompany(head)
    result.push({
      key: `b-${batchId}`,
      occurTime: head.occurTime,
      bizNo: head.bizNo,
      bizType: head.bizType,
      title: cleanTitle(head),
      projectName: head.projectName,
      companyId: company.companyId,
      companyName: company.companyName,
      amount: Number(head.amount),
      afterBalance: Number(head.afterBalance),
      counterpart,
      vouchers: head.vouchers || [],
    })
  }

  return result.sort((a, b) => String(b.occurTime).localeCompare(String(a.occurTime)))
}

function ledgerCompanyId(row: any): number | undefined {
  if (row?.companyId != null) return Number(row.companyId)
  const pool = pools.value.find((p) => Number(p.id) === Number(row?.poolId))
  if (pool?.companyId != null) return Number(pool.companyId)
  return undefined
}

function companyLabel(row: { companyId?: number; companyName?: string } | null | undefined) {
  if (!row) return '—'
  if (row.companyName) return row.companyName
  if (row.companyId != null) return `公司#${row.companyId}`
  return '—'
}

const filteredChannels = computed(() => {
  if (query.companyId == null) return channels.value
  const cid = Number(query.companyId)
  return channels.value.filter((c) => {
    if (c.companyId != null) return Number(c.companyId) === cid
    const pool = pools.value.find((p) => Number(p.id) === Number(c.poolId))
    return pool != null && Number(pool.companyId) === cid
  })
})

const companyRows = computed(() => buildCompanyRows(rawList.value))
const pagedRows = computed(() => {
  const start = (query.page - 1) * query.pageSize
  return companyRows.value.slice(start, start + query.pageSize)
})

function onCompanyChange() {
  if (query.channelId != null && !filteredChannels.value.some((c) => Number(c.id) === Number(query.channelId))) {
    query.channelId = undefined
  }
  onFilter()
}

async function load() {
  listLoading.value = true
  try {
    const dateRange = query.dateRange || []
    const res = await bizApi.ledgerPage({
      page: 1,
      pageSize: 500,
      accountType: 'POOL',
      companyId: query.companyId,
      bizType: query.bizType || undefined,
      channelId: query.channelId,
      keyword: query.keyword || undefined,
      minAmount: query.minAmount,
      maxAmount: query.maxAmount,
      startTime: dateRange[0] ? `${dateRange[0]} 00:00:00` : undefined,
      endTime: dateRange[1] ? `${dateRange[1]} 23:59:59` : undefined,
    })
    let poolList = res.list || []
    // 兼容旧后端未接 companyId：再按公司兜底过滤一次
    if (query.companyId != null) {
      const cid = Number(query.companyId)
      poolList = poolList.filter((r: any) => ledgerCompanyId(r) === cid)
    }
    rawList.value = poolList
    const needWallet = !query.bizType || ['SETTLE', 'TRANSFER', 'EXPENSE', 'REIMBURSE', 'SALARY', 'PAYOUT', 'ROLLBACK', 'WITHDRAW'].includes(query.bizType)
    const relatedIds = needWallet
      ? [...new Set(
          poolList
            .map((r: any) => Number(r.relatedId))
            .filter((id: number) => Number.isFinite(id) && id > 0),
        )]
      : []
    if (relatedIds.length) {
      const walletRes = await bizApi.ledgerPage({
        page: 1,
        pageSize: 500,
        accountType: 'WALLET',
        bizType: undefined,
      })
      const need = new Set(relatedIds)
      // relatedId 命中，或钱包行 id 本身就是批次号（提现先写钱包再挂公司）
      const extras = (walletRes.list || []).filter((r: any) => {
        const rid = Number(r.relatedId)
        const id = Number(r.id)
        return need.has(rid) || need.has(id)
      })
      rawList.value = [...poolList, ...extras]
    }
    query.page = 1
  } catch {
    // 拦截器已提示；保留当前列表避免空白闪烁
  } finally {
    listLoading.value = false
  }
}

async function loadSummary() {
  try {
    summary.value = await bizApi.summary()
    // 明细兜底：summary.pools 若缺公司名，用已加载的资金池补齐
    if ((!summary.value?.companyBalances || !summary.value.companyBalances.length)
        && Array.isArray(summary.value?.pools)
        && summary.value.pools.length
        && !pools.value.length) {
      try {
        pools.value = await bizApi.poolList()
      } catch {
        /* ignore */
      }
    }
    if (Array.isArray(summary.value?.pools) && summary.value.pools.length) {
      const nameById = new Map(pools.value.map((p: any) => [Number(p.id), p.companyName]))
      const companyNameByCompanyId = new Map(
        pools.value
          .filter((p: any) => p.companyId != null && p.companyName)
          .map((p: any) => [Number(p.companyId), p.companyName]),
      )
      for (const p of summary.value.pools) {
        if (!p.companyName) {
          p.companyName = nameById.get(Number(p.id)) || companyNameByCompanyId.get(Number(p.companyId))
        }
      }
    }
  } catch {
    summary.value = {}
  }
}

function openCompanyBalances() {
  companyBalanceVisible.value = true
}

function openProjectBalances() {
  projectBalanceVisible.value = true
}

function walletDisplayName(row: any) {
  return row.nickname || row.realName || row.username || (row.userId != null ? `用户#${row.userId}` : '—')
}

async function openWalletBalances() {
  walletBalanceVisible.value = true
  walletBalancesLoading.value = true
  try {
    // 优先用总账 summary（与公司/项目明细同权限），避免缺 wallet:list 时空白
    let list: any[] = Array.isArray(summary.value?.walletBalances)
      ? summary.value.walletBalances
      : []
    if (!list.length) {
      try {
        await loadSummary()
        list = Array.isArray(summary.value?.walletBalances) ? summary.value.walletBalances : []
      } catch {
        /* ignore, try walletPage */
      }
    }
    if (!list.length) {
      const res = await bizApi.walletPage({ page: 1, pageSize: 500 })
      list = res.list || []
    }
    walletBalances.value = list
      .map((r: any) => ({
        ...r,
        balance: Number(r.balance || 0),
        frozen: Number(r.frozen || 0),
        available: Number(r.available ?? (Number(r.balance || 0) - Number(r.frozen || 0))),
      }))
      .filter((r: any) => r.balance !== 0 || r.frozen !== 0)
      .sort((a: any, b: any) => b.balance - a.balance || b.frozen - a.frozen)
  } catch (e: any) {
    walletBalances.value = []
    ElMessage.error(e?.message || '加载个人钱包明细失败')
  } finally {
    walletBalancesLoading.value = false
  }
}

function openAssetsDetail() {
  assetsDetailVisible.value = true
}

function assetsCompanySummary({ columns }: { columns: any[]; data: any[] }) {
  return columns.map((col, index) => {
    if (index === 0) return '合计'
    const key = col.property
    if (key === 'poolBalance') return fmtMoney(assetsCompanyPoolSum.value)
    if (key === 'projectBalance') return fmtMoney(assetsCompanyProjectSum.value)
    if (key === 'companyProjectTotal') {
      return fmtMoney(assetsCompanyPoolSum.value + assetsCompanyProjectSum.value)
    }
    return ''
  })
}

function openDialog() {
  emptyForm()
  void ensureFormOptions()
  dialog.value = true
}

function emptyPayoutForm() {
  payoutForm.sourceType = 'COMPANY'
  payoutForm.poolId = undefined
  payoutForm.projectId = undefined
  payoutForm.fundType = 'SHARE_PENDING'
  payoutForm.payeeUserId = undefined
  payoutForm.amount = undefined
  payoutForm.remark = ''
  payoutProjectDetail.value = null
}

async function openPayoutDialog() {
  emptyPayoutForm()
  await ensureFormOptions()
  payoutForm.poolId = pools.value.find((p) => p.isDefault === 1)?.id ?? pools.value[0]?.id
  await loadPayoutUsers()
  payoutDialog.value = true
}

const payoutPool = computed(() => pools.value.find((p) => p.id === payoutForm.poolId))
const payoutCompanyId = computed(() => {
  if (payoutForm.sourceType === 'COMPANY') return payoutPool.value?.companyId as number | undefined
  return payoutProjectDetail.value?.companyId as number | undefined
})
const payoutPoolBalance = computed(() => Number(payoutPool.value?.balance || 0))
const payoutFundBalance = computed(() => {
  const d = payoutProjectDetail.value
  if (!d) return 0
  return payoutForm.fundType === 'NON_SHARE'
    ? Number(d.nonShareBalance || 0)
    : Number(d.sharePendingBalance || 0)
})

async function loadPayoutUsers() {
  const cid = payoutCompanyId.value
  payoutUsers.value = await sysApi.userList(cid != null ? { companyId: Number(cid) } : undefined)
}

async function onPayoutSourceChange() {
  payoutForm.projectId = undefined
  payoutProjectDetail.value = null
  if (payoutForm.sourceType === 'COMPANY' && !payoutForm.poolId) {
    payoutForm.poolId = pools.value.find((p) => p.isDefault === 1)?.id ?? pools.value[0]?.id
  }
  await loadPayoutUsers()
}

async function onPayoutPoolChange() {
  await loadPayoutUsers()
}

async function onPayoutProjectChange(projectId?: number) {
  payoutProjectDetail.value = null
  if (!projectId) return
  try {
    payoutProjectDetail.value = await bizApi.projectAccountDetail(projectId)
    const pending = Number(payoutProjectDetail.value?.sharePendingBalance || 0)
    const nonShare = Number(payoutProjectDetail.value?.nonShareBalance || 0)
    payoutForm.fundType = pending > 0 || nonShare <= 0 ? 'SHARE_PENDING' : 'NON_SHARE'
    await loadPayoutUsers()
  } catch (e: any) {
    ElMessage.error(e?.message || '加载项目账款失败')
  }
}

async function submitPayout() {
  if (!payoutForm.payeeUserId) {
    ElMessage.warning('请选择收款人')
    return
  }
  const amount = Number(payoutForm.amount)
  if (!amount || amount <= 0) {
    ElMessage.warning('请填写发钱金额')
    return
  }
  if (payoutForm.sourceType === 'COMPANY') {
    if (!payoutForm.poolId) {
      ElMessage.warning('请选择公司资金池')
      return
    }
    if (amount > payoutPoolBalance.value) {
      ElMessage.warning(`不能超过公司余额 ¥${payoutPoolBalance.value.toFixed(2)}`)
      return
    }
  } else {
    if (!payoutForm.projectId) {
      ElMessage.warning('请选择项目')
      return
    }
    if (amount > payoutFundBalance.value) {
      ElMessage.warning(`不能超过所选项目资金 ¥${payoutFundBalance.value.toFixed(2)}`)
      return
    }
  }
  const payee = payoutUsers.value.find((u) => u.id === payoutForm.payeeUserId)
  const payeeName = payee?.nickname || payee?.username || `#${payoutForm.payeeUserId}`
  payoutSaving.value = true
  try {
    const approval = await workflowApi.submit({
      type: 'DIRECT_PAYOUT',
      title: `财务发钱 · ${payeeName}`,
      amount,
      poolId: payoutForm.sourceType === 'COMPANY' ? payoutForm.poolId : undefined,
      projectId: payoutForm.sourceType === 'PROJECT' ? payoutForm.projectId : undefined,
      companyId: payoutCompanyId.value,
      remark: payoutForm.remark || undefined,
      payload: {
        sourceType: payoutForm.sourceType,
        payeeUserId: payoutForm.payeeUserId,
        fundType: payoutForm.sourceType === 'PROJECT' ? payoutForm.fundType : undefined,
      },
    })
    ElMessage.success(`${approvalFlowTip(approval)}。审批通过后将转入收款人钱包`)
    payoutDialog.value = false
    await Promise.all([load(), loadSummary()])
  } finally {
    payoutSaving.value = false
  }
}

async function ensureFormOptions() {
  const jobs: Promise<any>[] = []
  if (!projects.value.length) jobs.push(bizApi.projectList().then((r) => { projects.value = r }))
  if (!pools.value.length) jobs.push(bizApi.poolList().then((r) => { pools.value = r }))
  if (!channels.value.length) jobs.push(bizApi.payChannelList({ all: false }).then((r) => { channels.value = r }))
  if (jobs.length) await Promise.all(jobs)
  if (!form.poolId) {
    form.poolId = pools.value.find((p) => p.isDefault === 1)?.id ?? pools.value[0]?.id
  }
  syncFormChannel()
}

async function onUploadVoucher(options: any) {
  uploading.value = true
  try {
    const file = await bizApi.uploadLedgerVoucher(options.file)
    voucherFiles.value.push(file)
    form.voucherFileIds.push(file.id)
    ElMessage.success('凭证上传成功')
    options.onSuccess?.(file)
  } catch (e: any) {
    ElMessage.error(e.message || '凭证上传失败')
    options.onError?.(e)
  } finally {
    uploading.value = false
  }
}

function removeVoucher(index: number) {
  voucherFiles.value.splice(index, 1)
  form.voucherFileIds.splice(index, 1)
}

function previewVoucher(file: any) {
  window.open(fileUrl(file), '_blank')
}

function fileUrl(file: any) {
  if (file?.id != null) return `/api/file/preview/${file.id}`
  const url = String(file?.url || '')
  if (url.includes('/api/file/download/')) {
    const id = url.split('/').pop()
    if (id) return `/api/file/preview/${id}`
  }
  return url || ''
}

function isImage(file: any) {
  const name = String(file?.originalName || file?.name || file?.url || '').toLowerCase()
  return /\.(png|jpe?g|gif|webp|bmp)$/.test(name) || String(file?.contentType || '').startsWith('image/')
}

function openDetail(row: LedgerRow) {
  detailRow.value = row
  detailVisible.value = true
}

async function save() {
  if (!form.amount || form.amount <= 0) {
    ElMessage.warning('请输入有效金额')
    return
  }
  if (!['INCOME', 'EXPENSE'].includes(form.bizType)) {
    ElMessage.warning('仅支持公司入账或出账')
    return
  }
  if (form.bizType === 'INCOME' && !form.channelId) {
    ElMessage.warning('入账请选择收款渠道')
    return
  }
  if (form.bizType === 'INCOME' && form.channelId && !formChannels.value.some((c) => Number(c.id) === Number(form.channelId))) {
    ElMessage.warning('收款渠道与资金池不匹配，请重新选择')
    syncFormChannel()
    return
  }
  if (!form.poolId) {
    ElMessage.warning('请选择资金池')
    return
  }
  if (form.bizType === 'INCOME' && form.projectId && !form.fundType) {
    ElMessage.warning('关联项目时请选择资金类型')
    return
  }
  saving.value = true
  try {
    const res = await bizApi.registerLedger({
      bizType: form.bizType,
      accountType: 'POOL',
      poolId: form.poolId,
      channelId: form.bizType === 'INCOME' ? form.channelId : undefined,
      projectId: form.projectId,
      fundType: form.bizType === 'INCOME' && form.projectId ? form.fundType : undefined,
      amount: form.amount,
      feeMode: form.bizType === 'INCOME' && form.feeMode ? form.feeMode : undefined,
      feeValue: form.bizType === 'INCOME' && form.feeMode ? form.feeValue : undefined,
      title: form.title,
      remark: form.remark,
      voucherFileIds: form.voucherFileIds.length ? [...form.voucherFileIds] : undefined,
    })
    if (res.mode === 'DIRECT') {
      ElMessage.success(res.message || '已直接入账')
    } else {
      ElMessage.success(res.message || `${approvalFlowTip(res.approval)}。通过后才会入账`)
    }
    dialog.value = false
    await Promise.all([load(), loadSummary()])
  } finally {
    saving.value = false
  }
}

const selectedPoolCompanyId = computed(() => {
  const pool = pools.value.find((p) => p.id === form.poolId)
  return pool?.companyId as number | undefined
})

const registerHint = computed(() => {
  if (form.bizType === 'INCOME') return '入账将提交财务审批，通过后才会入账'
  const th = activeThreshold.value
  const enabled = Number(th?.enabled) === 1
  if (!th || !enabled) return '出账阈值未启用，将提交财务审批'
  const amount = Number(form.amount) || 0
  const notify = Number(th.notifyThreshold) || 0
  const approve = Number(th.approveThreshold) || 0
  if (amount >= approve) return `金额 ≥ ${approve}：需全体股东会签，通过后才会出账`
  if (amount >= notify) return `金额 ≥ ${notify}：将直接出账，并通知该公司股东`
  return `金额 < ${notify}：将直接出账`
})

const submitBtnLabel = computed(() => {
  if (form.bizType === 'INCOME') return '提交审批'
  const th = activeThreshold.value
  const enabled = Number(th?.enabled) === 1
  if (!th || !enabled) return '提交审批'
  const amount = Number(form.amount) || 0
  const approve = Number(th.approveThreshold) || 0
  return amount >= approve ? '提交股东会签' : '确认出账'
})

async function refreshActiveThreshold() {
  const cid = selectedPoolCompanyId.value
  if (!cid) {
    activeThreshold.value = { enabled: 0, notifyThreshold: 5000, approveThreshold: 30000 }
    return
  }
  try {
    activeThreshold.value = await bizApi.getLedgerThreshold(cid)
  } catch {
    activeThreshold.value = { enabled: 0, notifyThreshold: 5000, approveThreshold: 30000 }
  }
}

watch([() => form.poolId, () => form.bizType, () => dialog.value], () => {
  if (dialog.value) {
    syncFormChannel()
    void refreshActiveThreshold()
  }
})

async function openThresholdDialog() {
  if (!companies.value.length) {
    companies.value = await sysApi.myCompanies()
  }
  if (!thresholdForm.companyId && companies.value.length) {
    thresholdForm.companyId = companies.value[0].id
  }
  await loadThresholdForm()
  thresholdDialog.value = true
}

async function loadThresholdForm() {
  if (!thresholdForm.companyId) return
  const row = await bizApi.getLedgerThreshold(thresholdForm.companyId)
  thresholdForm.enabled = Number(row.enabled) === 1
  thresholdForm.notifyThreshold = Number(row.notifyThreshold ?? 5000)
  thresholdForm.approveThreshold = Number(row.approveThreshold ?? 30000)
}

async function onThresholdCompanyChange() {
  await loadThresholdForm()
  await refreshThresholdBadge()
}

async function saveThreshold() {
  if (!thresholdForm.companyId) {
    ElMessage.warning('请选择公司')
    return
  }
  if (thresholdForm.enabled && Number(thresholdForm.notifyThreshold) > Number(thresholdForm.approveThreshold)) {
    ElMessage.warning('通知线不能大于审批线')
    return
  }
  thresholdSaving.value = true
  try {
    await bizApi.saveLedgerThreshold({
      companyId: Number(thresholdForm.companyId),
      enabled: !!thresholdForm.enabled,
      notifyThreshold: Number(thresholdForm.notifyThreshold),
      approveThreshold: Number(thresholdForm.approveThreshold),
    })
    await loadThresholdForm()
    await refreshThresholdBadge()
    if (dialog.value) await refreshActiveThreshold()
    if (thresholdForm.enabled) {
      ElMessage.success(
        `已保存并启用：通知线 ${thresholdForm.notifyThreshold} / 审批线 ${thresholdForm.approveThreshold}。可在本弹窗随时查看或修改。`,
      )
    } else {
      ElMessage.success('已保存（未启用）。出账仍一律走财务审批；打开开关后再保存才会按阈值分流。')
    }
  } finally {
    thresholdSaving.value = false
  }
}

async function openTaxDialog() {
  if (!companies.value.length) {
    companies.value = await sysApi.myCompanies()
  }
  taxForm.companyId = taxForm.companyId
    || thresholdForm.companyId
    || companies.value[0]?.id
    || pools.value.find((p) => p.companyId)?.companyId
  // 弹窗状态挂在页面上，关闭不销毁；打开时丢掉未保存草稿与试算，避免「像缓存」
  taxForm.tiers = []
  taxForm.trialAmount = 8000
  taxForm.trialTax = 0
  taxForm.trialNet = 0
  taxForm.trialBreakdown = []
  taxForm.taxMode = 'FLAT'
  await loadTaxForm()
  taxDialog.value = true
}

async function loadTaxForm() {
  if (!taxForm.companyId) return
  const row = await bizApi.getWithdrawTaxTiers(Number(taxForm.companyId))
  taxForm.defaultTaxRate = Number(row.defaultTaxRate ?? 0.2)
  taxForm.taxMode = row.taxMode || 'FLAT'
  const tiers = row.tiers || []
  taxForm.tiers = tiers.length
    ? tiers.map((t: any) => ({
        minAmount: Number(t.minAmount ?? 0),
        maxAmount: t.maxAmount == null ? undefined : Number(t.maxAmount),
        taxRatePercent: Number(t.taxRate ?? 0) * 100,
      }))
    : []
  await runTaxTrial()
}

async function onTaxCompanyChange() {
  await loadTaxForm()
}

function addTaxTier() {
  const last = taxForm.tiers[taxForm.tiers.length - 1]
  const minAmount = last?.maxAmount != null ? Number(last.maxAmount) : (last ? Number(last.minAmount) + 5000 : 0)
  if (last && last.maxAmount == null) {
    last.maxAmount = minAmount
  }
  taxForm.tiers.push({
    minAmount,
    maxAmount: undefined,
    taxRatePercent: last ? Number(last.taxRatePercent) : 20,
  })
}

function removeTaxTier(index: number) {
  taxForm.tiers.splice(index, 1)
}

function progressiveTaxClient(amount: number, tiers: typeof taxForm.tiers) {
  if (!tiers.length) {
    const rate = Number(taxForm.defaultTaxRate || 0.2)
    const tax = Number((amount * rate).toFixed(2))
    return {
      tax,
      net: Number((amount - tax).toFixed(2)),
      breakdown: [{ minAmount: 0, maxAmount: null, taxRate: rate, taxableAmount: amount, tax }],
    }
  }
  let taxSum = 0
  const breakdown: any[] = []
  for (const t of tiers) {
    const min = Number(t.minAmount || 0)
    const max = t.maxAmount == null || t.maxAmount === ('' as any) ? null : Number(t.maxAmount)
    if (amount <= min) continue
    const upper = max == null ? amount : Math.min(amount, max)
    const taxable = upper - min
    if (taxable <= 0) continue
    const rate = Number(t.taxRatePercent || 0) / 100
    const sliceTax = Number((taxable * rate).toFixed(2))
    taxSum += sliceTax
    breakdown.push({
      minAmount: min,
      maxAmount: max,
      taxRate: rate,
      taxableAmount: Number(taxable.toFixed(2)),
      tax: sliceTax,
    })
  }
  taxSum = Number(taxSum.toFixed(2))
  return { tax: taxSum, net: Number((amount - taxSum).toFixed(2)), breakdown }
}

async function runTaxTrial() {
  const amount = Number(taxForm.trialAmount || 0)
  if (amount <= 0) {
    taxForm.trialTax = 0
    taxForm.trialNet = 0
    taxForm.trialBreakdown = []
    return
  }
  const local = progressiveTaxClient(amount, taxForm.tiers)
  taxForm.trialTax = local.tax
  taxForm.trialNet = local.net
  taxForm.trialBreakdown = local.breakdown
}

async function saveTaxTiers() {
  if (!taxForm.companyId) {
    ElMessage.warning('请选择公司')
    return
  }
  // 保存前强制最后一档无上限，避免填了上限导致保存被拒、重进变空
  if (taxForm.tiers.length) {
    taxForm.tiers[taxForm.tiers.length - 1].maxAmount = undefined
  }
  const tiers = taxForm.tiers.map((t, i) => {
    const rawMax = t.maxAmount
    const maxAmount =
      i === taxForm.tiers.length - 1
        ? null
        : rawMax == null || rawMax === ('' as any) || Number.isNaN(Number(rawMax))
          ? null
          : Number(rawMax)
    return {
      minAmount: Number(t.minAmount || 0),
      maxAmount,
      taxRate: Number((Number(t.taxRatePercent || 0) / 100).toFixed(4)),
      sort: i,
    }
  })
  for (let i = 0; i < tiers.length; i++) {
    const cur = tiers[i]
    if (cur.taxRate < 0 || cur.taxRate > 1) {
      ElMessage.warning(`第 ${i + 1} 档税率需在 0～100%`)
      return
    }
    if (cur.maxAmount != null && cur.maxAmount <= cur.minAmount) {
      ElMessage.warning(`第 ${i + 1} 档上限必须大于下限`)
      return
    }
    if (i < tiers.length - 1 && cur.maxAmount == null) {
      ElMessage.warning('非最后一档必须填写上限')
      return
    }
    if (i < tiers.length - 1 && Number(cur.maxAmount) !== Number(tiers[i + 1].minAmount)) {
      ElMessage.warning('档位须连续：上一档上限 = 下一档下限')
      return
    }
  }
  if (tiers.length && Number(tiers[0].minAmount) !== 0) {
    ElMessage.warning('第一档下限必须从 0 开始')
    return
  }
  taxSaving.value = true
  try {
    await bizApi.saveWithdrawTaxTiers({
      companyId: Number(taxForm.companyId),
      tiers,
    })
    await loadTaxForm()
    ElMessage.success(tiers.length ? `已保存 ${tiers.length} 档阶梯税率` : '已清空阶梯，将回退系统默认一口价税率')
  } catch (e: any) {
    ElMessage.error(e?.message || '保存失败，请确认有财务编辑权限且后端已重启')
  } finally {
    taxSaving.value = false
  }
}

const thresholdBadge = ref('未配置')

async function refreshThresholdBadge() {
  try {
    if (!companies.value.length) {
      companies.value = await sysApi.myCompanies()
    }
    const cid = thresholdForm.companyId || companies.value[0]?.id || pools.value.find((p) => p.companyId)?.companyId
    if (!cid) {
      thresholdBadge.value = '未配置'
      return
    }
    const row = await bizApi.getLedgerThreshold(Number(cid))
    const name = companies.value.find((c) => Number(c.id) === Number(cid))?.name || '当前公司'
    if (Number(row.enabled) === 1) {
      thresholdBadge.value = `${name}：已启用 ${row.notifyThreshold}/${row.approveThreshold}`
    } else if (row.configured === true || row.createTime || row.updateTime || row.createBy != null || row.updateBy != null) {
      thresholdBadge.value = `${name}：已保存未启用（${row.notifyThreshold}/${row.approveThreshold}）`
    } else {
      thresholdBadge.value = `${name}：未配置`
    }
  } catch {
    thresholdBadge.value = '未配置'
  }
}

function onFilter() {
  query.page = 1
  void load()
}

function resetFilter() {
  query.page = 1
  query.companyId = undefined
  query.bizType = ''
  query.channelId = undefined
  query.keyword = ''
  query.minAmount = undefined
  query.maxAmount = undefined
  query.dateRange = []
  void load()
}

onMounted(async () => {
  try {
    companies.value = await sysApi.myCompanies()
  } catch {
    companies.value = []
  }
  try {
    pools.value = await bizApi.poolList()
  } catch {
    pools.value = []
  }
  try {
    channels.value = await bizApi.payChannelList({ all: false })
  } catch {
    channels.value = []
  }
  await Promise.all([load(), loadSummary()])
  void refreshThresholdBadge()
})
</script>

<template>
  <div class="page-stack">
    <div class="page-top">
      <div class="page-top__main">
        <p class="page-desc">系统内资金 = 公司余额 + 项目余额 + 个人钱包；公司余额是尚未拨出的部分</p>
      </div>
      <div class="page-actions">
        <span class="threshold-badge">{{ thresholdBadge }}</span>
        <el-button @click="openThresholdDialog">出账阈值</el-button>
        <el-button @click="openTaxDialog">提现税率</el-button>
        <el-button @click="openPayoutDialog">发钱给个人</el-button>
        <el-button type="primary" @click="openDialog">登记流水</el-button>
      </div>
    </div>

    <div class="summary-row">
      <div
        class="summary-card summary-card--indigo summary-card--clickable"
        role="button"
        tabindex="0"
        @click="openAssetsDetail"
        @keyup.enter="openAssetsDetail"
      >
        <div class="summary-body">
          <div class="summary-label">系统内资金</div>
          <div class="summary-value">{{ fmtMoney(summary.assetsTotal) }}</div>
          <div class="summary-eq">
            公司 {{ fmtMoney(summary.poolTotal) }}
            + 项目 {{ fmtMoney(summary.projectTotal) }}
            + 个人 {{ fmtMoney(summary.walletTotal) }}
          </div>
          <div class="summary-hint">
            <template v-if="companyAssets.length > 1">共 {{ companyAssets.length }} 家公司，点击查看明细</template>
            <template v-else>点击查看各公司明细</template>
          </div>
        </div>
        <el-icon class="summary-glyph" :size="52"><Coin /></el-icon>
      </div>
      <div
        class="summary-card summary-card--violet summary-card--clickable"
        role="button"
        tabindex="0"
        @click="openCompanyBalances"
        @keyup.enter="openCompanyBalances"
      >
        <div class="summary-body">
          <div class="summary-label">公司余额</div>
          <div class="summary-value sm">{{ fmtMoney(summary.poolTotal) }}</div>
          <div class="summary-hint">
            <template v-if="companyBalances.length > 1">共 {{ companyBalances.length }} 家公司，点击查看明细</template>
            <template v-else-if="companyBalances.length === 1">{{ companyBalances[0].companyName || '当前公司' }} · 点击查看明细</template>
            <template v-else>还在公司账上，可入账 / 出账 / 预支到项目</template>
          </div>
        </div>
        <el-icon class="summary-glyph" :size="52"><OfficeBuilding /></el-icon>
      </div>
      <div
        class="summary-card summary-card--amber summary-card--clickable"
        role="button"
        tabindex="0"
        @click="openProjectBalances"
        @keyup.enter="openProjectBalances"
      >
        <div class="summary-body">
          <div class="summary-label">项目余额合计</div>
          <div class="summary-value sm">{{ fmtMoney(summary.projectTotal) }}</div>
          <div class="summary-hint">
            <template v-if="projectBalances.length > 1">共 {{ projectBalances.length }} 个项目，点击查看明细</template>
            <template v-else-if="projectBalances.length === 1">{{ projectBalances[0].projectName || '当前项目' }} · 点击查看明细</template>
            <template v-else>已预支到各项目、尚未花完或分完 · 点击查看</template>
          </div>
        </div>
        <el-icon class="summary-glyph" :size="52"><FolderOpened /></el-icon>
      </div>
      <div
        class="summary-card summary-card--cyan summary-card--clickable"
        role="button"
        tabindex="0"
        @click="openWalletBalances"
        @keyup.enter="openWalletBalances"
      >
        <div class="summary-body">
          <div class="summary-label">个人钱包合计</div>
          <div class="summary-value sm">{{ fmtMoney(summary.walletTotal) }}</div>
          <div class="summary-hint">
            <template v-if="Number(summary.walletCount) > 0">共 {{ summary.walletCount }} 人，点击查看明细</template>
            <template v-else>已分到个人，点击查看明细</template>
          </div>
        </div>
        <el-icon class="summary-glyph" :size="52"><Wallet /></el-icon>
      </div>
    </div>

    <div class="page-card">
      <el-form class="filter-bar" @submit.prevent="onFilter">
      <el-form-item label="公司">
        <el-select
          v-model="query.companyId"
          clearable
          filterable
          placeholder="全部公司"
          class="filter-select--wide"
          @change="onCompanyChange"
        >
          <el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="类型">
        <el-select v-model="query.bizType" clearable placeholder="全部" class="filter-select">
          <el-option label="入账" value="INCOME" />
          <el-option label="出账" value="EXPENSE" />
          <el-option label="手续费" value="FEE" />
          <el-option label="项目分钱" value="SETTLE" />
          <el-option label="项目预支" value="ADVANCE" />
          <el-option label="报销" value="REIMBURSE" />
          <el-option label="工资" value="SALARY" />
          <el-option label="发钱入钱包" value="PAYOUT" />
          <el-option label="回退" value="ROLLBACK" />
        </el-select>
      </el-form-item>
      <el-form-item label="收款渠道">
        <el-select v-model="query.channelId" clearable placeholder="全部" class="filter-select--wide">
          <el-option v-for="c in filteredChannels" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="发生时间">
        <el-date-picker
          v-model="query.dateRange"
          type="daterange"
          unlink-panels
          clearable
          value-format="YYYY-MM-DD"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          style="width: 220px"
        />
      </el-form-item>
      <el-form-item label="金额">
        <div class="amount-range">
          <el-input-number
            v-model="query.minAmount"
            :controls="false"
            :precision="2"
            placeholder="最小"
            class="amount-input"
          />
          <span class="amount-sep">至</span>
          <el-input-number
            v-model="query.maxAmount"
            :controls="false"
            :precision="2"
            placeholder="最大"
            class="amount-input"
          />
        </div>
      </el-form-item>
      <el-form-item label="关键词">
        <el-input
          v-model="query.keyword"
          clearable
          placeholder="编号 / 摘要"
          class="filter-keyword"
          @keyup.enter="onFilter"
        />
      </el-form-item>
      <el-form-item class="filter-actions">
        <el-button type="primary" native-type="submit" :loading="listLoading">查询</el-button>
        <el-button @click="resetFilter">重置</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="listLoading" :data="pagedRows" row-key="key" class="ledger-table" stripe empty-text="暂无流水">
      <el-table-column label="时间" width="150">
        <template #default="{ row }">{{ fmtTime(row.occurTime) }}</template>
      </el-table-column>
      <el-table-column label="编号" width="170" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="biz-no">{{ row.bizNo || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="100">
        <template #default="{ row }">
          <el-tag :type="bizTagType(row.bizType)" size="small">{{ bizLabel(row.bizType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="摘要" min-width="180" show-overflow-tooltip />
      <el-table-column label="对方 / 说明" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ row.counterpart || '—' }}</template>
      </el-table-column>
      <el-table-column label="金额" width="130" align="right">
        <template #default="{ row }">
          <span :class="amountClass(row.amount)">{{ fmtMoney(row.amount, true) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="公司余额" width="130" align="right">
        <template #default="{ row }">
          <span class="balance-text">{{ row.afterBalance != null ? fmtMoney(row.afterBalance) : '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="凭证" width="100" align="center">
        <template #default="{ row }">
          <span v-if="row.vouchers?.length" class="voucher-count">{{ row.vouchers.length }} 个</span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" align="center" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click.stop="openDetail(row)">详细</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="companyRows.length" class="page-footer">
      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.pageSize"
        :total="companyRows.length"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
      />
    </div>

    </div>

    <el-drawer v-model="assetsDetailVisible" title="系统内资金明细" size="560px" append-to-body>
      <div class="company-balance-head">
        <span>系统内资金合计</span>
        <strong>{{ fmtMoney(summary.assetsTotal) }}</strong>
      </div>
      <div class="assets-total-cards">
        <div class="assets-total-card">
          <span>公司余额合计</span>
          <b>{{ fmtMoney(summary.poolTotal) }}</b>
        </div>
        <div class="assets-total-card">
          <span>项目余额合计</span>
          <b>{{ fmtMoney(summary.projectTotal) }}</b>
        </div>
        <div class="assets-total-card">
          <span>个人钱包合计</span>
          <b>{{ fmtMoney(summary.walletTotal) }}</b>
        </div>
      </div>
      <h4 class="detail-sec">按公司（公司余额 + 项目余额）</h4>
      <el-table :data="companyAssets" stripe empty-text="暂无公司资金数据" show-summary :summary-method="assetsCompanySummary">
        <el-table-column label="公司" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.companyName || `公司#${row.companyId}` }}</template>
        </el-table-column>
        <el-table-column label="公司余额" width="120" align="right" prop="poolBalance">
          <template #default="{ row }">{{ fmtMoney(row.poolBalance) }}</template>
        </el-table-column>
        <el-table-column label="项目余额" width="120" align="right" prop="projectBalance">
          <template #default="{ row }">{{ fmtMoney(row.projectBalance) }}</template>
        </el-table-column>
        <el-table-column label="小计" width="120" align="right" prop="companyProjectTotal">
          <template #default="{ row }">
            <span class="balance-text">{{ fmtMoney(row.companyProjectTotal) }}</span>
          </template>
        </el-table-column>
      </el-table>
      <p class="company-balance-note assets-wallet-note">
        个人钱包 ¥{{ fmtMoney(summary.walletTotal).replace(/^¥/, '') }} 为全系统合计，不按公司拆分。
      </p>
    </el-drawer>

    <el-drawer v-model="companyBalanceVisible" title="公司余额明细" size="480px" append-to-body>
      <div class="company-balance-head">
        <span>合计</span>
        <strong>{{ fmtMoney(summary.poolTotal) }}</strong>
      </div>
      <el-table :data="companyBalances" stripe empty-text="暂无公司资金池">
        <el-table-column label="公司" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.companyName || `公司#${row.companyId}` }}</template>
        </el-table-column>
        <el-table-column label="资金池数" width="90" align="center">
          <template #default="{ row }">{{ row.poolCount ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="余额" width="140" align="right">
          <template #default="{ row }">
            <span class="balance-text">{{ fmtMoney(row.balance) }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <el-drawer v-model="projectBalanceVisible" title="项目余额明细" size="560px" append-to-body>
      <div class="company-balance-head">
        <span>合计</span>
        <strong>{{ fmtMoney(summary.projectTotal) }}</strong>
      </div>
      <el-table :data="projectBalances" stripe empty-text="暂无项目余额">
        <el-table-column label="项目" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.projectName || `项目#${row.projectId}` }}</span>
            <span v-if="row.projectCode" class="muted"> · {{ row.projectCode }}</span>
          </template>
        </el-table-column>
        <el-table-column label="公司" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.companyName || (row.companyId != null ? `公司#${row.companyId}` : '—') }}</template>
        </el-table-column>
        <el-table-column label="余额" width="130" align="right">
          <template #default="{ row }">
            <span class="balance-text">{{ fmtMoney(row.balance) }}</span>
          </template>
        </el-table-column>
      </el-table>
      <p class="company-balance-note">按项目列出已预支尚未花完或分完的余额；子项目会单独列出。</p>
    </el-drawer>

    <el-drawer v-model="walletBalanceVisible" title="个人钱包明细" size="560px" append-to-body>
      <div class="company-balance-head">
        <span>合计</span>
        <strong>{{ fmtMoney(summary.walletTotal) }}</strong>
      </div>
      <el-table
        v-loading="walletBalancesLoading"
        :data="walletBalances"
        stripe
        empty-text="暂无个人钱包余额"
      >
        <el-table-column label="人员" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ walletDisplayName(row) }}</template>
        </el-table-column>
        <el-table-column label="余额" width="120" align="right">
          <template #default="{ row }">
            <span class="balance-text">{{ fmtMoney(row.balance) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="冻结" width="110" align="right">
          <template #default="{ row }">{{ fmtMoney(row.frozen) }}</template>
        </el-table-column>
        <el-table-column label="可用" width="120" align="right">
          <template #default="{ row }">
            <span class="balance-text">{{ fmtMoney(row.available) }}</span>
          </template>
        </el-table-column>
      </el-table>
      <p class="company-balance-note">
        按人列出个人钱包余额；已分到个人，提现只扣个人钱包，不改公司余额。
      </p>
    </el-drawer>

    <el-drawer v-model="detailVisible" title="流水详细" size="520px" append-to-body>
      <template v-if="detailRow">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="时间">{{ fmtTime(detailRow.occurTime) }}</el-descriptions-item>
          <el-descriptions-item label="编号">{{ detailRow.bizNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="公司">{{ companyLabel(detailRow) }}</el-descriptions-item>
          <el-descriptions-item label="项目">{{ detailRow.projectName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="类型">{{ bizLabel(detailRow.bizType) }}</el-descriptions-item>
          <el-descriptions-item label="摘要">{{ detailRow.title || '—' }}</el-descriptions-item>
          <el-descriptions-item label="对方 / 说明">{{ detailRow.counterpart || '—' }}</el-descriptions-item>
          <el-descriptions-item label="金额">
            <span :class="amountClass(detailRow.amount)">{{ fmtMoney(detailRow.amount, true) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="公司余额">
            {{ detailRow.afterBalance != null ? fmtMoney(detailRow.afterBalance) : '—' }}
          </el-descriptions-item>
        </el-descriptions>

        <h4 class="detail-sec">凭证（{{ detailRow.vouchers?.length || 0 }}）</h4>
        <div v-if="detailRow.vouchers?.length" class="voucher-gallery">
          <div v-for="file in detailRow.vouchers" :key="file.id" class="voucher-card">
            <div v-if="isImage(file)" class="voucher-thumb" @click="previewVoucher(file)">
              <img :src="fileUrl(file)" :alt="file.originalName || '凭证'" />
            </div>
            <div v-else class="voucher-file" @click="previewVoucher(file)">
              <span>{{ file.originalName || `文件#${file.id}` }}</span>
            </div>
            <div class="voucher-actions">
              <el-button link type="primary" @click="previewVoucher(file)">查看 / 下载</el-button>
            </div>
          </div>
        </div>
        <div v-else class="empty-voucher">本笔流水没有上传凭证</div>
      </template>
    </el-drawer>

    <el-dialog v-model="dialog" :title="form.bizType === 'INCOME' ? '登记入账（需财务审批）' : '登记出账'" width="560px" :close-on-click-modal="false" @closed="emptyForm">
      <el-alert :title="registerHint" type="info" :closable="false" show-icon style="margin-bottom: 14px" />
      <el-form label-width="100px">
        <el-form-item label="类型">
          <el-select v-model="form.bizType" style="width: 100%">
            <el-option label="公司入账" value="INCOME" />
            <el-option label="公司出账" value="EXPENSE" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="pools.length > 1" label="资金池">
          <el-select v-model="form.poolId" style="width: 100%">
            <el-option v-for="p in pools" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.bizType === 'INCOME'" label="收款渠道" required>
          <el-select
            v-model="form.channelId"
            style="width: 100%"
            placeholder="支付宝/银行卡等"
            :disabled="!formChannels.length"
          >
            <el-option
              v-for="c in formChannels"
              :key="c.id"
              :label="`${c.name}（${c.channelTypeLabel || c.channelType}）`"
              :value="c.id"
            />
          </el-select>
          <div v-if="form.poolId && !formChannels.length" class="form-tip">该资金池暂无收款渠道，请先在「收款渠道」中配置</div>
        </el-form-item>
        <el-form-item label="关联项目">
          <ProjectCascadeSelect
            v-model="form.projectId"
            :projects="projects"
            mode="filter"
            top-placeholder="可选"
            child-placeholder="小项目（可选）"
            top-width="100%"
            child-width="100%"
          />
        </el-form-item>
        <el-form-item
          v-if="form.bizType === 'INCOME' && form.projectId"
          label="资金类型"
          required
        >
          <el-radio-group v-model="form.fundType">
            <el-radio value="SHARE_PENDING">待分成资金</el-radio>
            <el-radio value="NON_SHARE">非分成资金</el-radio>
          </el-radio-group>
          <div class="form-tip">审批通过后净额拨入项目对应资金池；支出默认从待分成扣</div>
        </el-form-item>
        <el-form-item label="总额" required>
          <el-input-number v-model="form.amount" :min="0.01" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item v-if="form.bizType === 'INCOME'" label="手续费">
          <div style="display: flex; gap: 8px; width: 100%">
            <el-select v-model="form.feeMode" clearable placeholder="无" style="width: 120px">
              <el-option label="固定金额" value="FIXED" />
              <el-option label="百分比" value="PERCENT" />
            </el-select>
            <el-input-number
              v-if="form.feeMode"
              v-model="form.feeValue"
              :min="0"
              :precision="2"
              :placeholder="form.feeMode === 'PERCENT' ? '如 0.6 表示 0.6%' : '金额'"
              style="flex: 1"
            />
          </div>
          <div v-if="form.feeMode && form.feeValue && form.amount" class="fee-hint">
            预计手续费 ¥{{
              form.feeMode === 'PERCENT'
                ? (Number(form.amount) * Number(form.feeValue) / 100).toFixed(2)
                : Number(form.feeValue).toFixed(2)
            }}
            ，净入账 ¥{{
              (
                Number(form.amount)
                - (form.feeMode === 'PERCENT'
                  ? Number(form.amount) * Number(form.feeValue) / 100
                  : Number(form.feeValue))
              ).toFixed(2)
            }}
          </div>
        </el-form-item>
        <el-form-item label="摘要"><el-input v-model="form.title" placeholder="例如：客户回款" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="凭证">
          <div class="voucher-box">
            <el-upload :show-file-list="false" :http-request="onUploadVoucher" accept="image/*,.pdf,.doc,.docx,.xls,.xlsx">
              <el-button :loading="uploading">上传凭证</el-button>
            </el-upload>
            <div v-if="voucherFiles.length" class="voucher-list">
              <div v-for="(file, index) in voucherFiles" :key="file.id" class="voucher-item">
                <el-link type="primary" :underline="false" @click="previewVoucher(file)">{{ file.originalName }}</el-link>
                <el-button link type="danger" @click="removeVoucher(index)">移除</el-button>
              </div>
            </div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ submitBtnLabel }}</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="payoutDialog"
      title="发钱给个人"
      width="560px"
      :close-on-click-modal="false"
      @closed="emptyPayoutForm"
    >
      <el-alert
        title="提交后按该公司「财务发钱」审批配置处理；通过后直接转入收款人个人钱包，无需回执。"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 14px"
      />
      <el-form label-width="100px">
        <el-form-item label="资金来源" required>
          <el-radio-group v-model="payoutForm.sourceType" @change="onPayoutSourceChange">
            <el-radio-button value="COMPANY">公司余额</el-radio-button>
            <el-radio-button value="PROJECT">项目资金</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="payoutForm.sourceType === 'COMPANY'" label="资金池" required>
          <el-select
            v-model="payoutForm.poolId"
            filterable
            style="width: 100%"
            placeholder="选择公司资金池"
            @change="onPayoutPoolChange"
          >
            <el-option
              v-for="p in pools"
              :key="p.id"
              :label="`${p.companyName ? p.companyName + ' · ' : ''}${p.name}（¥${Number(p.balance || 0).toFixed(2)}）`"
              :value="p.id"
            />
          </el-select>
          <div class="form-tip">当前可用 ¥{{ payoutPoolBalance.toFixed(2) }}</div>
        </el-form-item>
        <template v-else>
          <el-form-item label="项目" required>
            <ProjectCascadeSelect
              v-model="payoutForm.projectId"
              :projects="projects"
              mode="filter"
              top-placeholder="选择项目"
              child-placeholder="小项目（可选）"
              top-width="100%"
              child-width="100%"
              @update:model-value="onPayoutProjectChange"
            />
          </el-form-item>
          <el-form-item label="项目资金池" required>
            <el-radio-group v-model="payoutForm.fundType">
              <el-radio value="SHARE_PENDING">
                待分成（¥{{ Number(payoutProjectDetail?.sharePendingBalance || 0).toFixed(2) }}）
              </el-radio>
              <el-radio value="NON_SHARE">
                非分成（¥{{ Number(payoutProjectDetail?.nonShareBalance || 0).toFixed(2) }}）
              </el-radio>
            </el-radio-group>
          </el-form-item>
        </template>
        <el-form-item label="收款人" required>
          <el-select
            v-model="payoutForm.payeeUserId"
            filterable
            style="width: 100%"
            placeholder="选择收款人"
          >
            <el-option
              v-for="u in payoutUsers"
              :key="u.id"
              :label="u.nickname || u.username"
              :value="u.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="金额" required>
          <el-input-number
            v-model="payoutForm.amount"
            :min="0.01"
            :precision="2"
            :step="100"
            controls-position="right"
            style="width: 100%"
          />
          <div class="form-tip">
            上限 ¥{{
              (payoutForm.sourceType === 'COMPANY' ? payoutPoolBalance : payoutFundBalance).toFixed(2)
            }}
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="payoutForm.remark" type="textarea" :rows="2" placeholder="可选说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="payoutDialog = false">取消</el-button>
        <el-button type="primary" :loading="payoutSaving" @click="submitPayout">提交审批</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="thresholdDialog" title="公司出账阈值" width="480px" :close-on-click-modal="false">
      <el-form label-width="100px">
        <el-form-item label="公司" required>
          <el-select v-model="thresholdForm.companyId" style="width: 100%" @change="onThresholdCompanyChange">
            <el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用阈值">
          <el-switch v-model="thresholdForm.enabled" />
          <span style="margin-left: 8px; color: var(--el-text-color-secondary); font-size: 12px">
            关闭时出账一律走财务审批
          </span>
        </el-form-item>
        <el-form-item label="通知线">
          <el-input-number v-model="thresholdForm.notifyThreshold" :min="0" :precision="2" :step="1000" style="width: 100%" />
        </el-form-item>
        <el-form-item label="审批线">
          <el-input-number v-model="thresholdForm.approveThreshold" :min="0" :precision="2" :step="1000" style="width: 100%" />
        </el-form-item>
        <el-alert
          type="info"
          :closable="false"
          title="启用后：低于通知线直接出账；通知线到审批线之间直接出账并通知股东；达到审批线需全体股东会签。入账不受此配置影响。"
        />
        <el-alert
          style="margin-top: 12px"
          :type="thresholdForm.enabled ? 'success' : 'warning'"
          :closable="false"
          :title="thresholdForm.enabled
            ? `当前生效：通知线 ${thresholdForm.notifyThreshold} / 审批线 ${thresholdForm.approveThreshold}`
            : '当前未启用：无论填多少，出账都走财务审批。打开上方开关并保存后才会按阈值分流。'"
        />
      </el-form>
      <template #footer>
        <el-button @click="thresholdDialog = false">关闭</el-button>
        <el-button type="primary" :loading="thresholdSaving" @click="saveThreshold">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="taxDialog" title="提现阶梯税率" width="640px" :close-on-click-modal="false">
      <el-form label-width="88px">
        <el-form-item label="公司" required>
          <el-select v-model="taxForm.companyId" style="width: 100%" @change="onTaxCompanyChange">
            <el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-alert
          type="info"
          :closable="false"
          :title="`超额累进：各档分别计税后相加。最后一档上限须留空。未配置时回退默认一口价 ${(Number(taxForm.defaultTaxRate) * 100).toFixed(1)}%。`"
          style="margin-bottom: 12px"
        />
        <div class="tax-tier-table">
          <div class="tax-tier-head">
            <span>下限（含）</span>
            <span>上限（不含）</span>
            <span>税率 %</span>
            <span />
          </div>
          <div v-for="(t, i) in taxForm.tiers" :key="i" class="tax-tier-row">
            <el-input-number v-model="t.minAmount" :min="0" :precision="2" :controls="false" @change="runTaxTrial" />
            <el-input-number
              v-model="t.maxAmount"
              :min="0"
              :precision="2"
              :controls="false"
              placeholder="空=无上限"
              @change="runTaxTrial"
            />
            <el-input-number
              v-model="t.taxRatePercent"
              :min="0"
              :max="100"
              :precision="2"
              :controls="false"
              @change="runTaxTrial"
            />
            <el-button link type="danger" @click="removeTaxTier(i); runTaxTrial()">删</el-button>
          </div>
          <el-button type="primary" link @click="addTaxTier(); runTaxTrial()">添加档位</el-button>
        </div>
        <el-divider content-position="left">试算</el-divider>
        <el-form-item label="试算金额">
          <el-input-number v-model="taxForm.trialAmount" :min="0.01" :precision="2" style="width: 220px" @change="runTaxTrial" />
        </el-form-item>
        <el-form-item label="税额">
          <span>¥ {{ Number(taxForm.trialTax || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }}</span>
        </el-form-item>
        <el-form-item label="到手">
          <strong>¥ {{ Number(taxForm.trialNet || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }}</strong>
        </el-form-item>
        <ul v-if="taxForm.trialBreakdown.length" class="tax-breakdown">
          <li v-for="(b, i) in taxForm.trialBreakdown" :key="i">
            {{ b.minAmount }} ~ {{ b.maxAmount == null ? '∞' : b.maxAmount }}
            · {{ (Number(b.taxRate) * 100).toFixed(1) }}%
            · 计税基数 ¥{{ Number(b.taxableAmount).toFixed(2) }}
            · 税 ¥{{ Number(b.tax).toFixed(2) }}
          </li>
        </ul>
      </el-form>
      <template #footer>
        <el-button @click="taxDialog = false">关闭</el-button>
        <el-button type="primary" :loading="taxSaving" @click="saveTaxTiers">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.threshold-badge {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  max-width: 280px;
  line-height: 1.3;
}
.tax-tier-table {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 8px;
}
.tax-tier-head,
.tax-tier-row {
  display: grid;
  grid-template-columns: 1fr 1fr 110px 40px;
  gap: 8px;
  align-items: center;
}
.tax-tier-head {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.tax-breakdown {
  margin: 0 0 0 88px;
  padding: 0;
  list-style: none;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.summary-row {
  display: grid;
  grid-template-columns: 1.4fr 1fr 1fr 1fr;
  gap: 16px;
}
.summary-card {
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 108px;
  padding: 18px 16px 18px 20px;
  background: var(--kk-glass-bg);
  border: 1px solid var(--kk-glass-border);
  border-radius: var(--kk-radius);
  box-shadow: var(--kk-glass-shadow);
  backdrop-filter: var(--kk-glass-blur);
  -webkit-backdrop-filter: var(--kk-glass-blur);
}
.summary-card::before {
  content: "";
  position: absolute;
  right: -24px;
  top: 50%;
  width: 120px;
  height: 120px;
  border-radius: 50%;
  transform: translateY(-50%);
  filter: blur(32px);
  opacity: 0.22;
  pointer-events: none;
}
.summary-card--indigo::before { background: #d4d4d8; }
.summary-card--cyan::before { background: #a5f3fc; }
.summary-card--violet::before { background: #ddd6fe; }
.summary-card--amber::before { background: #fde68a; }
.summary-card--clickable {
  cursor: pointer;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}
.summary-card--clickable:hover {
  transform: translateY(-1px);
  box-shadow: var(--kk-glass-shadow), 0 0 0 1px rgba(124, 58, 237, 0.12);
}
.summary-card--clickable:focus-visible {
  outline: 2px solid rgba(124, 58, 237, 0.35);
  outline-offset: 2px;
}
.summary-card--indigo .summary-glyph { color: var(--kk-primary); }
.summary-card--cyan .summary-glyph { color: #0891b2; }
.summary-card--violet .summary-glyph { color: #7c3aed; }
.summary-card--amber .summary-glyph { color: #d97706; }
.summary-body {
  position: relative;
  z-index: 1;
  min-width: 0;
}
.summary-glyph {
  position: relative;
  z-index: 1;
  flex-shrink: 0;
  opacity: 1;
}
.summary-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--kk-text-secondary);
}
.summary-value {
  margin-top: 8px;
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
  color: var(--kk-text);
}
.summary-value.sm {
  font-size: 22px;
}
.summary-eq {
  margin-top: 8px;
  font-size: 12px;
  color: var(--kk-text-secondary);
  line-height: 1.5;
}
.summary-hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--kk-text-muted);
  line-height: 1.4;
}
.company-balance-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
  padding: 14px 16px;
  border-radius: var(--kk-radius, 18px);
  background: var(--kk-glass-bg, rgba(255, 255, 255, 0.46));
  border: 1px solid var(--kk-glass-border, rgba(255, 255, 255, 0.72));
  box-shadow: var(--kk-glass-shadow);
  font-size: 14px;
  color: var(--kk-text-secondary);
}
.company-balance-head strong {
  font-size: 20px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--kk-text);
}
.company-balance-sub {
  margin: -8px 0 14px;
  font-size: 12px;
  color: var(--kk-text-muted);
  line-height: 1.4;
}
.company-balance-note {
  display: inline;
  opacity: 0.85;
}
.assets-total-cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 8px;
}
.assets-total-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 14px;
  border-radius: 12px;
  background: var(--kk-glass-bg, rgba(255, 255, 255, 0.46));
  border: 1px solid var(--kk-glass-border, rgba(255, 255, 255, 0.72));
  box-shadow: var(--kk-glass-shadow);
}
.assets-total-card span {
  font-size: 12px;
  color: var(--kk-text-muted);
}
.assets-total-card b {
  font-size: 16px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--kk-text);
}
.assets-wallet-note {
  display: block;
  margin-top: 12px;
  font-size: 12px;
  color: var(--kk-text-muted);
  line-height: 1.45;
}
@media (max-width: 1100px) {
  .summary-row {
    grid-template-columns: 1fr 1fr;
  }
}
.fee-hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--kk-text-secondary);
  line-height: 1.5;
}
.biz-no {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  color: var(--kk-text-secondary);
}
.amt-in {
  color: var(--kk-success);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.amt-out {
  color: var(--kk-danger);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.balance-text {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--kk-text);
}
.muted {
  color: var(--kk-text-muted);
}
.form-tip {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.4;
  color: var(--kk-text-muted);
}
.voucher-count {
  color: var(--kk-primary);
  font-size: 13px;
}
.detail-sec {
  margin: 18px 0 10px;
  font-size: 14px;
  color: var(--kk-text);
}
.voucher-gallery {
  display: grid;
  gap: 12px;
}
.voucher-card {
  border: 1px solid var(--kk-glass-border);
  border-radius: 10px;
  padding: 10px;
  background: rgba(255, 255, 255, 0.28);
}
.voucher-thumb {
  cursor: pointer;
  border-radius: 8px;
  overflow: hidden;
  background: #fff;
  border: 1px solid rgba(0, 0, 0, 0.04);
}
.voucher-thumb img {
  display: block;
  width: 100%;
  max-height: 280px;
  object-fit: contain;
}
.voucher-file {
  cursor: pointer;
  padding: 14px;
  border-radius: 8px;
  background: #fff;
  border: 1px dashed #cbd5e1;
  color: var(--kk-text);
  font-size: 13px;
  word-break: break-all;
}
.voucher-actions {
  margin-top: 8px;
  text-align: right;
}
.empty-voucher {
  padding: 20px;
  text-align: center;
  color: var(--kk-text-muted);
  font-size: 13px;
  background: rgba(255, 255, 255, 0.28);
  border-radius: 8px;
}
.voucher-box {
  width: 100%;
}
.voucher-list {
  margin-top: 10px;
}
.voucher-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 0;
  border-bottom: 1px dashed var(--kk-hairline);
}
@media (max-width: 960px) {
  .summary-row {
    grid-template-columns: 1fr;
  }
}
@media (prefers-reduced-transparency: reduce) {
  .summary-card {
    background: #fff;
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}
</style>
