<template>
  <div class="page">
    <el-alert type="info" :closable="false" show-icon class="hint"
      description="费用报销（M8-8）：报销提交（发票重复 L1 阻断）→ 部门主管+财务两节点审批 → 系统自动落费用凭证与 FIFO 借款冲销（BR-4.6-01）→ 差额由出纳补付/退款（前款不清、后款不借：超期借款新增申请自动加签财务主管）。" />

    <!-- ============ 8.8.3 借款冲销 ============ -->
    <el-alert type="info" :closable="false" show-icon class="hint"
      title="报销审批通过后系统自动按借款日期 FIFO 冲销（BR-4.6-01 报销≤借款余额必须全额冲销不得付现）；差额结算由出纳在此执行。" />
    <el-table :data="approvedReimb" border stripe>
      <el-table-column prop="expNo" label="报销单号" width="170" />
      <el-table-column prop="employeeName" label="员工" width="100" />
      <el-table-column label="报销额" width="110" align="right">
        <template #default="{ row }">¥{{ fmt(row.totalAmount) }}</template>
      </el-table-column>
      <el-table-column label="结算状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.settleStatus === 'DONE' ? 'success' : 'warning'">
            {{ { PENDING: '待补付', DONE: '已结算', NONE: '未到' }[row.settleStatus] || row.settleStatus }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openDetail(row)">冲销明细</el-button>
          <el-button v-if="row.settleStatus === 'PENDING'" size="small" type="primary"
            @click="openPay(row)">补付差额</el-button>
          <el-button size="small" type="warning" @click="openRefund(row)">登记退款</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 补付对话框 -->
    <el-dialog v-model="payVisible" :title="`补付差额 ${payTarget?.expNo || ''}`" width="460px">
      <el-form label-width="100px">
        <el-form-item label="银行账户">
          <el-select v-model="payForm.bankAccountId" style="width: 100%">
            <el-option v-for="a in accounts" :key="a.id" :label="`${a.accountName}（余额 ¥${fmt(a.balance)}）`" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="付款日期"><el-date-picker v-model="payForm.payDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item>
      </el-form>
      <p class="muted">补付将走统一收付中心出账（借 2241 / 贷 1002），账户余额不足将 422 阻断。</p>
      <template #footer>
        <el-button @click="payVisible = false">取消</el-button>
        <el-button type="primary" @click="doPay">确认补付</el-button>
      </template>
    </el-dialog>

    <!-- 退款对话框 -->
    <el-dialog v-model="refundVisible" :title="`登记员工退款（剩余借款回收）`" width="460px">
      <el-form label-width="100px">
        <el-form-item label="借款单">
          <el-select v-model="refundForm.loanId" style="width: 100%" placeholder="选择该员工未核销借款">
            <el-option v-for="l in refundLoans" :key="l.id"
              :label="`${l.loanNo}（余额 ¥${fmt(l.outstandingBalance)}）`" :value="l.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="退款金额"><el-input-number v-model="refundForm.amount" :precision="2" :min="0.01" style="width: 100%" /></el-form-item>
        <el-form-item label="收款账户">
          <el-select v-model="refundForm.bankAccountId" style="width: 100%">
            <el-option v-for="a in accounts" :key="a.id" :label="a.accountName" :value="a.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="refundVisible = false">取消</el-button>
        <el-button type="primary" @click="doRefund">确认退款</el-button>
      </template>
    </el-dialog>

    <!-- 报销详情抽屉（报销申请/借款冲销两页共用） -->
    <ReimburseDetailDrawer />
  </div>
</template>

<script setup>
/**
 * 8.8.3 借款冲销（拆分自原费用报销多 tab 页，design D1）。
 * 冲销明细抽屉见 _parts/ReimburseDetailDrawer.vue；退款后经 useExpense.js 的 loadLoans 刷新借款页。
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getBankAccountsApi } from '@/api/fin/bank-account'
import {
  listReimburses, payDifference, refundLoan, listLoans
} from '@/api/fin/expense-reimbursement'
import { useExpense } from './useExpense'
import { today, fmt } from './expense-helpers'
import ReimburseDetailDrawer from './_parts/ReimburseDetailDrawer.vue'

const { openDetail, loadLoans } = useExpense()

// ---- 冲销/结算 ----
const approvedReimb = ref([])
const accounts = ref([])
const payVisible = ref(false)
const payTarget = ref(null)
const payForm = ref({ bankAccountId: '', payDate: today() })
const refundVisible = ref(false)
const refundTarget = ref(null)
const refundForm = ref({ loanId: '', amount: undefined, bankAccountId: '' })
const refundLoans = ref([])

async function loadApproved() {
  const res = await listReimburses({ status: 'APPROVED' })
  approvedReimb.value = res.data || []
}
async function loadAccounts() {
  const res = await getBankAccountsApi()
  accounts.value = res.data || []
  if (accounts.value.length) {
    payForm.value.bankAccountId = accounts.value[0].id
    refundForm.value.bankAccountId = accounts.value[0].id
  }
}
function openPay(row) {
  payTarget.value = row
  payVisible.value = true
}
async function doPay() {
  const res = await payDifference(payTarget.value.id, payForm.value)
  ElMessage.success(`补付 ¥${fmt(res.data.difference)}，凭证 ${res.data.voucherNo}`)
  payVisible.value = false
  loadApproved()
}
async function openRefund(row) {
  refundTarget.value = row
  refundForm.value = { loanId: '', amount: undefined, bankAccountId: payForm.value.bankAccountId }
  const res = await listLoans({ status: 'PAID' })
  refundLoans.value = (res.data || []).filter(l => Number(l.outstandingBalance) > 0)
  refundVisible.value = true
}
async function doRefund() {
  const res = await refundLoan(refundTarget.value.id, refundForm.value)
  ElMessage.success(`退款 ¥${fmt(res.data.amount)} 入账，借款余额 ¥${fmt(res.data.loanOutstanding)}`)
  refundVisible.value = false
  loadLoans()
}

onMounted(() => {
  loadApproved()
  loadAccounts()
})
</script>

<style scoped>
.page { padding: 12px }
.hint { margin-bottom: 12px }
.muted { color: #909399; font-size: 12px }
</style>
