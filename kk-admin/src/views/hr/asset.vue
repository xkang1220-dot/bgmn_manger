<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bizApi } from '@/api/biz'
import { sysApi } from '@/api/system'
import { workflowApi } from '@/api/workflow'
import { approvalFlowTip } from '@/utils/approvalTip'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const myUserId = computed(() => userStore.user?.id)

function canReturn(row: any) {
  return row.status === 'IN_USE' && myUserId.value != null && Number(row.holderUserId) === Number(myUserId.value)
}

const query = reactive({
  page: 1,
  pageSize: 12,
  companyId: undefined as number | undefined,
  status: '' as string,
  keyword: '',
})
const list = ref<any[]>([])
const total = ref(0)
const companies = ref<any[]>([])
const categories = ref<any[]>([])
const dialog = ref(false)
const detailDrawer = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const submitting = ref(false)
const detail = ref<any>(null)
const form = reactive<any>({
  id: undefined,
  companyId: undefined,
  categoryId: undefined,
  assetCode: '',
  name: '',
  originalValue: undefined,
  purchaseDate: '',
  deprStartDate: '',
  remark: '',
})

function fmt(n?: number) {
  return Number(n || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function statusLabel(s?: string) {
  if (s === 'IN_USE') return '领用中'
  return '在库'
}

async function loadCompanies() {
  companies.value = await sysApi.myCompanies()
}

async function loadCategories() {
  if (!form.companyId && !query.companyId) {
    categories.value = []
    return
  }
  categories.value = await bizApi.faCategoryList({ companyId: form.companyId || query.companyId })
}

async function load() {
  const res = await bizApi.faAssetPage({
    page: query.page,
    pageSize: query.pageSize,
    companyId: query.companyId,
    status: query.status || undefined,
    keyword: query.keyword || undefined,
  })
  list.value = res.list || []
  total.value = res.total || 0
}

async function openDetail(row: any) {
  detail.value = await bizApi.faAssetDetail(row.id)
  detailDrawer.value = true
}

async function open(row?: any) {
  isEdit.value = !!row
  Object.assign(form, row || {
    id: undefined,
    companyId: companies.value[0]?.id,
    categoryId: undefined,
    assetCode: '',
    name: '',
    originalValue: undefined,
    purchaseDate: '',
    deprStartDate: '',
    remark: '',
  })
  await loadCategories()
  dialog.value = true
}

watch(() => form.companyId, async () => {
  if (!dialog.value) return
  form.categoryId = undefined
  await loadCategories()
})

async function save() {
  if (!form.companyId) {
    ElMessage.warning('请选择公司')
    return
  }
  if (!form.categoryId) {
    ElMessage.warning('请选择折旧类别')
    return
  }
  if (!form.assetCode?.trim() || !form.name?.trim()) {
    ElMessage.warning('请填写编码和名称')
    return
  }
  if (!isEdit.value && (!form.originalValue || Number(form.originalValue) <= 0)) {
    ElMessage.warning('请填写原值')
    return
  }
  saving.value = true
  try {
    const payload = {
      ...form,
      purchaseDate: form.purchaseDate || null,
      deprStartDate: form.deprStartDate || null,
    }
    await bizApi.saveFaAsset(payload, isEdit.value)
    ElMessage.success('已保存')
    dialog.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function confirmFlow(type: string, companyId: number) {
  let desc: { tip?: string } | null = null
  try {
    desc = await workflowApi.flowDescribe(type, companyId)
  } catch {
    // 未配置时交由提交接口报错
    return
  }
  if (desc?.tip) {
    await ElMessageBox.confirm(desc.tip, '审批说明', { type: 'info', confirmButtonText: '继续提交' })
  }
}

async function submitBorrow(row: any) {
  if (!row.companyId) {
    ElMessage.warning('资产缺少公司')
    return
  }
  const amount = Number(row.originalValue) || 0
  try {
    await ElMessageBox.confirm(
      `领用后你将成为持有人，系统会按原值冻结你的个人钱包可用余额 ¥${amount.toLocaleString('zh-CN', { minimumFractionDigits: 2 })}。可用余额不足将无法提交。是否继续？`,
      '确认领用',
      { type: 'warning', confirmButtonText: '继续', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await confirmFlow('ASSET_BORROW', row.companyId)
  } catch {
    return
  }
  submitting.value = true
  try {
    const res = await workflowApi.submit({
      type: 'ASSET_BORROW',
      companyId: row.companyId,
      title: `资产领用 · ${row.assetCode} ${row.name}`,
      amount: row.originalValue,
      payload: { assetId: row.id, companyId: row.companyId },
    })
    ElMessage.success(approvalFlowTip(res))
  } finally {
    submitting.value = false
  }
}

async function submitReturn(row: any) {
  try {
    await confirmFlow('ASSET_RETURN', row.companyId)
  } catch {
    return
  }
  submitting.value = true
  try {
    const res = await workflowApi.submit({
      type: 'ASSET_RETURN',
      companyId: row.companyId,
      title: `资产归还 · ${row.assetCode} ${row.name}`,
      amount: row.originalValue,
      payload: { assetId: row.id, companyId: row.companyId },
    })
    ElMessage.success(approvalFlowTip(res))
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  await loadCompanies()
  await load()
})
</script>

<template>
  <div class="page-stack">
    <div class="page-card">
      <el-form class="filter-bar" @submit.prevent="query.page = 1; load()">
        <el-form-item label="公司">
          <el-select v-model="query.companyId" clearable placeholder="全部" style="width: 160px">
            <el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 120px">
            <el-option label="在库" value="IN_STOCK" />
            <el-option label="领用中" value="IN_USE" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键词">
          <el-input v-model="query.keyword" clearable placeholder="编码/名称" style="width: 160px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" native-type="submit">查询</el-button>
          <el-button v-permission="'fa:asset:add'" type="primary" plain @click="open()">入库</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="list" row-key="id">
        <el-table-column prop="assetCode" label="编码" width="120" />
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column prop="companyName" label="公司" width="120" />
        <el-table-column prop="categoryName" label="类别" width="110" />
        <el-table-column label="原值" width="110" align="right">
          <template #default="{ row }">¥{{ fmt(row.originalValue) }}</template>
        </el-table-column>
        <el-table-column label="净值" width="110" align="right">
          <template #default="{ row }">¥{{ fmt(row.netValue) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'IN_USE' ? 'warning' : 'success'" size="small">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="holderName" label="持有人" width="100">
          <template #default="{ row }">{{ row.holderName || '—' }}</template>
        </el-table-column>
        <el-table-column label="冻结" width="80">
          <template #default="{ row }">
            {{ Number(row.lockFreeze) === 1 ? '是' : '否' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button
              v-if="row.status === 'IN_STOCK'"
              v-permission="'fa:asset:edit'"
              link
              type="primary"
              @click="open(row)"
            >编辑</el-button>
            <el-button
              v-if="row.status === 'IN_STOCK'"
              link
              type="warning"
              :loading="submitting"
              @click="submitBorrow(row)"
            >领用</el-button>
            <el-button
              v-if="canReturn(row)"
              link
              type="success"
              :loading="submitting"
              @click="submitReturn(row)"
            >归还</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="total > query.pageSize" class="page-footer">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.pageSize"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="load"
        />
      </div>
    </div>

    <el-dialog v-model="dialog" :title="isEdit ? '编辑资产' : '资产入库'" width="520px">
      <el-form label-width="100px">
        <el-form-item label="公司" required>
          <el-select v-model="form.companyId" :disabled="isEdit" style="width: 100%">
            <el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="折旧类别" required>
          <el-select v-model="form.categoryId" style="width: 100%" placeholder="选择类别">
            <el-option
              v-for="c in categories"
              :key="c.id"
              :label="`${c.name}（${c.months}月 / 残值${(Number(c.residualRate || 0) * 100).toFixed(0)}%）`"
              :value="c.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="编码" required>
          <el-input v-model="form.assetCode" maxlength="64" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="128" />
        </el-form-item>
        <el-form-item label="原值" required>
          <el-input-number v-model="form.originalValue" :min="0.01" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="购入日">
          <el-date-picker v-model="form.purchaseDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="开始折旧">
          <el-date-picker v-model="form.deprStartDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailDrawer" title="资产详情" size="480px">
      <template v-if="detail">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="编码">{{ detail.assetCode }}</el-descriptions-item>
          <el-descriptions-item label="名称">{{ detail.name }}</el-descriptions-item>
          <el-descriptions-item label="公司">{{ detail.companyName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="类别">{{ detail.categoryName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="原值">¥{{ fmt(detail.originalValue) }}</el-descriptions-item>
          <el-descriptions-item label="残值">¥{{ fmt(detail.residualValue) }}</el-descriptions-item>
          <el-descriptions-item label="净值">¥{{ fmt(detail.netValue) }}</el-descriptions-item>
          <el-descriptions-item label="累计折旧">¥{{ fmt(detail.accumDepr) }}</el-descriptions-item>
          <el-descriptions-item label="折旧月数">{{ detail.deprMonths }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ statusLabel(detail.status) }}</el-descriptions-item>
          <el-descriptions-item label="持有人">{{ detail.holderName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="冻结中">{{ Number(detail.lockFreeze) === 1 ? `是（¥${fmt(detail.frozenAmount)}）` : '否' }}</el-descriptions-item>
        </el-descriptions>
        <h4 class="event-title">事件流水</h4>
        <el-table :data="detail.events || []" size="small">
          <el-table-column prop="eventTime" label="时间" width="160" />
          <el-table-column prop="eventType" label="类型" width="100" />
          <el-table-column label="金额" width="100" align="right">
            <template #default="{ row }">{{ row.amount != null ? fmt(row.amount) : '—' }}</template>
          </el-table-column>
          <el-table-column prop="remark" label="说明" min-width="120" show-overflow-tooltip />
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.page-stack { display: flex; flex-direction: column; gap: 16px; }
.page-footer { display: flex; justify-content: flex-end; margin-top: 16px; }
.filter-bar { display: flex; flex-wrap: wrap; gap: 8px 12px; margin-bottom: 12px; }
.event-title { margin: 20px 0 10px; font-size: 14px; font-weight: 600; }
</style>
