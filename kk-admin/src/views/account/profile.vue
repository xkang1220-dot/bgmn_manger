<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { authApi, type TotpGenerateResult } from '@/api/auth'
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

const totpQrCode = computed(() => {
  if (!totpSetup.value?.qrString) return ''
  return `https://api.qrserver.com/v1/create-qr-code/?size=200x200&data=${encodeURIComponent(totpSetup.value.qrString)}`
})

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

onMounted(async () => {
  const profile = await sysApi.profile()
  Object.assign(form, profile)
  queryCode.value = profile?.taskQueryCode || ''
  totpEnabled.value = await authApi.getTotpStatus()
})
</script>

<template>
  <div class="page-card" style="max-width: 560px">
    <h3 class="page-title">账号资料</h3>
    <el-form label-width="100px" style="margin-top: 12px">
      <el-form-item label="昵称"><el-input v-model="form.nickname" /></el-form-item>
      <el-form-item label="手机"><el-input v-model="form.phone" /></el-form-item>
      <el-form-item label="邮箱"><el-input v-model="form.email" /></el-form-item>
      <el-form-item><el-button type="primary" @click="save">保存资料</el-button></el-form-item>
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
