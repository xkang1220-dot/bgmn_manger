<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { bizApi } from '@/api/biz'
import { sysApi } from '@/api/system'

const companies = ref<any[]>([])
const companyId = ref<number | undefined>()
const range = ref<string[]>([])
const list = ref<any[]>([])
const loading = ref(false)

function defaultRange() {
  const d = new Date()
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const last = new Date(y, d.getMonth() + 1, 0).getDate()
  return [`${y}-${m}-01`, `${y}-${m}-${String(last).padStart(2, '0')}`]
}

async function loadMeta() {
  companies.value = await sysApi.myCompanies()
  if (!companyId.value && companies.value.length) {
    companyId.value = companies.value[0].id
  }
  if (!range.value.length) {
    range.value = defaultRange()
  }
}

async function load() {
  if (!companyId.value) return
  loading.value = true
  try {
    list.value = await bizApi.companyLeave({
      companyId: companyId.value,
      start: range.value?.[0],
      end: range.value?.[1],
    })
  } finally {
    loading.value = false
  }
}

watch(companyId, () => load())

onMounted(async () => {
  await loadMeta()
  await load()
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
          <el-date-picker
            v-model="range"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始"
            end-placeholder="结束"
          />
          <el-button type="primary" @click="load">查询</el-button>
        </div>
      </div>
      <p class="tip">仅列出已审批通过的请假日；无记录视为全勤。发薪时财务据此手工填写扣款与备注。</p>
      <el-table v-loading="loading" :data="list" stripe>
        <el-table-column prop="leaveDate" label="日期" width="140" />
        <el-table-column prop="userName" label="人员" width="140" />
        <el-table-column prop="reason" label="事由" min-width="220" show-overflow-tooltip />
        <el-table-column prop="approvalId" label="审批单" width="100" />
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
</style>
