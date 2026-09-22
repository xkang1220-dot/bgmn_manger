<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { bizApi } from '@/api/biz'
import { sysApi } from '@/api/system'
import { createDefaultContract, getEmploymentClauses } from './clauses'
import { amountToChinese, calcServiceMonths } from './utils'
import { renderFormalEmploymentPreview } from './preview'
import { exportLaborAgreementPDF } from './pdf-export'
import type { LaborContractData } from './types'
import './labor-doc.css'

const form = reactive<LaborContractData>(createDefaultContract())
const previewHtml = ref('')
const previewRef = ref<HTMLElement | null>(null)
const exporting = ref(false)
const archiveLoading = ref(false)
const archiveApplying = ref(false)
const archiveOptions = ref<any[]>([])
const companyOptions = ref<any[]>([])
const companyLoading = ref(false)
let applySeq = 0

const amountWords = computed(() => amountToChinese(form.payment.total))

/** 预览只读派生，避免 deep watch 里回写 form 造成递归更新 / 选人被冲掉 */
function buildPreviewData(): LaborContractData {
  const serviceMonths = calcServiceMonths(form.effectiveDate, form.endDate) || form.serviceMonths
  const amountInWords = amountToChinese(form.payment.total)
  const snapshot: LaborContractData = {
    ...form,
    partyA: { ...form.partyA },
    partyB: { ...form.partyB },
    payment: { ...form.payment },
    meta: { ...form.meta },
    serviceMonths,
    amountInWords,
    clauses: [],
  }
  snapshot.clauses = getEmploymentClauses(snapshot)
  return snapshot
}

function syncPreview() {
  const data = buildPreviewData()
  form.serviceMonths = data.serviceMonths
  form.amountInWords = data.amountInWords
  form.clauses = data.clauses
  previewHtml.value = renderFormalEmploymentPreview(data)
}

watch(
  () => [
    form.partyA,
    form.partyB,
    form.projectName,
    form.effectiveDate,
    form.endDate,
    form.signDate,
    form.serviceMonths,
    form.invoiceBy,
    form.payment.total,
    form.payment.payDays,
    form.refNumber,
  ],
  () => {
    const data = buildPreviewData()
    previewHtml.value = renderFormalEmploymentPreview(data)
  },
  { deep: true, immediate: true },
)

watch(
  () => [form.effectiveDate, form.endDate] as const,
  ([start, end]) => {
    const months = calcServiceMonths(start, end)
    if (months) form.serviceMonths = months
  },
)

function clearPartyB() {
  form.partyB.name = ''
  form.partyB.idNumber = ''
  form.partyB.address = ''
  form.partyB.bankAccountName = ''
  form.partyB.bankName = ''
  form.partyB.bankBranch = ''
  form.partyB.bankAccount = ''
}

function clearPartyA() {
  form.partyA.name = ''
  form.partyA.creditCode = ''
  form.partyA.legalRep = ''
  form.partyA.address = ''
}

function applyArchiveToPartyB(detail: any) {
  const banks = (detail.payMethods || []).filter((m: any) => m.methodType === 'BANK')
  const bank =
    banks.find((m: any) => Number(m.isDefault) === 1) ||
    banks[0] ||
    null

  form.partyB.name = detail.realName || ''
  form.partyB.idNumber = detail.idCard || ''
  form.partyB.address = detail.address || ''
  form.partyB.bankAccountName = bank?.accountName || detail.realName || ''
  form.partyB.bankName = bank?.bankName || ''
  // 档案收款方式只有 bankName + remark，无独立支行字段；remark 优先，否则用开户行回填
  form.partyB.bankBranch = (bank?.remark || bank?.bankName || '').trim()
  form.partyB.bankAccount = bank?.accountNo || ''
}

function applyCompanyToPartyA(company: any | null) {
  if (!company) {
    clearPartyA()
    return
  }
  form.partyA.name = company.name || ''
  // 部门 phone 字段复用为「社会统一信用代码」
  form.partyA.creditCode = company.phone || ''
  form.partyA.legalRep = company.leader || ''
  form.partyA.address = ''
}

async function loadArchives() {
  archiveLoading.value = true
  try {
    const res = await bizApi.archivePage({
      page: 1,
      pageSize: 200,
    })
    archiveOptions.value = res.list || []
  } catch (e: any) {
    ElMessage.error(e?.message || '加载人员列表失败')
  } finally {
    archiveLoading.value = false
  }
}

async function loadCompanies() {
  companyLoading.value = true
  try {
    companyOptions.value = (await sysApi.deptCompanies()) || []
  } catch (e: any) {
    ElMessage.error(e?.message || '加载公司列表失败')
  } finally {
    companyLoading.value = false
  }
}

function onCompanyChange(id: number | null | undefined) {
  form.companyId = id ?? null
  if (id == null || id === ('' as any)) {
    clearPartyA()
    return
  }
  const company = companyOptions.value.find((x) => Number(x.id) === Number(id))
  applyCompanyToPartyA(company || null)
}

async function onArchiveChange(id: number | null | undefined) {
  if (id == null || id === ('' as any)) {
    // 选人过程中 remote/选项刷新可能误触发空值，带入进行中忽略
    if (archiveApplying.value) return
    form.archiveId = null
    clearPartyB()
    return
  }

  const seq = ++applySeq
  archiveApplying.value = true
  form.archiveId = id

  const listed = archiveOptions.value.find((x) => Number(x.id) === Number(id))
  if (listed) {
    applyArchiveToPartyB({ ...listed, payMethods: listed.payMethods || [] })
  }

  try {
    const detail = await bizApi.archiveDetail(id)
    if (seq !== applySeq) return
    applyArchiveToPartyB(detail)
    ElMessage.success(`已带入：${form.partyB.name || '人员档案'}`)
  } catch (e: any) {
    if (seq !== applySeq) return
    if (listed?.realName) {
      ElMessage.warning(`已带入基本信息，收款账户需手填（${e?.message || '详情加载失败'}）`)
    } else {
      ElMessage.error(e?.message || '加载人员档案失败')
    }
  } finally {
    if (seq === applySeq) archiveApplying.value = false
  }
}

async function onExport() {
  if (!form.partyB.name?.trim()) {
    ElMessage.warning('请先选择乙方人员档案')
    return
  }
  if (!form.partyA.name?.trim()) {
    ElMessage.warning('请选择甲方公司')
    return
  }
  exporting.value = true
  try {
    syncPreview()
    await nextTick()
    const el = previewRef.value
    if (!el) throw new Error('预览区域未就绪')
    await exportLaborAgreementPDF(el, form)
    ElMessage.success('PDF 已导出')
  } catch (e: any) {
    console.error(e)
    ElMessage.error(e?.message || '导出失败')
  } finally {
    exporting.value = false
  }
}

function onReset() {
  applySeq++
  archiveApplying.value = false
  const next = createDefaultContract()
  Object.assign(form, next)
  form.partyA = { ...next.partyA }
  form.partyB = { ...next.partyB }
  form.payment = { ...next.payment }
  form.meta = { ...next.meta }
  form.clauses = [...next.clauses]
  form.companyId = null
  previewHtml.value = renderFormalEmploymentPreview(buildPreviewData())
}

onMounted(() => {
  loadArchives()
  loadCompanies()
})
</script>

<template>
  <div class="page-stack labor-page">
    <div class="page-top">
      <div class="page-top__main">
        <div class="labor-page__hint">选择公司与人员档案带入甲乙方信息，填写本期项目与费用后导出 PDF。菜单权限后续由管理员配置。</div>
      </div>
      <div class="page-actions">
        <el-button @click="onReset">清空重填</el-button>
        <el-button type="primary" :loading="exporting" @click="onExport">导出 PDF</el-button>
      </div>
    </div>

    <div class="labor-layout">
      <div class="page-card labor-form-card">
        <el-form label-position="top" class="labor-form">
          <div class="labor-section-title">乙方（人员档案）</div>
          <el-form-item label="选择人员">
            <el-select
              v-model="form.archiveId"
              filterable
              clearable
              placeholder="搜索姓名选择档案"
              :loading="archiveLoading"
              style="width: 100%"
              @change="onArchiveChange"
            >
              <el-option
                v-for="item in archiveOptions"
                :key="item.id"
                :label="`${item.realName || '未命名'}${item.employeeNo ? `（${item.employeeNo}）` : ''}`"
                :value="item.id"
              />
            </el-select>
          </el-form-item>
          <el-row :gutter="12">
            <el-col :span="12">
              <el-form-item label="姓名">
                <el-input v-model="form.partyB.name" placeholder="由档案带入，可改" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="身份证号">
                <el-input v-model="form.partyB.idNumber" placeholder="由档案带入，可改" />
              </el-form-item>
            </el-col>
          </el-row>
          <el-form-item label="住所地">
            <el-input v-model="form.partyB.address" placeholder="可选" />
          </el-form-item>
          <el-row :gutter="12">
            <el-col :span="12">
              <el-form-item label="开户名">
                <el-input v-model="form.partyB.bankAccountName" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="开户行">
                <el-input v-model="form.partyB.bankName" />
              </el-form-item>
            </el-col>
          </el-row>
          <el-row :gutter="12">
            <el-col :span="12">
              <el-form-item label="开户支行/地址">
                <el-input v-model="form.partyB.bankBranch" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="银行账号">
                <el-input v-model="form.partyB.bankAccount" />
              </el-form-item>
            </el-col>
          </el-row>

          <div class="labor-section-title">甲方（委托方）</div>
          <el-form-item label="甲方公司">
            <el-select
              v-model="form.companyId"
              filterable
              clearable
              placeholder="选择一级部门（公司）"
              :loading="companyLoading"
              style="width: 100%"
              @change="onCompanyChange"
            >
              <el-option
                v-for="item in companyOptions"
                :key="item.id"
                :label="item.name"
                :value="item.id"
              />
            </el-select>
          </el-form-item>
          <el-row :gutter="12">
            <el-col :span="12">
              <el-form-item label="社会统一信用代码">
                <el-input v-model="form.partyA.creditCode" placeholder="由公司带入，可改" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="法定代表人/授权代表">
                <el-input v-model="form.partyA.legalRep" placeholder="由公司带入，可改" />
              </el-form-item>
            </el-col>
          </el-row>

          <div class="labor-section-title">项目与费用</div>
          <el-form-item label="项目名称">
            <el-input v-model="form.projectName" placeholder="本期项目名称" />
          </el-form-item>
          <el-row :gutter="12">
            <el-col :span="12">
              <el-form-item label="服务开始">
                <el-date-picker
                  v-model="form.effectiveDate"
                  type="date"
                  value-format="YYYY-MM-DD"
                  style="width: 100%"
                />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="服务结束">
                <el-date-picker
                  v-model="form.endDate"
                  type="date"
                  value-format="YYYY-MM-DD"
                  style="width: 100%"
                />
              </el-form-item>
            </el-col>
          </el-row>
          <el-row :gutter="12">
            <el-col :span="12">
              <el-form-item label="服务月数">
                <el-input v-model="form.serviceMonths" placeholder="自动计算，可改" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="签订日期">
                <el-date-picker
                  v-model="form.signDate"
                  type="date"
                  value-format="YYYY-MM-DD"
                  style="width: 100%"
                />
              </el-form-item>
            </el-col>
          </el-row>
          <el-row :gutter="12">
            <el-col :span="12">
              <el-form-item label="含税总费用（元）">
                <el-input v-model="form.payment.total" placeholder="数字" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="大写金额">
                <el-input :model-value="amountWords" readonly />
              </el-form-item>
            </el-col>
          </el-row>
          <el-row :gutter="12">
            <el-col :span="12">
              <el-form-item label="验收后付款工作日">
                <el-input v-model="form.payment.payDays" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="发票/代开约定">
                <el-input v-model="form.invoiceBy" />
              </el-form-item>
            </el-col>
          </el-row>
          <el-form-item label="合同编号（导出文件名用）">
            <el-input v-model="form.refNumber" />
          </el-form-item>
        </el-form>
      </div>

      <div class="labor-preview-wrap">
        <div class="labor-doc-shell">
          <div
            id="labor-contract-preview"
            ref="previewRef"
            v-html="previewHtml"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.labor-page__hint {
  color: var(--kk-text-secondary);
  font-size: 13px;
  line-height: 1.5;
}

.labor-layout {
  display: grid;
  grid-template-columns: minmax(320px, 420px) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.labor-form-card {
  max-height: calc(100vh - 180px);
  overflow: auto;
}

.labor-section-title {
  font-size: 14px;
  font-weight: 600;
  margin: 8px 0 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid color-mix(in srgb, var(--kk-border, #ddd) 80%, transparent);
}

.labor-form :deep(.el-form-item) {
  margin-bottom: 12px;
}

.labor-preview-wrap {
  min-width: 0;
}

@media (max-width: 1100px) {
  .labor-layout {
    grid-template-columns: 1fr;
  }

  .labor-form-card {
    max-height: none;
  }
}
</style>
