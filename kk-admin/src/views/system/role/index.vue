<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { sysApi } from '@/api/system'

const list = ref<any[]>([])
const menus = ref<any[]>([])
const depts = ref<any[]>([])
const dialog = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const form = reactive<any>({ name: '', code: '', sort: 0, status: 1, dataScope: 1, remark: '', menuIds: [], deptIds: [] })

const SCOPE: Record<number, string> = {
  1: '全部',
  2: '自定义部门',
  3: '本部门',
  4: '本部门及以下',
  5: '仅本人',
}

async function load() {
  list.value = await sysApi.roleList()
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
  if (!form.name?.trim()) {
    ElMessage.warning('请填写角色名称')
    return
  }
  saving.value = true
  try {
    await sysApi.saveRole(form, isEdit.value)
    ElMessage.success('保存成功')
    dialog.value = false
    await load()
  } finally {
    saving.value = false
  }
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
  <div class="page-stack">
    <div class="page-top">
      <div class="page-top__main">
        <p class="page-desc">配置角色可访问的菜单与数据范围</p>
      </div>
      <div class="page-actions">
        <el-button type="primary" @click="open()">新建角色</el-button>
      </div>
    </div>

    <div v-if="list.length" class="board">
      <article v-for="row in list" :key="row.id" class="role-card">
        <div class="role-top">
          <div>
            <div class="name">{{ row.name }}</div>
            <div class="code">{{ row.code || '—' }}</div>
          </div>
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.status === 1 ? '启用' : '禁用' }}
          </el-tag>
        </div>
        <div class="role-scope">数据范围 · {{ SCOPE[row.dataScope] || '—' }}</div>
        <div v-if="row.remark" class="role-remark">{{ row.remark }}</div>
        <div class="role-ops icon-ops">
          <el-button text aria-label="编辑" title="编辑" @click="open(row)">
            <el-icon :size="16"><EditPen /></el-icon>
          </el-button>
          <el-button text class="is-danger" aria-label="删除" title="删除" @click="remove(row.id)">
            <el-icon :size="16"><Delete /></el-icon>
          </el-button>
        </div>
      </article>
    </div>
    <el-empty v-else description="暂无角色" />

    <el-dialog v-model="dialog" :title="isEdit ? '编辑角色' : '新建角色'" width="640px" :close-on-click-modal="false">
      <el-form label-width="100px">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="编码"><el-input v-model="form.code" /></el-form-item>
        <el-form-item label="数据范围">
          <el-select v-model="form.dataScope" style="width: 100%">
            <el-option :value="1" label="全部" />
            <el-option :value="2" label="自定义部门" />
            <el-option :value="3" label="本部门" />
            <el-option :value="4" label="本部门及以下" />
            <el-option :value="5" label="仅本人" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.dataScope === 2" label="自定义部门">
          <el-tree-select v-model="form.deptIds" :data="depts" :props="{ label: 'name', value: 'id' }" multiple show-checkbox check-strictly style="width: 100%" />
        </el-form-item>
        <el-form-item label="菜单权限">
          <div class="menu-tree">
            <el-tree
              v-if="dialog"
              :key="String(form.id ?? 'new')"
              :data="menus"
              show-checkbox
              node-key="id"
              :props="{ label: 'name' }"
              :default-checked-keys="form.menuIds"
              @check="(_: any, info: any) => (form.menuIds = [...info.checkedKeys, ...info.halfCheckedKeys])"
            />
          </div>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.board {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 14px;
}
.role-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 18px;
  background: var(--kk-glass-bg);
  border: 1px solid var(--kk-glass-border);
  border-radius: var(--kk-radius);
  box-shadow: var(--kk-glass-shadow);
  backdrop-filter: var(--kk-glass-blur);
  -webkit-backdrop-filter: var(--kk-glass-blur);
}
.role-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}
.name {
  font-size: 16px;
  font-weight: 600;
  color: var(--kk-text);
}
.code {
  margin-top: 4px;
  font-size: 12px;
  color: var(--kk-text-muted);
  font-variant-numeric: tabular-nums;
}
.role-scope {
  font-size: 13px;
  color: var(--kk-text-secondary);
}
.role-remark {
  font-size: 12px;
  color: var(--kk-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.role-ops {
  justify-content: flex-end;
  margin-top: auto;
  padding-top: 8px;
  border-top: 1px solid rgba(0, 0, 0, 0.06);
}
.menu-tree {
  max-height: 280px;
  overflow: auto;
  padding: 8px 12px;
  border: 1px solid var(--kk-glass-border);
  border-radius: var(--kk-radius-sm);
  background: rgba(255, 255, 255, 0.4);
}
@media (prefers-reduced-transparency: reduce) {
  .role-card {
    background: #fff;
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}
</style>
