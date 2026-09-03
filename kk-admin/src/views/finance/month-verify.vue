<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { bizApi } from '@/api/biz'
import { workflowApi } from '@/api/workflow'

const list = ref<any[]>([])
const channels = ref<any[]>([])
const dialog = ref(false)
const uploading = ref(false)
const form = reactive<any>({
  verifyMonth: '',
  channelId: undefined,
  statementBalance: undefined as number | undefined,
  remark: '',
})
const voucherFiles = ref<any[]>([])

const channelMap = computed(() => {
  const m = new Map<number, any>()
  channels.value.forEach((c) => m.set(c.id, c))
  return m
})

function fmt(n?: number) {
  if (n == null) return '—'
  return Number(n).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function fileUrl(file: any) {
  return file?.url || `/api/file/preview/${file?.id}`
}

async function load() {
  list.value = await bizApi.monthVerifyList()
  channels.value = await bizApi.payChannelList({ all: true })
}

function openSubmit() {
  const now = new Date()
  form.verifyMonth = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
  form.channelId = channels.value[0]?.id
  form.statementBalance = undefined
  form.remark = ''
  voucherFiles.value = []
  dialog.value = true
}

async function onUpload(options: any) {
  uploading.value = true
  try {
    const file = await workflowApi.uploadVoucher(options.file)
    voucherFiles.value.push(file)
    options.onSuccess?.(file)
  } catch (e: any) {
    ElMessage.error(e.message || '上传失败')
    options.onError?.(e)
  } finally {
    uploading.value = false
  }
}

async function submit() {
  if (!form.verifyMonth || !form.channelId) {
    ElMessage.warning('请选择月份和渠道')
    return
  }
  if (!voucherFiles.value.length) {
    ElMessage.warning('请上传账户截图和流水凭证')
    return
  }
  const ch = channelMap.value.get(form.channelId)
  await workflowApi.submit({
    type: 'MONTHLY_VERIFY',
    title: `月度核验 · ${form.verifyMonth} · ${ch?.name || ''}`,
    poolId: ch?.poolId,
    remark: form.remark,
    voucherFileIds: voucherFiles.value.map((f) => f.id),
    payload: {
      verifyMonth: form.verifyMonth,
      channelId: form.channelId,
      systemBalance: ch?.balance,
      statementBalance: form.statementBalance,
      voucherFileIds: voucherFiles.value.map((f) => f.id),
    },
  })
  ElMessage.success('已提交财务审批')
  dialog.value = false
  await load()
}

onMounted(load)
</script>

<template>
  <div class="page-card">
    <div class="page-header">
      <div>
        <h3 class="page-title">月度核验</h3>
        <p class="page-desc">每月上传账户截图与流水凭证，由财务审批确认</p>
      </div>
      <el-button type="primary" @click="openSubmit">提交本月核验</el-button>
    </div>

    <el-table :data="list" stripe>
      <el-table-column prop="verifyMonth" label="月份" width="100" />
      <el-table-column label="渠道" min-width="160">
        <template #default="{ row }">{{ row.channelName || row.channelId }}</template>
      </el-table-column>
      <el-table-column label="系统余额" width="130" align="right">
        <template #default="{ row }">¥ {{ fmt(row.systemBalance) }}</template>
      </el-table-column>
      <el-table-column label="对账单余额" width="130" align="right">
        <template #default="{ row }">¥ {{ fmt(row.statementBalance) }}</template>
      </el-table-column>
      <el-table-column label="差额" width="120" align="right">
        <template #default="{ row }">
          <span :class="Number(row.diffAmount || 0) === 0 ? '' : 'diff'">¥ {{ fmt(row.diffAmount) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'PASSED' ? 'success' : 'info'" size="small">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="凭证" min-width="160">
        <template #default="{ row }">
          <template v-if="row.vouchers?.length">
            <el-link
              v-for="f in row.vouchers"
              :key="f.id"
              type="primary"
              :href="fileUrl(f)"
              target="_blank"
              style="margin-right: 8px"
            >{{ f.originalName || '文件' }}</el-link>
          </template>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" title="提交月度核验" width="560px">
      <el-form label-width="110px">
        <el-form-item label="核验月份" required>
          <el-date-picker v-model="form.verifyMonth" type="month" value-format="YYYY-MM" style="width: 100%" />
        </el-form-item>
        <el-form-item label="收款渠道" required>
          <el-select v-model="form.channelId" style="width: 100%">
            <el-option
              v-for="c in channels"
              :key="c.id"
              :label="`${c.name}（¥${fmt(c.balance)}）`"
              :value="c.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="对账单余额">
          <el-input-number v-model="form.statementBalance" :precision="2" :min="0" style="width: 100%" placeholder="填截图/对账单上的余额" />
        </el-form-item>
        <el-form-item label="凭证" required>
          <el-upload :show-file-list="false" :http-request="onUpload" accept="image/*,.pdf">
            <el-button :loading="uploading">上传截图/流水</el-button>
          </el-upload>
          <div v-for="(f, i) in voucherFiles" :key="f.id" class="file-row">
            <span>{{ f.originalName }}</span>
            <el-button link type="danger" @click="voucherFiles.splice(i, 1)">移除</el-button>
          </div>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submit">提交审批</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-desc { margin: 4px 0 0; color: #64748b; font-size: 13px; }
.diff { color: #dc2626; font-weight: 600; }
.muted { color: #94a3b8; }
.file-row {
  display: flex; justify-content: space-between; align-items: center;
  margin-top: 6px; font-size: 13px;
}
</style>
