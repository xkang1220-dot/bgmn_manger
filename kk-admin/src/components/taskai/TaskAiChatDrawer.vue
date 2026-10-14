<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  hasConfirmCard,
  taskAiApi,
  type ClarifyField,
  type ConfirmTaskItem,
  type TaskAiChatResponse,
  type UiBlock,
} from '@/api/taskAi'
import BlockRenderer from './BlockRenderer.vue'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [boolean] }>()

type ChatRole = 'user' | 'assistant'
type GuideKind = 'log' | 'query'

interface ChatMessage {
  id: string
  role: ChatRole
  text?: string
  blocks?: UiBlock[]
  interactive?: boolean
}

interface GuideTip {
  id: string
  kind: GuideKind
  label: string
  /** 发给后端 / 记入用户气泡的文案 */
  message: string
}

const GUIDE_TIPS: GuideTip[] = [
  {
    id: 'log-today',
    kind: 'log',
    label: '登记一下我今天做了什么',
    message: '我想登记今天做的事情',
  },
  {
    id: 'timeline-today',
    kind: 'query',
    label: '查看一下我今天的任务时间线',
    message: '查看一下我今天的任务时间线',
  },
  {
    id: 'summary-today',
    kind: 'query',
    label: '汇总一下我今天完成了哪些任务',
    message: '汇总一下我今天完成了哪些任务，用统计和列表说明',
  },
]

const open = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const sessionId = ref<string>()
const input = ref('')
const sending = ref(false)
const confirming = ref(false)
const composing = ref(false)
const messages = ref<ChatMessage[]>([])
const listRef = ref<HTMLElement | null>(null)
const pendingConfirm = ref(false)
const guideIndex = ref(0)
const guidePaused = ref(false)

let guideTimer: ReturnType<typeof setInterval> | null = null

const busy = computed(() => sending.value || confirming.value)
const showGuides = computed(() => !input.value.trim() && !busy.value)

function onCompositionStart() {
  composing.value = true
}

function onCompositionEnd() {
  // IME 确认后偶发仍带 isComposing，延后一帧再放开
  requestAnimationFrame(() => {
    composing.value = false
  })
}

function onComposerKeydown(e: KeyboardEvent) {
  if (e.key !== 'Enter' || e.shiftKey) return
  // 中文输入法选词回车（如确认 ERP）不可触发发送
  if (e.isComposing || composing.value || e.keyCode === 229) return
  e.preventDefault()
  void onSend()
}

function uid() {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

async function scrollBottom() {
  await nextTick()
  const el = listRef.value
  if (el) el.scrollTop = el.scrollHeight
}

function resetConversation() {
  sessionId.value = undefined
  messages.value = []
  pendingConfirm.value = false
  input.value = ''
  guideIndex.value = 0
}

/**
 * blocks 是主要展示协议，replyText 通常只用于兼容旧客户端。
 * 查询类 block（列表、统计、图表）只能表达结构化数据，无法完整展示
 * 后端返回的时间范围、人员范围和空结果解释，因此在没有文本 block 时
 * 将 replyText 作为一条 markdown 放在结构化结果前面。
 */
function resolveAssistantBlocks(res: TaskAiChatResponse): UiBlock[] {
  const blocks = Array.isArray(res.blocks) ? res.blocks : []
  const replyText = res.replyText?.trim()

  if (!blocks.length) {
    return replyText
      ? [{ type: 'markdown', content: replyText }]
      : [{ type: 'markdown', content: '（无内容）' }]
  }

  const hasTextBlock = blocks.some(
    (block) => block.type === 'markdown' || block.type === 'clarify' || block.type === 'done',
  )
  const hasConfirm = blocks.some((block) => block.type === 'confirm_card')

  if (replyText && !hasTextBlock && !hasConfirm) {
    return [{ type: 'markdown', content: replyText }, ...blocks]
  }

  return blocks
}

function startGuideCarousel() {
  stopGuideCarousel()
  guideTimer = setInterval(() => {
    if (guidePaused.value || !showGuides.value) return
    guideIndex.value = (guideIndex.value + 1) % GUIDE_TIPS.length
  }, 3200)
}

function stopGuideCarousel() {
  if (guideTimer) {
    clearInterval(guideTimer)
    guideTimer = null
  }
}

async function onGuideClick(tip: GuideTip) {
  if (busy.value) return
  // 登记类走正式 chat，由后端收集信息 / 出 confirm_card
  await sendRaw(tip.message)
}

onMounted(() => {
  startGuideCarousel()
})

onBeforeUnmount(() => {
  stopGuideCarousel()
})

watch(
  () => props.modelValue,
  (v) => {
    if (v && !messages.value.length) {
      messages.value.push({
        id: uid(),
        role: 'assistant',
        text: '可以说「过去一小时在某某项目做了什么」来记工，或问「我今天做了什么」。项目负责人也可查成员今日任务。',
        blocks: [
          {
            type: 'markdown',
            content:
              '可以说「过去一小时在某某项目做了什么」来记工，或问「我今天做了什么」。项目负责人也可查成员今日任务。',
          },
        ],
        interactive: false,
      })
    }
  },
)

function appendAssistant(res: TaskAiChatResponse, interactive = true) {
  sessionId.value = res.sessionId || sessionId.value
  const blocks = Array.isArray(res.blocks) ? res.blocks : []
  // pendingConfirm 与 confirm_card 对齐；有卡片时不再用 replyText 重复展示
  pendingConfirm.value = !!res.pendingConfirm || hasConfirmCard(blocks)
  messages.value.push({
    id: uid(),
    role: 'assistant',
    blocks: resolveAssistantBlocks(res),
    interactive,
  })
  for (let i = 0; i < messages.value.length - 1; i++) {
    if (messages.value[i].role === 'assistant') messages.value[i].interactive = false
  }
}

function shouldResetSession(errMsg: string) {
  return /会话不存在|无权访问|已取消|已提交|请勿重复|不能继续/.test(errMsg || '')
}

async function sendRaw(message: string, clarifyAnswer?: {
  field: ClarifyField
  optionId: string
  label?: string
}) {
  const text = message.trim()
  if (!text && !clarifyAnswer) return
  if (busy.value) return

  const displayText = text || clarifyAnswer?.label || clarifyAnswer?.optionId || ''
  messages.value.push({ id: uid(), role: 'user', text: displayText })

  sending.value = true
  await scrollBottom()
  try {
    const payload = {
      sessionId: sessionId.value,
      message: text || clarifyAnswer?.label || '',
      clarifyAnswer,
    }
    const res = await taskAiApi.chat(payload)
    appendAssistant(res, true)
  } catch (e: any) {
    const msg = e?.message || '网络错误'
    if (shouldResetSession(msg)) {
      sessionId.value = undefined
      pendingConfirm.value = false
    }
    messages.value.push({
      id: uid(),
      role: 'assistant',
      blocks: [{ type: 'markdown', content: msg }],
      interactive: false,
    })
  } finally {
    sending.value = false
    await scrollBottom()
  }
}

async function onSend() {
  const text = input.value
  input.value = ''
  await sendRaw(text)
}

async function onClarify(payload: { field: ClarifyField; optionId: string; label: string }) {
  await sendRaw(payload.label, payload)
}

async function onConfirm(items: ConfirmTaskItem[]) {
  if (!sessionId.value) {
    ElMessage.warning('会话已失效，请重新发送')
    return
  }
  if (confirming.value) return
  confirming.value = true
  try {
    const res = await taskAiApi.confirm({
      sessionId: sessionId.value,
      items: items.map((item) => ({
        ...item,
        title: (item.title || '').slice(0, 200),
        content: item.content ?? '',
      })),
    })
    pendingConfirm.value = false
    for (const m of messages.value) {
      if (m.role === 'assistant') m.interactive = false
    }
    const blocks =
      Array.isArray(res.blocks) && res.blocks.length
        ? res.blocks
        : res.done
          ? [
              {
                type: 'done' as const,
                taskIds: res.done.taskIds || [],
                message: res.done.message || '已成功登记任务',
              },
            ]
          : [{ type: 'markdown' as const, content: '已确认入库。' }]
    messages.value.push({
      id: uid(),
      role: 'assistant',
      blocks,
      interactive: false,
    })
    // committed 会话不可继续复用
    sessionId.value = undefined
    ElMessage.success(res.done?.message || '已入库')
  } catch (e: any) {
    if (shouldResetSession(e?.message || '')) {
      sessionId.value = undefined
      pendingConfirm.value = false
    }
  } finally {
    confirming.value = false
    await scrollBottom()
  }
}

function onRevise() {
  pendingConfirm.value = false
  for (const m of messages.value) {
    if (m.role === 'assistant') m.interactive = false
  }
  input.value = ''
  // 短句补槽，不要在前端拼整句；后端合并上下文
  void sendRaw('我想改一下刚才的登记内容')
}

async function onCancelConfirm() {
  if (confirming.value) return
  const sid = sessionId.value
  pendingConfirm.value = false
  for (const m of messages.value) {
    if (m.role === 'assistant') m.interactive = false
  }
  if (sid) {
    try {
      await taskAiApi.cancel(sid)
    } catch {
      /* ignore */
    }
  }
  sessionId.value = undefined
  messages.value.push({
    id: uid(),
    role: 'assistant',
    blocks: [{ type: 'markdown', content: '已取消本次登记。可以继续提问或重新描述要登记的内容。' }],
    interactive: false,
  })
  await scrollBottom()
}

async function onRestart() {
  const sid = sessionId.value
  if (sid) {
    try {
      await taskAiApi.cancel(sid)
    } catch {
      /* ignore */
    }
  }
  resetConversation()
  messages.value.push({
    id: uid(),
    role: 'assistant',
    blocks: [{ type: 'markdown', content: '已开启新对话，请继续描述。' }],
    interactive: false,
  })
}
</script>

<template>
  <el-drawer
    v-model="open"
    class="task-ai-drawer"
    modal-class="task-ai-drawer-overlay"
    direction="ltr"
    size="420px"
    append-to-body
    :with-header="true"
    title="AI 助手"
  >
    <template #header>
      <div class="drawer-head">
        <div>
          <div class="drawer-title">AI 助手</div>
          <div class="drawer-sub">记工 · 问答 · 图表</div>
        </div>
        <el-button link type="primary" @click="onRestart">新对话</el-button>
      </div>
    </template>

    <div class="chat-shell">
      <div ref="listRef" class="chat-list">
        <div
          v-for="msg in messages"
          :key="msg.id"
          class="chat-row"
          :class="msg.role === 'user' ? 'is-user' : 'is-ai'"
        >
          <div class="bubble">
            <div v-if="msg.role === 'user'" class="user-text">{{ msg.text }}</div>
            <BlockRenderer
              v-else
              :blocks="msg.blocks || []"
              :interactive="!!msg.interactive"
              :busy="busy"
              @clarify="onClarify"
              @confirm="onConfirm"
              @revise="onRevise"
              @cancel-confirm="onCancelConfirm"
            />
          </div>
        </div>
        <div v-if="sending" class="chat-row is-ai">
          <div class="bubble typing">正在理解并调用工具…</div>
        </div>
      </div>

      <div class="composer">
        <div
          v-show="showGuides"
          class="guide-carousel"
          @mouseenter="guidePaused = true"
          @mouseleave="guidePaused = false"
        >
          <div
            class="guide-track"
            :style="{ transform: `translateX(-${guideIndex * 100}%)` }"
          >
            <button
              v-for="tip in GUIDE_TIPS"
              :key="tip.id"
              type="button"
              class="guide-chip"
              :disabled="busy"
              @click="onGuideClick(tip)"
            >
              <i class="guide-dot" aria-hidden="true" />
              <span>{{ tip.label }}</span>
            </button>
          </div>
          <div class="guide-dots" aria-hidden="true">
            <i
              v-for="(tip, idx) in GUIDE_TIPS"
              :key="tip.id"
              :class="{ active: idx === guideIndex }"
              @click="guideIndex = idx"
            />
          </div>
        </div>
        <p v-if="pendingConfirm" class="hint">有待确认的记工卡片</p>
        <div class="composer-shell" :class="{ 'is-disabled': busy }">
          <textarea
            v-model="input"
            class="composer-input"
            rows="3"
            maxlength="1000"
            placeholder="例如：过去一小时在 ERP 项目处理了线上问题"
            :disabled="busy"
            @compositionstart="onCompositionStart"
            @compositionend="onCompositionEnd"
            @keydown="onComposerKeydown"
          />
          <div class="composer-bar">
            <span class="composer-tip">Enter 发送 · Shift+Enter 换行</span>
            <button
              type="button"
              class="composer-send"
              :disabled="busy || !input.trim()"
              :aria-busy="busy"
              @click="onSend"
            >
              <el-icon v-if="busy" class="is-loading" :size="16"><Loading /></el-icon>
              <template v-else>
                <span>发送</span>
                <el-icon :size="14"><Promotion /></el-icon>
              </template>
            </button>
          </div>
        </div>
      </div>
    </div>
  </el-drawer>
</template>

<style scoped>
.drawer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding-right: 8px;
}

.drawer-title {
  font-size: 16px;
  font-weight: 650;
  color: #18181b;
}

.drawer-sub {
  margin-top: 2px;
  font-size: 12px;
  color: #71717a;
}

.chat-shell {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  gap: 12px;
}

.chat-list {
  flex: 1;
  min-height: 0;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 4px 2px 12px;
}

.chat-row {
  display: flex;
}

.chat-row.is-user {
  justify-content: flex-end;
}

.chat-row.is-ai {
  justify-content: flex-start;
}

.bubble {
  max-width: 100%;
  padding: 10px 12px;
  border-radius: 16px;
}

.is-user .bubble {
  max-width: 88%;
  background: rgba(37, 99, 235, 0.12);
  border: 1px solid rgba(37, 99, 235, 0.18);
}

.is-ai .bubble {
  width: 100%;
  background: rgba(255, 255, 255, 0.42);
  border: 1px solid rgba(255, 255, 255, 0.68);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
}

.user-text {
  font-size: 14px;
  line-height: 1.55;
  color: #1e3a8a;
  white-space: pre-wrap;
  word-break: break-word;
}

.typing {
  font-size: 13px;
  color: #71717a;
}

.composer {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 8px;
}

.guide-carousel {
  position: relative;
  overflow: hidden;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.55);
  border: 1px solid rgba(24, 24, 27, 0.06);
}

.guide-track {
  display: flex;
  width: 100%;
  transition: transform 0.45s var(--kk-ease, cubic-bezier(0.25, 1, 0.5, 1));
}

.guide-chip {
  flex: 0 0 100%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  height: 34px;
  padding: 0 36px 0 14px;
  border: 0;
  background: transparent;
  cursor: pointer;
  color: #3f3f46;
  font-size: 12.5px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: color 0.15s ease, background 0.15s ease;
}

.guide-chip:hover:not(:disabled) {
  color: #0f766e;
  background: rgba(20, 184, 166, 0.08);
}

.guide-chip:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.guide-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
  background: linear-gradient(135deg, #22d3ee, #0d9488);
}

.guide-dots {
  position: absolute;
  right: 10px;
  top: 50%;
  transform: translateY(-50%);
  display: flex;
  gap: 4px;
  pointer-events: none;
}

.guide-dots i {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: rgba(24, 24, 27, 0.15);
  pointer-events: auto;
  cursor: pointer;
}

.guide-dots i.active {
  background: #0d9488;
  width: 10px;
  border-radius: 999px;
}

.hint {
  margin: 0;
  font-size: 12px;
  color: #d97706;
}

@media (prefers-reduced-motion: reduce) {
  .guide-track {
    transition: none;
  }
}

.composer-shell {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 12px 10px;
  border-radius: 18px;
  border: 1px solid rgba(24, 24, 27, 0.08);
  background: rgba(255, 255, 255, 0.82);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.9),
    0 1px 2px rgba(0, 0, 0, 0.03);
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.composer-shell:focus-within {
  border-color: rgba(6, 182, 212, 0.4);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.95),
    0 0 0 3px rgba(6, 182, 212, 0.1),
    0 4px 16px rgba(6, 182, 212, 0.08);
}

.composer-shell.is-disabled {
  opacity: 0.72;
}

.composer-input {
  width: 100%;
  min-height: 68px;
  max-height: 140px;
  margin: 0;
  padding: 0 2px;
  border: 0;
  resize: none;
  outline: none;
  background: transparent;
  color: #18181b;
  font: inherit;
  font-size: 14px;
  line-height: 1.55;
}

.composer-input::placeholder {
  color: #a1a1aa;
}

.composer-input:disabled {
  cursor: not-allowed;
}

.composer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.composer-tip {
  font-size: 11px;
  color: #a1a1aa;
  user-select: none;
}

.composer-send {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-width: 76px;
  height: 34px;
  padding: 0 14px;
  border: 0;
  border-radius: 999px;
  cursor: pointer;
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.02em;
  background: linear-gradient(135deg, #27272a 0%, #18181b 100%);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.12);
  transition:
    transform 0.15s ease,
    opacity 0.15s ease,
    background 0.2s ease,
    box-shadow 0.2s ease;
}

.composer-send:hover:not(:disabled) {
  background: linear-gradient(135deg, #0e7490 0%, #0f766e 100%);
  box-shadow: 0 4px 12px rgba(14, 116, 144, 0.28);
  transform: translateY(-1px);
}

.composer-send:active:not(:disabled) {
  transform: translateY(0);
}

.composer-send:disabled {
  opacity: 0.38;
  cursor: not-allowed;
  box-shadow: none;
}

.composer-send .is-loading {
  animation: composer-spin 0.9s linear infinite;
}

@keyframes composer-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>

<style>
.task-ai-drawer-overlay.el-overlay {
  background: rgba(24, 24, 27, 0.1) !important;
}

.task-ai-drawer.el-drawer {
  top: 16px !important;
  left: 16px !important;
  bottom: 16px !important;
  height: auto !important;
  width: min(420px, calc(100vw - 32px)) !important;
  max-height: calc(100vh - 32px);
  border-radius: var(--kk-radius-lg, 24px) !important;
  border: 1px solid var(--kk-glass-border, rgba(255, 255, 255, 0.72));
  background: var(--kk-glass-overlay-bg, rgba(255, 255, 255, 0.42)) !important;
  backdrop-filter: var(--kk-glass-overlay-blur, saturate(180%) blur(28px));
  -webkit-backdrop-filter: var(--kk-glass-overlay-blur, saturate(180%) blur(28px));
  box-shadow:
    0 1px 2px rgba(0, 0, 0, 0.04),
    0 18px 48px rgba(24, 24, 27, 0.14);
  overflow: hidden;
}

.task-ai-drawer .el-drawer__header {
  margin-bottom: 12px;
  padding: 18px 18px 0;
}

.task-ai-drawer .el-drawer__body {
  padding: 0 16px 16px;
  height: calc(100% - 64px);
  overflow: hidden;
}

.task-ai-drawer .el-drawer__close-btn {
  color: #52525b;
}

@media (max-width: 768px) {
  .task-ai-drawer.el-drawer {
    top: 12px !important;
    left: 12px !important;
    bottom: 12px !important;
    width: calc(100vw - 24px) !important;
    max-height: calc(100vh - 24px);
  }
}
</style>
