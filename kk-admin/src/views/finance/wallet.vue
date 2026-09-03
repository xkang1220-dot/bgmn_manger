<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { bizApi } from '@/api/biz'
import { workflowApi } from '@/api/workflow'

const query = reactive({ page: 1, pageSize: 10 })
const mineQuery = reactive({ page: 1, pageSize: 10 })
const list = ref<any[]>([])
const total = ref(0)
const mine = ref<any>({})
const mineLedgers = ref<any[]>([])
const mineTotal = ref(0)
const reimburseDialog = ref(false)
const reimburseForm = reactive({ amount: 0, remark: '' })
const voucherFiles = ref<any[]>([])
const uploading = ref(false)

function bizLabel(v: string) {
  return ({
    INCOME: '入账',
    EXPENSE: '出账',
    TRANSFER: '划拨',
    SETTLE: '项目分钱',
    ADVANCE: '预支',
    RESERVE: '预留',
    SALARY: '工资',
    REIMBURSE: '报销',
    ROLLBACK: '回退',
  } as any)[v] || v
}

function fmtTime(t?: string) {
  if (!t) return '—'
  return t.replace('T', ' ').slice(0, 16)
}

function fmtMoney(n?: number, signed = false) {
  const v = Number(n ?? 0)
  const abs = Math.abs(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  if (!signed) return `¥${abs}`
  if (v > 0) return `+¥${abs}`
  if (v < 0) return `-¥${abs}`
  return `¥${abs}`
}

function amountClass(n?: number) {
  const v = Number(n ?? 0)
  if (v > 0) return 'amt-in'
  if (v < 0) return 'amt-out'
  return ''
}

function fileUrl(file: any) {
  return file?.url || `/api/file/preview/${file?.id}`
}

async function load() {
  mine.value = await bizApi.myWallet()
  const ledgerRes = await bizApi.myWalletLedger(mineQuery)
  mineLedgers.value = ledgerRes.list
  mineTotal.value = ledgerRes.total
  try {
    const res = await bizApi.walletPage(query)
    list.value = res.list
    total.value = res.total
  } catch {
    list.value = []
    total.value = 0
  }
}

function openReimburse() {
  reimburseForm.amount = 0
  reimburseForm.remark = ''
  voucherFiles.value = []
  reimburseDialog.value = true
}

async function onUploadVoucher(options: any) {
  uploading.value = true
  try {
    const file = await workflowApi.uploadVoucher(options.file)
    voucherFiles.value.push(file)
    ElMessage.success('发票已上传')
    options.onSuccess?.(file)
  } catch (e: any) {
    ElMessage.error(e.message || '上传失败')
    options.onError?.(e)
  } finally {
    uploading.value = false
  }
}

function removeVoucher(index: number) {
  voucherFiles.value.splice(index, 1)
}

async function submitReimburse() {
  if (!reimburseForm.amount || reimburseForm.amount <= 0) {
    ElMessage.warning('请填写报销金额')
    return
  }
  if (!voucherFiles.value.length) {
    ElMessage.warning('请上传发票/凭证')
    return
  }
  await workflowApi.submit({
    type: 'REIMBURSE_PERSONAL',
    title: '个人报销',
    amount: reimburseForm.amount,
    remark: reimburseForm.remark,
    voucherFileIds: voucherFiles.value.map((f) => f.id),
  })
  ElMessage.success('已提交：财务审批 → 上传回执 → 请你确认到账')
  reimburseDialog.value = false
  reimburseForm.amount = 0
  reimburseForm.remark = ''
  voucherFiles.value = []
}

onMounted(load)
</script>

<template>
  <div>
    <el-card shadow="never" style="margin-bottom: 16px">
      <div class="mine-head">
        <div>
          <div class="mine-title">我的钱包</div>
          <div class="mine-desc">包含项目分成、报销等到账收入；个人报销需上传发票，财务通过并回执后请确认到账</div>
        </div>
        <div class="mine-right">
          <div class="mine-balance">¥ {{ mine.balance ?? 0 }}</div>
          <el-button type="primary" size="small" @click="openReimburse">发起报销</el-button>
        </div>
      </div>
    </el-card>

    <div class="page-card" style="margin-bottom: 16px">
      <h3 class="page-title">我的资金明细</h3>
      <el-table :data="mineLedgers" style="margin-top: 12px">
        <el-table-column prop="bizNo" label="编号" width="170" />
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ fmtTime(row.occurTime) }}</template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }">{{ bizLabel(row.bizType) }}</template>
        </el-table-column>
        <el-table-column prop="title" label="摘要" min-width="160" show-overflow-tooltip />
        <el-table-column prop="projectName" label="项目" width="140">
          <template #default="{ row }">{{ row.projectName || '—' }}</template>
        </el-table-column>
        <el-table-column label="变动" width="120" align="right">
          <template #default="{ row }">
            <span :class="amountClass(row.amount)">{{ fmtMoney(row.amount, true) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="余额" width="120" align="right">
          <template #default="{ row }">{{ fmtMoney(row.afterBalance) }}</template>
        </el-table-column>
      </el-table>
      <el-pagination
        style="margin-top: 12px"
        v-model:current-page="mineQuery.page"
        :page-size="mineQuery.pageSize"
        :total="mineTotal"
        layout="total, prev, pager, next"
        @current-change="load"
      />
    </div>

    <el-dialog v-model="reimburseDialog" title="个人报销" width="480px" @closed="voucherFiles = []">
      <div class="flow-tip">流程：上传发票提交 → 财务审批查看发票 → 财务上传回执 → 你在审批中心确认到账</div>
      <el-form label-width="80px">
        <el-form-item label="金额" required>
          <el-input-number v-model="reimburseForm.amount" :min="0.01" :precision="2" />
        </el-form-item>
        <el-form-item label="发票" required>
          <div class="voucher-box">
            <el-upload :show-file-list="false" :http-request="onUploadVoucher" accept="image/*,.pdf">
              <el-button :loading="uploading" size="small">上传发票/凭证</el-button>
            </el-upload>
            <div v-if="voucherFiles.length" class="voucher-list">
              <div v-for="(file, index) in voucherFiles" :key="file.id" class="voucher-item">
                <a :href="fileUrl(file)" target="_blank" rel="noopener">{{ file.originalName || file.name || `文件#${file.id}` }}</a>
                <el-button link type="danger" @click="removeVoucher(index)">移除</el-button>
              </div>
            </div>
            <div v-else class="voucher-empty">至少上传 1 张发票或凭证</div>
          </div>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="reimburseForm.remark" type="textarea" :rows="3" placeholder="发票说明等" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reimburseDialog = false">取消</el-button>
        <el-button type="primary" @click="submitReimburse">提交审批</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.mine-head { display: flex; justify-content: space-between; align-items: center; }
.mine-title { font-size: 16px; font-weight: 600; }
.mine-desc { margin-top: 4px; color: #64748b; font-size: 13px; }
.mine-right { display: flex; align-items: center; gap: 12px; }
.mine-balance { font-size: 28px; font-weight: 700; }
.amt-in { color: #16a34a; font-weight: 600; }
.amt-out { color: #dc2626; font-weight: 600; }
.flow-tip {
  margin-bottom: 14px;
  padding: 10px 12px;
  background: #f8fafc;
  border: 1px solid #eef2f7;
  border-radius: 8px;
  color: #64748b;
  font-size: 13px;
  line-height: 1.5;
}
.voucher-box { width: 100%; }
.voucher-list { margin-top: 8px; display: flex; flex-direction: column; gap: 6px; }
.voucher-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 8px;
  background: #f8fafc;
  border-radius: 6px;
  font-size: 13px;
}
.voucher-item a { color: #1d4ed8; word-break: break-all; }
.voucher-empty { margin-top: 6px; font-size: 12px; color: #94a3b8; }
</style>
