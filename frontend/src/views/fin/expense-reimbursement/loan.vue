<template>
  <div class="page">
    <el-alert type="info" :closable="false" show-icon class="hint"
      description="费用报销（M8-8）：报销提交（发票重复 L1 阻断）→ 部门主管+财务两节点审批 → 系统自动落费用凭证与 FIFO 借款冲销（BR-4.6-01）→ 差额由出纳补付/退款（前款不清、后款不借：超期借款新增申请自动加签财务主管）。" />

    <!-- ============ 8.8.2 借款管理 ============ -->
    <el-form inline class="filter">
      <el-form-item>
        <el-button type="primary" @click="loanFormVisible = true">借款申请</el-button>
      </el-form-item>
    </el-form>

    <el-divider content-position="left">账龄监控（超 90 天红标：前款不清、后款不借 BR-4.6-02）</el-divider>
    <el-table :data="aging" border size="small" class="mb">
      <el-table-column prop="employeeName" label="员工" width="140" />
      <el-table-column label="未核销笔数" width="100" align="right" prop="loanCount" />
      <el-table-column label="未核销合计" width="130" align="right">
        <template #default="{ row }">¥{{ fmt(row.outstandingTotal) }}</template>
      </el-table-column>
      <el-table-column label="最大账龄(天)" width="120" align="right">
        <template #default="{ row }">
          <span :style="{ color: row.overdue ? '#f56c6c' : '', fontWeight: row.overdue ? 600 : '' }">
            {{ row.maxAgingDays }}{{ row.overdue ? ' ⚠' : '' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="150">
        <template #default="{ row }">
          <el-tag v-if="row.overdue" size="small" type="danger">需财务主管处理</el-tag>
          <el-tag v-else size="small" type="success">正常</el-tag>
        </template>
      </el-table-column>
    </el-table>

    <el-table :data="loans" border stripe>
      <el-table-column prop="loanNo" label="借款单号" width="170" />
      <el-table-column prop="employeeName" label="员工" width="100" />
      <el-table-column prop="purpose" label="事由" min-width="150" show-overflow-tooltip />
      <el-table-column label="金额" width="110" align="right">
        <template #default="{ row }">¥{{ fmt(row.amount) }}</template>
      </el-table-column>
      <el-table-column label="未核销余额" width="120" align="right">
        <template #default="{ row }">
          <span :style="{ color: Number(row.outstandingBalance) > 0 ? '#e6a23c' : '' }">¥{{ fmt(row.outstandingBalance) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="{ PENDING: 'warning', PAID: 'success', REJECTED: 'danger' }[row.status] || 'info'">
            {{ { DRAFT: '草稿', PENDING: '审批中', PAID: '已放款', REJECTED: '已驳回' }[row.status] || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="paidDate" label="放款日" width="100" />
    </el-table>

    <el-dialog v-model="loanFormVisible" title="借款申请（可选前置）" width="480px">
      <el-form label-width="90px">
        <el-form-item label="借款金额"><el-input-number v-model="loanForm.amount" :precision="2" :min="0.01" style="width: 100%" /></el-form-item>
        <el-form-item label="借款事由"><el-input v-model="loanForm.purpose" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="预计核销"><el-date-picker v-model="loanForm.expectedDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item>
      </el-form>
      <el-alert type="warning" :closable="false" show-icon
        title="存在超 90 天未核销借款时，本申请将自动加签财务主管双签（C-4.6-14）" />
      <template #footer>
        <el-button @click="loanFormVisible = false">取消</el-button>
        <el-button type="primary" @click="doApplyLoan">提交申请</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 8.8.2 借款管理（拆分自原费用报销多 tab 页，design D1）。
 * 借款列表/账龄为跨页共享状态，见 useExpense.js（借款冲销页退款后经 loadLoans 刷新）。
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { applyLoan } from '@/api/fin/expense-reimbursement'
import { useExpense } from './useExpense'
import { fmt } from './expense-helpers'

const { loans, aging, loadLoans } = useExpense()

// ---- 借款 ----
const loanFormVisible = ref(false)
const loanForm = ref({ amount: undefined, purpose: '', expectedDate: '' })

async function doApplyLoan() {
  await applyLoan(loanForm.value)
  ElMessage.success('借款申请已提交（超期将自动加签财务主管）')
  loanFormVisible.value = false
  loadLoans()
}

onMounted(() => {
  loadLoans()
})
</script>

<style scoped>
.page { padding: 12px }
.hint { margin-bottom: 12px }
.filter { margin-bottom: 4px }
.mb { margin-bottom: 14px }
</style>
