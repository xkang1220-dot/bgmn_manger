<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { companyTaskShareApi, type CompanyTaskShareOption, type CompanyTaskShareState } from '@/api/companyTaskShare'

const open = defineModel<boolean>({ default: false })
const props = defineProps<{ companies: CompanyTaskShareOption[] }>()

const companyId = ref<number>()
const members = ref<CompanyTaskShareOption[]>([])
const selectedUserIds = ref<number[]>([])
const range = ref<[string, string]>([today(), today()])
const state = ref<CompanyTaskShareState | null>(null)
const loading = ref(false)
const acting = ref(false)
let requestSeq = 0

const fullUrl = computed(() => {
  if (!state.value?.active || !state.value.path) return ''
  return `${window.location.origin}${state.value.path}`
})

const peopleChanged = computed(() => {
  const saved = (state.value?.userIds || []).map((id) => Number(id)).filter(Number.isFinite).sort((a, b) => a - b).join(',')
  const current = selectedUserIds.value.map((id) => Number(id)).filter(Number.isFinite).sort((a, b) => a - b).join(',')
  return saved !== current
})

const datesChanged = computed(() => {
  const currentFrom = normalizeDay(range.value?.[0])
  const currentTo = normalizeDay(range.value?.[1])
  if (!currentFrom || !currentTo) return true
  const savedFrom = normalizeDay(state.value?.dateFrom)
  const savedTo = normalizeDay(state.value?.dateTo)
  // 旧链接还没存过日期时，按当天展示会每天变化，必须能点保存把它固定住
  if (!savedFrom || !savedTo) return Boolean(state.value?.active)
  return savedFrom !== currentFrom || savedTo !== currentTo
})

const settingsChanged = computed(() => peopleChanged.value || datesChanged.value)

watch(open, (visible) => {
  if (!visible) return
  if (!companyId.value && props.companies.length) {
    companyId.value = props.companies[0].id
    return
  }
  void load()
})

watch(companyId, () => {
  if (open.value) void load()
})

async function load() {
  const seq = ++requestSeq
  const id = companyId.value
  state.value = null
  members.value = []
  selectedUserIds.value = []
  range.value = [today(), today()]
  if (!id) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    const [next, people] = await Promise.all([
      companyTaskShareApi.state(id),
      companyTaskShareApi.members(id),
    ])
    if (seq !== requestSeq) return
    state.value = next
    members.value = people
    const allowed = new Set(people.map((item) => Number(item.id)))
    selectedUserIds.value = (next.userIds || [])
      .map((userId) => Number(userId))
      .filter((userId) => Number.isFinite(userId) && allowed.has(userId))
    applyRange(next.dateFrom, next.dateTo)
  } catch {
    if (seq !== requestSeq) return
    state.value = null
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

function today() {
  const date = new Date()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

function normalizeDay(value: unknown) {
  if (Array.isArray(value) && value.length >= 3) {
    const year = Number(value[0])
    const month = Number(value[1])
    const day = Number(value[2])
    if (!year || !month || !day) return ''
    return `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`
  }
  const matched = /^(\d{4})-(\d{2})-(\d{2})/.exec(value == null ? '' : String(value).trim())
  return matched ? `${matched[1]}-${matched[2]}-${matched[3]}` : ''
}

function applyRange(from: unknown, to: unknown) {
  const start = normalizeDay(from) || today()
  const end = normalizeDay(to) || start
  range.value = start <= end ? [start, end] : [end, start]
}

function spanDays(from: string, to: string) {
  const start = Date.parse(`${from}T00:00:00`)
  const end = Date.parse(`${to}T00:00:00`)
  if (Number.isNaN(start) || Number.isNaN(end)) return 0
  return Math.round((end - start) / 86400000)
}

function chosenIds() {
  if (!selectedUserIds.value.length) {
    ElMessage.warning('请选择人员')
    return null
  }
  return [...selectedUserIds.value]
}

function chosenRange() {
  const dates = range.value
  const from = dates?.[0]
  const to = dates?.[1]
  if (!from || !to || !/^\d{4}-\d{2}-\d{2}$/.test(from) || !/^\d{4}-\d{2}-\d{2}$/.test(to)) {
    ElMessage.warning('请选择日期')
    return null
  }
  if (from > to) {
    ElMessage.warning('开始日期不能晚于结束日期')
    return null
  }
  if (spanDays(from, to) > 61) {
    ElMessage.warning('日期范围不能超过 62 天')
    return null
  }
  return [from, to] as const
}

async function generate() {
  if (!companyId.value || !chosenIds() || !chosenRange()) return
  if (state.value?.active) {
    try {
      await ElMessageBox.confirm('重新生成后，旧链接会立即失效。确定继续？', '重新生成外链', {
        type: 'warning',
        confirmButtonText: '重新生成',
        cancelButtonText: '取消',
      })
    } catch {
      return
    }
  }
  const userIds = chosenIds()
  const dates = chosenRange()
  if (!companyId.value || !userIds || !dates) return
  const id = companyId.value
  const seq = ++requestSeq
  acting.value = true
  try {
    const next = await companyTaskShareApi.generate(id, userIds, dates[0], dates[1])
    if (seq !== requestSeq || companyId.value !== id) return
    state.value = next
    applyRange(next?.dateFrom, next?.dateTo)
    ElMessage.success(next?.active ? '链接已生成' : '生成失败')
  } finally {
    acting.value = false
    if (seq === requestSeq) loading.value = false
  }
}

async function saveSettings() {
  const userIds = chosenIds()
  const dates = chosenRange()
  if (!companyId.value || !userIds || !dates || !state.value?.active) return
  const id = companyId.value
  const seq = ++requestSeq
  acting.value = true
  try {
    const next = await companyTaskShareApi.updateUsers(id, userIds, dates[0], dates[1])
    if (seq !== requestSeq || companyId.value !== id) return
    state.value = next
    applyRange(next?.dateFrom, next?.dateTo)
    ElMessage.success('已保存，链接不用重发')
  } finally {
    acting.value = false
    if (seq === requestSeq) loading.value = false
  }
}

async function revoke() {
  const id = companyId.value
  if (!id || !state.value?.active) return
  try {
    await ElMessageBox.confirm('作废后，这条链接将无法打开。确定继续？', '作废外链', {
      type: 'warning',
      confirmButtonText: '作废',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  if (companyId.value !== id) return
  acting.value = true
  try {
    await companyTaskShareApi.revoke(id)
    if (companyId.value !== id) return
    ElMessage.success('已作废')
    await load()
  } finally {
    acting.value = false
  }
}

async function copy() {
  if (!fullUrl.value) return
  try {
    await navigator.clipboard.writeText(fullUrl.value)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选择链接')
  }
}
</script>

<template>
  <el-dialog v-model="open" title="今日工作外链" width="560px" append-to-body>
    <p class="share-tip">先选人员和日期。老板打开后只能看这段时间里这些人，不能改日期。日期还没保存，或改了人员和日期，要先点保存再复制。保存不会换链接；重新生成后旧链接失效。</p>
    <el-form class="share-form" label-width="72px" @submit.prevent>
      <el-form-item label="公司">
        <el-select v-model="companyId" placeholder="选择公司" style="width: 100%">
          <el-option v-for="item in companies" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="人员">
        <el-select
          v-model="selectedUserIds"
          multiple
          filterable
          collapse-tags
          collapse-tags-tooltip
          placeholder="选择要展示的人"
          style="width: 100%"
          :loading="loading"
        >
          <el-option v-for="item in members" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="日期">
        <el-date-picker
          v-model="range"
          type="daterange"
          value-format="YYYY-MM-DD"
          range-separator="至"
          start-placeholder="开始"
          end-placeholder="结束"
          :clearable="false"
          style="width: 100%"
        />
      </el-form-item>
      <el-form-item label="链接">
        <el-input :model-value="fullUrl" readonly placeholder="还没有链接" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button v-if="state?.active" :disabled="acting || loading" @click="revoke">作废</el-button>
      <el-button :disabled="!fullUrl || loading || acting || settingsChanged" @click="copy">复制</el-button>
      <el-button
        v-if="state?.active"
        :type="settingsChanged ? 'primary' : 'default'"
        :disabled="acting || loading || !settingsChanged || !selectedUserIds.length"
        @click="saveSettings"
      >保存</el-button>
      <el-button
        :type="state?.active && settingsChanged ? 'default' : 'primary'"
        :loading="acting"
        :disabled="!companyId || loading || !selectedUserIds.length"
        @click="generate"
      >
        {{ state?.active ? '重新生成' : '生成链接' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.share-tip {
  margin: 0 0 16px;
  color: var(--kk-text-secondary);
  line-height: 1.6;
  font-size: 13px;
}

.share-form :deep(.el-date-editor) {
  --el-date-editor-width: 100%;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  box-sizing: border-box;
}
</style>
