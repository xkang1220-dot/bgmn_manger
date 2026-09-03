<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { sysApi } from '@/api/system'

const list = ref<any[]>([])
const menus = ref<any[]>([])
const depts = ref<any[]>([])
const dialog = ref(false)
const isEdit = ref(false)
const form = reactive<any>({ name: '', code: '', sort: 0, status: 1, dataScope: 1, remark: '', menuIds: [], deptIds: [] })

async function load() {
  list.value = await sysApi.roleList()
}

function collectIds(nodes: any[], acc: number[] = []) {
  for (const n of nodes || []) {
    acc.push(n.id)
    if (n.children) collectIds(n.children, acc)
  }
  return acc
}

async function open(row?: any) {
  isEdit.value = !!row
  if (row) {
    const detail = await sysApi.roleDetail(row.id)
    Object.assign(form, detail)
  } else {
    Object.assign(form, { id: undefined, name: '', code: '', sort: 0, status: 1, dataScope: 1, remark: '', menuIds: [], deptIds: [] })
  }
  dialog.value = true
}

async function save() {
  await sysApi.saveRole(form, isEdit.value)
  ElMessage.success('保存成功')
  dialog.value = false
  await load()
}

async function remove(id: number) {
  await ElMessageBox.confirm('确认删除角色？')
  await sysApi.deleteRole(id)
  await load()
}

onMounted(async () => {
  menus.value = await sysApi.menuTree()
  depts.value = await sysApi.deptTree()
  await load()
})
</script>

<template>
  <div class="page-card">
    <div class="page-header">
      <h3 class="page-title">角色权限</h3>
      <el-button type="primary" @click="open()">新建角色</el-button>
    </div>
    <el-table :data="list">
      <el-table-column prop="name" label="角色" />
      <el-table-column prop="code" label="编码" />
      <el-table-column label="数据范围">
        <template #default="{ row }">
          {{ ({ 1: '全部', 2: '自定义部门', 3: '本部门', 4: '本部门及以下', 5: '仅本人' } as Record<number, string>)[row.dataScope as number] }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row)">编辑</el-button>
          <el-button link type="danger" @click="remove(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-dialog v-model="dialog" :title="isEdit ? '编辑角色' : '新建角色'" width="640px">
      <el-form label-width="100px">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="编码"><el-input v-model="form.code" /></el-form-item>
        <el-form-item label="数据范围">
          <el-select v-model="form.dataScope">
            <el-option :value="1" label="全部" />
            <el-option :value="2" label="自定义部门" />
            <el-option :value="3" label="本部门" />
            <el-option :value="4" label="本部门及以下" />
            <el-option :value="5" label="仅本人" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.dataScope === 2" label="自定义部门">
          <el-tree-select v-model="form.deptIds" :data="depts" :props="{ label: 'name', value: 'id' }" multiple show-checkbox check-strictly />
        </el-form-item>
        <el-form-item label="菜单权限">
          <el-tree
            :data="menus"
            show-checkbox
            node-key="id"
            :props="{ label: 'name' }"
            :default-checked-keys="form.menuIds"
            @check="(_: any, info: any) => (form.menuIds = [...info.checkedKeys, ...info.halfCheckedKeys])"
          />
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
