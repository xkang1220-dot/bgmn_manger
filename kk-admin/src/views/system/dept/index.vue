<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { sysApi } from '@/api/system'

const tree = ref<any[]>([])
const dialog = ref(false)
const isEdit = ref(false)
const saving = ref(false)
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
  if (!form.name?.trim()) {
    ElMessage.warning('请填写部门名称')
    return
  }
  saving.value = true
  try {
    await sysApi.saveDept(form, isEdit.value)
    ElMessage.success('保存成功')
    dialog.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(id: number) {
  await ElMessageBox.confirm('确认删除部门？')
  await sysApi.deleteDept(id)
  await load()
}

onMounted(load)
</script>

<template>
  <div class="page-stack">
    <div class="page-top">
      <div class="page-top__main">
        <p class="page-desc">维护组织层级；可在部门下新增下级</p>
      </div>
      <div class="page-actions">
        <el-button type="primary" @click="open()">新建部门</el-button>
      </div>
    </div>

    <div class="page-card">
      <el-table :data="tree" row-key="id" default-expand-all empty-text="暂无部门">
        <el-table-column prop="name" label="部门" min-width="180" />
        <el-table-column prop="leader" label="负责人" width="140">
          <template #default="{ row }">{{ row.leader || '—' }}</template>
        </el-table-column>
        <el-table-column prop="phone" label="电话" width="140">
          <template #default="{ row }">{{ row.phone || '—' }}</template>
        </el-table-column>
        <el-table-column prop="sort" label="排序" width="80" align="right" />
        <el-table-column label="操作" width="140" fixed="right" align="center">
          <template #default="{ row }">
            <div class="icon-ops">
              <el-button text aria-label="新增下级" title="新增下级" @click="open(undefined, row.id)">
                <el-icon :size="16"><Plus /></el-icon>
              </el-button>
              <el-button text aria-label="编辑" title="编辑" @click="open(row)">
                <el-icon :size="16"><EditPen /></el-icon>
              </el-button>
              <el-button text class="is-danger" aria-label="删除" title="删除" @click="remove(row.id)">
                <el-icon :size="16"><Delete /></el-icon>
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog
      v-model="dialog"
      :title="isEdit ? '编辑部门' : form.parentId ? '新增下级部门' : '新建部门'"
      width="480px"
      :close-on-click-modal="false"
    >
      <el-form label-width="90px">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="负责人"><el-input v-model="form.leader" /></el-form-item>
        <el-form-item label="电话"><el-input v-model="form.phone" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="form.sort" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
