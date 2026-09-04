<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { sysApi } from '@/api/system'

const tree = ref<any[]>([])
const dialog = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const form = reactive<any>({
  parentId: 0, name: '', type: 2, path: '', component: '', permission: '', icon: '', sort: 0, visible: 1, status: 1,
})

const TYPE_LABEL: Record<number, string> = { 1: '目录', 2: '菜单', 3: '按钮' }
const TYPE_TAG: Record<number, string> = { 1: 'info', 2: '', 3: 'warning' }

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
  if (!form.name?.trim()) {
    ElMessage.warning('请填写菜单名称')
    return
  }
  saving.value = true
  try {
    await sysApi.saveMenu(form, isEdit.value)
    ElMessage.success('保存成功')
    dialog.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(id: number) {
  await ElMessageBox.confirm('确认删除菜单？')
  await sysApi.deleteMenu(id)
  await load()
}

onMounted(load)
</script>

<template>
  <div class="page-stack">
    <div class="page-top">
      <div class="page-top__main">
        <p class="page-desc">侧栏菜单与按钮权限；可在节点下新增下级</p>
      </div>
      <div class="page-actions">
        <el-button type="primary" @click="open()">新建菜单</el-button>
      </div>
    </div>

    <div class="page-card">
      <el-table :data="tree" row-key="id" default-expand-all empty-text="暂无菜单">
        <el-table-column prop="name" label="名称" min-width="160" />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag :type="(TYPE_TAG[row.type] as '' | 'info' | 'warning') || undefined" size="small">
              {{ TYPE_LABEL[row.type] || '—' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="path" label="路径" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.path || '—' }}</template>
        </el-table-column>
        <el-table-column prop="permission" label="权限标识" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.permission || '—' }}</template>
        </el-table-column>
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
      :title="isEdit ? '编辑菜单' : form.parentId ? '新增下级菜单' : '新建菜单'"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-form label-width="90px">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.type" style="width: 100%">
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
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
