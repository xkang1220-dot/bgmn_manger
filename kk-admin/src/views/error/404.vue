<script setup lang="ts">
import { useRouter } from 'vue-router'
import KkLogoMark from '@/components/KkLogoMark.vue'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

function goHome() {
  void router.replace(userStore.token ? '/account' : '/login')
}

function goBack() {
  if (window.history.length > 1) {
    router.back()
    return
  }
  goHome()
}
</script>

<template>
  <main class="not-found">
    <div class="not-found__glow not-found__glow--left" />
    <div class="not-found__glow not-found__glow--right" />

    <section class="not-found__card">
      <div class="not-found__brand" aria-label="BGMN">
        <KkLogoMark />
      </div>
      <p class="not-found__code">404</p>
      <h1>页面不存在</h1>
      <p class="not-found__message">你访问的页面可能已被移除、名称已更改，或暂时不可用。</p>
      <div class="not-found__actions">
        <el-button size="large" @click="goBack">返回上一页</el-button>
        <el-button type="primary" size="large" @click="goHome">
          {{ userStore.token ? '返回个人中心' : '返回登录' }}
        </el-button>
      </div>
    </section>
  </main>
</template>

<style scoped>
.not-found {
  position: relative;
  display: grid;
  min-height: 100vh;
  place-items: center;
  overflow: hidden;
  padding: 24px;
  background: #f1f1f1;
}

.not-found__glow {
  position: absolute;
  width: 420px;
  height: 420px;
  border-radius: 50%;
  filter: blur(100px);
  pointer-events: none;
}

.not-found__glow--left {
  top: -180px;
  left: -140px;
  background: rgba(161, 161, 170, 0.28);
}

.not-found__glow--right {
  right: -160px;
  bottom: -200px;
  background: rgba(82, 82, 91, 0.18);
}

.not-found__card {
  position: relative;
  z-index: 1;
  width: min(560px, 100%);
  padding: 48px 40px;
  border: 1px solid rgba(255, 255, 255, 0.9);
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.58);
  box-shadow: 0 20px 60px rgba(24, 24, 27, 0.08);
  backdrop-filter: saturate(180%) blur(24px);
  text-align: center;
}

.not-found__brand {
  width: 52px;
  height: 52px;
  margin: 0 auto 22px;
  overflow: hidden;
  border-radius: 14px;
  color: #fff;
  background: #18181b;
  box-shadow: 0 8px 24px rgba(24, 24, 27, 0.2);
}

.not-found__code {
  margin: 0;
  color: #a1a1aa;
  font-size: clamp(72px, 16vw, 112px);
  font-weight: 800;
  line-height: 0.95;
  letter-spacing: -0.08em;
}

.not-found h1 {
  margin: 22px 0 10px;
  color: #18181b;
  font-size: 28px;
  letter-spacing: -0.03em;
}

.not-found__message {
  max-width: 420px;
  margin: 0 auto;
  color: #727272;
  font-size: 14px;
  line-height: 1.8;
}

.not-found__actions {
  display: flex;
  justify-content: center;
  gap: 10px;
  margin-top: 30px;
}

@media (max-width: 480px) {
  .not-found {
    padding: 12px;
  }

  .not-found__card {
    padding: 36px 20px;
    border-radius: 18px;
  }

  .not-found__actions {
    flex-direction: column-reverse;
  }

  .not-found__actions .el-button {
    width: 100%;
    margin-left: 0;
  }
}
</style>
