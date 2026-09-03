<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { bizApi } from '@/api/biz'

const list = ref<any[]>([])
const summary = ref<any>({})
const drawer = ref(false)
const active = ref<any>(null)
const ledgers = ref<any[]>([])
const allForSummary = ref<any[]>([])
const ledgerTotal = ref(0)
const loadingDetail = ref(false)
const ledgerQuery = reactive({ page: 1, pageSize: 20 })

const SOURCE_LABEL: Record<string, string> = {
  SETTLE: '项目分成',
  REIMBURSE: '报销到账',
  TRANSFER: '历史划拨',
  SALARY: '工资到账',
  ROLLBACK: '资金回退',
  INCOME: '其他入账',
  EXPENSE: '其他出账',
}

function fmt(n?: number) {
  return Number(n || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
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

function bizLabel(v?: string) {
  return SOURCE_LABEL[v || ''] || v || '—'
}

/** 按类型汇总来源 */
const sourceSummary = computed(() => {
  const map = new Map<string, number>()
  for (const row of allForSummary.value) {
    const type = row.bizType || 'OTHER'
    map.set(type, (map.get(type) || 0) + Number(row.amount || 0))
  }
  return [...map.entries()]
    .map(([bizType, amount]) => ({ bizType, amount, label: bizLabel(bizType) }))
    .sort((a, b) => Math.abs(b.amount) - Math.abs(a.amount))
})

async function load() {
  summary.value = await bizApi.summary()
  const res = await bizApi.walletPage({ page: 1, pageSize: 200 })
  list.value = res.list
}

async function openDetail(row: any) {
  active.value = row
  drawer.value = true
  ledgerQuery.page = 1
  allForSummary.value = []
  await Promise.all([loadLedgers(), loadSummaryLedgers()])
}

async function loadSummaryLedgers() {
  if (!active.value?.userId) return
  try {
    const res = await bizApi.walletUserLedger(active.value.userId, { page: 1, pageSize: 500 })
    allForSummary.value = res.list
  } catch {
    allForSummary.value = []
  }
}

async function loadLedgers() {
  if (!active.value?.userId) return
  loadingDetail.value = true
  try {
    const res = await bizApi.walletUserLedger(active.value.userId, {
      page: ledgerQuery.page,
      pageSize: ledgerQuery.pageSize,
    })
    ledgers.value = res.list
    ledgerTotal.value = res.total
  } catch (e: any) {
    ledgers.value = []
    ledgerTotal.value = 0
    ElMessage.error(e?.message || '加载资金明细失败')
  } finally {
    loadingDetail.value = false
  }
}

function displayName(row: any) {
  return row.nickname || row.username || row.realName || `用户${row.userId}`
}

onMounted(load)
</script>

<template>
  <div class="page-card">
    <div class="page-header">
      <div>
        <h3 class="page-title">全员钱包</h3>
        <p class="page-desc">看每人余额；点开卡片可查看钱从哪来（项目分成、报销等到账明细）</p>
      </div>
    </div>

    <div class="summary">
      <div class="s-card">
        <span>公司总账合计</span>
        <b>¥ {{ fmt(summary.poolTotal) }}</b>
      </div>
      <div class="s-card">
        <span>个人钱包合计</span>
        <b>¥ {{ fmt(summary.walletTotal) }}</b>
      </div>
      <div class="s-card">
        <span>账户人数</span>
        <b>{{ summary.walletCount ?? list.length }}</b>
      </div>
    </div>

    <div class="board">
      <article v-for="row in list" :key="row.id" class="person" @click="openDetail(row)">
        <div class="name">{{ displayName(row) }}</div>
        <div class="bal">¥ {{ fmt(row.balance) }}</div>
        <div class="sub">点击查看资金来源明细</div>
      </article>
    </div>
    <el-empty v-if="!list.length" description="暂无钱包数据" />

    <el-drawer
      v-model="drawer"
      :title="active ? `${displayName(active)} · 资金来源` : '资金来源'"
      size="560px"
      destroy-on-close
    >
      <div v-if="active" class="drawer-body" v-loading="loadingDetail">
        <div class="drawer-balance">
          <span>当前余额</span>
          <b>¥ {{ fmt(active.balance) }}</b>
        </div>

        <div class="section-title">来源汇总</div>
        <div v-if="sourceSummary.length" class="source-grid">
          <div v-for="item in sourceSummary" :key="item.bizType" class="source-card">
            <span>{{ item.label }}</span>
            <b :class="amountClass(item.amount)">{{ fmtMoney(item.amount, true) }}</b>
          </div>
        </div>
        <el-empty v-else description="暂无流水" :image-size="64" />

        <div class="section-title">明细流水</div>
        <el-table :data="ledgers" stripe size="small">
          <el-table-column label="时间" width="140">
            <template #default="{ row }">{{ fmtTime(row.occurTime) }}</template>
          </el-table-column>
          <el-table-column label="来源" width="100">
            <template #default="{ row }">{{ bizLabel(row.bizType) }}</template>
          </el-table-column>
          <el-table-column prop="title" label="说明" min-width="140" show-overflow-tooltip />
          <el-table-column prop="projectName" label="项目" width="110" show-overflow-tooltip>
            <template #default="{ row }">{{ row.projectName || '—' }}</template>
          </el-table-column>
          <el-table-column label="金额" width="110" align="right">
            <template #default="{ row }">
              <span :class="amountClass(row.amount)">{{ fmtMoney(row.amount, true) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          style="margin-top: 12px"
          v-model:current-page="ledgerQuery.page"
          :page-size="ledgerQuery.pageSize"
          :total="ledgerTotal"
          layout="total, prev, pager, next"
          @current-change="loadLedgers"
        />
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.page-desc { margin: 4px 0 0; color: #64748b; font-size: 13px; }
.summary { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-bottom: 16px; }
.s-card { background: #f8fafc; border-radius: 10px; padding: 14px 16px; }
.s-card span { display: block; font-size: 12px; color: #94a3b8; }
.s-card b { font-size: 20px; }
.board { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 12px; }
.person {
  border: 1px solid #e8edf4; border-radius: 12px; padding: 16px; background: #fff;
  cursor: pointer; transition: box-shadow .15s ease;
}
.person:hover { box-shadow: 0 6px 18px rgba(15, 23, 42, .08); }
.name { font-weight: 600; margin-bottom: 8px; }
.bal { font-size: 18px; font-weight: 700; color: #0f172a; }
.sub { margin-top: 8px; font-size: 12px; color: #64748b; }
.drawer-body { padding: 0 4px 12px; }
.drawer-balance {
  display: flex; justify-content: space-between; align-items: baseline;
  padding: 12px 14px; background: #eef2ff; border-radius: 10px; margin-bottom: 16px;
}
.drawer-balance span { color: #64748b; font-size: 13px; }
.drawer-balance b { font-size: 22px; color: #1d4ed8; }
.section-title {
  margin: 8px 0 10px; font-size: 14px; font-weight: 600; color: #0f172a;
}
.source-grid {
  display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-bottom: 18px;
}
.source-card {
  background: #f8fafc; border-radius: 8px; padding: 10px 12px;
  display: flex; justify-content: space-between; gap: 8px; font-size: 13px;
}
.source-card span { color: #64748b; }
.amt-in { color: #16a34a; font-weight: 600; }
.amt-out { color: #dc2626; font-weight: 600; }
</style>
