<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bizApi } from '@/api/biz'
import { sysApi } from '@/api/system'

const query = reactive({ page: 1, pageSize: 12, companyId: undefined as number | undefined, name: '' })
const list = ref<any[]>([])
const total = ref(0)
const companies = ref<any[]>([])
const dialog = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const form = reactive<any>({
  id: undefined,
  companyId: undefined,
  name: '',
  months: 36,
  residualRate: 0.05,
  status: 1,
  remark: '',
})

function fmtRate(v?: number) {
  return `${(Number(v || 0) * 100).toFixed(2)}%`
}

async function loadCompanies() {
  companies.value = await sysApi.myCompanies()
}

async function load() {
  const res = await bizApi.faCategoryPage({ ...query })
  list.value = res.list || []
  total.value = res.total || 0
}

function open(row?: any) {
  isEdit.value = !!row
  Object.assign(form, row || {
    id: undefined,
    companyId: companies.value[0]?.id,
    name: '',
    months: 36,
    residualRate: 0.05,
    status: 1,
    remark: '',
  })
  if (row && row.residualRate != null) {
    form.residualRatePct = Number(row.residualRate) * 100
  } else {
    form.residualRatePct = 5
  }
  dialog.value = true
}

async function save() {
  if (!form.companyId) {
    ElMessage.warning('请选择公司')
    return
  }
  if (!form.name?.trim()) {
    ElMessage.warning('请填写类别名称')
    return
  }
  if (!form.months || form.months < 1) {
    ElMessage.warning('折旧月数至少为 1')
    return
  }
  saving.value = true
  try {
    const payload = {
      ...form,
      residualRate: Number(form.residualRatePct || 0) / 100,
    }
    await bizApi.saveFaCategory(payload, isEdit.value)
    ElMessage.success('已保存')
    dialog.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(row: any) {
  await ElMessageBox.confirm(`删除折旧类别「${row.name}」？`, '确认', { type: 'warning' })
  await bizApi.deleteFaCategory(row.id)
  ElMessage.success('已删除')
  await load()
}

onMounted(async () => {
  await loadCompanies()
  await load()
})
</script>

<template>
  <div class="page-stack">
    <div class="page-card">
      <el-form class="filter-bar" @submit.prevent="query.page = 1; load()">
        <el-form-item label="公司">
          <el-select v-model="query.companyId" clearable placeholder="全部" style="width: 180px">
            <el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="query.name" clearable placeholder="类别名" style="width: 160px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" native-type="submit">查询</el-button>
          <el-button v-permission="'fa:category:add'" type="primary" plain @click="open()">新建类别</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="list" row-key="id">
        <el-table-column prop="companyName" label="公司" min-width="120" />
        <el-table-column prop="name" label="类别" min-width="140" />
        <el-table-column prop="months" label="折旧月数" width="110" align="right" />
        <el-table-column label="残值率" width="100" align="right">
          <template #default="{ row }">{{ fmtRate(row.residualRate) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'fa:category:edit'" link type="primary" @click="open(row)">编辑</el-button>
            <el-button v-permission="'fa:category:remove'" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="total > query.pageSize" class="page-footer">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.pageSize"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="load"
        />
      </div>
    </div>

    <el-dialog v-model="dialog" :title="isEdit ? '编辑折旧类别' : '新建折旧类别'" width="480px">
      <el-form label-width="100px">
        <el-form-item label="公司" required>
          <el-select v-model="form.companyId" :disabled="isEdit" style="width: 100%">
            <el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="64" />
        </el-form-item>
        <el-form-item label="折旧月数" required>
          <el-input-number v-model="form.months" :min="1" :max="600" style="width: 100%" />
        </el-form-item>
        <el-form-item label="残值率%" required>
          <el-input-number v-model="form.residualRatePct" :min="0" :max="100" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-stack { display: flex; flex-direction: column; gap: 16px; }
.page-footer { display: flex; justify-content: flex-end; margin-top: 16px; }
.filter-bar { display: flex; flex-wrap: wrap; gap: 8px 12px; margin-bottom: 12px; }
</style>
