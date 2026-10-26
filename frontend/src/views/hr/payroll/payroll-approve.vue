<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <span class="title">核算审批（10.5.4，PayrollRun 锁定与更正）</span>
        <div>
          <el-input v-model="period" placeholder="核算期 如 202610" clearable style="width:140px;margin-right:8px" @change="load" />
          <el-button @click="load">刷新</el-button>
        </div>
      </div>
      <el-alert type="warning" :closable="false" style="margin-bottom:12px"
                title="审批通过即锁定（BR-4.7-13），明细不可改；修改须发起薪酬更正申请（L2），通过后生成关联原批次的更正批次；审批须在发放日前 2 个工作日完成" />

      <el-table :data="runs" height="220" style="margin-bottom:12px" @row-click="loadDetail">
        <el-table-column prop="runNo" label="批次号" width="170" />
        <el-table-column prop="period" label="核算期" width="90" />
        <el-table-column prop="empCount" label="人数" width="70" />
        <el-table-column prop="netTotal" label="实发合计" width="120" />
        <el-table-column label="类型" width="80">
          <template #default="{ row }">{{ row.runType === 'CORRECTION' ? '更正' : '正常' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="{ DRAFT: 'info', SUBMITTING: 'warning', APPROVED: 'success', REJECTED: 'danger', VOID: 'danger', PAID: 'success', RECONCILED: 'success', ARCHIVED: 'info' }[row.status]">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <template v-if="row.status === 'SUBMITTING'">
              <el-button link type="success" size="small" @click.stop="doApprove(row, true)">审批通过</el-button>
              <el-button link type="danger" size="small" @click.stop="doApprove(row, false)">驳回</el-button>
            </template>
            <el-button v-if="row.status === 'APPROVED' && row.runType === 'NORMAL'" link type="warning" size="small"
                       @click.stop="openCorrection(row)">发起更正</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 更正申请 -->
      <el-table :data="corrections" height="180" v-if="corrections.length">
        <el-table-column prop="corrNo" label="更正单号" width="170" />
        <el-table-column prop="empId" label="员工ID" width="190" />
        <el-table-column prop="adjustItem" label="调整项" width="120" />
        <el-table-column prop="adjustAmount" label="调整金额" width="110" />
        <el-table-column prop="reason" label="原因" min-width="140" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="{ SUBMITTED: 'warning', APPROVED: 'success', REJECTED: 'danger', POSTED: 'success' }[row.status]">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170">
          <template #default="{ row }">
            <template v-if="row.status === 'SUBMITTED'">
              <el-button link type="success" size="small" @click="doApproveCorr(row, true)">L2 通过</el-button>
              <el-button link type="danger" size="small" @click="doApproveCorr(row, false)">驳回</el-button>
            </template>
            <span v-else-if="row.correctionRunId" class="muted">批次 {{ row.correctionRunId.slice(0, 8) }}…</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { payRunList, payApprove, payCorrectionList, paySubmitCorrection, payApproveCorrection } from '@/api/hr/payroll'

const now = new Date()
const period = ref(`${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}`)
const runs = ref([])
const corrections = ref([])

async function load() {
  const [r, c] = await Promise.all([
    payRunList({ period: period.value }),
    payCorrectionList({ period: period.value })
  ])
  runs.value = r.data || []
  corrections.value = c.data || []
}
async function loadDetail() { /* 行点击占位：审批页以批次列表为主 */ }
async function doApprove(row, ok) {
  const { value } = await ElMessageBox.prompt(
    ok ? '审批意见（通过后批次锁定，明细不可改）' : '驳回原因',
    ok ? '审批通过' : '驳回',
    ok ? { inputValue: '同意' } : { inputPattern: /\S+/, inputErrorMessage: '原因必填' })
  try {
    await payApprove(row.id, { approve: ok, opinion: value })
    ElMessage.success(ok ? '已通过并锁定（工资条已推送）' : '已驳回'); load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
function openCorrection(row) {
  ElMessageBox.prompt('更正信息（格式：员工ID,调整项,金额,原因）', '薪酬更正申请', {
    inputPlaceholder: 'empId,OVERTIME_WAGE,500,漏发加班工资',
    inputPattern: /\S+,\S+,-?\d+(\.\d+)?,\S+/,
    inputErrorMessage: '格式：员工ID,调整项,金额,原因'
  }).then(async ({ value }) => {
    const [empId, adjustItem, amount, reason] = value.split(',')
    await paySubmitCorrection({ runId: row.id, period: row.period, empId, adjustItem, adjustAmount: Number(amount), reason })
    ElMessage.success('更正申请已提交（待 HR 经理 L2 审批）'); load()
  }).catch(() => {})
}
async function doApproveCorr(row, ok) {
  const { value } = await ElMessageBox.prompt(ok ? '审批意见' : '驳回原因', ok ? 'L2 审批通过' : '驳回',
    ok ? { inputValue: '同意' } : { inputPattern: /\S+/, inputErrorMessage: '原因必填' })
  try {
    await payApproveCorrection(row.id, { approve: ok, opinion: value })
    ElMessage.success(ok ? '已通过（生成更正批次）' : '已驳回'); load()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e?.message) }
}
onMounted(load)
</script>

<style scoped>
.page { padding: 16px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-weight: 600; }
.muted { color: #999; }
</style>
