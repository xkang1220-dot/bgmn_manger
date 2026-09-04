<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { bizApi } from '@/api/biz'

const list = ref<any[]>([])
const summary = ref<any>({})
const keyword = ref('')
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

const SOURCE_TAG: Record<string, string> = {
  SETTLE: 'primary',
  REIMBURSE: 'warning',
  TRANSFER: 'info',
  SALARY: 'success',
  ROLLBACK: 'danger',
  INCOME: 'success',
  EXPENSE: 'danger',
}

const AVATAR_TONES = ['indigo', 'cyan', 'violet', 'amber'] as const

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

function bizTagType(v?: string) {
  return SOURCE_TAG[v || ''] || 'info'
}

function displayName(row: any) {
  return row.nickname || row.username || row.realName || `用户${row.userId}`
}

function initial(row: any) {
  const name = displayName(row).replace(/\s/g, '')
  return name.slice(0, 1) || '?'
}

function avatarTone(row: any) {
  const name = displayName(row)
  let hash = 0
  for (let i = 0; i < name.length; i++) hash = (hash * 31 + name.charCodeAt(i)) >>> 0
  return AVATAR_TONES[hash % AVATAR_TONES.length]
}

const filteredList = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) return list.value
  return list.value.filter((row) => displayName(row).toLowerCase().includes(q))
})

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

onMounted(load)
</script>

<template>
  <div class="page-stack">
    <div class="page-top">
      <div class="page-top__main">
        <p class="page-desc">看每人余额；点卡片查看钱从哪来（项目分成、报销等到账明细）</p>
      </div>
    </div>

    <div class="stat-grid">
      <div class="stat-card stat-card--violet">
        <div class="stat-body">
          <div class="stat-label">公司总账合计</div>
          <div class="stat-value">¥ {{ fmt(summary.poolTotal) }}</div>
        </div>
        <el-icon class="stat-glyph" :size="48"><OfficeBuilding /></el-icon>
      </div>
      <div class="stat-card stat-card--indigo">
        <div class="stat-body">
          <div class="stat-label">个人钱包合计</div>
          <div class="stat-value">¥ {{ fmt(summary.walletTotal) }}</div>
        </div>
        <el-icon class="stat-glyph" :size="48"><Wallet /></el-icon>
      </div>
      <div class="stat-card stat-card--cyan">
        <div class="stat-body">
          <div class="stat-label">账户人数</div>
          <div class="stat-value">{{ summary.walletCount ?? list.length }}</div>
        </div>
        <el-icon class="stat-glyph" :size="48"><User /></el-icon>
      </div>
    </div>

    <el-form class="filter-bar" @submit.prevent>
      <el-form-item label="姓名">
        <el-input
          v-model="keyword"
          clearable
          placeholder="搜索姓名 / 账号"
          class="filter-keyword filter-keyword--wide"
        />
      </el-form-item>
    </el-form>

    <div v-if="filteredList.length" class="board">
      <article
        v-for="row in filteredList"
        :key="row.id"
        class="person"
        :class="['person--' + avatarTone(row), { 'is-zero': !Number(row.balance) }]"
        role="button"
        tabindex="0"
        @click="openDetail(row)"
        @keyup.enter="openDetail(row)"
      >
        <div class="person-top">
          <span class="avatar" :class="'avatar--' + avatarTone(row)">{{ initial(row) }}</span>
          <div class="person-id">
            <div class="name">{{ displayName(row) }}</div>
            <div class="sub">查看资金来源</div>
          </div>
          <el-icon class="person-go" :size="16"><ArrowRight /></el-icon>
        </div>
        <div class="bal">¥ {{ fmt(row.balance) }}</div>
      </article>
    </div>
    <el-empty
      v-else
      :description="list.length ? '没有匹配的人' : '暂无钱包数据'"
    />

    <el-drawer
      v-model="drawer"
      :title="active ? `${displayName(active)} · 资金来源` : '资金来源'"
      size="560px"
      destroy-on-close
      append-to-body
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
        <el-table :data="ledgers" stripe empty-text="暂无明细">
          <el-table-column label="时间" width="140">
            <template #default="{ row }">{{ fmtTime(row.occurTime) }}</template>
          </el-table-column>
          <el-table-column label="来源" width="108">
            <template #default="{ row }">
              <el-tag :type="bizTagType(row.bizType)" size="small">{{ bizLabel(row.bizType) }}</el-tag>
            </template>
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
        <div v-if="ledgerTotal > ledgerQuery.pageSize" class="page-footer">
          <el-pagination
            v-model:current-page="ledgerQuery.page"
            :page-size="ledgerQuery.pageSize"
            :total="ledgerTotal"
            layout="total, prev, pager, next"
            @current-change="loadLedgers"
          />
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.filter-keyword--wide {
  width: 220px;
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}
.stat-card {
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 96px;
  padding: 18px 16px 18px 20px;
  background: var(--kk-glass-bg);
  border: 1px solid var(--kk-glass-border);
  border-radius: var(--kk-radius);
  box-shadow: var(--kk-glass-shadow);
  backdrop-filter: var(--kk-glass-blur);
  -webkit-backdrop-filter: var(--kk-glass-blur);
}
.stat-card::before {
  content: "";
  position: absolute;
  right: -24px;
  top: 50%;
  width: 110px;
  height: 110px;
  border-radius: 50%;
  transform: translateY(-50%);
  filter: blur(32px);
  opacity: 0.22;
  pointer-events: none;
}
.stat-card--indigo::before { background: #d4d4d8; }
.stat-card--cyan::before { background: #a5f3fc; }
.stat-card--violet::before { background: #ddd6fe; }
.stat-card--indigo .stat-glyph { color: var(--kk-primary); }
.stat-card--cyan .stat-glyph { color: #0891b2; }
.stat-card--violet .stat-glyph { color: #7c3aed; }
.stat-body { position: relative; z-index: 1; min-width: 0; }
.stat-glyph { position: relative; z-index: 1; flex-shrink: 0; opacity: 1; }
.stat-label { font-size: 13px; font-weight: 500; color: var(--kk-text-secondary); }
.stat-value {
  margin-top: 8px;
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
  color: var(--kk-text);
}

.board {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 14px;
}
.person {
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 18px 18px 16px;
  cursor: pointer;
  background: var(--kk-glass-bg);
  border: 1px solid var(--kk-glass-border);
  border-radius: var(--kk-radius);
  box-shadow: var(--kk-glass-shadow);
  backdrop-filter: var(--kk-glass-blur);
  -webkit-backdrop-filter: var(--kk-glass-blur);
  transition: box-shadow 0.15s var(--kk-ease);
}
.person::before {
  content: "";
  position: absolute;
  right: -28px;
  bottom: -36px;
  width: 120px;
  height: 120px;
  border-radius: 50%;
  filter: blur(28px);
  opacity: 0.22;
  pointer-events: none;
}
.person--indigo::before { background: #d4d4d8; }
.person--cyan::before { background: #a5f3fc; }
.person--violet::before { background: #ddd6fe; }
.person--amber::before { background: #fde68a; }
.person:hover { box-shadow: 0 8px 28px rgba(0, 0, 0, 0.08); }
.person:hover .person-go { color: var(--kk-text-secondary); }
.person:focus-visible {
  outline: 2px solid var(--kk-primary);
  outline-offset: 2px;
}
.person-top {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.person-go {
  margin-left: auto;
  flex-shrink: 0;
  color: var(--kk-text-muted);
}
.avatar {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 16px;
  font-weight: 700;
  line-height: 1;
}
.avatar--indigo { background: #f4f4f5; color: #18181b; }
.avatar--cyan { background: #ecfeff; color: #0891b2; }
.avatar--violet { background: #f5f3ff; color: #7c3aed; }
.avatar--amber { background: #fffbeb; color: #d97706; }
.person-id { min-width: 0; }
.name {
  font-weight: 600;
  font-size: 15px;
  color: var(--kk-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sub { margin-top: 2px; font-size: 12px; color: var(--kk-text-muted); }
.bal {
  position: relative;
  z-index: 1;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
  color: var(--kk-text);
}
.person.is-zero .bal { color: var(--kk-text-muted); font-weight: 600; }

.drawer-body { padding: 0 4px 12px; }
.drawer-balance {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  padding: 14px 16px;
  margin-bottom: 16px;
  background: rgba(255, 255, 255, 0.45);
  border: 1px solid var(--kk-glass-border);
  border-radius: var(--kk-radius-sm);
}
.drawer-balance span { color: var(--kk-text-secondary); font-size: 13px; }
.drawer-balance b {
  font-size: 22px;
  color: var(--kk-text);
  font-variant-numeric: tabular-nums;
}
.section-title {
  margin: 8px 0 10px;
  font-size: 14px;
  font-weight: 600;
  color: var(--kk-text);
}
.source-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-bottom: 18px;
}
.source-card {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 12px;
  font-size: 13px;
  background: rgba(255, 255, 255, 0.45);
  border-radius: var(--kk-radius-sm);
}
.source-card span { color: var(--kk-text-secondary); }
.amt-in { color: var(--kk-success); font-weight: 600; font-variant-numeric: tabular-nums; }
.amt-out { color: var(--kk-danger); font-weight: 600; font-variant-numeric: tabular-nums; }

@media (max-width: 1100px) {
  .stat-grid { grid-template-columns: 1fr; }
}
@media (prefers-reduced-transparency: reduce) {
  .stat-card,
  .person {
    background: #fff;
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}
</style>
