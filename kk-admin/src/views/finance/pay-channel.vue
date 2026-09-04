<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { bizApi } from '@/api/biz'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const canEdit = computed(() => userStore.hasPermission('finance:channel:edit'))

const list = ref<any[]>([])
const pools = ref<any[]>([])
const dialog = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const form = reactive<any>({
  id: undefined,
  poolId: undefined,
  channelType: 'ALIPAY',
  name: '',
  accountNo: '',
  accountName: '',
  bankName: '',
  sort: 0,
  status: 1,
  remark: '',
})

const TYPE_OPTS = [
  { value: 'ALIPAY', label: '支付宝', icon: 'Wallet', tone: 'indigo' },
  { value: 'WECHAT', label: '微信', icon: 'ChatDotRound', tone: 'cyan' },
  { value: 'BANK', label: '银行卡', icon: 'CreditCard', tone: 'violet' },
  { value: 'CASH', label: '现金', icon: 'Coin', tone: 'amber' },
  { value: 'OTHER', label: '其他', icon: 'More', tone: 'slate' },
]

const TYPE_MAP = Object.fromEntries(TYPE_OPTS.map((o) => [o.value, o]))

const totalBalance = computed(() => list.value.reduce((s, c) => s + Number(c.balance || 0), 0))
const activeCount = computed(() => list.value.filter((c) => c.status !== 0).length)

function typeMeta(c: any) {
  return TYPE_MAP[c.channelType] || TYPE_OPTS[TYPE_OPTS.length - 1]
}

function typeLabel(c: any) {
  return c.channelTypeLabel || typeMeta(c).label
}

function fmt(n?: number) {
  return Number(n || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function accountLine(c: any) {
  const name = c.accountName || ''
  const no = c.accountNo || ''
  if (name && no) return `${name} · ${no}`
  if (name) return name
  if (no) return no
  return '未填账号'
}

async function load() {
  list.value = await bizApi.payChannelList({ all: true })
  pools.value = await bizApi.poolList()
}

function open(row?: any) {
  if (!canEdit.value) {
    ElMessage.warning('没有编辑渠道权限')
    return
  }
  isEdit.value = !!row
  Object.assign(form, row || {
    id: undefined,
    poolId: pools.value.find((p) => p.isDefault === 1)?.id ?? pools.value[0]?.id,
    channelType: 'ALIPAY',
    name: '',
    accountNo: '',
    accountName: '',
    bankName: '',
    sort: 0,
    status: 1,
    remark: '',
  })
  dialog.value = true
}

async function save() {
  if (!canEdit.value) return
  if (!form.name?.trim()) {
    ElMessage.warning('请填写渠道名称')
    return
  }
  if (!form.poolId) {
    ElMessage.warning('请选择资金池')
    return
  }
  saving.value = true
  try {
    await bizApi.savePayChannel(form, isEdit.value)
    ElMessage.success('已保存')
    dialog.value = false
    await load()
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page-stack">
    <div class="page-top">
      <div class="page-top__main">
        <p class="page-desc">支付宝 / 微信 / 银行卡等；入账时选择渠道，余额按净额累计</p>
      </div>
      <div class="page-actions">
        <el-button v-if="canEdit" type="primary" @click="open()">新建渠道</el-button>
      </div>
    </div>

    <div class="stat-grid">
      <div class="stat-card stat-card--indigo">
        <div class="stat-body">
          <div class="stat-label">渠道余额合计</div>
          <div class="stat-value">¥ {{ fmt(totalBalance) }}</div>
        </div>
        <el-icon class="stat-glyph" :size="48"><Wallet /></el-icon>
      </div>
      <div class="stat-card stat-card--cyan">
        <div class="stat-body">
          <div class="stat-label">启用渠道</div>
          <div class="stat-value">{{ activeCount }}<span class="stat-unit"> / {{ list.length }}</span></div>
        </div>
        <el-icon class="stat-glyph" :size="48"><CreditCard /></el-icon>
      </div>
    </div>

    <div v-if="list.length" class="board">
      <article
        v-for="c in list"
        :key="c.id"
        class="wallet"
        :class="[`wallet--${typeMeta(c).tone}`, { 'is-off': c.status === 0 }]"
      >
        <div class="wallet-top">
          <span class="wallet-chip" :class="'wallet-chip--' + typeMeta(c).tone">
            <el-icon :size="16"><component :is="typeMeta(c).icon" /></el-icon>
            {{ typeLabel(c) }}
          </span>
          <el-tag v-if="c.status === 0" size="small" type="info">停用</el-tag>
          <el-button v-if="canEdit" class="wallet-edit" link type="primary" @click="open(c)">编辑</el-button>
        </div>
        <h4 class="wallet-name">{{ c.name }}</h4>
        <div class="wallet-bal">¥ {{ fmt(c.balance) }}</div>
        <div class="wallet-meta">
          <span>{{ accountLine(c) }}</span>
          <span>归属 {{ c.poolName || '—' }}</span>
        </div>
      </article>
    </div>
    <el-empty v-else description="暂无收款渠道" />

    <el-dialog
      v-model="dialog"
      :title="isEdit ? '编辑渠道' : '新建渠道'"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-form label-width="100px" @submit.prevent>
        <el-form-item label="类型">
          <el-select v-model="form.channelType" style="width: 100%">
            <el-option v-for="o in TYPE_OPTS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如：公司支付宝" maxlength="40" />
        </el-form-item>
        <el-form-item label="资金池" required>
          <el-select v-model="form.poolId" style="width: 100%">
            <el-option v-for="p in pools" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="户名"><el-input v-model="form.accountName" /></el-form-item>
        <el-form-item label="账号"><el-input v-model="form.accountNo" placeholder="可脱敏，如 ****8888" /></el-form-item>
        <el-form-item v-if="form.channelType === 'BANK'" label="开户行">
          <el-input v-model="form.bankName" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.stat-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
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
.stat-card--indigo .stat-glyph { color: var(--kk-primary); }
.stat-card--cyan .stat-glyph { color: #0891b2; }
.stat-body { position: relative; z-index: 1; min-width: 0; }
.stat-glyph { position: relative; z-index: 1; flex-shrink: 0; }
.stat-label { font-size: 13px; font-weight: 500; color: var(--kk-text-secondary); }
.stat-value {
  margin-top: 8px;
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
  color: var(--kk-text);
}
.stat-unit {
  margin-left: 4px;
  font-size: 14px;
  font-weight: 500;
  color: var(--kk-text-muted);
}

.board {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
}
.wallet {
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  min-height: 188px;
  padding: 20px 20px 16px;
  background: var(--kk-glass-bg);
  border: 1px solid var(--kk-glass-border);
  border-radius: var(--kk-radius);
  box-shadow: var(--kk-glass-shadow);
  backdrop-filter: var(--kk-glass-blur);
  -webkit-backdrop-filter: var(--kk-glass-blur);
}
.wallet::before {
  content: "";
  position: absolute;
  right: -20px;
  bottom: -40px;
  width: 140px;
  height: 140px;
  border-radius: 50%;
  filter: blur(28px);
  opacity: 0.22;
  pointer-events: none;
}
.wallet--indigo::before { background: #d4d4d8; }
.wallet--cyan::before { background: #a5f3fc; }
.wallet--violet::before { background: #ddd6fe; }
.wallet--amber::before { background: #fde68a; }
.wallet--slate::before { background: #e2e8f0; }
.wallet.is-off { opacity: 0.62; }
.wallet-top {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 8px;
}
.wallet-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 10px 0 8px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
}
.wallet-chip--indigo { background: #f4f4f5; color: #18181b; }
.wallet-chip--cyan { background: #ecfeff; color: #0891b2; }
.wallet-chip--violet { background: #f5f3ff; color: #7c3aed; }
.wallet-chip--amber { background: #fffbeb; color: #d97706; }
.wallet-chip--slate { background: #f4f4f5; color: #52525b; }
.wallet-edit { margin-left: auto; }
.wallet-name {
  position: relative;
  z-index: 1;
  margin: 16px 0 6px;
  font-size: 16px;
  font-weight: 600;
  color: var(--kk-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wallet-bal {
  position: relative;
  z-index: 1;
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
  color: var(--kk-text);
}
.wallet-meta {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-top: auto;
  padding-top: 14px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--kk-text-muted);
}

@media (max-width: 1100px) {
  .stat-grid { grid-template-columns: 1fr; }
}
@media (prefers-reduced-transparency: reduce) {
  .stat-card,
  .wallet {
    background: #fff;
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}
</style>
