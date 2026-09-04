<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import KkLogoMark from '@/components/KkLogoMark.vue'
import { ElMessage } from 'element-plus'
import { authApi } from '@/api/auth'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)
const totpStatusLoading = ref(false)
const totpRequired = ref(false)
const totpDigits = ref<string[]>(Array(6).fill(''))
const totpInputs = ref<HTMLInputElement[]>([])
let totpStatusRequestId = 0

const form = reactive({ username: 'admin', password: 'admin123' })
const totpCode = computed(() => totpDigits.value.join(''))

function resetTotpInput() {
  totpDigits.value = Array(6).fill('')
}

function setTotpInput(el: unknown, index: number) {
  if (el instanceof HTMLInputElement) totpInputs.value[index] = el
}

function focusTotpInput(index: number) {
  totpInputs.value[index]?.focus()
}

async function checkTotpByUsername() {
  const username = form.username.trim()
  const requestId = ++totpStatusRequestId
  if (!username) {
    totpRequired.value = false
    resetTotpInput()
    return
  }
  totpStatusLoading.value = true
  try {
    const enabled = await authApi.getTotpStatusByUsername(username)
    if (requestId !== totpStatusRequestId) return
    totpRequired.value = enabled
    resetTotpInput()
    if (enabled) setTimeout(() => focusTotpInput(0), 80)
  } catch {
    if (requestId === totpStatusRequestId) {
      totpRequired.value = false
      resetTotpInput()
    }
  } finally {
    if (requestId === totpStatusRequestId) totpStatusLoading.value = false
  }
}

function onTotpDigitInput(event: Event, index: number) {
  const input = event.target as HTMLInputElement
  const digit = input.value.replace(/\D/g, '').slice(-1)
  totpDigits.value[index] = digit
  input.value = digit
  if (digit && index < 5) focusTotpInput(index + 1)
  tryAutoSubmitTotp()
}

function onTotpDigitKeydown(event: KeyboardEvent, index: number) {
  if (event.key === 'Backspace' && !totpDigits.value[index] && index > 0) focusTotpInput(index - 1)
}

function onTotpPaste(event: ClipboardEvent) {
  event.preventDefault()
  const digits = (event.clipboardData?.getData('text') || '').replace(/\D/g, '').slice(0, 6)
  if (!digits) return
  totpDigits.value = Array.from({ length: 6 }, (_, i) => digits[i] ?? '')
  focusTotpInput(Math.min(digits.length, 5))
  tryAutoSubmitTotp()
}

function tryAutoSubmitTotp() {
  if (!totpRequired.value || loading.value || !/^\d{6}$/.test(totpCode.value)) return
  setTimeout(() => {
    if (totpRequired.value && !loading.value && /^\d{6}$/.test(totpCode.value)) void onLogin()
  }, 120)
}

async function onLogin() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入账号和密码')
    return
  }
  if (!totpRequired.value && form.username.trim()) await checkTotpByUsername()
  if (totpRequired.value && !/^\d{6}$/.test(totpCode.value)) {
    ElMessage.warning('请输入6位两步验证码')
    return
  }
  loading.value = true
  try {
    await userStore.login({
      username: form.username.trim(),
      password: form.password,
      totpCode: totpRequired.value ? totpCode.value : undefined,
    })
    ElMessage.success('登录成功')
    await router.push('/dashboard')
  } catch {
    if (totpRequired.value) {
      resetTotpInput()
      focusTotpInput(0)
    }
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (form.username.trim()) void checkTotpByUsername()
})
</script>

<template>
  <div class="login-page">
    <div class="login-bg">
      <div class="orb orb-1" />
      <div class="orb orb-2" />
      <div class="orb orb-3" />
    </div>

    <div class="login-shell">
      <section class="login-brand">
        <div class="brand-mark"><KkLogoMark /></div>
        <h1>BGMN</h1>
        <p>财务 · 项目分钱 · 人事档案 · 权限文件</p>
        <ul class="brand-list">
          <li>资金池统一管理</li>
          <li>项目参与人自动/手动分钱</li>
          <li>全流程进出账审计</li>
        </ul>
      </section>

      <section class="login-panel">
        <h2>欢迎回来</h2>
        <p class="panel-sub">登录后台管理系统</p>

        <el-form class="login-form" @keyup.enter="onLogin">
          <el-form-item>
            <el-input
              v-model="form.username"
              size="large"
              placeholder="账号"
              prefix-icon="User"
              @blur="checkTotpByUsername"
            />
          </el-form-item>
          <el-form-item>
            <el-input
              v-model="form.password"
              size="large"
              type="password"
              show-password
              placeholder="密码"
              prefix-icon="Lock"
            />
          </el-form-item>
          <el-form-item v-if="totpRequired">
            <div class="totp-label">两步验证码</div>
            <div class="totp-boxes">
              <input
                v-for="(_, index) in totpDigits"
                :key="index"
                :ref="(el) => setTotpInput(el, index)"
                class="totp-box"
                :value="totpDigits[index]"
                maxlength="1"
                inputmode="numeric"
                autocomplete="one-time-code"
                @input="onTotpDigitInput($event, index)"
                @keydown="onTotpDigitKeydown($event, index)"
                @paste="onTotpPaste"
              />
            </div>
          </el-form-item>
          <el-button
            type="primary"
            size="large"
            class="login-btn"
            :loading="loading || totpStatusLoading"
            @click="onLogin"
          >
            登录
          </el-button>
        </el-form>

        <div class="login-hint">演示账号 admin / admin123</div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  position: relative;
  overflow: hidden;
  background: #09090b;
}

.login-bg {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.55;
}

.orb-1 {
  width: 420px;
  height: 420px;
  background: #18181b;
  top: -120px;
  left: -80px;
}

.orb-2 {
  width: 360px;
  height: 360px;
  background: #52525b;
  bottom: -100px;
  right: 10%;
}

.orb-3 {
  width: 280px;
  height: 280px;
  background: #a1a1aa;
  top: 40%;
  right: -60px;
}

.login-shell {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: 1fr 420px;
  width: min(960px, 100%);
  border-radius: 24px;
  overflow: hidden;
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.45);
  border: 1px solid rgba(255, 255, 255, 0.08);
}

.login-brand {
  padding: 48px 40px;
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.06) 0%, rgba(255, 255, 255, 0.02) 100%);
  backdrop-filter: blur(20px);
  color: #e2e8f0;
}

.brand-mark {
  width: 52px;
  height: 52px;
  border-radius: 14px;
  overflow: hidden;
  color: #fff;
  background: #18181b;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.35);
  margin-bottom: 24px;
}

.login-brand h1 {
  margin: 0 0 10px;
  font-size: 28px;
  font-weight: 800;
  letter-spacing: -0.03em;
  color: #fff;
}

.login-brand p {
  margin: 0 0 28px;
  color: #94a3b8;
  font-size: 14px;
}

.brand-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.brand-list li {
  position: relative;
  padding-left: 18px;
  margin-bottom: 12px;
  font-size: 14px;
  color: #cbd5e1;
}

.brand-list li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 8px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #d4d4d8;
}

.login-panel {
  padding: 48px 36px;
  background: #fff;
}

.login-panel h2 {
  margin: 0;
  font-size: 24px;
  font-weight: 800;
  letter-spacing: -0.02em;
  color: #0f172a;
}

.panel-sub {
  margin: 8px 0 28px;
  color: #64748b;
  font-size: 14px;
}

.login-form :deep(.el-input__wrapper) {
  padding: 4px 12px;
  box-shadow: 0 0 0 1px #e2e8f0 inset;
}

.login-form :deep(.el-input__wrapper:hover),
.login-form :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px #d1d1d2 inset;
}

.login-btn {
  width: 100%;
  height: 46px;
  font-size: 15px;
  font-weight: 600;
  margin-top: 4px;
}

.login-hint {
  margin-top: 20px;
  text-align: center;
  font-size: 13px;
  color: #94a3b8;
}

.totp-label {
  margin-bottom: 8px;
  font-size: 13px;
  color: #64748b;
}

.totp-boxes {
  display: flex;
  gap: 8px;
  justify-content: center;
}

.totp-box {
  width: 44px;
  height: 48px;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  text-align: center;
  font-size: 20px;
  font-weight: 700;
  outline: none;
  transition: border-color 0.2s, box-shadow 0.2s;
}

.totp-box:focus {
  border-color: #18181b;
  box-shadow: 0 0 0 3px rgba(24, 24, 27, 0.15);
}

@media (max-width: 860px) {
  .login-shell {
    grid-template-columns: 1fr;
  }
  .login-brand {
    display: none;
  }
}
</style>
