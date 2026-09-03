<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { bizApi } from '@/api/biz'

const router = useRouter()
const list = ref<any[]>([])
const dialog = ref(false)
const isEdit = ref(false)
const form = reactive<any>({
  name: '',
  balance: 0,
  isDefault: 0,
  status: 1,
  remark: '',
})

async function load() {
  list.value = await bizApi.poolList()
}

function open(row?: any) {
  isEdit.value = !!row
  Object.assign(form, row || {
    id: undefined,
    name: '',
    balance: 0,
    isDefault: 0,
    status: 1,
    remark: '',
  })
  dialog.value = true
}

async function save() {
  if (!form.name?.trim()) {
    ElMessage.warning('请填写资金池名称')
    return
  }
  const payload = isEdit.value
    ? {
        id: form.id,
        name: form.name,
        isDefault: form.isDefault,
        status: form.status,
        remark: form.remark,
      }
    : {
        name: form.name,
        balance: form.balance ?? 0,
        isDefault: form.isDefault,
        status: form.status,
        remark: form.remark,
      }
  await bizApi.savePool(payload, isEdit.value)
  ElMessage.success('保存成功')
  dialog.value = false
  await load()
}

function goLedger(poolId?: number) {
  router.push({ path: '/finance/ledger', query: poolId ? { poolId: String(poolId) } : {} })
}

function formatMoney(v: number | string | undefined) {
  const n = Number(v || 0)
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

onMounted(load)
</script>

<template>
  <div class="page-stack">
    <div class="page-top">
      <div class="page-top__main">
        <p class="page-desc">所有动账必须走进出账/分钱并写入流水；新建填初始余额会自动生成「期初建账」记录</p>
      </div>
      <div class="page-actions">
        <el-button @click="goLedger()">去进出账</el-button>
        <el-button type="primary" @click="open()">新增资金池</el-button>
      </div>
    </div>

    <div class="page-card">
      <div class="table-wrap">
        <el-table :data="list">
          <el-table-column prop="name" label="名称" min-width="140" />
          <el-table-column label="余额" width="160" align="right">
            <template #default="{ row }">
              <span class="money">¥ {{ formatMoney(row.balance) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="默认" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="row.isDefault === 1 ? 'success' : 'info'" size="small">
                {{ row.isDefault === 1 ? '是' : '否' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
                {{ row.status === 1 ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip />
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="open(row)">编辑</el-button>
              <el-button link type="primary" @click="goLedger(row.id)">进出账</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <el-dialog v-model="dialog" :title="isEdit ? '编辑资金池' : '新增资金池'" width="480px">
      <el-form label-width="90px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如：公司主资金池" />
        </el-form-item>
        <el-form-item v-if="!isEdit" label="初始余额">
          <el-input-number v-model="form.balance" :min="0" :precision="2" :step="1000" style="width: 100%" />
        </el-form-item>
        <el-form-item v-else label="当前余额">
          <div class="balance-readonly">
            <span class="money">¥ {{ formatMoney(form.balance) }}</span>
            <el-button link type="primary" @click="dialog = false; goLedger(form.id)">去进出账调整</el-button>
          </div>
        </el-form-item>
        <el-form-item label="默认">
          <el-switch v-model="form.isDefault" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.money {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
  color: #0f172a;
}

.balance-readonly {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
}
</style>
