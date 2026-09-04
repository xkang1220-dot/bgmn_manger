<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { sysApi } from '@/api/system'

const query = reactive({ page: 1, pageSize: 12, username: '', nickname: '' })
const list = ref<any[]>([])
const total = ref(0)
const roles = ref<any[]>([])
const depts = ref<any[]>([])
const dialog = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const form = reactive<any>({ username: '', nickname: '', password: '123456', phone: '', email: '', deptId: undefined, roleIds: [], status: 1, gender: 0 })

const AVATAR_TONES = ['indigo', 'cyan', 'violet', 'amber'] as const
const filteredEmpty = computed(() => !list.value.length && (!!query.username.trim() || !!query.nickname.trim()))

function initial(row: any) {
  const name = String(row.nickname || row.username || '').replace(/\s/g, '')
  return name.slice(0, 1) || '?'
}

function avatarTone(row: any) {
  const name = String(row.nickname || row.username || '')
  let hash = 0
  for (let i = 0; i < name.length; i++) hash = (hash * 31 + name.charCodeAt(i)) >>> 0
  return AVATAR_TONES[hash % AVATAR_TONES.length]
}

async function load() {
  const res = await sysApi.userPage(query)
  list.value = res.list
  total.value = res.total
}

function onFilter() {
  query.page = 1
  load()
}

function resetFilter() {
  query.username = ''
  query.nickname = ''
  query.page = 1
  load()
}

function open(row?: any) {
  isEdit.value = !!row
  Object.assign(form, row || { id: undefined, username: '', nickname: '', password: '123456', phone: '', email: '', deptId: undefined, roleIds: [], status: 1, gender: 0 })
  dialog.value = true
}

async function save() {
  if (!form.username?.trim()) {
    ElMessage.warning('请填写账号')
    return
  }
  saving.value = true
  try {
    await sysApi.saveUser(form, isEdit.value)
    ElMessage.success('保存成功')
    dialog.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(id: number) {
  await ElMessageBox.confirm('确认删除账号？')
  await sysApi.deleteUser(id)
  await load()
}

async function reset(id: number) {
  await ElMessageBox.confirm('确认将该账号密码重置为 123456？')
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
  <div class="page-stack">
    <div class="page-top">
      <div class="page-top__main">
        <p class="page-desc">系统登录账号；点右上角图标编辑、重置密码或删除</p>
      </div>
      <div class="page-actions">
        <el-button type="primary" @click="open()">新建账号</el-button>
      </div>
    </div>

    <el-form class="filter-bar" @submit.prevent="onFilter">
      <el-form-item label="账号">
        <el-input v-model="query.username" clearable placeholder="搜索账号" class="filter-keyword" @keyup.enter="onFilter" />
      </el-form-item>
      <el-form-item label="昵称">
        <el-input v-model="query.nickname" clearable placeholder="搜索昵称" class="filter-keyword" @keyup.enter="onFilter" />
      </el-form-item>
      <el-form-item class="filter-actions">
        <el-button type="primary" native-type="submit">查询</el-button>
        <el-button @click="resetFilter">重置</el-button>
      </el-form-item>
    </el-form>

    <div v-if="list.length" class="board">
      <article
        v-for="row in list"
        :key="row.id"
        class="user-card"
        :class="['user-card--' + avatarTone(row), { 'is-off': row.status !== 1 }]"
      >
        <div class="user-top">
          <span class="avatar" :class="'avatar--' + avatarTone(row)">{{ initial(row) }}</span>
          <div class="user-id">
            <div class="name">{{ row.nickname || row.username }}</div>
            <div class="sub">{{ row.username }}</div>
          </div>
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.status === 1 ? '启用' : '禁用' }}
          </el-tag>
        </div>
        <div class="user-meta">
          <div><span>部门</span><b>{{ row.deptName || '—' }}</b></div>
          <div><span>手机</span><b>{{ row.phone || '—' }}</b></div>
        </div>
        <div class="user-ops icon-ops">
          <el-button text aria-label="编辑" title="编辑" @click="open(row)">
            <el-icon :size="16"><EditPen /></el-icon>
          </el-button>
          <el-button text aria-label="重置密码" title="重置密码" @click="reset(row.id)">
            <el-icon :size="16"><RefreshRight /></el-icon>
          </el-button>
          <el-button text class="is-danger" aria-label="删除" title="删除" @click="remove(row.id)">
            <el-icon :size="16"><Delete /></el-icon>
          </el-button>
        </div>
      </article>
    </div>
    <el-empty v-else :description="filteredEmpty ? '没有匹配的账号' : '暂无账号'" />

    <div v-if="total > query.pageSize" class="page-footer">
      <el-pagination
        v-model:current-page="query.page"
        :page-size="query.pageSize"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="load"
      />
    </div>

    <el-dialog v-model="dialog" :title="isEdit ? '编辑账号' : '新建账号'" width="560px" :close-on-click-modal="false">
      <el-form label-width="90px">
        <el-form-item label="账号"><el-input v-model="form.username" :disabled="isEdit" /></el-form-item>
        <el-form-item v-if="!isEdit" label="密码"><el-input v-model="form.password" /></el-form-item>
        <el-form-item label="昵称"><el-input v-model="form.nickname" /></el-form-item>
        <el-form-item label="部门">
          <el-tree-select v-model="form.deptId" :data="depts" :props="{ label: 'name', value: 'id' }" check-strictly style="width: 100%" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.roleIds" multiple style="width: 100%">
            <el-option v-for="r in roles" :key="r.id" :label="r.name" :value="r.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="手机"><el-input v-model="form.phone" /></el-form-item>
        <el-form-item label="邮箱"><el-input v-model="form.email" /></el-form-item>
        <el-form-item label="状态"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" /></el-form-item>
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
.user-card {
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 18px;
  background: var(--kk-glass-bg);
  border: 1px solid var(--kk-glass-border);
  border-radius: var(--kk-radius);
  box-shadow: var(--kk-glass-shadow);
  backdrop-filter: var(--kk-glass-blur);
  -webkit-backdrop-filter: var(--kk-glass-blur);
}
.user-card::before {
  content: "";
  position: absolute;
  right: -28px;
  bottom: -36px;
  width: 120px;
  height: 120px;
  border-radius: 50%;
  filter: blur(28px);
  opacity: 0.22;
  pointer-events: none;
}
.user-card--indigo::before { background: #c7d2fe; }
.user-card--cyan::before { background: #a5f3fc; }
.user-card--violet::before { background: #ddd6fe; }
.user-card--amber::before { background: #fde68a; }
.user-card.is-off { opacity: 0.62; }
.user-top {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 12px;
}
.avatar {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 16px;
  font-weight: 700;
}
.avatar--indigo { background: #eef2ff; color: #4f46e5; }
.avatar--cyan { background: #ecfeff; color: #0891b2; }
.avatar--violet { background: #f5f3ff; color: #7c3aed; }
.avatar--amber { background: #fffbeb; color: #d97706; }
.user-id { min-width: 0; flex: 1; }
.name {
  font-weight: 600;
  font-size: 15px;
  color: var(--kk-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sub { margin-top: 2px; font-size: 12px; color: var(--kk-text-muted); }
.user-meta {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px 12px;
  font-size: 12px;
  color: var(--kk-text-muted);
}
.user-meta b {
  display: block;
  margin-top: 2px;
  font-size: 13px;
  font-weight: 600;
  color: var(--kk-text);
}
.user-ops {
  position: relative;
  z-index: 1;
  justify-content: flex-end;
  margin-top: auto;
  padding-top: 8px;
  border-top: 1px solid rgba(0, 0, 0, 0.06);
}
@media (prefers-reduced-transparency: reduce) {
  .user-card {
    background: #fff;
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}
</style>
