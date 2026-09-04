<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { workflowApi } from '@/api/workflow'

const scope = ref('todo')
const query = reactive({
  page: 1,
  pageSize: 10,
  type: '',
  status: '',
  keyword: '',
  minAmount: undefined as number | undefined,
  maxAmount: undefined as number | undefined,
  dateRange: [] as string[],
})
const list = ref<any[]>([])
const total = ref(0)
const detail = ref<any>(null)
const drawer = ref(false)
const comment = ref('')
const rollbackDialog = ref(false)
const rollbackForm = reactive({ mode: 'FULL', amount: 0, reason: '' })
const uploading = ref(false)

const payload = computed<any>(() => detail.value?.payloadData || {})

function dateParam(d?: string, end = false) {
  if (!d) return undefined
  return end ? `${d} 23:59:59` : `${d} 00:00:00`
}

async function load() {
  const res = await workflowApi.page({
    page: query.page,
    pageSize: query.pageSize,
    type: query.type || undefined,
    status: query.status || undefined,
    scope: scope.value,
    keyword: query.keyword || undefined,
    minAmount: query.minAmount,
    maxAmount: query.maxAmount,
    startTime: dateParam(query.dateRange?.[0]),
    endTime: dateParam(query.dateRange?.[1], true),
  })
  list.value = res.list
  total.value = res.total
}

async function openDetail(row: any) {
  detail.value = await workflowApi.detail(row.id)
  drawer.value = true
  comment.value = ''
}

async function approve() {
  if (!detail.value) return
  await workflowApi.approve(detail.value.id, comment.value || '同意')
  ElMessage.success('已通过')
  await refreshDetail()
  await load()
}

async function reject() {
  if (!detail.value) return
  await workflowApi.reject(detail.value.id, comment.value || '拒绝')
  ElMessage.success('已拒绝')
  await refreshDetail()
  await load()
}

async function withdraw() {
  if (!detail.value) return
  await ElMessageBox.confirm('确认撤回该审批？')
  await workflowApi.withdraw(detail.value.id)
  ElMessage.success('已撤回')
  await refreshDetail()
  await load()
}

async function confirmReceived() {
  if (!detail.value) return
  await ElMessageBox.confirm('确认钱已到账？确认后将正式动账')
  await workflowApi.confirm(detail.value.id)
  ElMessage.success('已确认到账并完成动账')
  await refreshDetail()
  await load()
}

async function onUploadReceipt(options: any) {
  if (!detail.value) return
  uploading.value = true
  try {
    const file = await workflowApi.uploadVoucher(options.file)
    await workflowApi.uploadReceipt(detail.value.id, [file.id])
    ElMessage.success('回执已上传，等待申请人确认')
    options.onSuccess?.(file)
    await refreshDetail()
    await load()
  } catch (e: any) {
    ElMessage.error(e.message || '上传失败')
    options.onError?.(e)
  } finally {
    uploading.value = false
  }
}

function fileUrl(file: any) {
  return file?.url || `/api/file/preview/${file?.id}`
}

function isImage(file: any) {
  const name = String(file?.originalName || file?.name || file?.url || '').toLowerCase()
  return /\.(png|jpe?g|gif|webp|bmp)$/.test(name) || String(file?.contentType || '').startsWith('image/')
}

function previewFile(file: any) {
  window.open(fileUrl(file), '_blank')
}

function confirmLabel(status?: number) {
  return ({ 0: '无需确认', 1: '待财务上传回执', 2: '待申请人确认到账', 3: '已确认到账' } as any)[status ?? 0] || '—'
}

function openRollback() {
  rollbackForm.mode = 'FULL'
  rollbackForm.amount = Number(detail.value?.amount || 0)
  rollbackForm.reason = ''
  rollbackDialog.value = true
}

async function submitRollback() {
  if (!detail.value) return
  await workflowApi.rollback({
    approvalId: detail.value.id,
    mode: rollbackForm.mode,
    amount: rollbackForm.mode === 'PARTIAL' ? rollbackForm.amount : undefined,
    reason: rollbackForm.reason,
  })
  ElMessage.success('已发起回退审批')
  rollbackDialog.value = false
  await refreshDetail()
  await load()
}

async function refreshDetail() {
  if (detail.value?.id) {
    detail.value = await workflowApi.detail(detail.value.id)
  }
}

function fmtTime(t?: string) {
  if (!t) return '—'
  return t.replace('T', ' ').slice(0, 16)
}

function fmtMoney(n?: number | string | null) {
  if (n == null || n === '') return '—'
  return Number(n).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function pct(n?: number | string | null) {
  if (n == null || n === '') return '—'
  return `${Number(n).toFixed(2)}%`
}

function onFilter() {
  query.page = 1
  load()
}

function resetFilter() {
  query.type = ''
  query.status = ''
  query.keyword = ''
  query.minAmount = undefined
  query.maxAmount = undefined
  query.dateRange = []
  query.page = 1
  load()
}

onMounted(load)
</script>

<template>
  <div class="page-card">
    <div class="page-header">
      <div>
        <h3 class="page-title">审批中心</h3>
        <p class="page-desc">待办 / 我发起 / 全部；报销类：上传发票 → 财务审批 → 财务回执 → 申请人确认到账</p>
      </div>
    </div>

    <el-form class="filter-bar" @submit.prevent="onFilter">
      <el-form-item label="范围">
        <el-radio-group v-model="scope" @change="onFilter">
          <el-radio-button value="todo">待我处理</el-radio-button>
          <el-radio-button value="mine">我发起的</el-radio-button>
          <el-radio-button value="all">全部</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="类型">
        <el-select v-model="query.type" clearable placeholder="全部" class="filter-select--wide">
          <el-option label="创建项目" value="PROJECT_CREATE" />
          <el-option label="删除项目" value="PROJECT_DELETE" />
          <el-option label="个人报销" value="REIMBURSE_PERSONAL" />
          <el-option label="项目报销" value="REIMBURSE_PROJECT" />
          <el-option label="项目预支" value="PROJECT_ADVANCE" />
          <el-option label="分成配置" value="SHARE_CONFIG" />
          <el-option label="项目分钱" value="PROJECT_SETTLE" />
          <el-option label="工资申请" value="SALARY_APPLY" />
          <el-option label="预留回公司" value="RESERVE_RETURN" />
          <el-option label="总账登记" value="LEDGER_REGISTER" />
          <el-option label="月度核验" value="MONTHLY_VERIFY" />
          <el-option label="资金回退" value="ROLLBACK" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="query.status" clearable placeholder="全部" class="filter-select">
          <el-option label="待审批" value="PENDING" />
          <el-option label="已通过" value="APPROVED" />
          <el-option label="已拒绝" value="REJECTED" />
          <el-option label="已撤回" value="WITHDRAWN" />
        </el-select>
      </el-form-item>
      <el-form-item label="申请时间">
        <el-date-picker
          v-model="query.dateRange"
          type="daterange"
          unlink-panels
          clearable
          value-format="YYYY-MM-DD"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          style="width: 240px"
        />
      </el-form-item>
      <el-form-item label="金额">
        <div class="amount-range">
          <el-input-number
            v-model="query.minAmount"
            :controls="false"
            :precision="2"
            placeholder="最小"
            class="amount-input"
          />
          <span class="amount-sep">至</span>
          <el-input-number
            v-model="query.maxAmount"
            :controls="false"
            :precision="2"
            placeholder="最大"
            class="amount-input"
          />
        </div>
      </el-form-item>
      <el-form-item label="关键词">
        <el-input
          v-model="query.keyword"
          clearable
          placeholder="单号 / 标题"
          class="filter-keyword"
          @keyup.enter="onFilter"
        />
      </el-form-item>
      <el-form-item class="filter-actions">
        <el-button type="primary" native-type="submit">查询</el-button>
        <el-button @click="resetFilter">重置</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="list" stripe @row-click="openDetail" class="clickable">
      <el-table-column prop="bizNo" label="单号" width="180" />
      <el-table-column prop="typeLabel" label="类型" width="110" />
      <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
      <el-table-column prop="applicantName" label="申请人" width="100" />
      <el-table-column prop="projectName" label="项目" width="120" show-overflow-tooltip>
        <template #default="{ row }">{{ row.projectName || '—' }}</template>
      </el-table-column>
      <el-table-column label="金额" width="120" align="right">
        <template #default="{ row }">{{ row.amount != null ? `¥${fmtMoney(row.amount)}` : '—' }}</template>
      </el-table-column>
      <el-table-column prop="statusLabel" label="状态" width="100" />
      <el-table-column label="时间" width="150">
        <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
      </el-table-column>
    </el-table>

    <el-pagination
      style="margin-top: 12px"
      v-model:current-page="query.page"
      :page-size="query.pageSize"
      :total="total"
      layout="total, prev, pager, next"
      @current-change="load"
    />

    <el-drawer v-model="drawer" title="审批详情" size="560px" append-to-body>
      <template v-if="detail">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="单号">{{ detail.bizNo }}</el-descriptions-item>
          <el-descriptions-item label="类型">{{ detail.typeLabel }}</el-descriptions-item>
          <el-descriptions-item label="标题">{{ detail.title }}</el-descriptions-item>
          <el-descriptions-item label="申请人">{{ detail.applicantName }}</el-descriptions-item>
          <el-descriptions-item label="项目">{{ detail.projectName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="金额">{{ detail.amount != null ? `¥${fmtMoney(detail.amount)}` : '—' }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ detail.statusLabel }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.passMode" label="通过方式">
            {{ detail.passMode === 'ANY' ? '或签（一人通过）' : '会签（全部通过）' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.confirmStatus != null && detail.confirmStatus > 0" label="到账确认">
            {{ confirmLabel(detail.confirmStatus) }}
          </el-descriptions-item>
          <el-descriptions-item label="时间">{{ fmtTime(detail.createTime) }}</el-descriptions-item>
          <el-descriptions-item label="备注">{{ detail.remark || '—' }}</el-descriptions-item>
        </el-descriptions>

        <h4 class="sec">发票 / 凭证（{{ detail.voucherFiles?.length || 0 }}）</h4>
        <div v-if="detail.voucherFiles?.length" class="file-gallery">
          <div v-for="file in detail.voucherFiles" :key="file.id" class="file-card">
            <div v-if="isImage(file)" class="file-thumb" @click="previewFile(file)">
              <img :src="fileUrl(file)" :alt="file.originalName" />
            </div>
            <a v-else :href="fileUrl(file)" target="_blank" rel="noopener" class="file-link">
              {{ file.originalName || `文件#${file.id}` }}
            </a>
          </div>
        </div>
        <div v-else class="empty-tip">申请人未上传发票</div>

        <h4 class="sec">财务回执（{{ detail.receiptFiles?.length || 0 }}）</h4>
        <div v-if="detail.receiptFiles?.length" class="file-gallery">
          <div v-for="file in detail.receiptFiles" :key="file.id" class="file-card">
            <div v-if="isImage(file)" class="file-thumb" @click="previewFile(file)">
              <img :src="fileUrl(file)" :alt="file.originalName" />
            </div>
            <a v-else :href="fileUrl(file)" target="_blank" rel="noopener" class="file-link">
              {{ file.originalName || `文件#${file.id}` }}
            </a>
          </div>
        </div>
        <div v-else class="empty-tip">财务尚未上传回执</div>

        <h4 class="sec">申请内容</h4>
        <div class="payload-box">
          <template v-if="detail.type === 'SHARE_CONFIG'">
            <div class="kv-grid">
              <div><span>预算基数</span><b>¥{{ fmtMoney(payload.budget) }}</b></div>
              <div><span>分成 %</span><b>{{ pct(payload.settlePercent) }}</b></div>
              <div><span>预留 %（结束回公司）</span><b>{{ pct(payload.reservePercent) }}</b></div>
              <div><span>支出</span><b>不占比例，从项目结余扣</b></div>
            </div>
            <div class="sub-title">分成人员</div>
            <el-table v-if="payload.members?.length" :data="payload.members" size="small">
              <el-table-column label="人员" min-width="100">
                <template #default="{ row }">{{ row.userName || row.userId || '—' }}</template>
              </el-table-column>
              <el-table-column prop="layer" label="角色" width="90" />
              <el-table-column label="分成 %" width="90" align="right">
                <template #default="{ row }">{{ pct(row.percent) }}</template>
              </el-table-column>
              <el-table-column prop="remark" label="备注" min-width="80" show-overflow-tooltip />
            </el-table>
            <div v-else class="empty-tip">未配置分成人员</div>
          </template>

          <template v-else-if="detail.type === 'PROJECT_SETTLE'">
            <div class="amount-line">本次分钱 <b>¥{{ fmtMoney(detail.amount) }}</b></div>
            <el-table v-if="payload.items?.length" :data="payload.items" size="small">
              <el-table-column label="人员" min-width="100">
                <template #default="{ row }">{{ row.userName || row.userId || '—' }}</template>
              </el-table-column>
              <el-table-column prop="layer" label="角色" width="90" />
              <el-table-column label="金额" width="120" align="right">
                <template #default="{ row }">+¥{{ fmtMoney(row.amount) }}</template>
              </el-table-column>
            </el-table>
            <div v-else class="empty-tip">无分钱明细</div>
          </template>

          <template v-else-if="detail.type === 'LEDGER_REGISTER'">
            <div class="kv-grid">
              <div><span>记账类型</span><b>{{ payload.bizTypeLabel || payload.bizType || '—' }}</b></div>
              <div><span>金额</span><b>¥{{ fmtMoney(payload.amount ?? detail.amount) }}</b></div>
              <div><span>摘要</span><b>{{ payload.title || detail.title || '—' }}</b></div>
              <div><span>说明</span><b>{{ payload.remark || detail.remark || '—' }}</b></div>
            </div>
          </template>

          <template v-else-if="['PROJECT_ADVANCE', 'REIMBURSE_PROJECT', 'REIMBURSE_PERSONAL', 'SALARY_APPLY', 'RESERVE_RETURN'].includes(detail.type)">
            <div class="kv-grid">
              <div><span>{{ detail.type === 'RESERVE_RETURN' ? '结余金额' : '金额' }}</span><b>¥{{ fmtMoney(detail.amount) }}</b></div>
              <div v-if="detail.projectName"><span>项目</span><b>{{ detail.projectName }}</b></div>
              <div><span>说明</span><b>{{ detail.remark || payload.remark || (detail.type === 'RESERVE_RETURN' ? '项目结余退回公司总账' : '—') }}</b></div>
            </div>
          </template>

          <template v-else-if="detail.type === 'PROJECT_CREATE'">
            <div class="kv-grid">
              <div><span>项目名称</span><b>{{ payload.name || '—' }}</b></div>
              <div><span>编码</span><b>{{ payload.code || '—' }}</b></div>
              <div><span>负责人</span><b>{{ payload.ownerName || payload.ownerId || '—' }}</b></div>
              <div><span>预算</span><b>¥{{ fmtMoney(payload.budget) }}</b></div>
              <div><span>周期</span><b>{{ payload.startDate || '—' }} ~ {{ payload.endDate || '—' }}</b></div>
              <div><span>说明</span><b>{{ payload.description || '—' }}</b></div>
            </div>
            <template v-if="payload.members?.length">
              <div class="sub-title">初始分成人员</div>
              <el-table :data="payload.members" size="small">
                <el-table-column label="人员" min-width="100">
                  <template #default="{ row }">{{ row.userName || row.userId || '—' }}</template>
                </el-table-column>
                <el-table-column prop="layer" label="角色" width="90" />
                <el-table-column label="分成 %" width="90" align="right">
                  <template #default="{ row }">{{ pct(row.percent) }}</template>
                </el-table-column>
              </el-table>
            </template>
          </template>

          <template v-else-if="detail.type === 'ROLLBACK'">
            <div class="kv-grid">
              <div><span>原单号</span><b>{{ payload.originBizNo || payload.originApprovalId || '—' }}</b></div>
              <div><span>模式</span><b>{{ payload.mode === 'PARTIAL' ? '部分回退' : '全部回退' }}</b></div>
              <div><span>金额</span><b>¥{{ fmtMoney(payload.amount ?? detail.amount) }}</b></div>
              <div><span>原因</span><b>{{ detail.remark || payload.reason || '—' }}</b></div>
            </div>
          </template>

          <template v-else-if="detail.type === 'PROJECT_DELETE'">
            <div class="empty-tip">申请删除项目：{{ detail.projectName || payload.projectId || '—' }}</div>
          </template>

          <template v-else>
            <div class="empty-tip">{{ detail.remark || '无额外申请明细' }}</div>
          </template>
        </div>

        <h4 class="sec">会签人</h4>
        <div v-for="t in detail.tasks || []" :key="t.id" class="task-row">
          <span>{{ t.assigneeName }}</span>
          <el-tag size="small">{{ t.action }}</el-tag>
          <span class="muted">{{ t.comment || '' }}</span>
        </div>

        <h4 class="sec">操作记录</h4>
        <el-timeline>
          <el-timeline-item v-for="log in detail.logs || []" :key="log.id" :timestamp="fmtTime(log.createTime)">
            {{ log.operatorName || '系统' }} · {{ log.action }} · {{ log.remark || '' }}
          </el-timeline-item>
        </el-timeline>

        <div v-if="detail.canHandle" class="actions">
          <el-input v-model="comment" type="textarea" :rows="2" placeholder="审批意见" />
          <div class="btns">
            <el-button type="primary" @click="approve">通过</el-button>
            <el-button type="danger" @click="reject">拒绝</el-button>
          </div>
        </div>
        <div class="btns" style="margin-top: 12px">
          <el-button v-if="detail.canWithdraw" @click="withdraw">撤回</el-button>
          <el-upload v-if="detail.canUploadReceipt" :show-file-list="false" :http-request="onUploadReceipt">
            <el-button :loading="uploading" type="warning">上传财务回执</el-button>
          </el-upload>
          <el-button v-if="detail.canConfirm" type="success" @click="confirmReceived">确认到账</el-button>
          <el-button v-if="detail.canRollback" @click="openRollback">发起回退</el-button>
        </div>
      </template>
    </el-drawer>

    <el-dialog v-model="rollbackDialog" title="资金回退" width="420px">
      <el-form label-width="90px">
        <el-form-item label="模式">
          <el-radio-group v-model="rollbackForm.mode">
            <el-radio value="FULL">全部回退</el-radio>
            <el-radio value="PARTIAL">部分回退</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="rollbackForm.mode === 'PARTIAL'" label="金额">
          <el-input-number v-model="rollbackForm.amount" :min="0.01" :precision="2" />
        </el-form-item>
        <el-form-item label="原因">
          <el-input v-model="rollbackForm.reason" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rollbackDialog = false">取消</el-button>
        <el-button type="primary" @click="submitRollback">提交回退审批</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-desc { margin: 4px 0 0; color: #64748b; font-size: 13px; }
.clickable :deep(tbody tr) { cursor: pointer; }
.sec { margin: 18px 0 8px; font-size: 14px; color: #0f172a; }
.task-row { display: flex; gap: 10px; padding: 6px 0; font-size: 13px; align-items: center; }
.muted { color: #94a3b8; }
.actions { margin-top: 16px; }
.btns { display: flex; gap: 8px; margin-top: 10px; flex-wrap: wrap; }
.payload-box {
  padding: 12px;
  background: #f8fafc;
  border: 1px solid #eef2f7;
  border-radius: 10px;
}
.kv-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px 14px;
}
.kv-grid div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.kv-grid span { font-size: 12px; color: #94a3b8; }
.kv-grid b { font-size: 14px; color: #0f172a; font-weight: 600; word-break: break-all; }
.sub-title {
  margin: 12px 0 8px;
  font-size: 13px;
  color: #475569;
  font-weight: 500;
}
.amount-line {
  margin-bottom: 10px;
  font-size: 13px;
  color: #64748b;
}
.amount-line b { color: #0f172a; font-size: 16px; margin-left: 6px; }
.empty-tip { font-size: 13px; color: #94a3b8; }
.file-gallery {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.file-card {
  width: 120px;
  border: 1px solid #eef2f7;
  border-radius: 8px;
  overflow: hidden;
  background: #fff;
}
.file-thumb {
  height: 90px;
  cursor: pointer;
  background: #f8fafc;
}
.file-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.file-link {
  display: block;
  padding: 10px 8px;
  font-size: 12px;
  color: var(--kk-primary);
  word-break: break-all;
}
</style>
