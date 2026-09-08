<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { bizApi } from '@/api/biz'

const route = useRoute()
const yearMonth = ref('')
const list = ref<any[]>([])
const loading = ref(false)

function currentYm() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
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
  ElMessage.success('已确认，将进入本月发薪队列')
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
  yearMonth.value = typeof q === 'string' && q ? q : currentYm()
  load()
})
</script>

<template>
  <div class="page-stack">
    <div class="page-card">
      <div class="toolbar">
        <el-input v-model="yearMonth" placeholder="yyyy-MM" style="width: 140px" />
        <el-button @click="load">刷新</el-button>
      </div>
      <p class="tip">请核对各项目金额后确认。未确认将不进入当月自动发薪，之后需走「工资申请」手动提交。</p>

      <div v-loading="loading">
        <el-empty v-if="!list.length" description="本月暂无待确认工资" />
        <div v-for="row in list" :key="row.lineId" class="confirm-card">
          <div class="confirm-head">
            <div>
              <strong>{{ row.companyName || '公司' }}</strong>
              <span class="ym">{{ row.yearMonth }}</span>
            </div>
            <el-tag :type="row.status === 'CONFIRMED' ? 'success' : 'warning'">
              {{ row.status === 'CONFIRMED' ? '已确认' : '待确认' }}
            </el-tag>
          </div>
          <el-table :data="itemsOf(row)" size="small">
            <el-table-column prop="projectName" label="项目" />
            <el-table-column prop="amount" label="金额" width="120" />
          </el-table>
          <div class="confirm-foot">
            <span>合计 <b>{{ row.totalAmount }}</b> 元</span>
            <div>
              <el-button
                v-if="row.status === 'PENDING_CONFIRM'"
                type="primary"
                @click="confirm(row)"
              >确认本月工资</el-button>
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
.confirm-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 12px;
}
</style>
