<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { bizApi } from '@/api/biz'

const route = useRoute()
const yearMonth = ref('')
const list = ref<any[]>([])
const loading = ref(false)

function isWeeklyRow(row: any) {
  return row?.cycleType === 'WEEKLY' || row?.payload?.cycleType === 'WEEKLY' || /^(\d{4})-W(\d{2})$/.test(String(row?.yearMonth || ''))
}

function scopeWord(row?: any) {
  return isWeeklyRow(row) ? '本周' : '本月'
}

function fmtMoney(v: any) {
  const n = Number(v)
  if (Number.isNaN(n)) return '0.00'
  return n.toFixed(2)
}

function grossOf(row: any) {
  const p = row?.payload || {}
  if (p.grossAmount != null) return p.grossAmount
  return row?.totalAmount
}

function deductionOf(row: any) {
  return Number(row?.payload?.deductionAmount || 0)
}

async function load() {
  loading.value = true
  try {
    list.value = await bizApi.mySalaryConfirm(yearMonth.value || undefined)
  } finally {
    loading.value = false
  }
}

async function confirm(row: any) {
  await bizApi.confirmSalary(row.lineId)
  ElMessage.success(`已确认，将进入${scopeWord(row)}发薪队列`)
  await load()
}

async function revoke(row: any) {
  await bizApi.revokeSalaryConfirm(row.lineId)
  ElMessage.success('已撤销确认')
  await load()
}

function itemsOf(row: any) {
  return row?.payload?.items || []
}

watch(yearMonth, () => load())

onMounted(() => {
  const q = route.query.yearMonth
  // 有链接参数时按周期筛；从菜单进入则不传，后端同时返回本月+本周待确认
  yearMonth.value = typeof q === 'string' && q ? q : ''
  load()
})
</script>

<template>
  <div class="page-stack">
    <div class="page-card">
      <div class="toolbar">
        <el-input v-model="yearMonth" placeholder="yyyy-MM 或 yyyy-Www" style="width: 180px" />
        <el-button @click="load">刷新</el-button>
      </div>
      <p class="tip">请核对项目金额、考勤扣款与备注后确认。未确认将不进入当期发薪。</p>

      <div v-loading="loading">
        <el-empty v-if="!list.length" description="暂无待确认工资" />
        <div v-for="row in list" :key="row.lineId" class="confirm-card">
          <div class="confirm-head">
            <div>
              <strong>{{ row.companyName || '公司' }}</strong>
              <span class="ym">{{ row.yearMonth }}</span>
              <el-tag size="small" effect="plain" style="margin-left: 8px">
                {{ isWeeklyRow(row) ? '周结' : '月结' }}
              </el-tag>
              <el-tag
                v-if="row.payload?.fullAttendance === false"
                size="small"
                type="warning"
                effect="plain"
                style="margin-left: 6px"
              >
                请假 {{ row.payload?.leaveDays || 0 }} 天
              </el-tag>
            </div>
            <el-tag :type="row.status === 'CONFIRMED' ? 'success' : 'warning'">
              {{ row.status === 'CONFIRMED' ? '已确认' : '待确认' }}
            </el-tag>
          </div>
          <el-table :data="itemsOf(row)" size="small">
            <el-table-column prop="projectName" label="项目" />
            <el-table-column label="金额" width="120" align="right">
              <template #default="{ row: item }">¥{{ fmtMoney(item.amount) }}</template>
            </el-table-column>
          </el-table>
          <div class="money-row">
            <span>应发 <b>¥{{ fmtMoney(grossOf(row)) }}</b></span>
            <span v-if="deductionOf(row) > 0">
              扣款 <b class="ded">−¥{{ fmtMoney(deductionOf(row)) }}</b>
              <template v-if="row.payload?.deductionRemark">
                （{{ row.payload.deductionRemark }}）
              </template>
            </span>
            <span>实发 <b>¥{{ fmtMoney(row.totalAmount) }}</b></span>
          </div>
          <div v-if="row.payload?.leaveDates?.length" class="leave-tip">
            请假日：{{ row.payload.leaveDates.join('、') }}
          </div>
          <div class="confirm-foot">
            <span />
            <div>
              <el-button
                v-if="row.status === 'PENDING_CONFIRM'"
                type="primary"
                @click="confirm(row)"
              >确认{{ scopeWord(row) }}工资</el-button>
              <el-button
                v-if="row.status === 'CONFIRMED'"
                @click="revoke(row)"
              >撤销确认</el-button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 10px;
  margin-bottom: 8px;
}
.tip {
  color: #64748b;
  font-size: 13px;
  margin: 0 0 16px;
}
.confirm-card {
  border: 1px solid rgba(255, 255, 255, 0.5);
  border-radius: 14px;
  padding: 16px;
  margin-bottom: 14px;
  background: rgba(255, 255, 255, 0.35);
}
.confirm-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}
.ym {
  margin-left: 10px;
  color: #64748b;
}
.money-row {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-top: 12px;
  font-size: 13px;
  color: #475569;
}
.money-row .ded {
  color: #b45309;
}
.leave-tip {
  margin-top: 8px;
  font-size: 12px;
  color: #94a3b8;
}
.confirm-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 12px;
}
</style>
