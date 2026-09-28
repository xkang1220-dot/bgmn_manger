<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bizApi } from '@/api/biz'
import ProjectCascadeSelect from '@/components/project/ProjectCascadeSelect.vue'

const users = ref<any[]>([]), projects = ref<any[]>([]), items = ref<any[]>([])
const attendanceUserIds = ref(new Set<number>())
const dialog = ref(false), isEdit = ref(false), saving = ref(false)
const form = reactive<any>({})
const selectedUserAttendanceEnabled = computed(() => attendanceUserIds.value.has(form.userId))
const selectableUsers = computed(() => {
  const configured = new Set(items.value.map((row: any) => Number(row.userId)))
  return users.value.filter((u: any) => Number(u.id) === Number(form.userId) || !configured.has(Number(u.id)))
})
const payModeLabel = (row: any) => row.payMode === 'DAILY' ? '按天计薪' : '固定月薪'

async function load() {
  projects.value = await bizApi.projectList()
  const attendanceUsers = await bizApi.attendanceUsers()
  users.value = attendanceUsers || []
  attendanceUserIds.value = new Set(users.value.map((u: any) => u.id))
  items.value = await bizApi.salaryItems()
}
function defaults() { return { id: undefined, userId: undefined, projectId: undefined, cycleType: 'MONTHLY', payMode: 'MONTHLY', amount: 3000, payDay: 20, normalCoefficient: 1, restCoefficient: 0.3, normalDayRate: 0, restDayRate: 0, enabled: 1, remark: '' } }
function open(row?: any) { isEdit.value = !!row; Object.assign(form, defaults(), row || {}); dialog.value = true }
async function save() {
  if (!form.userId || !form.projectId) return ElMessage.warning('请选择员工和出款项目')
  if (form.payMode === 'MONTHLY' && (!form.amount || form.normalCoefficient == null || form.restCoefficient == null)) return ElMessage.warning('请填写月薪和工资系数')
  saving.value = true
  try { await bizApi.saveSalaryItem({ ...form, cycleType: 'MONTHLY' }, isEdit.value); ElMessage.success('工资配置已保存'); dialog.value = false; await load() } finally { saving.value = false }
}
async function remove(row: any) { await ElMessageBox.confirm(`删除「${row.userName} / ${row.projectName}」的工资配置？`, '确认'); await bizApi.deleteSalaryItem(row.id); ElMessage.success('已删除'); await load() }
onMounted(load)
</script>

<template>
  <div class="page-card">
    <div class="head"><div><h3>员工工资配置</h3><p>全局配置，无需关联公司；当前仅计算月薪。出款公司由所选项目自动确定。</p></div><el-button v-permission="'hr:salary:edit'" type="primary" @click="open()">新增配置</el-button></div>
    <el-table :data="items" stripe>
      <el-table-column prop="userName" label="员工" min-width="110" /><el-table-column prop="projectName" label="出款项目" min-width="150" />
      <el-table-column label="计薪方式" width="110"><template #default="{ row }">{{ payModeLabel(row) }}</template></el-table-column><el-table-column label="发薪日" width="90"><template #default="{ row }">每月 {{ row.payDay || 20 }} 日</template></el-table-column>
      <el-table-column label="薪资标准" min-width="180"><template #default="{ row }"><template v-if="row.payMode === 'DAILY'">平常 ¥{{ row.normalDayRate }}/天，周末/节假日 ¥{{ row.restDayRate }}/天</template><template v-else>¥{{ row.amount }}/月</template></template></el-table-column>
      <el-table-column label="考勤计算" min-width="190"><template #default="{ row }"><span v-if="row.payMode === 'DAILY'">自动计算（按实际天数）</span><span v-else>平常 × {{ row.normalCoefficient }}，周末/节假日 × {{ row.restCoefficient }}</span></template></el-table-column>
      <el-table-column label="状态" width="80"><template #default="{ row }">{{ row.enabled === 1 ? '启用' : '停用' }}</template></el-table-column><el-table-column prop="remark" label="备注" min-width="120" />
      <el-table-column label="操作" width="140" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="open(row)">编辑</el-button><el-button link type="danger" @click="remove(row)">删除</el-button></template></el-table-column>
    </el-table>
    <el-dialog v-model="dialog" :title="isEdit ? '编辑工资配置' : '新增工资配置'" width="600px">
      <el-form label-width="130px">
        <el-form-item label="员工" required><el-select v-model="form.userId" filterable style="width:100%" no-data-text="暂无未配置工资的考勤员工"><el-option v-for="u in selectableUsers" :key="u.id" :label="u.name" :value="u.id" /></el-select></el-form-item>
        <el-form-item label="出款项目" required><ProjectCascadeSelect v-model="form.projectId" :projects="projects" mode="pick" top-placeholder="重点或重大" child-placeholder="请选择小项目" top-width="100%" child-width="100%" :clearable="false" /></el-form-item>
        <el-form-item label="计薪方式" required><el-radio-group v-model="form.payMode"><el-radio-button value="MONTHLY">固定月薪</el-radio-button><el-radio-button value="DAILY" :disabled="!selectedUserAttendanceEnabled">按天计薪</el-radio-button></el-radio-group><span v-if="!selectedUserAttendanceEnabled" class="hint">按天计薪仅适用于已启用考勤的员工</span></el-form-item>
        <el-form-item label="发薪日" required><el-input-number v-model="form.payDay" :min="1" :max="28" :precision="0" /><span class="hint">考勤区间按上月该日至本月该日计算</span></el-form-item>
        <template v-if="form.payMode === 'MONTHLY'"><el-form-item label="月薪" required><el-input-number v-model="form.amount" :min="0.01" :precision="2" style="width:100%" /></el-form-item><el-form-item label="平常上班系数"><el-input-number v-model="form.normalCoefficient" :min="0" :step="0.1" :precision="2" /></el-form-item><el-form-item label="周末节假日系数"><el-input-number v-model="form.restCoefficient" :min="0" :step="0.1" :precision="2" /></el-form-item><div class="formula">计算公式：月薪 ÷ 当月实际天数（28/29/30/31）×（平常出勤天数 × 平常系数 + 周末/节假日出勤天数 × 对应系数）</div></template>
        <template v-else><el-form-item label="平常上班日薪" required><el-input-number v-model="form.normalDayRate" :min="0" :precision="2" style="width:100%" /></el-form-item><el-form-item label="周末节假日日薪" required><el-input-number v-model="form.restDayRate" :min="0" :precision="2" style="width:100%" /></el-form-item></template>
        <el-alert title="值班日统一按平常上班计算，不再计入周末或节假日。" type="info" :closable="false" show-icon /><el-form-item label="启用" class="top-gap"><el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" /></el-form-item><el-form-item label="备注"><el-input v-model="form.remark" /></el-form-item>
      </el-form><template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>
<style scoped>.head{display:flex;justify-content:space-between;align-items:flex-start;margin-bottom:16px;gap:16px}.head h3{margin:0 0 6px}.head p{margin:0;color:#64748b;font-size:13px}.hint{margin-left:10px;color:#94a3b8;font-size:13px}.unit{margin-left:8px}.top-gap{margin-top:18px}.formula{margin:-4px 0 16px 130px;color:#64748b;font-size:13px;line-height:1.6}</style>
