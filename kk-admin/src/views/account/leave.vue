<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { bizApi } from '@/api/biz'
import { sysApi } from '@/api/system'
import { approvalFlowTip } from '@/utils/approvalTip'

const companies = ref<any[]>([])
const list = ref<any[]>([])
const loading = ref(false)
const submitting = ref(false)
const form = reactive({
  companyId: undefined as number | undefined,
  range: [] as string[],
  reason: '',
})

async function loadMeta() {
  companies.value = await sysApi.myCompanies()
  if (!form.companyId && companies.value.length) {
    form.companyId = companies.value[0].id
  }
}

async function load() {
  loading.value = true
  try {
    list.value = await bizApi.myLeave({
      companyId: form.companyId,
    })
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!form.companyId) {
    ElMessage.warning('请选择公司')
    return
  }
  if (!form.range?.length || form.range.length < 2) {
    ElMessage.warning('请选择请假起止日期')
    return
  }
  if (!String(form.reason || '').trim()) {
    ElMessage.warning('请填写请假事由')
    return
  }
  submitting.value = true
  try {
    const res = await bizApi.submitLeave({
      companyId: form.companyId,
      startDate: form.range[0],
      endDate: form.range[1],
      reason: String(form.reason).trim(),
    })
    ElMessage.success(approvalFlowTip(res))
    form.reason = ''
    form.range = []
    await load()
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  await loadMeta()
  await load()
})
</script>

<template>
  <div class="page-stack">
    <div class="page-card">
      <h3 class="card-title">提交请假</h3>
      <p class="tip">默认全勤；请假需审批通过后才会记入考勤。财务发薪时按考勤手工扣款。</p>
      <el-form label-width="90px" style="max-width: 520px">
        <el-form-item label="所属公司" required>
          <el-select v-model="form.companyId" filterable style="width: 100%" @change="load">
            <el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="请假日期" required>
          <el-date-picker
            v-model="form.range"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始"
            end-placeholder="结束"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="事由" required>
          <el-input v-model="form.reason" type="textarea" :rows="3" maxlength="500" show-word-limit />
        </el-form-item>
        <el-form-item>
          <el-button v-permission="'hr:leave:submit'" type="primary" :loading="submitting" @click="submit">
            提交审批
          </el-button>
        </el-form-item>
      </el-form>
    </div>

    <div class="page-card">
      <div class="toolbar">
        <h3 class="card-title">已通过考勤</h3>
        <el-button @click="load">刷新</el-button>
      </div>
      <el-table v-loading="loading" :data="list" stripe>
        <el-table-column prop="leaveDate" label="日期" width="140" />
        <el-table-column prop="companyName" label="公司" min-width="140" />
        <el-table-column prop="reason" label="事由" min-width="200" show-overflow-tooltip />
        <el-table-column prop="approvalId" label="审批单" width="100" />
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.card-title {
  margin: 0 0 12px;
  font-size: 16px;
  font-weight: 600;
}
.tip {
  margin: 0 0 16px;
  color: #64748b;
  font-size: 13px;
  line-height: 1.5;
}
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.toolbar .card-title {
  margin: 0;
}
</style>
