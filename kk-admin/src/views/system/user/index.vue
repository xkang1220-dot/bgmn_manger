<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { sysApi } from '@/api/system'

const query = reactive({ page: 1, pageSize: 10, username: '', nickname: '' })
const list = ref<any[]>([])
const total = ref(0)
const roles = ref<any[]>([])
const depts = ref<any[]>([])
const dialog = ref(false)
const isEdit = ref(false)
const form = reactive<any>({ username: '', nickname: '', password: '123456', phone: '', email: '', deptId: undefined, roleIds: [], status: 1, gender: 0 })

async function load() {
  const res = await sysApi.userPage(query)
  list.value = res.list
  total.value = res.total
}

function open(row?: any) {
  isEdit.value = !!row
  Object.assign(form, row || { id: undefined, username: '', nickname: '', password: '123456', phone: '', email: '', deptId: undefined, roleIds: [], status: 1, gender: 0 })
  dialog.value = true
}

async function save() {
  await sysApi.saveUser(form, isEdit.value)
  ElMessage.success('保存成功')
  dialog.value = false
  await load()
}

async function remove(id: number) {
  await ElMessageBox.confirm('确认删除账号？')
  await sysApi.deleteUser(id)
  await load()
}

async function reset(id: number) {
  await sysApi.resetPwd(id)
  ElMessage.success('已重置为 123456')
}

onMounted(async () => {
  roles.value = await sysApi.roleList()
  depts.value = await sysApi.deptTree()
  await load()
})
</script>

<template>
  <div class="page-card">
    <div class="page-header">
      <h3 class="page-title">账号管理</h3>
      <el-button type="primary" @click="open()">新建账号</el-button>
    </div>
    <div class="toolbar">
      <el-input v-model="query.username" placeholder="用户名" clearable style="width: 180px" />
      <el-button type="primary" @click="load">查询</el-button>
    </div>
    <el-table :data="list">
      <el-table-column prop="username" label="账号" />
      <el-table-column prop="nickname" label="昵称" />
      <el-table-column prop="deptName" label="部门" />
      <el-table-column prop="phone" label="手机" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">{{ row.status === 1 ? '启用' : '禁用' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="220">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row)">编辑</el-button>
          <el-button link @click="reset(row.id)">重置密码</el-button>
          <el-button link type="danger" @click="remove(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination style="margin-top: 12px" v-model:current-page="query.page" :total="total" layout="total, prev, pager, next" @current-change="load" />
    <el-dialog v-model="dialog" :title="isEdit ? '编辑账号' : '新建账号'" width="560px">
      <el-form label-width="90px">
        <el-form-item label="账号"><el-input v-model="form.username" :disabled="isEdit" /></el-form-item>
        <el-form-item v-if="!isEdit" label="密码"><el-input v-model="form.password" /></el-form-item>
        <el-form-item label="昵称"><el-input v-model="form.nickname" /></el-form-item>
        <el-form-item label="部门">
          <el-tree-select v-model="form.deptId" :data="depts" :props="{ label: 'name', value: 'id' }" check-strictly />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.roleIds" multiple>
            <el-option v-for="r in roles" :key="r.id" :label="r.name" :value="r.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="手机"><el-input v-model="form.phone" /></el-form-item>
        <el-form-item label="邮箱"><el-input v-model="form.email" /></el-form-item>
        <el-form-item label="状态"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
