<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { sysApi } from '@/api/system'

const query = reactive({ page: 1, pageSize: 12, username: '', nickname: '' })
const list = ref<any[]>([])
const total = ref(0)
const roles = ref<any[]>([])
const depts = ref<any[]>([])
const companies = ref<any[]>([])
const dialog = ref(false)
const isEdit = ref(false)
const saving = ref(false)

const SCOPE_OPTIONS = [
  { value: 1, label: '公司全部' },
  { value: 3, label: '本部门' },
  { value: 4, label: '本部门及以下' },
  { value: 5, label: '仅本人' },
]

/** 越大越宽；2 自定义不参与账号部门行回填 */
const SCOPE_WIDTH: Record<number, number> = { 1: 4, 4: 3, 3: 2, 5: 1 }

type DeptRow = { deptId?: number; dataScope: number; isPrimary: number }
type OrgBlock = { companyId?: number; depts: DeptRow[]; roleIds: number[] }

function emptyDeptRow(isPrimary = 0, dataScope = 5): DeptRow {
  return { deptId: undefined, dataScope, isPrimary }
}

function suggestedDataScope(roleIds?: Array<number | string>): number {
  let best: number | null = null
  let bestWidth = -1
  for (const id of roleIds || []) {
    const rid = Number(id)
    const role = roles.value.find((r) => Number(r.id) === rid)
    const scope = Number(role?.dataScope)
    if (!Number.isFinite(scope) || SCOPE_WIDTH[scope] == null) continue
    const w = SCOPE_WIDTH[scope]
    if (w > bestWidth) {
      bestWidth = w
      best = scope
    }
  }
  return best ?? 5
}

function applyRoleDataScopeToBlock(block: OrgBlock, roleIds?: Array<number | string>) {
  const ids = roleIds ?? block.roleIds
  const scope = suggestedDataScope(ids)
  for (const row of block.depts) {
    row.dataScope = scope
  }
}

function emptyOrgBlock(): OrgBlock {
  return { companyId: undefined, depts: [emptyDeptRow(0)], roleIds: [] }
}

function emptyForm() {
  return {
    id: undefined as number | undefined,
    username: '',
    nickname: '',
    password: '123456',
    phone: '',
    email: '',
    status: 1,
    gender: 0,
    globalAdmin: false,
    orgBlocks: [emptyOrgBlock()] as OrgBlock[],
  }
}

const form = reactive<any>(emptyForm())

const AVATAR_TONES = ['indigo', 'cyan', 'violet', 'amber'] as const
const filteredEmpty = computed(() => !list.value.length && (!!query.username.trim() || !!query.nickname.trim()))

const adminRole = computed(() => roles.value.find((r) => r.code === 'admin'))
const companyRoles = computed(() => roles.value.filter((r) => r.code !== 'admin'))

const usedCompanyIds = computed(() =>
  form.orgBlocks.map((b: OrgBlock) => b.companyId).filter((id: number | undefined) => id != null),
)

function companyOptionsFor(block: OrgBlock) {
  return companies.value.filter(
    (c) => c.id === block.companyId || !usedCompanyIds.value.includes(c.id),
  )
}

function findDeptNode(nodes: any[], id?: number): any | null {
  if (id == null) return null
  for (const n of nodes || []) {
    if (n.id === id) return n
    const child = findDeptNode(n.children || [], id)
    if (child) return child
  }
  return null
}

function resolveCompanyId(deptId?: number): number | undefined {
  if (deptId == null) return undefined
  let cur = findDeptNode(depts.value, deptId)
  let guard = 0
  while (cur && guard++ < 32) {
    const parentId = cur.parentId
    if (parentId == null || parentId === 0) return cur.id
    cur = findDeptNode(depts.value, parentId)
  }
  return undefined
}

function companyDeptTree(companyId?: number) {
  if (companyId == null) return []
  const node = findDeptNode(depts.value, companyId)
  return node ? [node] : []
}

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

function addOrgBlock() {
  form.orgBlocks.push(emptyOrgBlock())
}

function removeOrgBlock(idx: number) {
  if (form.orgBlocks.length <= 1) return
  form.orgBlocks.splice(idx, 1)
  ensurePrimaryDept()
}

function onCompanyChange(block: OrgBlock) {
  block.depts = [emptyDeptRow(0)]
  block.roleIds = []
  ensurePrimaryDept()
}

function addDeptInBlock(block: OrgBlock) {
  block.depts.push(emptyDeptRow(0, suggestedDataScope(block.roleIds)))
}

function onRolesChange(block: OrgBlock, roleIds?: Array<number | string>) {
  // 以 change 回传值为准，避免偶发读到旧的 v-model
  if (roleIds) block.roleIds = roleIds.map(Number)
  applyRoleDataScopeToBlock(block, block.roleIds)
}

function removeDeptInBlock(block: OrgBlock, deptIdx: number) {
  if (block.depts.length <= 1) return
  block.depts.splice(deptIdx, 1)
  ensurePrimaryDept()
}

function setPrimaryDept(block: OrgBlock, deptIdx: number) {
  for (const b of form.orgBlocks as OrgBlock[]) {
    for (const d of b.depts) d.isPrimary = 0
  }
  block.depts[deptIdx].isPrimary = 1
}

function ensurePrimaryDept() {
  const all = (form.orgBlocks as OrgBlock[]).flatMap((b) => b.depts)
  if (!all.length) return
  if (!all.some((d) => d.isPrimary === 1)) {
    all[0].isPrimary = 1
  }
}

function buildOrgBlocksFromDetail(detail: any): OrgBlock[] {
  const deptRows = (detail.userDepts?.length
    ? detail.userDepts
    : detail.deptId
      ? [{ deptId: detail.deptId, dataScope: 5, isPrimary: 1, companyId: resolveCompanyId(detail.deptId) }]
      : []) as any[]

  const roleRows = (detail.roleBindings || []) as any[]
  const companyIds = new Set<number>()
  for (const d of deptRows) {
    const cid = d.companyId ?? resolveCompanyId(d.deptId)
    if (cid) companyIds.add(cid)
  }
  for (const b of roleRows) {
    if (b.companyId && b.companyId !== 0) companyIds.add(b.companyId)
  }

  if (!companyIds.size) return [emptyOrgBlock()]

  return [...companyIds].map((companyId) => {
    const deptsInCompany = deptRows
      .filter((d) => (d.companyId ?? resolveCompanyId(d.deptId)) === companyId)
      .map((d) => ({
        deptId: d.deptId,
        dataScope: d.dataScope ?? 5,
        isPrimary: d.isPrimary ?? 0,
      }))
    const roleIds = roleRows
      .filter((b) => b.companyId === companyId && !isAdminRoleId(b.roleId))
      .map((b) => b.roleId)
      .filter(Boolean)
    return {
      companyId,
      depts: deptsInCompany.length ? deptsInCompany : [emptyDeptRow(0)],
      roleIds,
    }
  })
}

function isAdminRoleId(roleId?: number) {
  return !!roles.value.find((r) => r.id === roleId && r.code === 'admin')
}

function flattenPayload() {
  const userDepts: DeptRow[] = []
  const roleBindings: { roleId: number; companyId: number }[] = []

  for (const block of form.orgBlocks as OrgBlock[]) {
    if (!block.companyId) continue
    for (const d of block.depts) {
      if (!d.deptId) continue
      userDepts.push({
        deptId: d.deptId,
        dataScope: d.dataScope ?? 5,
        isPrimary: d.isPrimary ?? 0,
      })
    }
    for (const roleId of block.roleIds || []) {
      roleBindings.push({ roleId, companyId: block.companyId })
    }
  }

  if (form.globalAdmin && adminRole.value) {
    roleBindings.push({ roleId: adminRole.value.id, companyId: 0 })
  }

  return { userDepts, roleBindings }
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

async function open(row?: any) {
  isEdit.value = !!row
  Object.assign(form, emptyForm())
  if (row?.id) {
    const detail = await sysApi.userDetail(row.id)
    const hasGlobalAdmin = (detail.roleBindings || []).some(
      (b: any) => b.companyId === 0 && isAdminRoleId(b.roleId),
    ) || (detail.roleIds || []).some((id: number) => isAdminRoleId(id) && !(detail.roleBindings || []).length)

    Object.assign(form, {
      id: detail.id,
      username: detail.username,
      nickname: detail.nickname,
      phone: detail.phone,
      email: detail.email,
      status: detail.status ?? 1,
      gender: detail.gender ?? 0,
      password: '',
      globalAdmin: hasGlobalAdmin,
      orgBlocks: buildOrgBlocksFromDetail(detail),
    })
    ensurePrimaryDept()
  } else if (form.orgBlocks[0]) {
    form.orgBlocks[0].depts[0].isPrimary = 1
  }
  dialog.value = true
}

async function save() {
  if (!form.username?.trim()) {
    ElMessage.warning('请填写账号')
    return
  }

  for (const block of form.orgBlocks as OrgBlock[]) {
    if (!block.companyId) {
      ElMessage.warning('请为每个组织块选择公司')
      return
    }
    if (!block.depts.length || block.depts.some((d) => !d.deptId)) {
      ElMessage.warning('请完整填写各部门')
      return
    }
    for (const d of block.depts) {
      const cid = resolveCompanyId(d.deptId)
      if (cid !== block.companyId) {
        ElMessage.warning('部门必须属于所选公司')
        return
      }
    }
  }

  const { userDepts, roleBindings } = flattenPayload()
  if (!userDepts.length) {
    ElMessage.warning('请至少选择一个部门')
    return
  }
  if (!userDepts.some((d) => d.isPrimary === 1)) {
    ElMessage.warning('请指定主部门')
    return
  }

  saving.value = true
  try {
    await sysApi.saveUser(
      {
        id: form.id,
        username: form.username,
        nickname: form.nickname,
        password: form.password,
        phone: form.phone,
        email: form.email,
        status: form.status,
        gender: form.gender,
        deptId: userDepts.find((d) => d.isPrimary === 1)?.deptId,
        userDepts,
        roleBindings,
      },
      isEdit.value,
    )
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
  companies.value = await sysApi.deptCompanies()
  await load()
})
</script>

<template>
  <div class="page-stack">
    <div class="page-top">
      <div class="page-top__main">
        <p class="page-desc">系统登录账号；按公司配置部门与角色</p>
      </div>
      <div class="page-actions">
        <el-button v-permission="'system:user:add'" type="primary" @click="open()">新建账号</el-button>
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
          <el-button v-permission="'system:user:edit'" text aria-label="编辑" title="编辑" @click="open(row)">
            <el-icon :size="16"><EditPen /></el-icon>
          </el-button>
          <el-button v-permission="'system:user:resetPwd'" text aria-label="重置密码" title="重置密码" @click="reset(row.id)">
            <el-icon :size="16"><RefreshRight /></el-icon>
          </el-button>
          <el-button v-permission="'system:user:remove'" text class="is-danger" aria-label="删除" title="删除" @click="remove(row.id)">
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

    <el-dialog v-model="dialog" :title="isEdit ? '编辑账号' : '新建账号'" width="760px" :close-on-click-modal="false">
      <el-form label-width="90px">
        <el-form-item label="账号"><el-input v-model="form.username" :disabled="isEdit" /></el-form-item>
        <el-form-item v-if="!isEdit" label="密码"><el-input v-model="form.password" /></el-form-item>
        <el-form-item label="昵称"><el-input v-model="form.nickname" /></el-form-item>

        <el-form-item label="组织权限">
          <div class="org-stack">
            <div v-for="(block, bIdx) in form.orgBlocks" :key="bIdx" class="org-block">
              <div class="org-block__head">
                <el-select
                  v-model="block.companyId"
                  placeholder="选择公司"
                  style="width: 220px"
                  @change="onCompanyChange(block)"
                >
                  <el-option
                    v-for="c in companyOptionsFor(block)"
                    :key="c.id"
                    :label="c.name"
                    :value="c.id"
                  />
                </el-select>
                <el-button text type="danger" :disabled="form.orgBlocks.length <= 1" @click="removeOrgBlock(Number(bIdx))">
                  删除公司
                </el-button>
              </div>

              <div class="org-section-label">部门与数据范围</div>
              <div v-for="(row, dIdx) in block.depts" :key="dIdx" class="bind-row">
                <el-tree-select
                  v-model="row.deptId"
                  :data="companyDeptTree(block.companyId)"
                  :props="{ label: 'name', value: 'id' }"
                  check-strictly
                  :disabled="!block.companyId"
                  placeholder="选择部门"
                  style="flex: 1.4"
                />
                <el-select v-model="row.dataScope" placeholder="数据范围" style="flex: 1">
                  <el-option v-for="s in SCOPE_OPTIONS" :key="s.value" :label="s.label" :value="s.value" />
                </el-select>
                <el-button text :type="row.isPrimary === 1 ? 'primary' : ''" @click="setPrimaryDept(block, Number(dIdx))">
                  {{ row.isPrimary === 1 ? '主部门' : '设为主' }}
                </el-button>
                <el-button text type="danger" :disabled="block.depts.length <= 1" @click="removeDeptInBlock(block, Number(dIdx))">
                  删除
                </el-button>
              </div>
              <el-button text type="primary" :disabled="!block.companyId" @click="addDeptInBlock(block)">+ 添加部门</el-button>

              <div class="org-section-label">该公司角色</div>
              <el-select
                v-model="block.roleIds"
                multiple
                clearable
                :disabled="!block.companyId"
                placeholder="选择角色后自动回填数据范围"
                style="width: 100%"
                @change="onRolesChange(block, $event)"
              >
                <el-option v-for="r in companyRoles" :key="r.id" :label="r.name" :value="r.id" />
              </el-select>
            </div>

            <el-button text type="primary" @click="addOrgBlock">+ 添加公司</el-button>

            <div v-if="adminRole" class="admin-row">
              <el-checkbox v-model="form.globalAdmin">全局管理员（跨公司，不绑具体公司）</el-checkbox>
            </div>
          </div>
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
.org-stack {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.org-block {
  padding: 12px 14px;
  border: 1px solid rgba(0, 0, 0, 0.08);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.35);
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.org-block__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.org-section-label {
  margin-top: 4px;
  font-size: 12px;
  color: var(--kk-text-muted);
}
.bind-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.admin-row {
  padding-top: 4px;
}
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
