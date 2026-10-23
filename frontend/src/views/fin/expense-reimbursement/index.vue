<template>
  <div class="page">
    <el-alert type="info" :closable="false" show-icon class="hint"
      description="费用报销（M8-8）：报销提交（发票重复 L1 阻断）→ 部门主管+财务两节点审批 → 系统自动落费用凭证与 FIFO 借款冲销（BR-4.6-01）→ 差额由出纳补付/退款（前款不清、后款不借：超期借款新增申请自动加签财务主管）。" />
    <el-tabs v-model="activeTab">
      <!-- ============ 8.8.1 报销申请 ============ -->
      <el-tab-pane label="报销申请" name="reimburse">
        <el-form inline class="filter">
          <el-form-item label="状态">
            <el-select v-model="reimFilter.status" clearable placeholder="全部" style="width: 150px" @change="loadReimburses">
              <el-option label="审批中" value="PENDING" />
              <el-option label="已审批" value="APPROVED" />
              <el-option label="已驳回" value="REJECTED" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="openSubmit">提交报销</el-button>
            <el-button @click="typesVisible = true">费用类型维护</el-button>
          </el-form-item>
        </el-form>

        <el-table :data="reimburses" border stripe>
          <el-table-column prop="expNo" label="报销单号" width="170" />
          <el-table-column prop="employeeName" label="申请人" width="110" />
          <el-table-column label="金额" width="110" align="right">
            <template #default="{ row }">¥{{ fmt(row.totalAmount) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="reimbTag(row.status)">{{ reimbName(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="结算" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.settleStatus === 'DONE' ? 'success' : row.settleStatus === 'PENDING' ? 'warning' : 'info'">
                {{ { NONE: '未到结算', PENDING: '待补付', DONE: '已结算' }[row.settleStatus] || row.settleStatus }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="occurPeriod" label="归属期" width="90" />
          <el-table-column prop="createDate" label="提交时间" width="170" />
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button size="small" @click="openDetail(row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>

        <!-- 提交对话框 -->
        <el-dialog v-model="submitVisible" title="提交费用报销单" width="760px" top="4vh">
          <el-form label-width="90px">
            <el-form-item label="费用明细">
              <div style="width: 100%">
                <div v-for="(ln, i) in formLines" :key="i" class="line-row">
                  <el-select v-model="ln.expenseType" placeholder="费用类型" style="width: 130px">
                    <el-option v-for="t in expenseTypes" :key="t.typeCode"
                      :label="`${t.typeName}${t.status !== 'ACTIVE' ? '（停用）' : ''}`" :value="t.typeCode"
                      :disabled="t.status !== 'ACTIVE'" />
                  </el-select>
                  <el-input-number v-model="ln.amount" :precision="2" :min="0.01" placeholder="金额" style="width: 130px" />
                  <el-date-picker v-model="ln.occurDate" type="date" value-format="YYYY-MM-DD" placeholder="发生日期" style="width: 150px" />
                  <el-input v-model="ln.invoiceNo" placeholder="发票号（重复将阻断）" style="width: 180px" />
                  <el-button v-if="formLines.length > 1" link type="danger" @click="formLines.splice(i, 1)">删除</el-button>
                </div>
                <el-button link type="primary" @click="formLines.push({ expenseType: '', amount: undefined, occurDate: today(), invoiceNo: '' })">+ 加一行</el-button>
              </div>
            </el-form-item>
            <el-form-item label="备注">
              <el-input v-model="submitForm.remark" type="textarea" :rows="2" />
            </el-form-item>
          </el-form>
          <el-alert type="warning" :closable="false" show-icon
            title="提交即校验：发票号历史重复将 L1 阻断并展示原单号（C-4.6-13）；类型映射缺失 422" />
          <template #footer>
            <el-button @click="submitVisible = false">取消</el-button>
            <el-button type="primary" @click="doSubmit">提交审批</el-button>
          </template>
        </el-dialog>

        <!-- 类型维护抽屉 -->
        <el-drawer v-model="typesVisible" title="费用类型-科目映射维护" size="560px">
          <div style="margin-bottom: 10px">
            <el-button size="small" type="primary" @click="openTypeForm()">新增类型</el-button>
          </div>
          <el-table :data="expenseTypes" border size="small">
            <el-table-column prop="typeCode" label="编码" width="110" />
            <el-table-column prop="typeName" label="名称" width="120" />
            <el-table-column prop="accountCode" label="科目" width="90" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openTypeForm(row)">编辑</el-button>
                <el-button link :type="row.status === 'ACTIVE' ? 'warning' : 'success'" size="small"
                  @click="toggleType(row)">{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-form v-if="typeFormVisible" label-width="80px" style="margin-top: 14px">
            <el-form-item label="编码"><el-input v-model="typeForm.typeCode" :disabled="!!typeForm.id" /></el-form-item>
            <el-form-item label="名称"><el-input v-model="typeForm.typeName" /></el-form-item>
            <el-form-item label="费用科目"><el-input v-model="typeForm.accountCode" placeholder="如 660202（须 DR 方向）" /></el-form-item>
            <el-button type="primary" size="small" @click="saveType">保存</el-button>
          </el-form>
        </el-drawer>

        <!-- 详情抽屉 -->
        <el-drawer v-model="detailVisible" :title="`报销详情 ${detail.expNo || ''}`" size="640px">
          <template v-if="detail.id">
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="申请人">{{ detail.employeeName }}</el-descriptions-item>
              <el-descriptions-item label="总额">¥{{ fmt(detail.totalAmount) }}</el-descriptions-item>
              <el-descriptions-item label="状态">{{ reimbName(detail.status) }}</el-descriptions-item>
              <el-descriptions-item label="结算状态">{{ detail.settleStatus }}</el-descriptions-item>
            </el-descriptions>
            <el-divider content-position="left">明细行</el-divider>
            <el-table :data="detail.lines || []" border size="small">
              <el-table-column prop="expenseType" label="类型" width="90" />
              <el-table-column label="金额" width="100" align="right">
                <template #default="{ row }">¥{{ fmt(row.amount) }}</template>
              </el-table-column>
              <el-table-column prop="invoiceNo" label="发票号" width="150" />
              <el-table-column prop="occurDate" label="发生日" width="100" />
            </el-table>
            <el-divider content-position="left">借款冲销（FIFO）</el-divider>
            <el-table :data="detail.writeoffs || []" border size="small">
              <el-table-column prop="loanNo" label="借款单" width="150" />
              <el-table-column label="冲销金额" width="110" align="right">
                <template #default="{ row }">¥{{ fmt(row.amount) }}</template>
              </el-table-column>
              <el-table-column prop="writeoffDate" label="冲销日" width="100" />
            </el-table>
            <p v-if="!(detail.writeoffs || []).length" class="muted">无未核销借款，全额挂应付 2241</p>
          </template>
        </el-drawer>
      </el-tab-pane>

      <!-- ============ 8.8.2 借款管理 ============ -->
      <el-tab-pane label="借款管理" name="loan">
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
      </el-tab-pane>

      <!-- ============ 8.8.3 借款冲销 ============ -->
      <el-tab-pane label="借款冲销" name="writeoff">
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
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getBankAccountsApi } from '@/api/fin/bank-account'
import {
  submitReimburse, listReimburses, reimburseDetail, payDifference, refundLoan,
  listExpenseTypes, saveExpenseType, updateExpenseTypeStatus,
  applyLoan, listLoans, loanAgingView
} from '@/api/fin/expense-reimbursement'

const route = useRoute()
const activeTab = ref(route.meta?.tab || 'reimburse')

function today() { return new Date().toISOString().slice(0, 10) }
function fmt(v) {
  const n = Number(v)
  return isNaN(n) ? '0.00' : n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function reimbName(s) { return { PENDING: '审批中', APPROVED: '已审批', REJECTED: '已驳回' }[s] || s }
function reimbTag(s) { return { PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger' }[s] || 'info' }

// ---- 报销 ----
const reimburses = ref([])
const reimFilter = ref({ status: '' })
const submitVisible = ref(false)
const submitForm = ref({ remark: '' })
const formLines = ref([])
const expenseTypes = ref([])
const typesVisible = ref(false)
const typeFormVisible = ref(false)
const typeForm = ref({})
const detailVisible = ref(false)
const detail = ref({})

async function loadReimburses() {
  const res = await listReimburses({ status: reimFilter.value.status || undefined })
  reimburses.value = res.data || []
}
function openSubmit() {
  submitForm.value = { remark: '' }
  formLines.value = [{ expenseType: '', amount: undefined, occurDate: today(), invoiceNo: '' }]
  submitVisible.value = true
}
async function doSubmit() {
  const total = formLines.value.reduce((s, l) => s + (Number(l.amount) || 0), 0)
  if (total <= 0) { ElMessage.warning('明细金额合计须大于 0'); return }
  await submitReimburse({ totalAmount: total, remark: submitForm.value.remark, lines: formLines.value })
  ElMessage.success('已提交审批（部门主管 → 财务审核）')
  submitVisible.value = false
  loadReimburses()
}
async function loadTypes() {
  const res = await listExpenseTypes()
  expenseTypes.value = res.data || []
}
function openTypeForm(row) {
  typeForm.value = row ? { ...row } : { typeCode: '', typeName: '', accountCode: '' }
  typeFormVisible.value = true
}
async function saveType() {
  await saveExpenseType(typeForm.value)
  ElMessage.success('映射已保存')
  typeFormVisible.value = false
  loadTypes()
}
async function toggleType(row) {
  await updateExpenseTypeStatus(row.id, row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE')
  loadTypes()
}
async function openDetail(row) {
  const res = await reimburseDetail(row.id)
  detail.value = res.data || {}
  detailVisible.value = true
}

// ---- 借款 ----
const loans = ref([])
const aging = ref([])
const loanFormVisible = ref(false)
const loanForm = ref({ amount: undefined, purpose: '', expectedDate: '' })

async function loadLoans() {
  const res = await listLoans({})
  loans.value = res.data || []
  const a = await loanAgingView()
  aging.value = a.data || []
}
async function doApplyLoan() {
  await applyLoan(loanForm.value)
  ElMessage.success('借款申请已提交（超期将自动加签财务主管）')
  loanFormVisible.value = false
  loadLoans()
}

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
  loadReimburses()
  loadTypes()
  loadLoans()
  loadApproved()
  loadAccounts()
})
</script>

<style scoped>
.page { padding: 12px }
.hint { margin-bottom: 12px }
.filter { margin-bottom: 4px }
.mb { margin-bottom: 14px }
.muted { color: #909399; font-size: 12px }
.line-row { display: flex; gap: 8px; margin-bottom: 8px; align-items: center }
</style>
