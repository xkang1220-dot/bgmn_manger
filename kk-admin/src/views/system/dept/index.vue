<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { sysApi } from '@/api/system'

const tree = ref<any[]>([])
const dialog = ref(false)
const isEdit = ref(false)
const form = reactive<any>({ parentId: 0, name: '', sort: 0, leader: '', phone: '', email: '', status: 1 })

async function load() {
  tree.value = await sysApi.deptTree()
}

function open(row?: any, parentId = 0) {
  isEdit.value = !!row
  Object.assign(form, row || { id: undefined, parentId, name: '', sort: 0, leader: '', phone: '', email: '', status: 1 })
  dialog.value = true
}

async function save() {
  await sysApi.saveDept(form, isEdit.value)
  ElMessage.success('保存成功')
  dialog.value = false
  await load()
}

async function remove(id: number) {
  await ElMessageBox.confirm('确认删除部门？')
  await sysApi.deleteDept(id)
  await load()
}

onMounted(load)
</script>

<template>
  <div class="page-card">
    <div class="page-header">
      <h3 class="page-title">部门管理</h3>
      <el-button type="primary" @click="open()">新建部门</el-button>
    </div>
    <el-table :data="tree" row-key="id" default-expand-all>
      <el-table-column prop="name" label="部门" />
      <el-table-column prop="leader" label="负责人" />
      <el-table-column prop="phone" label="电话" />
      <el-table-column prop="sort" label="排序" width="80" />
      <el-table-column label="操作" width="220">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(undefined, row.id)">新增下级</el-button>
          <el-button link type="primary" @click="open(row)">编辑</el-button>
          <el-button link type="danger" @click="remove(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-dialog v-model="dialog" :title="isEdit ? '编辑部门' : '新建部门'" width="480px">
      <el-form label-width="90px">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="负责人"><el-input v-model="form.leader" /></el-form-item>
        <el-form-item label="电话"><el-input v-model="form.phone" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="form.sort" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
