<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { bizApi } from '@/api/biz'
import { sysApi } from '@/api/system'
import { ticketApi, TICKET_TYPE_OPTIONS, TICKET_URGENCY_OPTIONS } from '@/api/ticket'
import TicketRichEditor from '@/components/ticket/TicketRichEditor.vue'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  'update:modelValue': [boolean]
  success: []
}>()

const companies = ref<any[]>([])
const allProjects = ref<any[]>([])
const saving = ref(false)
const form = reactive({
  companyId: undefined as number | undefined,
  projectId: undefined as number | undefined,
  title: '',
  type: 'bug',
  urgency: 'normal',
  description: '',
})

const projects = computed(() => {
  if (!form.companyId) return []
  return allProjects.value.filter((p) => Number(p.companyId) === Number(form.companyId))
})

watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    form.projectId = undefined
    form.title = ''
    form.type = 'bug'
    form.urgency = 'normal'
    form.description = ''
    if (!companies.value.length) {
      companies.value = await sysApi.myCompanies()
    }
    if (!allProjects.value.length) {
      allProjects.value = await bizApi.projectList().catch(() => [])
    }
    if (!form.companyId && companies.value.length === 1) {
      form.companyId = companies.value[0].id
    }
  },
)

watch(
  () => form.companyId,
  () => {
    form.projectId = undefined
  },
)

function close() {
  emit('update:modelValue', false)
}

async function submit() {
  if (!form.companyId) {
    ElMessage.warning('请选择所属公司')
    return
  }
  if (!form.title.trim() || form.title.trim().length < 2) {
    ElMessage.warning('请填写标题（至少 2 字）')
    return
  }
  if (!form.description) {
    ElMessage.warning('请填写描述')
    return
  }
  const plain = form.description.replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').trim()
  if (!plain && !form.description.toLowerCase().includes('<img')) {
    ElMessage.warning('请填写描述')
    return
  }
  saving.value = true
  try {
    await ticketApi.create({
      companyId: form.companyId,
      projectId: form.projectId,
      title: form.title.trim(),
      type: form.type,
      urgency: form.urgency,
      description: form.description,
    })
    ElMessage.success('工单已提交')
    emit('success')
    close()
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  companies.value = await sysApi.myCompanies()
  allProjects.value = await bizApi.projectList().catch(() => [])
})
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="提交工单"
    width="640px"
    destroy-on-close
    @close="close"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form label-width="88px">
      <el-form-item label="所属公司" required>
        <el-select v-model="form.companyId" placeholder="选择公司" style="width: 100%">
          <el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="关联项目">
        <el-select
          v-model="form.projectId"
          clearable
          filterable
          placeholder="可选，关联到项目"
          style="width: 100%"
          :disabled="!form.companyId"
        >
          <el-option
            v-for="p in projects"
            :key="p.id"
            :label="p.code ? `${p.name}（${p.code}）` : p.name"
            :value="p.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="标题" required>
        <el-input v-model="form.title" maxlength="120" show-word-limit placeholder="简要描述问题" />
      </el-form-item>
      <el-form-item label="类型" required>
        <el-radio-group v-model="form.type">
          <el-radio-button v-for="o in TICKET_TYPE_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="紧急程度" required>
        <el-radio-group v-model="form.urgency">
          <el-radio-button v-for="o in TICKET_URGENCY_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="描述" required>
        <TicketRichEditor v-model="form.description" placeholder="复现步骤、期望结果等" :min-height="160" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">提交</el-button>
    </template>
  </el-dialog>
</template>
