<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { sysApi } from '@/api/system'

const tree = ref<any[]>([])
const dialog = ref(false)
const isEdit = ref(false)
const form = reactive<any>({
  parentId: 0, name: '', type: 2, path: '', component: '', permission: '', icon: '', sort: 0, visible: 1, status: 1,
})

async function load() {
  tree.value = await sysApi.menuTree()
}

function open(row?: any, parentId = 0) {
  isEdit.value = !!row
  Object.assign(form, row || {
    id: undefined, parentId, name: '', type: 2, path: '', component: '', permission: '', icon: '', sort: 0, visible: 1, status: 1,
  })
  dialog.value = true
}

async function save() {
  await sysApi.saveMenu(form, isEdit.value)
  ElMessage.success('保存成功')
  dialog.value = false
  await load()
}

async function remove(id: number) {
  await ElMessageBox.confirm('确认删除菜单？')
  await sysApi.deleteMenu(id)
  await load()
}

onMounted(load)
</script>

<template>
  <div class="page-card">
    <div class="page-header">
      <h3 class="page-title">菜单管理</h3>
      <el-button type="primary" @click="open()">新建菜单</el-button>
    </div>
    <el-table :data="tree" row-key="id" default-expand-all>
      <el-table-column prop="name" label="名称" />
      <el-table-column prop="path" label="路径" />
      <el-table-column prop="permission" label="权限标识" />
      <el-table-column label="类型" width="90">
        <template #default="{ row }">{{ ({ 1: '目录', 2: '菜单', 3: '按钮' } as Record<number, string>)[row.type as number] }}</template>
      </el-table-column>
      <el-table-column label="操作" width="220">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(undefined, row.id)">新增下级</el-button>
          <el-button link type="primary" @click="open(row)">编辑</el-button>
          <el-button link type="danger" @click="remove(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-dialog v-model="dialog" :title="isEdit ? '编辑菜单' : '新建菜单'" width="560px">
      <el-form label-width="90px">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.type">
            <el-option :value="1" label="目录" />
            <el-option :value="2" label="菜单" />
            <el-option :value="3" label="按钮" />
          </el-select>
        </el-form-item>
        <el-form-item label="路径"><el-input v-model="form.path" /></el-form-item>
        <el-form-item label="组件"><el-input v-model="form.component" /></el-form-item>
        <el-form-item label="权限标识"><el-input v-model="form.permission" /></el-form-item>
        <el-form-item label="图标"><el-input v-model="form.icon" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="form.sort" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
