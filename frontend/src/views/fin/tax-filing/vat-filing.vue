<template>
  <div class="page">
    <div class="head">
      <span class="title">增值税申报（汇总 → 勾稽 C-4.14-04 → 生成 → 三级审批 → 缴款 → 归档）</span>
      <div>
        <el-input v-model="period" placeholder="所属期 yyyyMM" style="width: 130px; margin-right: 8px" size="small" />
        <el-button size="small" @click="load">查询</el-button>
        <el-button type="primary" size="small" @click="doSummarize">① 汇总</el-button>
        <el-button type="warning" size="small" @click="doReconcile">② 勾稽校验</el-button>
        <el-button type="success" size="small" @click="doGenerate">③ 生成申报表</el-button>
        <el-button size="small" @click="ldtiVisible = true">留抵退税申请</el-button>
      </div>
    </div>

    <el-alert v-if="reconcileResult" :type="reconcileResult.passed ? 'success' : 'error'" :closable="false" class="mb8"
      :title="reconcileResult.passed ? '勾稽通过：申报数与账载数一致' : `勾稽未通过（C-4.14-04）：差异合计 ${reconcileResult.diffTotal}`">
      <div v-if="!reconcileResult.passed">
        <el-table :data="reconcileResult.rows" size="small" border>
          <el-table-column prop="line" label="栏次" />
          <el-table-column prop="declared" label="申报数" width="120" />
          <el-table-column prop="gl" label="账载数" width="120" />
          <el-table-column prop="diff" label="差额" width="120" />
        </el-table>
      </div>
      <div v-if="reconcileResult.pendingVoucherCount > 0">提示：另有 {{ reconcileResult.pendingVoucherCount }} 张涉税科目凭证待过账</div>
    </el-alert>

    <el-alert v-if="summary" type="info" :closable="false" class="mb8"
      :title="`汇总（${period}）：销项 ${summary.outputTax} = 蓝票 ${summary.blueTax} − 红票 ${summary.redTax}；进项 ${summary.inputTax}；转出 ${summary.transferOut}；上期留抵 ${summary.prevCredit}；异常票 ${summary.abnormalInvoices} 张`" />

    <el-table :data="rows" size="small" border>
      <el-table-column prop="returnNo" label="申报单号" width="180" />
      <el-table-column prop="period" label="所属期" width="80" />
      <el-table-column label="税种" width="90">
        <template #default="{ row }">{{ row.taxType === 'VAT' ? '增值税' : '所得税预缴' }}</template>
      </el-table-column>
      <el-table-column prop="payableTax" label="应纳税额" width="100" />
      <el-table-column prop="endCredit" label="期末留抵" width="90" />
      <el-table-column label="勾稽" width="80">
        <template #default="{ row }">
          <el-tag :type="row.reconStatus === 'PASSED' ? 'success' : row.reconStatus === 'BLOCKED' ? 'danger' : 'info'" size="small">
            {{ { PASSED: '通过', BLOCKED: '受阻', PENDING: '待校验' }[row.reconStatus] || row.reconStatus }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="receiptNo" label="回执" width="130" />
      <el-table-column prop="lockFlag" label="锁定" width="60">
        <template #default="{ row }">{{ row.lockFlag === '1' ? '🔒' : '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="280">
        <template #default="{ row }">
          <el-button v-if="row.status === 'DRAFT'" link type="primary" size="small" @click="doSubmit(row)">提交审批</el-button>
          <el-button v-if="row.lockFlag === '1' && !row.receiptNo" link type="warning" size="small" @click="openRegister(row)">登记回执</el-button>
          <el-button v-if="row.status === 'FILED'" link type="success" size="small" @click="openPay(row)">登记缴款</el-button>
          <el-button v-if="['DRAFT','PENDING_APPROVAL'].includes(row.status)" link type="danger" size="small" @click="openOverdue(row)">逾期事件</el-button>
          <el-button v-if="row.lockFlag === '1'" link type="danger" size="small" @click="openAdjust(row)">跨期调整</el-button>
          <el-button link type="info" size="small" @click="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 缴款回执登记 -->
    <el-dialog v-model="registerVisible" title="申报提交与回执登记（离线留桩）" width="480px">
      <el-form label-width="100px" size="small">
        <el-form-item label="提交日期"><el-date-picker v-model="registerForm.submitDate" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="回执编号"><el-input v-model="registerForm.receiptNo" /></el-form-item>
        <el-form-item label="申报成功日"><el-date-picker v-model="registerForm.fileDate" value-format="YYYY-MM-DD" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerVisible = false">取消</el-button>
        <el-button type="primary" @click="doRegister">登记</el-button>
      </template>
    </el-dialog>

    <!-- 缴款登记 -->
    <el-dialog v-model="payVisible" title="缴款登记" width="440px">
      <el-form label-width="90px" size="small">
        <el-form-item label="缴款日期"><el-date-picker v-model="payForm.payDate" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="缴款金额"><el-input-number v-model="payForm.payAmount" :min="0" :precision="2" /></el-form-item>
        <el-form-item label="缴款凭证号"><el-input v-model="payForm.payVoucherNo" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="payVisible = false">取消</el-button>
        <el-button type="primary" @click="doPay">登记</el-button>
      </template>
    </el-dialog>

    <!-- 逾期事件 -->
    <el-dialog v-model="overdueVisible" title="逾期申报事件单（滞纳金估算 + 升级财务经理）" width="480px">
      <el-form label-width="90px" size="small">
        <el-form-item label="故障说明" required>
          <el-input v-model="overdueForm.faultNote" type="textarea" :rows="3"
            placeholder="系统/电子税务局通道故障起止时间与影响范围（证据留痕）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="overdueVisible = false">取消</el-button>
        <el-button type="danger" @click="doOverdue">生成事件单</el-button>
      </template>
    </el-dialog>

    <!-- 跨期调整 -->
    <el-dialog v-model="adjustVisible" title="跨期调整（BR-4.14-31：快照 + 二级审批）" width="520px">
      <el-form label-width="110px" size="small">
        <el-form-item label="调整后销项"><el-input-number v-model="adjustForm.outputTax" :precision="2" /></el-form-item>
        <el-form-item label="调整后进项"><el-input-number v-model="adjustForm.inputTax" :precision="2" /></el-form-item>
        <el-form-item label="调整后转出"><el-input-number v-model="adjustForm.transferOut" :precision="2" /></el-form-item>
        <el-form-item label="调整原因" required>
          <el-input v-model="adjustForm.reason" type="textarea" :rows="2" placeholder="含是否需更正申报评估结论" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjustVisible = false">取消</el-button>
        <el-button type="primary" @click="doAdjust">提交调整</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-drawer v-model="detailVisible" :title="`申报详情 ${detailRow ? detailRow.returnNo : ''}`" size="560px">
      <template v-if="detail">
        <el-descriptions :column="2" size="small" border>
          <el-descriptions-item label="销项税额">{{ detail.return.outputTax }}</el-descriptions-item>
          <el-descriptions-item label="进项税额">{{ detail.return.inputTax }}</el-descriptions-item>
          <el-descriptions-item label="进项转出">{{ detail.return.transferOut }}</el-descriptions-item>
          <el-descriptions-item label="上期留抵">{{ detail.return.prevCredit }}</el-descriptions-item>
          <el-descriptions-item label="应纳税额">{{ detail.return.payableTax }}</el-descriptions-item>
          <el-descriptions-item label="期末留抵">{{ detail.return.endCredit }}</el-descriptions-item>
          <el-descriptions-item label="回执编号">{{ detail.return.receiptNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="缴款">{{ detail.return.payAmount || '—' }}（{{ detail.return.payDate || '' }}）</el-descriptions-item>
        </el-descriptions>
        <el-divider content-position="left">栏次明细（BR-4.14-30 差异定位基础）</el-divider>
        <el-table :data="detail.lines" size="small" border max-height="300">
          <el-table-column prop="lineCode" label="栏次编码" width="150" />
          <el-table-column prop="lineName" label="栏次" width="140" />
          <el-table-column prop="amount" label="金额" width="100" />
          <el-table-column prop="sourceDesc" label="来源" show-overflow-tooltip />
        </el-table>
        <template v-if="detail.approval && detail.approval.tasks">
          <el-divider content-position="left">三级审批轨迹（FR-4.14-5-5）</el-divider>
          <el-timeline size="small">
            <el-timeline-item v-for="t in detail.approval.tasks" :key="t.id"
              :type="t.status === 'PASSED' ? 'success' : t.status === 'REJECTED' ? 'danger' : 'primary'"
              :timestamp="t.opTime || ''">
              {{ t.nodeName }}（{{ t.signerName || '待签' }}）{{ t.opinion ? `：${t.opinion}` : '' }}
            </el-timeline-item>
          </el-timeline>
        </template>
      </template>
    </el-drawer>

    <!-- 留抵退税申请（BR-4.14-33 / S-4.14-15） -->
    <el-dialog v-model="ldtiVisible" title="留抵退税申请（BR-4.14-33 四条件校验）" width="560px">
      <el-alert type="info" :closable="false" class="mb8"
        title="允许退还 = 增量留抵 × 进项构成比例 × 退还比例；任一条件不满足将阻断并列出未满足项" />
      <el-form label-width="160px" size="small">
        <el-form-item label="所属期 yyyyMM"><el-input v-model="ldtiForm.period" placeholder="209906" style="width: 140px" /></el-form-item>
        <el-form-item label="纳税信用等级">
          <el-select v-model="ldtiForm.creditLevel" style="width: 140px">
            <el-option v-for="l in ['A','B','M','C','D']" :key="l" :label="l + ' 级'" :value="l" />
          </el-select>
        </el-form-item>
        <el-form-item label="增量留抵税额"><el-input-number v-model="ldtiForm.incrCredit" :precision="2" :step="100000" /></el-form-item>
        <el-form-item label="进项构成比例"><el-input-number v-model="ldtiForm.constitutionRatio" :min="0" :max="1" :step="0.01" :precision="4" /></el-form-item>
        <el-form-item label="第 6 月增量留抵"><el-input-number v-model="ldtiForm.month6Incr" :precision="2" :step="100000" /></el-form-item>
        <el-form-item label="6 个月增量合计"><el-input-number v-model="ldtiForm.sixMonthTotal" :precision="2" :step="100000" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ldtiVisible = false">取消</el-button>
        <el-button type="primary" @click="doLdti">提交申请</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  taxSummarize, taxReconcile, taxGenerate, taxSubmit, taxRegister,
  taxMarkOverdue, taxAdjust, taxPage, taxDetail, ldtiApply
} from '@/api/fin/tax-filing'

const period = ref(new Date().toISOString().slice(0, 7).replace('-', ''))
const rows = ref([])
const summary = ref(null)
const reconcileResult = ref(null)
const registerVisible = ref(false)
const registerRow = ref(null)
const registerForm = ref({})
const payVisible = ref(false)
const payRow = ref(null)
const payForm = ref({})
const overdueVisible = ref(false)
const overdueRow = ref(null)
const overdueForm = ref({})
const adjustVisible = ref(false)
const adjustRow = ref(null)
const adjustForm = ref({})
const detailVisible = ref(false)
const detailRow = ref(null)
const detail = ref(null)
const ldtiVisible = ref(false)
const ldtiForm = ref({ creditLevel: 'A' })

async function load() {
  try {
    const { data } = await taxPage({ size: 200, period: period.value, taxType: 'VAT' })
    rows.value = data.records || data
  } catch (e) { /* ignore */ }
}

async function doSummarize() {
  try {
    const { data } = await taxSummarize(period.value)
    summary.value = data
    ElMessage.success('汇总完成')
  } catch (e) { /* 422 已提示 */ }
}

async function doReconcile() {
  try {
    const { data } = await taxReconcile(period.value)
    reconcileResult.value = data
  } catch (e) { /* ignore */ }
}

async function doGenerate() {
  try {
    const { data } = await taxGenerate(period.value)
    ElMessage.success(`申报表已生成：${data.returnNo}（应纳 ${data.payableTax}${Number(data.endCredit) > 0 ? `，留抵结转 ${data.endCredit}` : ''}）`)
    reconcileResult.value = null
    load()
  } catch (e) { /* 422 差异报告已提示 */ }
}

async function doSubmit(row) {
  try {
    const { data } = await taxSubmit(row.id)
    ElMessage.success(`已提交三级审批：${data.apprNo}`)
    load()
  } catch (e) { /* ignore */ }
}

function openRegister(row) {
  registerRow.value = row
  registerForm.value = { submitDate: '', receiptNo: '', fileDate: '' }
  registerVisible.value = true
}

async function doRegister() {
  try {
    await taxRegister(registerRow.value.id, registerForm.value)
    ElMessage.success('登记成功')
    registerVisible.value = false
    load()
  } catch (e) { /* ignore */ }
}

function openPay(row) {
  payRow.value = row
  payForm.value = { payDate: '', payAmount: row.payableTax, payVoucherNo: '' }
  payVisible.value = true
}

async function doPay() {
  try {
    await taxRegister(payRow.value.id, payForm.value)
    ElMessage.success('缴款登记完成')
    payVisible.value = false
    load()
  } catch (e) { /* ignore */ }
}

function openOverdue(row) {
  overdueRow.value = row
  overdueForm.value = { faultNote: '' }
  overdueVisible.value = true
}

async function doOverdue() {
  try {
    const { data } = await taxMarkOverdue(overdueRow.value.id, overdueForm.value.faultNote)
    ElMessage.warning(`逾期事件单已生成：滞纳 ${data.overdueDays} 天，估算滞纳金 ${data.lateFee}`)
    overdueVisible.value = false
    load()
  } catch (e) { /* ignore */ }
}

function openAdjust(row) {
  adjustRow.value = row
  adjustForm.value = { outputTax: row.outputTax, inputTax: row.inputTax, transferOut: row.transferOut, reason: '' }
  adjustVisible.value = true
}

async function doAdjust() {
  try {
    const { data } = await taxAdjust(adjustRow.value.id, adjustForm.value)
    ElMessage.success(`跨期调整已提交：${data.adjNo}（二级审批）`)
    adjustVisible.value = false
  } catch (e) { /* ignore */ }
}

async function openDetail(row) {
  detailRow.value = row
  detail.value = null
  detailVisible.value = true
  try {
    const { data } = await taxDetail(row.id)
    detail.value = data
  } catch (e) { /* ignore */ }
}

async function doLdti() {
  try {
    const { data } = await ldtiApply(ldtiForm.value)
    ElMessage.success(`留抵退税申请已创建：${data.applyNo}（允许退还 ${data.allowedRefund}，请提交 L2 审批）`)
    ldtiVisible.value = false
  } catch (e) { /* 422 四条件未满足项已提示 */ }
}

function statusText(s) {
  return { DRAFT: '草稿', PENDING_APPROVAL: '审批中', APPROVED: '已审批', SUBMITTED: '已提交',
    FILED: '已申报', PAID: '已缴款', REJECTED: '已驳回', OVERDUE: '逾期' }[s] || s
}
function statusTag(s) {
  return { DRAFT: 'info', PENDING_APPROVAL: 'warning', APPROVED: 'warning', SUBMITTED: 'primary',
    FILED: 'primary', PAID: 'success', REJECTED: 'danger', OVERDUE: 'danger' }[s] || 'info'
}

onMounted(load)
</script>

<style scoped>
.page { padding: 12px; }
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.title { font-size: 15px; font-weight: 600; }
.mb8 { margin-bottom: 8px; }
</style>
