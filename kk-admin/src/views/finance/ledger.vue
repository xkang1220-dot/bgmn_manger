<script setup lang="ts">
import { onMounted, reactive, ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { bizApi } from '@/api/biz'
import { workflowApi } from '@/api/workflow'

const query = reactive({
  page: 1,
  pageSize: 20,
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
const uploading = ref(false)

const form = reactive<any>({
  bizType: 'INCOME',
  poolId: undefined,
  channelId: undefined,
  projectId: undefined,
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

interface LedgerRow {
  key: string
  occurTime: string
  bizNo?: string
  bizType: string
  title: string
  projectName?: string
  channelName?: string
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
}

const TYPE_TAG: Record<string, string> = {
  INCOME: 'success',
  EXPENSE: 'danger',
  TRANSFER: 'warning',
  SETTLE: 'primary',
  ADVANCE: '',
  RESERVE: 'info',
  REIMBURSE: 'warning',
  ROLLBACK: 'danger',
}

function emptyForm() {
  Object.assign(form, {
    bizType: 'INCOME',
    poolId: pools.value.find((p) => p.isDefault === 1)?.id ?? pools.value[0]?.id,
    channelId: channels.value[0]?.id,
    projectId: undefined,
    amount: 0,
    feeMode: '',
    feeValue: undefined,
    title: '',
    remark: '',
    voucherFileIds: [],
  })
  voucherFiles.value = []
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
    result.push({
      key: `s-${row.id}`,
      occurTime: row.occurTime,
      bizNo: row.bizNo,
      bizType: row.bizType,
      title: cleanTitle(row),
      projectName: row.projectName,
      amount: Number(row.amount),
      afterBalance: Number(row.afterBalance),
      counterpart: row.projectName || '—',
      vouchers: row.vouchers || [],
    })
  }

  for (const [batchId, poolItems] of byBatch) {
    const head = [...poolItems].sort((a, b) => a.id - b.id)[0]
    const relatedWallets = rows.filter(
      (r) => r.relatedId === batchId && r.accountType === 'WALLET' && Math.abs(Number(r.amount)) > 0,
    )
    let counterpart = head.projectName || '—'
    if (relatedWallets.length) {
      counterpart = relatedWallets
        .map((w) => `${w.userName || '个人'}${Number(w.amount) > 0 ? '+' : ''}${fmtMoney(w.amount).replace('¥', '')}`)
        .join('、')
    } else if (head.bizType === 'ADVANCE' || head.bizType === 'RESERVE') {
      counterpart = head.projectName ? `项目 · ${head.projectName}` : '项目'
    }
    result.push({
      key: `b-${batchId}`,
      occurTime: head.occurTime,
      bizNo: head.bizNo,
      bizType: head.bizType,
      title: cleanTitle(head),
      projectName: head.projectName,
      amount: Number(head.amount),
      afterBalance: Number(head.afterBalance),
      counterpart,
      vouchers: head.vouchers || [],
    })
  }

  return result.sort((a, b) => String(b.occurTime).localeCompare(String(a.occurTime)))
}

const companyRows = computed(() => buildCompanyRows(rawList.value))
const pagedRows = computed(() => {
  const start = (query.page - 1) * query.pageSize
  return companyRows.value.slice(start, start + query.pageSize)
})

async function load() {
  try {
    const dateRange = query.dateRange || []
    const res = await bizApi.ledgerPage({
      page: 1,
      pageSize: 500,
      accountType: 'POOL',
      bizType: query.bizType || undefined,
      channelId: query.channelId,
      keyword: query.keyword || undefined,
      minAmount: query.minAmount,
      maxAmount: query.maxAmount,
      startTime: dateRange[0] ? `${dateRange[0]} 00:00:00` : undefined,
      endTime: dateRange[1] ? `${dateRange[1]} 23:59:59` : undefined,
    })
    rawList.value = res.list
    const needWallet = !query.bizType || ['SETTLE', 'TRANSFER', 'EXPENSE', 'REIMBURSE', 'SALARY', 'ROLLBACK'].includes(query.bizType)
    const relatedIds = needWallet
      ? [...new Set(res.list.map((r: any) => r.relatedId).filter(Boolean))]
      : []
    if (relatedIds.length) {
      const walletRes = await bizApi.ledgerPage({
        page: 1,
        pageSize: 500,
        accountType: 'WALLET',
        bizType: undefined,
      })
      const need = new Set(relatedIds)
      const extras = walletRes.list.filter((r: any) => need.has(r.relatedId))
      rawList.value = [...res.list, ...extras]
    }
    query.page = 1
  } catch {
    // 拦截器已提示；保留当前列表避免空白闪烁
  }
}

async function loadSummary() {
  try {
    summary.value = await bizApi.summary()
  } catch {
    summary.value = {}
  }
}

function openDialog() {
  emptyForm()
  void ensureFormOptions()
  dialog.value = true
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
  if (!form.channelId) {
    form.channelId = channels.value.find((c) => !form.poolId || c.poolId === form.poolId)?.id
  }
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
  return file?.url || `/api/file/preview/${file?.id}`
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
  const typeLabel = form.bizType === 'INCOME' ? '入账' : '出账'
  await workflowApi.submit({
    type: 'LEDGER_REGISTER',
    title: form.title || `总账${typeLabel}`,
    amount: form.amount,
    poolId: form.poolId,
    projectId: form.projectId,
    remark: form.remark,
    voucherFileIds: form.voucherFileIds.length ? form.voucherFileIds : undefined,
    payload: {
      bizType: form.bizType,
      accountType: 'POOL',
      poolId: form.poolId,
      channelId: form.bizType === 'INCOME' ? form.channelId : undefined,
      projectId: form.projectId,
      amount: form.amount,
      feeMode: form.bizType === 'INCOME' && form.feeMode ? form.feeMode : undefined,
      feeValue: form.bizType === 'INCOME' && form.feeMode ? form.feeValue : undefined,
      title: form.title,
      remark: form.remark,
      voucherFileIds: form.voucherFileIds.length ? [...form.voucherFileIds] : undefined,
    },
  })
  ElMessage.success('已提交审批，通过后才会入账')
  dialog.value = false
  await Promise.all([load(), loadSummary()])
}

function onFilter() {
  query.page = 1
  void load()
}

onMounted(async () => {
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
})
</script>

<template>
  <div class="page-card">
    <div class="page-header">
      <div>
        <h3 class="page-title">公司总账</h3>
        <p class="page-desc">公司进出账流水。系统内资金 = 公司余额 + 项目余额 + 个人钱包；三者相加才是全部，公司余额只是还没拨出去的那一块</p>
      </div>
      <el-button type="primary" @click="openDialog">登记流水</el-button>
    </div>

    <div class="summary-row">
      <div class="summary-card main">
        <div class="summary-label">系统内资金</div>
        <div class="summary-value">{{ fmtMoney(summary.assetsTotal) }}</div>
        <div class="summary-eq">
          公司 {{ fmtMoney(summary.poolTotal) }}
          + 项目 {{ fmtMoney(summary.projectTotal) }}
          + 个人 {{ fmtMoney(summary.walletTotal) }}
        </div>
      </div>
      <div class="summary-card">
        <div class="summary-label">公司余额</div>
        <div class="summary-value sm">{{ fmtMoney(summary.poolTotal) }}</div>
        <div class="summary-hint">还在公司账上，可入账/出账/预支到项目</div>
      </div>
      <div class="summary-card">
        <div class="summary-label">项目余额合计</div>
        <div class="summary-value sm">{{ fmtMoney(summary.projectTotal) }}</div>
        <div class="summary-hint">已预支到各项目、尚未花完/分完</div>
      </div>
      <div class="summary-card">
        <div class="summary-label">个人钱包合计</div>
        <div class="summary-value sm">{{ fmtMoney(summary.walletTotal) }}</div>
        <div class="summary-hint">已分到个人手里的钱，是系统内资金的一部分，不会超过系统内资金</div>
      </div>
    </div>

    <div class="toolbar">
      <el-select v-model="query.bizType" clearable placeholder="类型" style="width: 130px" @change="onFilter">
        <el-option label="入账" value="INCOME" />
        <el-option label="出账" value="EXPENSE" />
        <el-option label="手续费" value="FEE" />
        <el-option label="项目分钱" value="SETTLE" />
        <el-option label="项目预支" value="ADVANCE" />
        <el-option label="回退" value="ROLLBACK" />
      </el-select>
      <el-select v-model="query.channelId" clearable placeholder="收款渠道" style="width: 160px" @change="onFilter">
        <el-option v-for="c in channels" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
      <el-date-picker
        v-model="query.dateRange"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="开始"
        end-placeholder="结束"
        style="width: 250px"
        @change="onFilter"
      />
      <el-input-number v-model="query.minAmount" :min="0" :precision="2" controls-position="right" placeholder="最小金额" style="width: 120px" />
      <el-input-number v-model="query.maxAmount" :min="0" :precision="2" controls-position="right" placeholder="最大金额" style="width: 120px" />
      <el-input v-model="query.keyword" clearable placeholder="编号/摘要" style="width: 140px" @keyup.enter="onFilter" />
      <el-button type="primary" @click="onFilter">查询</el-button>
    </div>

    <el-table :data="pagedRows" row-key="key" class="ledger-table" stripe>
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

    <el-pagination
      style="margin-top: 12px"
      v-model:current-page="query.page"
      v-model:page-size="query.pageSize"
      :total="companyRows.length"
      :page-sizes="[10, 20, 50]"
      layout="total, sizes, prev, pager, next"
    />

    <el-drawer v-model="detailVisible" title="流水详细" size="520px">
      <template v-if="detailRow">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="时间">{{ fmtTime(detailRow.occurTime) }}</el-descriptions-item>
          <el-descriptions-item label="编号">{{ detailRow.bizNo || '—' }}</el-descriptions-item>
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

    <el-dialog v-model="dialog" title="登记流水（需审批）" width="560px" @closed="emptyForm">
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
          <el-select v-model="form.channelId" style="width: 100%" placeholder="支付宝/银行卡等">
            <el-option
              v-for="c in channels.filter((x) => !form.poolId || x.poolId === form.poolId)"
              :key="c.id"
              :label="`${c.name}（${c.channelTypeLabel || c.channelType}）`"
              :value="c.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="关联项目">
          <el-select v-model="form.projectId" clearable style="width: 100%">
            <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="到账总额" required>
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
        <el-button type="primary" @click="save">提交审批</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-desc {
  margin: 4px 0 0;
  color: #64748b;
  font-size: 13px;
}
.summary-row {
  display: grid;
  grid-template-columns: 1.4fr 1fr 1fr 1fr;
  gap: 12px;
  margin-bottom: 16px;
}
.summary-card {
  padding: 14px 16px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
}
.summary-card.main {
  background: linear-gradient(135deg, #f8fafc, #eef2ff);
}
.summary-label {
  font-size: 12px;
  color: #64748b;
}
.summary-value {
  margin-top: 6px;
  font-size: 28px;
  font-weight: 700;
  color: #0f172a;
  letter-spacing: -0.02em;
}
.summary-value.sm {
  font-size: 22px;
}
.summary-eq {
  margin-top: 8px;
  font-size: 12px;
  color: #64748b;
  line-height: 1.5;
}
.summary-hint {
  margin-top: 6px;
  font-size: 12px;
  color: #94a3b8;
  line-height: 1.4;
}
@media (max-width: 1100px) {
  .summary-row {
    grid-template-columns: 1fr 1fr;
  }
}
.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-bottom: 12px;
}
.fee-hint {
  margin-top: 6px;
  font-size: 12px;
  color: #64748b;
  line-height: 1.5;
}
.biz-no {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  color: #475569;
}
.amt-in {
  color: #16a34a;
  font-weight: 600;
}
.amt-out {
  color: #dc2626;
  font-weight: 600;
}
.balance-text {
  font-weight: 600;
  color: #334155;
}
.muted {
  color: #94a3b8;
}
.voucher-count {
  color: #2563eb;
  font-size: 13px;
}
.detail-sec {
  margin: 18px 0 10px;
  font-size: 14px;
  color: #0f172a;
}
.voucher-gallery {
  display: grid;
  gap: 12px;
}
.voucher-card {
  border: 1px solid #e8edf4;
  border-radius: 10px;
  padding: 10px;
  background: #f8fafc;
}
.voucher-thumb {
  cursor: pointer;
  border-radius: 8px;
  overflow: hidden;
  background: #fff;
  border: 1px solid #eef2f7;
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
  color: #334155;
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
  color: #94a3b8;
  font-size: 13px;
  background: #f8fafc;
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
  border-bottom: 1px dashed #e2e8f0;
}
@media (max-width: 960px) {
  .summary-row {
    grid-template-columns: 1fr;
  }
}
</style>
