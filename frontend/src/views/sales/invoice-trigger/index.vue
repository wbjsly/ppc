<template>
  <div class="page-wrap">
    <el-alert type="info" :closable="false" show-icon class="mb12"
              title="3.8.1 开票触发"
              description="发货确认后自动生成开票申请（金额 = 该次发货额，与发货单一一对应）并同步生成应收。链路：调整金额 → 提交（税务资质 C-4.3-06 L1 + 容差校验）→ 财务审核 → 开具（外部开票系统桩，回写应收与 SO）→ 客户确认 / 异议 → 红字发票。" />

    <el-card shadow="never" class="mb12">
      <template #header>
        <div class="card-head">
          <span>开票申请列表</span>
          <div>
            <el-select v-model="filterStatus" size="small" style="width:150px" @change="load">
              <el-option label="待提交 DRAFT" value="DRAFT" />
              <el-option label="待审核 AUDITING" value="AUDITING" />
              <el-option label="审核通过 APPROVED" value="APPROVED" />
              <el-option label="已退回 RETURNED" value="RETURNED" />
              <el-option label="已开具 ISSUED" value="ISSUED" />
              <el-option label="客户确认 CONFIRMED" value="CONFIRMED" />
              <el-option label="异议 DISPUTED" value="DISPUTED" />
              <el-option label="已红冲 RED" value="RED" />
              <el-option label="全部" value="" />
            </el-select>
            <el-input v-model="keyword" size="small" style="width:180px;margin-left:8px"
                      placeholder="申请单/发货单/客户" clearable @keyup.enter="load" />
            <el-button size="small" style="margin-left:8px" @click="load">查询</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" size="small" border v-loading="loading">
        <el-table-column prop="applyNo" label="申请单号" width="165" />
        <el-table-column prop="shipNo" label="发货单" width="160" />
        <el-table-column prop="soNo" label="SO" width="155" show-overflow-tooltip />
        <el-table-column prop="customerName" label="客户" min-width="130" show-overflow-tooltip />
        <el-table-column label="发货额" width="105" align="right">
          <template #default="{ row }">¥{{ fmt(row.shipAmount) }}</template>
        </el-table-column>
        <el-table-column label="申请额" width="105" align="right">
          <template #default="{ row }">
            <span :style="{ color: diffColor(row) }">¥{{ fmt(row.applyAmount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="税务资质" width="96">
          <template #default="{ row }">
            <el-tag size="small" :type="row.taxQualified === '1' ? 'success' : 'danger'">
              {{ row.taxQualified === '1' ? '通过' : '未校验/未过' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="发票号" width="150">
          <template #default="{ row }">{{ row.invoiceNo || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ statusName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="330" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link @click="openDetail(row)">详情</el-button>
            <template v-if="row.status === 'DRAFT' || row.status === 'RETURNED'">
              <el-button size="small" link type="warning" @click="openAmount(row)">调金额</el-button>
              <el-button size="small" link type="primary" @click="doSubmit(row)">提交</el-button>
            </template>
            <template v-if="row.status === 'AUDITING'">
              <el-button size="small" link type="success" @click="doAudit(row, true)">通过</el-button>
              <el-button size="small" link type="danger" @click="doAudit(row, false)">退回</el-button>
            </template>
            <el-button v-if="row.status === 'APPROVED'" size="small" link type="success"
                       @click="doIssue(row)">开具</el-button>
            <template v-if="row.status === 'ISSUED' || row.status === 'DISPUTED'">
              <el-button size="small" link type="success" @click="doConfirm(row)">客户确认</el-button>
              <el-button size="small" link type="danger" @click="openDispute(row)">异议</el-button>
            </template>
            <el-button v-if="row.invoiceId" size="small" link type="danger"
                       @click="openRed(row)">红字</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 调金额 -->
    <el-dialog v-model="amountDialog" :title="`调整开票金额 ${current?.applyNo}`" width="460px">
      <el-form label-width="110px" size="small">
        <el-form-item label="发货金额">
          <span>¥{{ fmt(current?.shipAmount) }}（基准，容差内可调整）</span>
        </el-form-item>
        <el-form-item label="申请开票金额">
          <el-input-number v-model="amountForm.applyAmount" :min="0" :precision="2"
                           style="width:200px" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="amountForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <p class="hint">提交时校验 |申请额 − 发货额| / 发货额 ≤ TOLERANCE_DEFAULT（0.5%），超限阻断（C-0-02）。</p>
      <template #footer>
        <el-button @click="amountDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="doAmount">保存</el-button>
      </template>
    </el-dialog>

    <!-- 异议 -->
    <el-dialog v-model="disputeDialog" :title="`客户异议 ${current?.applyNo}`" width="480px">
      <el-input v-model="disputeNote" type="textarea" :rows="3"
                placeholder="异议说明（必填，如：金额与合同不符 / 抬头信息错误）" />
      <p class="hint">异议后可协商处理或对已开发票发起红字冲销。</p>
      <template #footer>
        <el-button @click="disputeDialog = false">取消</el-button>
        <el-button type="danger" :disabled="!disputeNote" :loading="submitting"
                   @click="doDispute">登记异议</el-button>
      </template>
    </el-dialog>

    <!-- 红字发票 -->
    <el-dialog v-model="redDialog" :title="`红字发票（原票 ${current?.invoiceNo}）`" width="500px">
      <el-form label-width="110px" size="small">
        <el-form-item label="红冲金额">
          <el-input-number v-model="redForm.amount" :min="0" :precision="2" style="width:200px" />
        </el-form-item>
        <el-form-item label="退货单号">
          <el-input v-model="redForm.returnNo" placeholder="可空（关联退货单时填写）" />
        </el-form-item>
        <el-form-item label="红冲原因">
          <el-input v-model="redForm.reason" type="textarea" :rows="2" placeholder="必填" />
        </el-form-item>
      </el-form>
      <p class="hint">红字发票关联原票与退货单，同步冲减原应收余额（余额 = 金额 − 红冲 − 已核销）。</p>
      <template #footer>
        <el-button @click="redDialog = false">取消</el-button>
        <el-button type="danger" :disabled="!redForm.reason" :loading="submitting"
                   @click="doRed">开具红字</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-dialog v-model="detailVisible" :title="`开票申请 ${detail?.apply?.applyNo}`" width="880px">
      <template v-if="detail">
        <el-alert v-if="detail.taxCheck && !detail.taxCheck.pass" type="error" :closable="false"
                  show-icon class="mb12" :title="`C-4.3-06 阻断：${detail.taxCheck.reason}`" />
        <el-descriptions :column="3" size="small" border class="mb12">
          <el-descriptions-item label="状态">{{ statusName(detail.apply.status) }}</el-descriptions-item>
          <el-descriptions-item label="发货单">{{ detail.apply.shipNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="SO">{{ detail.apply.soNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="发货额">¥{{ fmt(detail.apply.shipAmount) }}</el-descriptions-item>
          <el-descriptions-item label="申请额">¥{{ fmt(detail.apply.applyAmount) }}</el-descriptions-item>
          <el-descriptions-item label="税码/税率">
            {{ detail.apply.taxCode || '—' }} / {{ pct(detail.apply.taxRate) }}
          </el-descriptions-item>
          <el-descriptions-item label="不含税额">¥{{ fmt(detail.apply.netAmount) }}</el-descriptions-item>
          <el-descriptions-item label="税额">¥{{ fmt(detail.apply.taxAmount) }}</el-descriptions-item>
          <el-descriptions-item label="发票号">{{ detail.apply.invoiceNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="应收单">{{ detail.apply.arNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="审核人">{{ detail.apply.auditBy || '—' }}</el-descriptions-item>
          <el-descriptions-item label="审核意见" :span="3">
            {{ detail.apply.auditOpinion || '—' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.apply.disputeNote" label="异议说明" :span="3">
            {{ detail.apply.disputeNote }}
          </el-descriptions-item>
        </el-descriptions>
        <el-table :data="detail.shipLines" size="small" border class="mb12">
          <el-table-column prop="lineNo" label="#" width="50" />
          <el-table-column prop="itemCode" label="SKU" width="140" />
          <el-table-column prop="itemName" label="名称" min-width="150" show-overflow-tooltip />
          <el-table-column prop="qty" label="数量" width="90" align="right" />
          <el-table-column label="单价" width="100" align="right">
            <template #default="{ row }">{{ Number(row.unitPrice || 0).toFixed(4) }}</template>
          </el-table-column>
          <el-table-column label="金额" width="110" align="right">
            <template #default="{ row }">¥{{ fmt(row.amount) }}</template>
          </el-table-column>
        </el-table>
        <template v-if="detail.ar">
          <h4>应收与明细</h4>
          <el-descriptions :column="3" size="small" border class="mb12">
            <el-descriptions-item label="应收单">{{ detail.ar.arNo }}</el-descriptions-item>
            <el-descriptions-item label="开票日">{{ detail.ar.invoiceDate }}</el-descriptions-item>
            <el-descriptions-item label="到期日">{{ detail.ar.dueDate }}</el-descriptions-item>
            <el-descriptions-item label="应收金额">¥{{ fmt(detail.ar.amount) }}</el-descriptions-item>
            <el-descriptions-item label="已核销">¥{{ fmt(detail.ar.paidAmount) }}</el-descriptions-item>
            <el-descriptions-item label="状态">{{ detail.ar.status }}</el-descriptions-item>
          </el-descriptions>
          <el-table :data="detail.arItems" size="small" border>
            <el-table-column prop="lineNo" label="#" width="50" />
            <el-table-column prop="itemCode" label="SKU" width="140" />
            <el-table-column prop="qty" label="数量" width="90" align="right" />
            <el-table-column label="金额" width="110" align="right">
              <template #default="{ row }">¥{{ fmt(row.amount) }}</template>
            </el-table-column>
            <el-table-column label="税率" width="80">
              <template #default="{ row }">{{ pct(row.taxRate) }}</template>
            </el-table-column>
            <el-table-column label="税额" width="100" align="right">
              <template #default="{ row }">¥{{ fmt(row.taxAmount) }}</template>
            </el-table-column>
          </el-table>
        </template>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getApplyPageApi, getApplyDetailApi, updateApplyAmountApi, submitApplyApi,
  auditApplyApi, issueApplyApi, confirmApplyApi, disputeApplyApi, redInvoiceApi
} from '@/api/fin/ar'

const rows = ref([])
const loading = ref(false)
const filterStatus = ref('')
const keyword = ref('')
const current = ref(null)
const detail = ref(null)
const detailVisible = ref(false)
const amountDialog = ref(false)
const disputeDialog = ref(false)
const redDialog = ref(false)
const submitting = ref(false)
const disputeNote = ref('')
const amountForm = ref({ applyAmount: 0, remark: '' })
const redForm = ref({ amount: 0, returnNo: '', reason: '' })

function statusName(s) {
  return { DRAFT: '待提交', AUDITING: '待审核', APPROVED: '审核通过', RETURNED: '已退回',
    ISSUED: '已开具', CONFIRMED: '客户确认', DISPUTED: '异议中', RED: '已红冲',
    CANCELLED: '已取消' }[s] || s
}
function statusTag(s) {
  return { DRAFT: 'info', AUDITING: 'warning', APPROVED: 'primary', RETURNED: 'danger',
    ISSUED: 'success', CONFIRMED: 'success', DISPUTED: 'danger', RED: 'danger' }[s] || ''
}
function fmt(v) { return Number(v || 0).toFixed(2) }
function pct(v) { return v == null ? '—' : (Number(v) * 100).toFixed(2) + '%' }
function diffColor(row) {
  if (!row.shipAmount || Number(row.shipAmount) === 0) return ''
  const d = Math.abs(Number(row.applyAmount) - Number(row.shipAmount)) / Number(row.shipAmount)
  return d > 0.005 ? '#f56c6c' : ''
}

async function load() {
  loading.value = true
  try {
    const res = await getApplyPageApi({
      current: 1, size: 100,
      status: filterStatus.value || undefined,
      keyword: keyword.value || undefined
    })
    rows.value = res.data.records || []
  } finally {
    loading.value = false
  }
}

async function openDetail(row) {
  current.value = row
  const res = await getApplyDetailApi(row.id)
  detail.value = res.data
  detailVisible.value = true
}

function openAmount(row) {
  current.value = row
  amountForm.value = { applyAmount: Number(row.applyAmount), remark: row.remark || '' }
  amountDialog.value = true
}

async function doAmount() {
  submitting.value = true
  try {
    await updateApplyAmountApi(current.value.id, amountForm.value)
    ElMessage.success('开票金额已保存（提交时按容差校验）')
    amountDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    submitting.value = false
  }
}

async function doSubmit(row) {
  try {
    await submitApplyApi(row.id)
    ElMessage.success('已提交财务审核')
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '提交失败')
  }
}

async function doAudit(row, pass) {
  let opinion = ''
  if (!pass) {
    try {
      const r = await ElMessageBox.prompt('退回修改请填写审核意见', '开票审核',
        { inputPattern: /\S+/, inputErrorMessage: '意见必填' })
      opinion = r.value
    } catch (e) { return }
  }
  try {
    await auditApplyApi(row.id, pass, opinion)
    ElMessage.success(pass ? '审核通过，可开具发票' : '已退回修改')
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '审核失败')
  }
}

async function doIssue(row) {
  try {
    await ElMessageBox.confirm(
      `确认开具发票？金额 ¥${fmt(row.applyAmount)}（外部开票系统按桩口径，开具后回写应收与 SO）`,
      '发票开具', { type: 'warning' })
  } catch (e) { return }
  try {
    const res = await issueApplyApi(row.id)
    ElMessage.success(`已开具 ${res.data.invoice.invoiceNo}，已回写应收与 SO`)
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '开具失败')
  }
}

async function doConfirm(row) {
  try {
    await confirmApplyApi(row.id)
    ElMessage.success('已记录客户确认')
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '确认失败')
  }
}

function openDispute(row) {
  current.value = row
  disputeNote.value = ''
  disputeDialog.value = true
}

async function doDispute() {
  submitting.value = true
  try {
    await disputeApplyApi(current.value.id, disputeNote.value)
    ElMessage.success('已登记客户异议')
    disputeDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '登记失败')
  } finally {
    submitting.value = false
  }
}

function openRed(row) {
  current.value = row
  redForm.value = { amount: Number(row.applyAmount), returnNo: '', reason: '' }
  redDialog.value = true
}

async function doRed() {
  submitting.value = true
  try {
    await redInvoiceApi({
      originInvoiceId: current.value.invoiceId,
      returnNo: redForm.value.returnNo || undefined,
      amount: redForm.value.amount,
      reason: redForm.value.reason
    })
    ElMessage.success('红字发票已开具，原应收余额已冲减')
    redDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '红冲失败')
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; font-weight: bold; }
.hint { color: #909399; font-size: 12px; margin-top: 8px; }
.mb12 { margin-bottom: 12px; }
.muted { color: #909399; }
</style>
