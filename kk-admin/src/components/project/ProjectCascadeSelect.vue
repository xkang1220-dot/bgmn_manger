<script setup lang="ts">
import { computed, ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue?: number
    projects?: any[]
    companyId?: number
    /**
     * filter：筛选用，可选重大外壳本身
     * pick：动账/配薪，重点+重大（必选小项目），不含常规
     * task：建任务，常规+重点+重大（重大必选小项目），默认仅进行中
     */
    mode?: 'filter' | 'pick' | 'task'
    /** 隐藏已完成/已关闭项目（status=2/3）；已选中的仍保留便于回显 */
    excludeCompleted?: boolean
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
    excludeCompleted: false,
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

function selectedKeepTopId() {
  const selectedId = props.modelValue == null ? null : Number(props.modelValue)
  if (selectedId == null) return null
  const selected = companyProjects.value.find((p) => Number(p.id) === selectedId)
  if (!selected) return null
  return Number(isTop(selected) ? selected.id : selected.parentId)
}

const topOptions = computed(() => {
  const tops = companyProjects.value.filter(isTop)
  if (props.mode === 'pick') {
    // 动账/配薪：重点 + 重大外壳（再选小项目）；不含常规
    return tops.filter((p) => {
      const scale = String(p.scale || '').toUpperCase()
      return scale === 'KEY' || scale === 'MAJOR'
    })
  }
  if (props.mode === 'task') {
    // 建任务：与项目管理「进行中」一致，含常规；已选中的项目始终保留便于回显
    const keepTopId = selectedKeepTopId()
    return tops.filter((p) => {
      if (keepTopId != null && Number(p.id) === keepTopId) return true
      return Number(p.status) === 1
    })
  }
  // filter：默认全量；excludeCompleted 时隐藏已完成/已关闭，已选中的始终保留
  if (props.excludeCompleted) {
    const keepTopId = selectedKeepTopId()
    return tops.filter((p) => {
      if (keepTopId != null && Number(p.id) === keepTopId) return true
      const status = Number(p.status)
      return status !== 2 && status !== 3
    })
  }
  return tops
})

const selectedTop = computed(() =>
  companyProjects.value.find((p) => isTop(p) && Number(p.id) === Number(topId.value)),
)

const childOptions = computed(() => {
  if (!topId.value || !isMajorShell(selectedTop.value)) return []
  const children = companyProjects.value.filter((p) => Number(p.parentId) === Number(topId.value))
  const selectedId = props.modelValue == null ? null : Number(props.modelValue)
  if (props.mode === 'task') {
    // 重大已是进行中时，小项目仍可选（筹备/进行中）；已关闭排除
    return children.filter((p) => {
      if (selectedId != null && Number(p.id) === selectedId) return true
      return Number(p.status) !== 3
    })
  }
  if (props.excludeCompleted) {
    return children.filter((p) => {
      if (selectedId != null && Number(p.id) === selectedId) return true
      const status = Number(p.status)
      return status !== 2 && status !== 3
    })
  }
  return children
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
    // filter：重大本身可作条件；pick/task：等选小项目
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
  // 清空小项目：filter 回退到重大；pick/task 清空最终值
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
        :value="Number(p.id)"
      />
    </el-select>
    <el-select
      v-if="showChild"
      v-model="childId"
      :clearable="clearable"
      :disabled="disabled"
      filterable
      :placeholder="mode === 'pick' || mode === 'task' ? '请选择小项目' : childPlaceholder"
      :style="{ width: childWidth }"
      @change="onChildChange"
    >
      <el-option v-for="p in childOptions" :key="p.id" :label="p.name" :value="Number(p.id)" />
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
