<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
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

onMounted(async () => {
  Object.assign(form, await sysApi.profile())
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
