<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bizApi } from '@/api/biz'
import { sysApi } from '@/api/system'

const query = reactive({ page: 1, pageSize: 10, realName: '', employeeNo: '' })
const list = ref<any[]>([])
const total = ref(0)
const users = ref<any[]>([])
const dialog = ref(false)
const detailDrawer = ref(false)
const isEdit = ref(false)
const detail = ref<any>(null)
const form = reactive<any>({
  userId: undefined,
  realName: '',
  employeeNo: '',
  position: '',
  education: '',
  entryDate: '',
  idCard: '',
  address: '',
  emergencyContact: '',
  emergencyPhone: '',
  remark: '',
})

function emptyForm() {
  return {
    id: undefined,
    userId: undefined,
    realName: '',
    employeeNo: '',
    position: '',
    education: '',
    entryDate: '',
    idCard: '',
    address: '',
    emergencyContact: '',
    emergencyPhone: '',
    remark: '',
  }
}

async function load() {
  const res = await bizApi.archivePage(query)
  list.value = res.list
  total.value = res.total
}

function open(row?: any) {
  isEdit.value = !!row
  Object.assign(form, row ? { ...row } : emptyForm())
  dialog.value = true
}

async function showDetail(row: any) {
  detail.value = await bizApi.archiveDetail(row.id)
  detailDrawer.value = true
}

async function save() {
  if (!form.userId) {
    ElMessage.warning('请绑定系统账号')
    return
  }
  if (!form.realName?.trim()) {
    ElMessage.warning('请填写姓名')
    return
  }
  await bizApi.saveArchive(form, isEdit.value)
  ElMessage.success('保存成功')
  dialog.value = false
  await load()
}

async function remove(id: number) {
  await ElMessageBox.confirm('确认删除档案？')
  await bizApi.deleteArchive(id)
  ElMessage.success('已删除')
  await load()
}

onMounted(async () => {
  users.value = await sysApi.userList()
  await load()
})
</script>

<template>
  <div class="page-card">
    <div class="page-header">
      <h3 class="page-title">人员档案</h3>
      <el-button type="primary" @click="open()">新建档案</el-button>
    </div>
    <div class="toolbar">
      <el-input v-model="query.realName" placeholder="姓名" clearable style="width: 180px" @keyup.enter="load" />
      <el-button type="primary" @click="load">查询</el-button>
    </div>
    <el-table :data="list">
      <el-table-column label="姓名" min-width="120">
        <template #default="{ row }">
          <el-link type="primary" :underline="false" @click="showDetail(row)">{{ row.realName }}</el-link>
        </template>
      </el-table-column>
      <el-table-column prop="employeeNo" label="工号" width="120" />
      <el-table-column prop="username" label="账号" width="120" />
      <el-table-column prop="deptName" label="部门" width="120" />
      <el-table-column prop="position" label="岗位" width="120" />
      <el-table-column prop="entryDate" label="入职" width="120" />
      <el-table-column prop="phone" label="电话" width="130" />
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row)">编辑</el-button>
          <el-button link type="danger" @click="remove(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      style="margin-top: 12px"
      v-model:current-page="query.page"
      :total="total"
      layout="total, prev, pager, next"
      @current-change="load"
    />

    <el-dialog v-model="dialog" :title="isEdit ? '编辑档案' : '新建档案'" width="640px">
      <el-form label-width="100px">
        <el-form-item label="绑定账号" required>
          <el-select v-model="form.userId" filterable :disabled="isEdit" style="width: 100%">
            <el-option v-for="u in users" :key="u.id" :label="`${u.nickname || u.username}`" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="姓名" required><el-input v-model="form.realName" /></el-form-item>
        <el-form-item label="工号"><el-input v-model="form.employeeNo" /></el-form-item>
        <el-form-item label="岗位"><el-input v-model="form.position" /></el-form-item>
        <el-form-item label="学历"><el-input v-model="form.education" /></el-form-item>
        <el-form-item label="入职日期"><el-date-picker v-model="form.entryDate" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item>
        <el-form-item label="身份证"><el-input v-model="form.idCard" /></el-form-item>
        <el-form-item label="地址"><el-input v-model="form.address" /></el-form-item>
        <el-form-item label="紧急联系人"><el-input v-model="form.emergencyContact" /></el-form-item>
        <el-form-item label="紧急电话"><el-input v-model="form.emergencyPhone" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailDrawer" title="档案详情" size="420px">
      <template v-if="detail">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="姓名">{{ detail.realName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="工号">{{ detail.employeeNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="账号">{{ detail.username || '—' }}</el-descriptions-item>
          <el-descriptions-item label="部门">{{ detail.deptName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="岗位">{{ detail.position || '—' }}</el-descriptions-item>
          <el-descriptions-item label="学历">{{ detail.education || '—' }}</el-descriptions-item>
          <el-descriptions-item label="入职日期">{{ detail.entryDate || '—' }}</el-descriptions-item>
          <el-descriptions-item label="电话">{{ detail.phone || '—' }}</el-descriptions-item>
          <el-descriptions-item label="身份证">{{ detail.idCard || '—' }}</el-descriptions-item>
          <el-descriptions-item label="地址">{{ detail.address || '—' }}</el-descriptions-item>
          <el-descriptions-item label="紧急联系人">{{ detail.emergencyContact || '—' }}</el-descriptions-item>
          <el-descriptions-item label="紧急电话">{{ detail.emergencyPhone || '—' }}</el-descriptions-item>
          <el-descriptions-item label="备注">{{ detail.remark || '—' }}</el-descriptions-item>
        </el-descriptions>
        <div style="margin-top: 16px; text-align: right">
          <el-button type="primary" @click="open(detail); detailDrawer = false">编辑档案</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>
