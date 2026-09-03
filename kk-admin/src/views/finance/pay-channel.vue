<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { bizApi } from '@/api/biz'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const canEdit = computed(() => userStore.hasPermission('finance:channel:edit'))

const list = ref<any[]>([])
const pools = ref<any[]>([])
const dialog = ref(false)
const isEdit = ref(false)
const form = reactive<any>({
  id: undefined,
  poolId: undefined,
  channelType: 'ALIPAY',
  name: '',
  accountNo: '',
  accountName: '',
  bankName: '',
  sort: 0,
  status: 1,
  remark: '',
})

const TYPE_OPTS = [
  { value: 'ALIPAY', label: '支付宝' },
  { value: 'WECHAT', label: '微信' },
  { value: 'BANK', label: '银行卡' },
  { value: 'CASH', label: '现金' },
  { value: 'OTHER', label: '其他' },
]

function fmt(n?: number) {
  return Number(n || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

async function load() {
  list.value = await bizApi.payChannelList({ all: true })
  pools.value = await bizApi.poolList()
}

function open(row?: any) {
  if (!canEdit.value) {
    ElMessage.warning('没有编辑渠道权限')
    return
  }
  isEdit.value = !!row
  Object.assign(form, row || {
    id: undefined,
    poolId: pools.value.find((p) => p.isDefault === 1)?.id ?? pools.value[0]?.id,
    channelType: 'ALIPAY',
    name: '',
    accountNo: '',
    accountName: '',
    bankName: '',
    sort: 0,
    status: 1,
    remark: '',
  })
  dialog.value = true
}

async function save() {
  if (!canEdit.value) return
  if (!form.name?.trim()) {
    ElMessage.warning('请填写渠道名称')
    return
  }
  if (!form.poolId) {
    ElMessage.warning('请选择资金池')
    return
  }
  await bizApi.savePayChannel(form, isEdit.value)
  ElMessage.success('已保存')
  dialog.value = false
  await load()
}

onMounted(load)
</script>

<template>
  <div class="page-card">
    <div class="page-header">
      <div>
        <h3 class="page-title">收款渠道</h3>
        <p class="page-desc">支付宝 / 微信 / 银行卡等；入账时选择渠道，余额按净额累计</p>
      </div>
      <el-button v-if="canEdit" type="primary" @click="open()">新建渠道</el-button>
    </div>

    <div class="board">
      <div v-for="c in list" :key="c.id" class="channel-card" :class="{ off: c.status === 0 }">
        <div class="ch-top">
          <el-tag size="small">{{ c.channelTypeLabel || c.channelType }}</el-tag>
          <el-button v-if="canEdit" link type="primary" @click="open(c)">编辑</el-button>
        </div>
        <h4>{{ c.name }}</h4>
        <div class="bal">¥ {{ fmt(c.balance) }}</div>
        <div class="meta">{{ c.accountName || '—' }} · {{ c.accountNo || '无账号' }}</div>
        <div class="meta">归属：{{ c.poolName || '—' }}</div>
      </div>
    </div>

    <el-dialog v-model="dialog" :title="isEdit ? '编辑渠道' : '新建渠道'" width="520px">
      <el-form label-width="100px">
        <el-form-item label="类型">
          <el-select v-model="form.channelType" style="width: 100%">
            <el-option v-for="o in TYPE_OPTS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如：公司支付宝" />
        </el-form-item>
        <el-form-item label="资金池" required>
          <el-select v-model="form.poolId" style="width: 100%">
            <el-option v-for="p in pools" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="户名"><el-input v-model="form.accountName" /></el-form-item>
        <el-form-item label="账号"><el-input v-model="form.accountNo" placeholder="可脱敏，如 ****8888" /></el-form-item>
        <el-form-item v-if="form.channelType === 'BANK'" label="开户行">
          <el-input v-model="form.bankName" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-desc { margin: 4px 0 0; color: #64748b; font-size: 13px; }
.board {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px;
}
.channel-card {
  border: 1px solid #e8edf4;
  border-radius: 12px;
  padding: 14px;
  background: #fff;
}
.channel-card.off { opacity: 0.55; }
.ch-top { display: flex; justify-content: space-between; align-items: center; }
.channel-card h4 { margin: 10px 0 6px; font-size: 15px; }
.bal { font-size: 22px; font-weight: 700; color: #1d4ed8; margin-bottom: 8px; }
.meta { font-size: 12px; color: #94a3b8; line-height: 1.6; }
</style>
