<script setup lang="ts">
import { useRouter } from 'vue-router'

defineProps<{
  taskIds: number[]
  message?: string
}>()

const router = useRouter()

function goAccount() {
  router.push('/account')
}
</script>

<template>
  <div class="done">
    <div class="done-msg">{{ message || '已成功登记任务' }}</div>
    <div v-if="taskIds?.length" class="done-ids">
      任务 ID：{{ taskIds.join('、') }}
    </div>
    <el-button link type="primary" @click="goAccount">去工作台查看</el-button>
  </div>
</template>

<style scoped>
.done {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px;
  border-radius: 14px;
  background: rgba(16, 185, 129, 0.1);
  border: 1px solid rgba(16, 185, 129, 0.22);
}

.done-msg {
  font-size: 14px;
  font-weight: 600;
  color: #065f46;
}

.done-ids {
  font-size: 12px;
  color: #047857;
}
</style>
