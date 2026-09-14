<script setup lang="ts">
import { computed, ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue?: number
    projects?: any[]
    companyId?: number
    /** filter：可选重大后不选小项目；pick：重大必须再选小项目，且不含常规/重大外壳作最终值 */
    mode?: 'filter' | 'pick'
    clearable?: boolean
    disabled?: boolean
    topWidth?: string
    childWidth?: string
    topPlaceholder?: string
    childPlaceholder?: string
  }>(),
  {
    projects: () => [],
    mode: 'filter',
    clearable: true,
    disabled: false,
    topWidth: '180px',
    childWidth: '180px',
    topPlaceholder: '全部项目',
    childPlaceholder: '全部小项目',
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: number | undefined]
  change: [value: number | undefined]
}>()

const topId = ref<number | undefined>()
const childId = ref<number | undefined>()

function isTop(p: any) {
  return p && (p.parentId == null || p.parentId === 0)
}

function isMajorShell(p: any) {
  return isTop(p) && String(p.scale || '').toUpperCase() === 'MAJOR'
}

const companyProjects = computed(() => {
  const list = props.projects || []
  if (props.companyId == null || props.companyId === undefined) return list
  return list.filter((p) => Number(p.companyId) === Number(props.companyId))
})

const topOptions = computed(() => {
  const tops = companyProjects.value.filter(isTop)
  if (props.mode === 'pick') {
    // 动账/配薪：重点 + 重大外壳（再选小项目）；不含常规
    return tops.filter((p) => {
      const scale = String(p.scale || '').toUpperCase()
      return scale === 'KEY' || scale === 'MAJOR'
    })
  }
  return tops
})

const selectedTop = computed(() =>
  companyProjects.value.find((p) => isTop(p) && Number(p.id) === Number(topId.value)),
)

const childOptions = computed(() => {
  if (!topId.value || !isMajorShell(selectedTop.value)) return []
  return companyProjects.value.filter((p) => Number(p.parentId) === Number(topId.value))
})

const showChild = computed(() => !!topId.value && isMajorShell(selectedTop.value))

function scaleTag(p: any) {
  const scale = String(p?.scale || '').toUpperCase()
  if (scale === 'MAJOR') return '重大'
  if (scale === 'KEY') return '重点'
  if (scale === 'NORMAL') return '常规'
  return ''
}

function emitValue(v: number | undefined) {
  emit('update:modelValue', v)
  emit('change', v)
}

function syncFromModel() {
  const id = props.modelValue
  if (id == null) {
    // 已选重大、尚未选/不必选小项目时保留顶层，避免 emit(undefined) 后被清空（pick 模式尤其）
    if (topId.value != null && isMajorShell(selectedTop.value) && childId.value == null) {
      return
    }
    topId.value = undefined
    childId.value = undefined
    return
  }
  const all = companyProjects.value.length ? companyProjects.value : props.projects || []
  const hit = all.find((p) => Number(p.id) === Number(id))
  if (!hit) {
    // 列表尚未加载完时先占位，待 projects 到达再解析
    topId.value = Number(id)
    childId.value = undefined
    return
  }
  if (hit.parentId) {
    topId.value = Number(hit.parentId)
    childId.value = Number(hit.id)
  } else {
    topId.value = Number(hit.id)
    childId.value = undefined
  }
}

watch(
  () => [props.modelValue, props.projects, props.companyId] as const,
  () => syncFromModel(),
  { immediate: true },
)

watch(
  () => props.companyId,
  (n, o) => {
    if (o === undefined) return
    if (Number(n) === Number(o)) return
    topId.value = undefined
    childId.value = undefined
    emitValue(undefined)
  },
)

function onTopChange(v: number | undefined) {
  topId.value = v
  childId.value = undefined
  const top = companyProjects.value.find((p) => Number(p.id) === Number(v))
  if (v == null) {
    emitValue(undefined)
    return
  }
  if (isMajorShell(top)) {
    // filter：重大本身可作条件；pick：等选小项目
    emitValue(props.mode === 'filter' ? v : undefined)
    return
  }
  emitValue(v)
}

function onChildChange(v: number | undefined) {
  if (v != null) {
    emitValue(v)
    return
  }
  // 清空小项目：filter 回退到重大；pick 清空最终值
  if (props.mode === 'filter' && topId.value != null && isMajorShell(selectedTop.value)) {
    emitValue(topId.value)
  } else {
    emitValue(undefined)
  }
}
</script>

<template>
  <div class="project-cascade">
    <el-select
      v-model="topId"
      :clearable="clearable"
      :disabled="disabled"
      filterable
      :placeholder="topPlaceholder"
      :style="{ width: topWidth }"
      @change="onTopChange"
    >
      <el-option
        v-for="p in topOptions"
        :key="p.id"
        :label="scaleTag(p) ? `${p.name}（${scaleTag(p)}）` : p.name"
        :value="p.id"
      />
    </el-select>
    <el-select
      v-if="showChild"
      v-model="childId"
      :clearable="clearable"
      :disabled="disabled"
      filterable
      :placeholder="mode === 'pick' ? '请选择小项目' : childPlaceholder"
      :style="{ width: childWidth }"
      @change="onChildChange"
    >
      <el-option v-for="p in childOptions" :key="p.id" :label="p.name" :value="p.id" />
    </el-select>
  </div>
</template>

<style scoped>
.project-cascade {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
</style>
