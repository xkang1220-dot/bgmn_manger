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
        <div class="stat-icon">
          <el-icon :size="22"><component :is="card.icon" /></el-icon>
        </div>
        <div class="stat-body">
          <div class="stat-label">{{ card.label }}</div>
          <div class="stat-value">
            <template v-if="card.key.includes('Total')">¥ {{ fmtMoney(summary[card.key]) }}</template>
            <template v-else>{{ summary[card.key] ?? 0 }}</template>
          </div>
        </div>
      </div>
    </div>

    <div class="page-card">
      <div class="section-head">
        <h3 class="page-title">资金池一览</h3>
        <span class="section-tip">公司资金统一存放于此</span>
      </div>
      <el-table :data="summary.pools || []" stripe>
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

.welcome-title {
  margin: 0;
  font-size: 24px;
  font-weight: 800;
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
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px;
  border-radius: var(--kk-radius);
  border: 1px solid var(--kk-card-border);
  background: #fff;
  box-shadow: var(--kk-card-shadow);
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}

.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--kk-card-shadow-hover);
}

.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.stat-card--indigo .stat-icon {
  background: linear-gradient(135deg, #eef2ff, #e0e7ff);
  color: #4f46e5;
}
.stat-card--cyan .stat-icon {
  background: linear-gradient(135deg, #ecfeff, #cffafe);
  color: #0891b2;
}
.stat-card--violet .stat-icon {
  background: linear-gradient(135deg, #f5f3ff, #ede9fe);
  color: #7c3aed;
}
.stat-card--amber .stat-icon {
  background: linear-gradient(135deg, #fffbeb, #fef3c7);
  color: #d97706;
}

.stat-label {
  font-size: 13px;
  color: var(--kk-text-secondary);
}

.stat-value {
  margin-top: 6px;
  font-size: 26px;
  font-weight: 800;
  letter-spacing: -0.03em;
  color: var(--kk-text);
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
  font-weight: 700;
  color: var(--kk-primary);
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
</style>
