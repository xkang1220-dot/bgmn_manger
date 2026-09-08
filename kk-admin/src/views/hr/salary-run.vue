<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bizApi } from '@/api/biz'
import { sysApi } from '@/api/system'

const companies = ref<any[]>([])
const companyId = ref<number | undefined>()
const yearMonth = ref('')
const runs = ref<any[]>([])
const lines = ref<any[]>([])
const activeRunId = ref<number | undefined>()
const loading = ref(false)

function currentYm() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

async function loadMeta() {
  companies.value = await sysApi.myCompanies()
  if (!companyId.value && companies.value.length) companyId.value = companies.value[0].id
  if (!yearMonth.value) yearMonth.value = currentYm()
}

async function loadRuns() {
  if (!companyId.value) return
  loading.value = true
  try {
    runs.value = await bizApi.salaryRuns({
      companyId: companyId.value,
      yearMonth: yearMonth.value || undefined,
    })
    lines.value = []
    activeRunId.value = undefined
  } finally {
    loading.value = false
  }
}

async function openLines(run: any) {
  activeRunId.value = run.id
  lines.value = await bizApi.salaryRunLines(run.id)
}

async function doPreview() {
  if (!companyId.value) return
  await ElMessageBox.confirm('将为本月有配置的人员发送/补发工资预告，是否继续？', '手动预告')
  const run = await bizApi.salaryPreview({ companyId: companyId.value, yearMonth: yearMonth.value })
  ElMessage.success(run?.message || '预告完成')
  await loadRuns()
}

async function doPay() {
  if (!companyId.value) return
  await ElMessageBox.confirm('将为已确认且余额足够的人员生成月度工资审批，是否继续？', '手动发薪')
  const run = await bizApi.salaryPay({ companyId: companyId.value, yearMonth: yearMonth.value })
  ElMessage.success(run?.message || '发薪完成')
  await loadRuns()
}

const statusLabel: Record<string, string> = {
  PENDING_CONFIRM: '待确认',
  CONFIRMED: '已确认',
  APPROVAL_CREATED: '已生成审批',
  SKIPPED: '已跳过',
  FAILED: '失败',
  RUNNING: '进行中',
  DONE: '完成',
  FAILED_RUN: '失败',
}

const statusType: Record<string, string> = {
  PENDING_CONFIRM: 'warning',
  CONFIRMED: 'success',
  APPROVAL_CREATED: 'success',
  SKIPPED: 'info',
  FAILED: 'danger',
  RUNNING: '',
  DONE: 'success',
  FAILED_RUN: 'danger',
}

const triggerLabel: Record<string, string> = {
  AUTO: '定时',
  MANUAL: '手动',
}

watch([companyId, yearMonth], () => loadRuns())

onMounted(async () => {
  await loadMeta()
  await loadRuns()
})
</script>

<template>
  <div class="page-stack">
    <div class="page-card">
      <div class="toolbar">
        <div class="toolbar__left">
          <el-select v-model="companyId" placeholder="公司" style="width: 200px">
            <el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
          <el-input
            v-model="yearMonth"
            placeholder="yyyy-MM"
            clearable
            style="width: 130px"
            @keyup.enter="loadRuns"
          />
          <el-button type="primary" @click="loadRuns">查询</el-button>
        </div>
        <div class="toolbar__right">
          <el-button v-permission="'hr:salary:run:manual'" @click="doPreview">手动预告</el-button>
          <el-button v-permission="'hr:salary:run:manual'" type="primary" @click="doPay">手动发薪</el-button>
        </div>
      </div>

      <p class="tip">点击一行可查看该批次明细。预告需员工确认后，发薪才会生成审批。</p>

      <el-table
        v-loading="loading"
        :data="runs"
        stripe
        highlight-current-row
        class="run-table"
        @row-click="openLines"
      >
        <el-table-column prop="yearMonth" label="月份" width="110" />
        <el-table-column prop="phase" label="阶段" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.phase === 'PREVIEW' ? 'warning' : 'success'" effect="plain">
              {{ row.phase === 'PREVIEW' ? '预告' : '发薪' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="(statusType[row.status] as any) || 'info'">
              {{ statusLabel[row.status] || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="triggerType" label="触发" width="90">
          <template #default="{ row }">{{ triggerLabel[row.triggerType] || row.triggerType || '—' }}</template>
        </el-table-column>
        <el-table-column prop="message" label="摘要" min-width="220" show-overflow-tooltip />
        <el-table-column prop="startedAt" label="开始时间" min-width="170" />
      </el-table>
    </div>

    <div v-if="activeRunId" class="page-card">
      <div class="detail-head">
        <h3 class="card-title">批次明细</h3>
        <span class="detail-id">#{{ activeRunId }}</span>
      </div>
      <el-table :data="lines" stripe>
        <el-table-column prop="userName" label="人员" width="120" />
        <el-table-column prop="totalAmount" label="合计" width="110" align="right">
          <template #default="{ row }">
            {{ row.totalAmount != null ? `¥ ${row.totalAmount}` : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="(statusType[row.status] as any) || 'info'">
              {{ statusLabel[row.status] || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="skipReason" label="原因" min-width="180" show-overflow-tooltip />
        <el-table-column prop="approvalId" label="审批单" width="100" />
        <el-table-column prop="confirmedAt" label="确认时间" min-width="170" />
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.tip {
  margin: 0 0 14px;
  color: #64748b;
  font-size: 13px;
  line-height: 1.5;
}

.run-table :deep(.el-table__row) {
  cursor: pointer;
}

.detail-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 12px;
}

.card-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.detail-id {
  color: #94a3b8;
  font-size: 13px;
}
</style>
