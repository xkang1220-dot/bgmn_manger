<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { authApi, type TotpGenerateResult } from '@/api/auth'
import { bizApi } from '@/api/biz'
import { sysApi } from '@/api/system'

const form = reactive<any>({ nickname: '', email: '', phone: '', gender: 0 })
const pwd = reactive({ oldPassword: '', newPassword: '' })
const totpEnabled = ref(false)
const totpDialogVisible = ref(false)
const totpSetupLoading = ref(false)
const totpVerifyLoading = ref(false)
const totpSetup = ref<TotpGenerateResult | null>(null)
const totpVerifyCode = ref('')
const queryCode = ref('')
const codeLoading = ref(false)
const archiveSaving = ref(false)

const METHOD_TYPES = [
  { value: 'BANK', label: '银行卡' },
  { value: 'ALIPAY', label: '支付宝' },
]

const archive = reactive<any>({
  id: undefined,
  realName: '',
  employeeNo: '',
  position: '',
  education: '',
  entryDate: '',
  idCard: '',
  address: '',
  emergencyContact: '',
  emergencyPhone: '',
  remark: '',
  payMethods: [] as any[],
})

const totpQrCode = computed(() => {
  if (!totpSetup.value?.qrString) return ''
  return `https://api.qrserver.com/v1/create-qr-code/?size=200x200&data=${encodeURIComponent(totpSetup.value.qrString)}`
})

function emptyPayMethod() {
  return {
    id: undefined,
    methodType: 'BANK',
    accountName: '',
    accountNo: '',
    bankName: '',
    isDefault: 0,
    remark: '',
  }
}

function addPayMethod() {
  if (!archive.payMethods) archive.payMethods = []
  const row = emptyPayMethod()
  if (!archive.payMethods.length) row.isDefault = 1
  archive.payMethods.push(row)
}

function removePayMethod(index: number) {
  archive.payMethods.splice(index, 1)
  if (archive.payMethods.length && !archive.payMethods.some((m: any) => Number(m.isDefault) === 1)) {
    archive.payMethods[0].isDefault = 1
  }
}

function setDefaultPayMethod(index: number) {
  archive.payMethods.forEach((m: any, i: number) => {
    m.isDefault = i === index ? 1 : 0
  })
}

async function save() {
  await sysApi.updateProfile(form)
  ElMessage.success('已保存')
}

async function changePwd() {
  await sysApi.updatePassword(pwd)
  ElMessage.success('密码已修改')
  pwd.oldPassword = ''
  pwd.newPassword = ''
}

async function openTotpSetup() {
  totpDialogVisible.value = true
  totpVerifyCode.value = ''
  totpSetupLoading.value = true
  try {
    totpSetup.value = await authApi.generateTotp()
  } finally {
    totpSetupLoading.value = false
  }
}

async function verifyTotpSetup() {
  totpVerifyLoading.value = true
  try {
    await authApi.verifyTotp(totpVerifyCode.value)
    ElMessage.success('已开启二次验证')
    totpDialogVisible.value = false
    totpEnabled.value = true
  } finally {
    totpVerifyLoading.value = false
  }
}

async function generateQueryCode() {
  if (queryCode.value) {
    try {
      await ElMessageBox.confirm('重新生成后，旧码会立即失效。确定继续？', '重新生成查询码', {
        type: 'warning',
        confirmButtonText: '重新生成',
        cancelButtonText: '取消',
      })
    } catch {
      return
    }
  }
  codeLoading.value = true
  try {
    queryCode.value = await sysApi.generateTaskQueryCode()
    ElMessage.success(queryCode.value ? '已生成查询码' : '生成失败')
  } finally {
    codeLoading.value = false
  }
}

async function copyQueryCode() {
  if (!queryCode.value) return
  try {
    await navigator.clipboard.writeText(queryCode.value)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选中')
  }
}

async function loadArchive() {
  const mine = await bizApi.myArchive()
  Object.assign(archive, {
    id: mine?.id,
    realName: mine?.realName || '',
    employeeNo: mine?.employeeNo || '',
    position: mine?.position || '',
    education: mine?.education || '',
    entryDate: mine?.entryDate ? String(mine.entryDate).slice(0, 10) : '',
    idCard: mine?.idCard || '',
    address: mine?.address || '',
    emergencyContact: mine?.emergencyContact || '',
    emergencyPhone: mine?.emergencyPhone || '',
    remark: mine?.remark || '',
    payMethods: (mine?.payMethods || []).map((m: any) => ({ ...m })),
  })
}

async function saveArchive() {
  if (!archive.realName?.trim()) {
    ElMessage.warning('请填写姓名')
    return
  }
  for (const m of archive.payMethods || []) {
    if (!m.accountNo?.trim()) {
      ElMessage.warning('请填写收款账号')
      return
    }
    if (m.methodType === 'BANK' && !m.bankName?.trim()) {
      ElMessage.warning('银行卡请填写开户行')
      return
    }
  }
  archiveSaving.value = true
  try {
    await bizApi.saveMyArchive({
      id: archive.id,
      realName: archive.realName.trim(),
      employeeNo: archive.employeeNo?.trim() || '',
      position: archive.position?.trim() || '',
      education: archive.education?.trim() || '',
      entryDate: archive.entryDate || null,
      idCard: archive.idCard?.trim() || '',
      address: archive.address?.trim() || '',
      emergencyContact: archive.emergencyContact?.trim() || '',
      emergencyPhone: archive.emergencyPhone?.trim() || '',
      remark: archive.remark?.trim() || '',
      payMethods: (archive.payMethods || []).map((m: any, i: number) => ({
        id: m.id,
        methodType: m.methodType,
        accountName: m.accountName || '',
        accountNo: m.accountNo,
        bankName: m.bankName || '',
        remark: m.remark || '',
        sort: i,
        isDefault: Number(m.isDefault) === 1 ? 1 : 0,
      })),
    })
    ElMessage.success('员工档案已保存')
    await loadArchive()
  } finally {
    archiveSaving.value = false
  }
}

onMounted(async () => {
  try {
    const profile = await sysApi.profile()
    Object.assign(form, profile)
    queryCode.value = profile?.taskQueryCode || ''
  } catch {
    // 基础资料失败交由全局提示
  }
  try {
    totpEnabled.value = await authApi.getTotpStatus()
  } catch {
    totpEnabled.value = false
  }
  try {
    await loadArchive()
  } catch {
    ElMessage.warning('员工档案加载失败，可稍后重试保存')
  }
})
</script>

<template>
  <div class="page-card profile-card">
    <h3 class="page-title">账号资料</h3>
    <el-form label-width="100px" class="profile-form">
      <el-form-item label="昵称"><el-input v-model="form.nickname" /></el-form-item>
      <el-form-item label="手机"><el-input v-model="form.phone" /></el-form-item>
      <el-form-item label="邮箱"><el-input v-model="form.email" /></el-form-item>
      <el-form-item><el-button type="primary" @click="save">保存资料</el-button></el-form-item>

      <el-divider content-position="left">员工档案</el-divider>
      <p class="section-tip">在此填写后会同步到人事「人员档案」，用于发薪、报销等。</p>
      <el-form-item label="姓名" required><el-input v-model="archive.realName" maxlength="64" /></el-form-item>
      <el-form-item label="工号"><el-input v-model="archive.employeeNo" maxlength="64" /></el-form-item>
      <el-form-item label="岗位"><el-input v-model="archive.position" maxlength="64" /></el-form-item>
      <el-form-item label="学历"><el-input v-model="archive.education" maxlength="64" /></el-form-item>
      <el-form-item label="入职日期">
        <el-date-picker v-model="archive.entryDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
      </el-form-item>
      <el-form-item label="身份证"><el-input v-model="archive.idCard" maxlength="32" /></el-form-item>
      <el-form-item label="地址"><el-input v-model="archive.address" maxlength="255" /></el-form-item>
      <el-form-item label="紧急联系人"><el-input v-model="archive.emergencyContact" maxlength="64" /></el-form-item>
      <el-form-item label="紧急电话"><el-input v-model="archive.emergencyPhone" maxlength="32" /></el-form-item>
      <el-form-item label="备注"><el-input v-model="archive.remark" type="textarea" :rows="2" maxlength="255" /></el-form-item>
      <el-form-item label="收款方式">
        <div class="pay-box">
          <div v-for="(m, index) in archive.payMethods" :key="index" class="pay-card">
            <div class="pay-card__head">
              <el-select v-model="m.methodType" class="pay-type">
                <el-option v-for="t in METHOD_TYPES" :key="t.value" :label="t.label" :value="t.value" />
              </el-select>
              <div class="pay-card__actions">
                <el-checkbox
                  :model-value="Number(m.isDefault) === 1"
                  @change="(checked: boolean | string | number) => { if (checked) setDefaultPayMethod(Number(index)) }"
                >默认</el-checkbox>
                <el-button link type="danger" @click="removePayMethod(Number(index))">删除</el-button>
              </div>
            </div>
            <div class="pay-card__grid">
              <div class="pay-field">
                <span class="pay-field__label">户名</span>
                <el-input v-model="m.accountName" placeholder="收款人姓名" />
              </div>
              <div class="pay-field">
                <span class="pay-field__label">账号</span>
                <el-input v-model="m.accountNo" placeholder="卡号 / 支付宝账号" />
              </div>
              <div v-if="m.methodType === 'BANK'" class="pay-field pay-field--full">
                <span class="pay-field__label">开户行</span>
                <el-input v-model="m.bankName" placeholder="如：中国工商银行某某支行" />
              </div>
            </div>
          </div>

          <button type="button" class="pay-add" @click="addPayMethod">
            <span class="pay-add__plus">+</span>
            添加收款方式
          </button>
          <p class="pay-tip">支持银行卡 / 支付宝，可多条；默认用于发工资与报销申请</p>
        </div>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="archiveSaving" @click="saveArchive">保存员工档案</el-button>
      </el-form-item>

      <el-divider />
      <el-form-item label="原密码"><el-input v-model="pwd.oldPassword" type="password" show-password /></el-form-item>
      <el-form-item label="新密码"><el-input v-model="pwd.newPassword" type="password" show-password /></el-form-item>
      <el-form-item><el-button @click="changePwd">修改密码</el-button></el-form-item>
      <el-divider />
      <el-form-item label="二次验证">
        <el-tag :type="totpEnabled ? 'success' : 'info'">{{ totpEnabled ? '已开启' : '未开启' }}</el-tag>
        <el-button link type="primary" style="margin-left: 12px" @click="openTotpSetup">设置</el-button>
      </el-form-item>
      <el-divider />
      <el-form-item label="任务查询码">
        <div class="query-code-block">
          <div v-if="queryCode" class="query-code-value">{{ queryCode }}</div>
          <span v-else class="query-code-empty">还没有查询码</span>
          <div class="query-code-actions">
            <el-button v-if="queryCode" @click="copyQueryCode">复制</el-button>
            <el-button type="primary" :loading="codeLoading" @click="generateQueryCode">
              {{ queryCode ? '重新生成' : '生成查询码' }}
            </el-button>
          </div>
          <p class="query-code-tip">生成后，别人只需这 4 位即可查看你负责、参与或创建的任务</p>
        </div>
      </el-form-item>
    </el-form>

    <el-dialog v-model="totpDialogVisible" title="绑定二次验证" width="420px">
      <div v-loading="totpSetupLoading">
        <img v-if="totpQrCode" :src="totpQrCode" alt="qr" style="width: 180px; display: block; margin: 0 auto 12px" />
        <el-input v-model="totpVerifyCode" placeholder="输入验证码" />
      </div>
      <template #footer>
        <el-button @click="totpDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="totpVerifyLoading" @click="verifyTotpSetup">确认开启</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.profile-card {
  max-width: 720px;
}

.profile-form {
  margin-top: 12px;
}

.section-tip {
  margin: -4px 0 14px;
  padding-left: 100px;
  font-size: 12px;
  color: var(--kk-text-secondary);
  line-height: 1.5;
}

.pay-box {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.pay-card {
  padding: 14px 16px;
  border-radius: var(--kk-radius-sm, 14px);
  background: rgba(255, 255, 255, 0.38);
  border: 1px solid var(--kk-glass-border, rgba(255, 255, 255, 0.72));
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
}

.pay-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.pay-type {
  width: 132px;
}

.pay-card__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pay-card__grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.pay-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.pay-field--full {
  grid-column: 1 / -1;
}

.pay-field__label {
  font-size: 12px;
  color: var(--kk-text-secondary, #71717a);
  line-height: 1.2;
}

.pay-add {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: 100%;
  min-height: 40px;
  border-radius: var(--kk-radius-sm, 14px);
  border: 1px dashed rgba(24, 24, 27, 0.16);
  background: rgba(255, 255, 255, 0.22);
  color: var(--kk-text, #18181b);
  font-size: 13px;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;
}

.pay-add:hover {
  background: rgba(255, 255, 255, 0.42);
  border-color: rgba(24, 24, 27, 0.28);
}

.pay-add__plus {
  font-size: 16px;
  line-height: 1;
  font-weight: 600;
}

.pay-tip {
  margin: 0;
  font-size: 12px;
  color: var(--kk-text-secondary, #71717a);
  line-height: 1.5;
}

@media (max-width: 640px) {
  .pay-card__grid {
    grid-template-columns: 1fr;
  }

  .pay-card__head {
    flex-wrap: wrap;
  }
}

.query-code-block {
  width: 100%;
}

.query-code-value {
  font-size: 28px;
  font-weight: 700;
  letter-spacing: 0.28em;
  font-variant-numeric: tabular-nums;
  color: var(--kk-text);
  line-height: 1.2;
}

.query-code-empty {
  color: var(--kk-text-muted);
  font-size: 13px;
}

.query-code-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

.query-code-tip {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--kk-text-secondary);
  line-height: 1.5;
}
</style>
