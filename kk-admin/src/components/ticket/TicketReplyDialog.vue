<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ticketApi } from '@/api/ticket'
import TicketRichEditor from '@/components/ticket/TicketRichEditor.vue'
import { useUserStore } from '@/stores/user'

const props = defineProps<{
  modelValue: boolean
  ticketId?: number
  ticketTitle?: string
}>()

const emit = defineEmits<{ 'update:modelValue': [boolean]; closed: [] }>()

const userStore = useUserStore()
const loading = ref(false)
const sending = ref(false)
const messages = ref<any[]>([])
const content = ref('')
const listRef = ref<HTMLDivElement | null>(null)

watch(
  () => [props.modelValue, props.ticketId] as const,
  async ([open, id]) => {
    if (!open || !id) return
    content.value = ''
    await load()
    await ticketApi.markRead(id)
  },
)

async function load() {
  if (!props.ticketId) return
  loading.value = true
  try {
    const res = await ticketApi.replyPage(props.ticketId, { page: 1, pageSize: 200, order: 'asc' })
    messages.value = res.list || []
    await nextTick()
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
  } finally {
    loading.value = false
  }
}

function onVisibleChange(v: boolean) {
  emit('update:modelValue', v)
  if (!v) emit('closed')
}

function isMine(msg: any) {
  return Number(msg.senderId) === Number(userStore.user?.id)
}

async function send() {
  if (!props.ticketId) return
  const plain = content.value.replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').trim()
  if (!plain && !content.value.includes('<img')) {
    ElMessage.warning('请输入回复内容')
    return
  }
  sending.value = true
  try {
    const msg = await ticketApi.reply(props.ticketId, content.value)
    messages.value.push(msg)
    content.value = ''
    await nextTick()
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
  } finally {
    sending.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="ticketTitle ? `沟通 · ${ticketTitle}` : '工单沟通'"
    width="680px"
    destroy-on-close
    @update:model-value="onVisibleChange"
  >
    <div v-loading="loading" ref="listRef" class="reply-list">
      <div
        v-for="msg in messages"
        :key="msg.id"
        class="reply-bubble"
        :class="{ mine: isMine(msg) }"
      >
        <div class="meta">
          <span>{{ isMine(msg) ? '我' : (msg.senderType === 'handler' ? '对方（处理）' : '对方（用户）') }}</span>
          <span>{{ msg.senderName }}</span>
          <span>{{ msg.createTime }}</span>
        </div>
        <div class="body" v-html="msg.content" />
      </div>
      <el-empty v-if="!loading && !messages.length" description="暂无回复" :image-size="64" />
    </div>
    <div class="reply-composer">
      <TicketRichEditor v-model="content" placeholder="输入回复…" :min-height="100" />
      <div class="actions">
        <el-button type="primary" :loading="sending" @click="send">发送</el-button>
      </div>
    </div>
  </el-dialog>
</template>

<style scoped>
.reply-list {
  max-height: 360px;
  overflow: auto;
  padding: 8px 4px 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.reply-bubble {
  max-width: 88%;
  align-self: flex-start;
  padding: 10px 12px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.55);
  border: 1px solid rgba(255, 255, 255, 0.7);
}
.reply-bubble.mine {
  align-self: flex-end;
  background: rgba(37, 99, 235, 0.12);
}
.meta {
  display: flex;
  gap: 10px;
  font-size: 12px;
  color: #71717a;
  margin-bottom: 6px;
}
.body {
  font-size: 14px;
  line-height: 1.55;
  word-break: break-word;
}
.body :deep(img) {
  max-width: 100%;
  border-radius: 8px;
}
.reply-composer {
  border-top: 1px solid rgba(24, 24, 27, 0.08);
  padding-top: 12px;
}
.actions {
  margin-top: 10px;
  display: flex;
  justify-content: flex-end;
}
</style>
