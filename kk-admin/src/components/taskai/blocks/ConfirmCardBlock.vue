<script setup lang="ts">
import { reactive, watch } from 'vue'
import {
  TASK_STATUS_OPTIONS,
  type ConfirmTaskItem,
} from '@/api/taskAi'

const props = defineProps<{
  items: ConfirmTaskItem[]
  disabled?: boolean
}>()

const emit = defineEmits<{
  confirm: [items: ConfirmTaskItem[]]
  revise: []
  cancel: []
}>()

const draft = reactive<{ items: ConfirmTaskItem[] }>({ items: [] })
/** 用户改过起止时间的 tempId；未改动时优先展示后端 timeLabel，不擅自套默认规则 */
const timeEdited = reactive<Record<string, boolean>>({})

watch(
  () => props.items,
  (list) => {
    draft.items = (list || []).map((item) => ({
      ...item,
      content: item.content ?? '',
      status: item.status ?? 0,
      statusLabel:
        item.statusLabel ||
        TASK_STATUS_OPTIONS.find((s) => s.value === item.status)?.label ||
        '待办',
    }))
    for (const key of Object.keys(timeEdited)) delete timeEdited[key]
  },
  { immediate: true, deep: true },
)

function onStatusChange(row: ConfirmTaskItem) {
  row.statusLabel =
    TASK_STATUS_OPTIONS.find((s) => s.value === row.status)?.label || String(row.status)
}

function formatTimeLabel(startAt?: string, endAt?: string) {
  const start = (startAt || '').trim()
  const end = (endAt || '').trim()
  if (!start && !end) return '—'
  if (start && end) {
    const sParts = start.split(/\s+/)
    const eParts = end.split(/\s+/)
    const sameDay = sParts[0] && sParts[0] === eParts[0]
    if (sameDay && sParts[1] && eParts[1]) return `${sParts[1]} ~ ${eParts[1]}`
    return `${start} ~ ${end}`
  }
  return start || end
}

function displayTime(item: ConfirmTaskItem) {
  const key = item.tempId || item.title
  if (!timeEdited[key] && item.timeLabel?.trim()) return item.timeLabel.trim()
  return formatTimeLabel(item.startAt, item.endAt)
}

function onTimeBlur(item: ConfirmTaskItem) {
  const key = item.tempId || item.title
  timeEdited[key] = true
  const label = formatTimeLabel(item.startAt, item.endAt)
  item.timeLabel = label === '—' ? '' : label
  // 时长以后端 confirm 重算为准，避免前端猜错
  item.durationLabel = ''
}

function submit() {
  emit(
    'confirm',
    draft.items.map((item) => ({ ...item })),
  )
}
</script>

<template>
  <div class="confirm-card">
    <div class="confirm-head">
      <strong>确认登记</strong>
      <span>共 {{ draft.items.length }} 条，确认后才会入库</span>
    </div>

    <div v-for="(item, idx) in draft.items" :key="item.tempId || idx" class="confirm-item">
      <div class="confirm-item-top">
        <span class="idx">{{ idx + 1 }}</span>
        <el-input
          v-model="item.title"
          class="title-input"
          :disabled="disabled"
          maxlength="200"
          placeholder="事项标题"
        />
      </div>

      <div class="meta-grid">
        <div class="meta">
          <span class="meta-label">项目</span>
          <span class="meta-value">{{ item.projectName || '—' }}</span>
        </div>
        <div class="meta">
          <span class="meta-label">时间</span>
          <span class="meta-value">{{ displayTime(item) }}</span>
        </div>
        <div class="meta">
          <span class="meta-label">时长</span>
          <span class="meta-value">{{ item.durationLabel || '确认后按起止重算' }}</span>
        </div>
        <div class="meta">
          <span class="meta-label">状态</span>
          <el-select
            v-model="item.status"
            size="small"
            :disabled="disabled"
            style="width: 100%"
            @change="onStatusChange(item)"
          >
            <el-option
              v-for="opt in TASK_STATUS_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </div>
      </div>

      <div class="field-row">
        <label class="field">
          <span>开始</span>
          <el-input
            v-model="item.startAt"
            size="small"
            :disabled="disabled"
            placeholder="yyyy-MM-dd HH:mm:ss"
            @blur="onTimeBlur(item)"
          />
        </label>
        <label class="field">
          <span>结束</span>
          <el-input
            v-model="item.endAt"
            size="small"
            :disabled="disabled"
            placeholder="yyyy-MM-dd HH:mm:ss"
            @blur="onTimeBlur(item)"
          />
        </label>
      </div>

      <label class="field">
        <span>备注</span>
        <el-input
          v-model="item.content"
          :disabled="disabled"
          type="textarea"
          :rows="2"
          maxlength="500"
          placeholder="可选"
        />
      </label>
    </div>

    <div class="confirm-actions">
      <el-button link :disabled="disabled" @click="emit('cancel')">取消</el-button>
      <el-button :disabled="disabled" @click="emit('revise')">再改改</el-button>
      <el-button type="primary" :disabled="disabled || !draft.items.length" @click="submit">
        确认入库
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.confirm-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid rgba(24, 24, 27, 0.08);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03), 0 8px 20px rgba(0, 0, 0, 0.04);
}

.confirm-head {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.confirm-head strong {
  font-size: 14px;
  font-weight: 650;
  color: #18181b;
}

.confirm-head span {
  font-size: 12px;
  color: #71717a;
}

.confirm-item {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  border-radius: 14px;
  background: #fff;
  border: 1px solid rgba(24, 24, 27, 0.06);
}

.confirm-item-top {
  display: flex;
  align-items: center;
  gap: 8px;
}

.idx {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 650;
  color: #0f766e;
  background: rgba(20, 184, 166, 0.12);
}

.title-input :deep(.el-input__wrapper) {
  box-shadow: none;
  background: transparent;
  padding-left: 0;
}

.title-input :deep(.el-input__inner) {
  font-size: 15px;
  font-weight: 600;
  color: #18181b;
}

.meta-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  padding: 8px 10px;
  border-radius: 10px;
  background: #f4f4f5;
}

.meta-label {
  font-size: 11px;
  color: #71717a;
}

.meta-value {
  font-size: 13px;
  font-weight: 550;
  color: #27272a;
  word-break: break-all;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: #71717a;
}

.field-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.confirm-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
