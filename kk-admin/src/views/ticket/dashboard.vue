<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { bizApi } from '@/api/biz'
import { sysApi } from '@/api/system'
import { ticketApi, ticketStatusLabel, ticketTypeLabel } from '@/api/ticket'

const router = useRouter()
const companies = ref<any[]>([])
const allProjects = ref<any[]>([])
const companyId = ref<number | undefined>()
const projectId = ref<number | undefined>()
const cycleId = ref<number | undefined>()
const cycles = ref<any[]>([])
const loading = ref(false)
const overview = ref<any>(null)
const syncingFilter = ref(false)

const projects = computed(() => {
  if (!companyId.value) return []
  return allProjects.value.filter((p) => Number(p.companyId) === Number(companyId.value))
})

const kpis = computed(() => overview.value?.kpis || {})
const warnings = computed(() => overview.value?.warnings || [])
const typeDist = computed(() => overview.value?.typeDistribution || [])
const statusDist = computed(() => overview.value?.statusDistribution || [])
const bugTrend = computed(() => overview.value?.bugTrend || [])
const snapshot = computed(() => overview.value?.cycleSnapshot || [])
const myStats = computed(() => overview.value?.mySubmissions || {})
const maxBug = computed(() => Math.max(1, ...bugTrend.value.map((x: any) => Number(x.count || 0))))
const maxType = computed(() => Math.max(1, ...typeDist.value.map((x: any) => Number(x.value || 0))))
const maxStatus = computed(() => Math.max(1, ...statusDist.value.map((x: any) => Number(x.value || 0))))

function barWidth(value: number | string, max: number) {
  return `${(Number(value || 0) / max) * 100}%`
}

async function loadCompanies() {
  companies.value = await sysApi.myCompanies()
  if (!companyId.value && companies.value.length) {
    companyId.value = companies.value[0].id
  }
}

async function loadProjects() {
  allProjects.value = await bizApi.projectList().catch(() => [])
}

async function loadCycles() {
  if (!companyId.value) {
    cycles.value = []
    return
  }
  cycles.value = await ticketApi.cycleList(companyId.value).catch(() => [])
}

async function load() {
  if (!companyId.value) return
  loading.value = true
  try {
    overview.value = await ticketApi.dashboardOverview(
      companyId.value,
      cycleId.value,
      projectId.value,
    )
  } finally {
    loading.value = false
  }
}

function onSecondaryFilter() {
  if (syncingFilter.value) return
  load()
}

async function onCompanyChange() {
  syncingFilter.value = true
  cycleId.value = undefined
  projectId.value = undefined
  await loadCycles()
  await nextTick()
  syncingFilter.value = false
  await load()
}

onMounted(async () => {
  await Promise.all([loadCompanies(), loadProjects()])
  await loadCycles()
  await load()
})
</script>

<template>
  <div class="page-stack">
  <div class="page-card" v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="title">任务看板</h2>
        <p class="sub">工单 KPI、分布与预警（按公司）</p>
      </div>
      <div class="filters">
        <el-select v-model="companyId" placeholder="公司" style="width: 180px" @change="onCompanyChange">
          <el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-select
          v-model="projectId"
          clearable
          filterable
          placeholder="项目"
          style="width: 200px"
          @change="onSecondaryFilter"
        >
          <el-option
            v-for="p in projects"
            :key="p.id"
            :label="p.code ? `${p.name}（${p.code}）` : p.name"
            :value="p.id"
          />
        </el-select>
        <el-select
          v-model="cycleId"
          clearable
          placeholder="周期（默认进行中）"
          style="width: 200px"
          @change="onSecondaryFilter"
        >
          <el-option v-for="c in cycles" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-button @click="router.push('/ticket/submissions')">我的提交</el-button>
      </div>
    </div>

    <div class="kpi-grid">
      <div class="kpi"><div class="n">{{ kpis.total || 0 }}</div><div class="l">总数</div></div>
      <div class="kpi"><div class="n">{{ kpis.inProgress || 0 }}</div><div class="l">进行中</div></div>
      <div class="kpi"><div class="n">{{ kpis.pendingBugs || 0 }}</div><div class="l">待修 BUG</div></div>
      <div class="kpi"><div class="n">{{ kpis.avgFixDays || 0 }}</div><div class="l">平均修复天</div></div>
      <div class="kpi"><div class="n">{{ kpis.cycleCompleteRate || 0 }}%</div><div class="l">周期完成率</div></div>
      <div class="kpi"><div class="n">{{ kpis.slaRate || 0 }}%</div><div class="l">SLA</div></div>
    </div>

    <div v-if="warnings.length" class="warnings">
      <div v-for="(w, i) in warnings" :key="i" class="warn">{{ w.message }}</div>
    </div>

    <div class="panels">
      <div class="panel">
        <h3>类型分布</h3>
        <div v-for="item in typeDist" :key="item.name" class="bar-row">
          <span class="label">{{ ticketTypeLabel(item.name) }}</span>
          <div class="bar"><i :style="{ width: barWidth(item.value, maxType) }" /></div>
          <span class="val">{{ item.value }}</span>
        </div>
        <el-empty v-if="!typeDist.length" :image-size="48" description="暂无数据" />
      </div>
      <div class="panel">
        <h3>状态分布</h3>
        <div v-for="item in statusDist" :key="item.name" class="bar-row">
          <span class="label">{{ ticketStatusLabel(item.name) }}</span>
          <div class="bar"><i :style="{ width: barWidth(item.value, maxStatus) }" /></div>
          <span class="val">{{ item.value }}</span>
        </div>
        <el-empty v-if="!statusDist.length" :image-size="48" description="暂无数据" />
      </div>
      <div class="panel">
        <h3>近 14 日 BUG 新增</h3>
        <div class="trend">
          <div v-for="d in bugTrend" :key="d.date" class="trend-col" :title="`${d.date}: ${d.count}`">
            <div class="col" :style="{ height: (Number(d.count) / maxBug) * 100 + '%' }" />
          </div>
        </div>
      </div>
      <div class="panel">
        <h3>我的提交</h3>
        <p>总数 {{ myStats.total || 0 }} · 完成 {{ myStats.completed || 0 }} · 完成率 {{ myStats.rate || 0 }}%</p>
        <el-button type="primary" link @click="router.push('/ticket/submissions')">查看我的工单</el-button>
      </div>
    </div>

    <div class="panel snapshot">
      <h3>周期任务快照</h3>
      <el-table :data="snapshot" size="small">
        <el-table-column prop="ticketNo" label="编号" min-width="140" />
        <el-table-column prop="title" label="标题" min-width="160" show-overflow-tooltip />
        <el-table-column label="类型" width="80">
          <template #default="{ row }">{{ ticketTypeLabel(row.type) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">{{ ticketStatusLabel(row.status) }}</template>
        </el-table-column>
        <el-table-column prop="progress" label="进度" width="80" />
        <el-table-column prop="submitterName" label="提交人" width="100" />
      </el-table>
    </div>
  </div>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}
.title {
  margin: 0;
  font-size: 20px;
  font-weight: 650;
}
.sub {
  margin: 6px 0 0;
  color: #71717a;
  font-size: 13px;
}
.filters {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
}
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}
.kpi {
  padding: 14px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.42);
  border: 1px solid rgba(255, 255, 255, 0.7);
}
.kpi .n {
  font-size: 22px;
  font-weight: 650;
}
.kpi .l {
  margin-top: 4px;
  font-size: 12px;
  color: #71717a;
}
.warnings {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 14px;
}
.warn {
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(220, 38, 38, 0.08);
  color: #991b1b;
  font-size: 13px;
}
.panels {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.panel {
  padding: 14px 16px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.38);
  border: 1px solid rgba(255, 255, 255, 0.7);
}
.panel h3 {
  margin: 0 0 12px;
  font-size: 15px;
}
.bar-row {
  display: grid;
  grid-template-columns: 72px 1fr 36px;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
  font-size: 13px;
}
.bar {
  height: 8px;
  border-radius: 999px;
  background: rgba(24, 24, 27, 0.08);
  overflow: hidden;
}
.bar i {
  display: block;
  height: 100%;
  background: #2563eb;
  border-radius: inherit;
}
.trend {
  display: flex;
  align-items: flex-end;
  gap: 4px;
  height: 120px;
}
.trend-col {
  flex: 1;
  height: 100%;
  display: flex;
  align-items: flex-end;
}
.trend-col .col {
  width: 100%;
  min-height: 2px;
  border-radius: 4px 4px 0 0;
  background: #dc2626;
}
.snapshot {
  margin-top: 12px;
}
@media (max-width: 960px) {
  .kpi-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
  .panels {
    grid-template-columns: 1fr;
  }
}
</style>
