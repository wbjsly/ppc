<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>转正管理（10.7.2）—— 主管评价 / HR 审核 / 超期兜底</span>
          <el-button type="primary" size="small" @click="openSubmit">发起转正</el-button>
        </div>
      </template>
      <el-alert :closable="false" type="info" show-icon class="mb8"
        title="超期未评价：每日扫描自动按期转正（留痕「系统默认转正」）；延期须法定上限内（L1）；终止衔接离职流程" />

      <el-table :data="rows" size="small" border>
        <el-table-column prop="trfNo" label="单号" width="120" />
        <el-table-column prop="empName" label="员工" width="100" />
        <el-table-column label="建议" width="110">
          <template #default="{ row }">
            <el-tag :type="row.proposal === 'REGULAR' ? 'success' : row.proposal === 'EXTEND' ? 'warning' : 'danger'" size="small">
              {{ { REGULAR: '按期转正', EXTEND: '延期', TERMINATE: '终止' }[row.proposal] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="评分" width="100">
          <template #default="{ row }">能力 {{ row.abilityScore }} / 态度 {{ row.attitudeScore }}</template>
        </el-table-column>
        <el-table-column label="调薪后月薪" width="100">
          <template #default="{ row }">{{ row.newSalary ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="120">
          <template #default="{ row }">{{ statusName(row.status) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-button v-if="row.status === 'DRAFT'" link type="primary" size="small"
              @click="hrReview(row)">HR 审核</el-button>
            <span v-else class="muted">{{ row.status === 'EFFECTED' ? '已执行' : '审批中' }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 发起转正 -->
    <el-dialog v-model="submitVisible" title="发起转正（主管评价）" width="520px">
      <el-form label-width="100px">
        <el-form-item label="员工">
          <el-select v-model="form.empId" filterable style="width:100%">
            <el-option v-for="e in probationEmps" :key="e.id" :label="`${e.empNo} ${e.name}`" :value="e.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="能力评分"><el-rate v-model="form.abilityScore" /></el-form-item>
        <el-form-item label="态度评分"><el-rate v-model="form.attitudeScore" /></el-form-item>
        <el-alert v-if="lowScore" :closable="false" type="warning" show-icon class="mb8"
                  title="评分低于 3 分：系统建议「延期」或「终止」（仅建议不强制，须有事实依据）" />
        <el-form-item label="综合评价"><el-input v-model="form.attitudeNote" type="textarea" /></el-form-item>
        <el-form-item label="转正建议">
          <el-radio-group v-model="form.proposal">
            <el-radio value="REGULAR">按期转正</el-radio>
            <el-radio value="EXTEND">延期</el-radio>
            <el-radio value="TERMINATE">终止</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.proposal === 'EXTEND'" label="延期(月)">
          <el-input-number v-model="form.extendMonths" :min="1" :max="12" />
        </el-form-item>
        <el-form-item v-if="form.proposal === 'REGULAR'" label="调薪后月薪">
          <el-input-number v-model="form.newSalary" :min="0" :step="500" style="width:200px" />
          <div class="muted">可选；填写后审批通过自动写回主档月薪并留痕，不填则薪酬不变</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="submitVisible = false">取消</el-button>
        <el-button type="primary" @click="doSubmit">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { trfList, probationSubmit, probationHrReview, empList } from '@/api/hr/employee-archive'

const rows = ref([])
const probationEmps = ref([])
const submitVisible = ref(false)
const form = ref({ abilityScore: 4, attitudeScore: 4, proposal: 'REGULAR', newSalary: null })
// 评分 < 3 联动建议（FR-4.7-4-2：仅建议不强制）
const lowScore = computed(() => (form.value.abilityScore || 5) < 3 || (form.value.attitudeScore || 5) < 3)

const statusName = (s) => ({ DRAFT: '待 HR 审核', APPROVING: '审批中', EFFECTED: '已执行',
  AUTO_REGULAR: '系统默认转正', REJECTED: '驳回' }[s] || s)

async function loadAll() {
  try { rows.value = (await trfList({ type: 'PROBATION' })).data || [] } catch (e) { /* ignore */ }
  try {
    const { data } = await empList({ status: 'PROBATION' })
    probationEmps.value = (data || []).map(r => r.employee)
  } catch (e) { /* ignore */ }
}

async function openSubmit() {
  form.value = { abilityScore: 4, attitudeScore: 4, proposal: 'REGULAR', newSalary: null }
  submitVisible.value = true
}

async function doSubmit() {
  try {
    await probationSubmit(form.value)
    ElMessage.success('转正申请已提交')
    submitVisible.value = false
    loadAll()
  } catch (e) { /* ignore */ }
}

async function hrReview(row) {
  const { value } = await ElMessageBox.prompt('HR 审核意见（合规校验：延期法定上限）', 'HR 审核', {
    inputValidator: (v) => (v && v.trim()) || '意见必填'
  })
  try {
    await probationHrReview(row.id, value)
    ElMessage.success('已提交审批（HR 专员 → HR 经理）')
    loadAll()
  } catch (e) { /* ignore */ }
}

onMounted(loadAll)
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
.mb8 { margin-bottom: 8px; }
.muted { color: #909399; font-size: 12px; }
</style>
