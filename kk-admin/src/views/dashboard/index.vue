<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { bizApi } from '@/api/biz'

const loading = ref(false)
const summary = ref<any>({})

const statCards = [
  { key: 'poolTotal', label: '资金池总额', icon: 'Wallet', tone: 'indigo' },
  { key: 'walletTotal', label: '个人分成总额', icon: 'Coin', tone: 'cyan' },
  { key: 'poolCount', label: '资金池数量', icon: 'OfficeBuilding', tone: 'violet' },
  { key: 'projectCount', label: '进行中项目', icon: 'FolderOpened', tone: 'amber' },
]

function fmtMoney(n?: number) {
  return Number(n ?? 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

onMounted(async () => {
  loading.value = true
  try {
    summary.value = await bizApi.summary()
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div v-loading="loading" class="dashboard">
    <div class="welcome">
      <div>
        <h2 class="welcome-title">工作台</h2>
        <p class="welcome-desc">资金池、项目分钱与个人钱包一览</p>
      </div>
    </div>

    <div class="stat-grid">
      <div
        v-for="card in statCards"
        :key="card.key"
        class="stat-card"
        :class="`stat-card--${card.tone}`"
      >
        <div class="stat-body">
          <div class="stat-label">{{ card.label }}</div>
          <div class="stat-value">
            <template v-if="card.key.includes('Total')">¥ {{ fmtMoney(summary[card.key]) }}</template>
            <template v-else>{{ summary[card.key] ?? 0 }}</template>
          </div>
        </div>
        <el-icon class="stat-glyph" :size="64"><component :is="card.icon" /></el-icon>
      </div>
    </div>

    <div class="glass-panel">
      <div class="section-head">
        <h3 class="page-title">资金池一览</h3>
        <span class="section-tip">公司资金统一存放于此</span>
      </div>
      <el-table :data="summary.pools || []">
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column label="余额" width="160" align="right">
          <template #default="{ row }">
            <span class="money">¥ {{ fmtMoney(row.balance) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="默认" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.isDefault === 1" type="success" size="small" effect="light">默认</el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="200" show-overflow-tooltip />
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.dashboard {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.dashboard > .glass-panel {
  flex: 1 0 auto;
}

.welcome-title {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
  letter-spacing: -0.03em;
  color: var(--kk-text);
}

.welcome-desc {
  margin: 6px 0 0;
  color: var(--kk-text-secondary);
  font-size: 14px;
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.stat-card {
  border-radius: var(--kk-radius);
  background: var(--kk-glass-bg);
  border: 1px solid var(--kk-glass-border);
  box-shadow: var(--kk-glass-shadow);
  backdrop-filter: var(--kk-glass-blur);
  -webkit-backdrop-filter: var(--kk-glass-blur);
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 108px;
  padding: 22px 18px 22px 22px;
  transition: transform 0.2s var(--kk-ease), box-shadow 0.2s var(--kk-ease);
}

.stat-card::before {
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

.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.08);
}

.stat-card--indigo::before { background: #d4d4d8; }
.stat-card--cyan::before { background: #a5f3fc; }
.stat-card--violet::before { background: #ddd6fe; }
.stat-card--amber::before { background: #fde68a; }

.stat-card--indigo .stat-glyph { color: var(--kk-primary); }
.stat-card--cyan .stat-glyph { color: #0891b2; }
.stat-card--violet .stat-glyph { color: #7c3aed; }
.stat-card--amber .stat-glyph { color: #d97706; }

.stat-body {
  position: relative;
  z-index: 1;
  min-width: 0;
}

.stat-glyph {
  position: relative;
  z-index: 1;
  flex-shrink: 0;
  margin-right: -6px;
  opacity: 1;
}

.stat-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--kk-text-secondary);
}

.stat-value {
  margin-top: 8px;
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
  color: var(--kk-text);
}

.glass-panel {
  padding: 22px 24px;
}

.section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.section-tip {
  font-size: 13px;
  color: var(--kk-text-muted);
}

.money {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--kk-text);
}

@media (max-width: 1100px) {
  .stat-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 640px) {
  .stat-grid {
    grid-template-columns: 1fr;
  }
}

@media (prefers-reduced-transparency: reduce) {
  .stat-card {
    background: #fff;
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .stat-card {
    transition: none;
  }
  .stat-card:hover {
    transform: none;
  }
}
</style>
