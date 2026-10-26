<template>
  <div class="page">
    <div class="head">
      <span class="title">发票作废（三条件：当月开具 + 未交付 + 购方未认证 → 审批 → 执行作废）</span>
      <div>
        <el-select v-model="query.status" placeholder="状态" clearable style="width: 130px; margin-right: 8px" size="small">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已作废" value="VOIDED" />
          <el-option label="已驳回" value="REJECTED" />
        </el-select>
        <el-input v-model="query.keyword" placeholder="申请单号/发票号/客户" clearable style="width: 170px; margin-right: 8px" size="small" />
        <el-button size="small" @click="load">查询</el-button>
        <el-button type="primary" size="small" @click="openApply">发起作废申请</el-button>
      </div>
    </div>

    <el-alert type="info" :closable="false" class="mb8"
      title="口径说明：「购方未认证」以系内「客户未确认该发票」为代理判定；跨月或已交付的发票不可作废，请发起红冲（BR-4.14-24）" />

    <el-table :data="rows" size="small" border>
      <el-table-column prop="applyNo" label="作废申请单号" width="170" />
      <el-table-column prop="invoiceNo" label="发票号" width="140" />
      <el-table-column prop="customerName" label="客户" min-width="130" show-overflow-tooltip />
      <el-table-column prop="amount" label="发票金额" width="100" />
      <el-table-column prop="reason" label="作废原因" min-width="150" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button link type="info" size="small" @click="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 发起作废申请 -->
    <el-dialog v-model="applyVisible" title="发票作废申请（BR-4.14-24 三条件任一不满足 → 阻断引导红冲）" width="560px">
      <el-form label-width="90px" size="small">
        <el-form-item label="发票" required>
          <el-select v-model="applyForm.invoiceId" filterable placeholder="选择销项发票" style="width: 100%">
            <el-option v-for="inv in invoiceOptions" :key="inv.id"
              :label="`${inv.invoiceNo}｜${inv.customerName || ''}｜${inv.amount}｜${inv.invoiceDate || ''}`"
              :value="inv.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="作废原因" required>
          <el-input v-model="applyForm.reason" type="textarea" :rows="2" placeholder="开票信息有误当月作废…" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="applyVisible = false">取消</el-button>
        <el-button type="primary" @click="doApply">提交申请并送审</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-drawer v-model="detailVisible" :title="`作废申请详情 ${detailRow ? detailRow.applyNo : ''}`" size="480px">
      <template v-if="detail">
        <el-descriptions :column="1" size="small" border>
          <el-descriptions-item label="状态">{{ statusText(detail.apply.status) }}</el-descriptions-item>
          <el-descriptions-item label="发票号">{{ detail.apply.invoiceNo }}</el-descriptions-item>
          <el-descriptions-item label="客户">{{ detail.apply.customerName }}</el-descriptions-item>
          <el-descriptions-item label="金额">{{ detail.apply.amount }}</el-descriptions-item>
          <el-descriptions-item label="原因">{{ detail.apply.reason }}</el-descriptions-item>
          <el-descriptions-item label="发票状态">{{ detail.invoice ? detail.invoice.status : '—' }}</el-descriptions-item>
        </el-descriptions>
        <template v-if="detail.approval && detail.approval.tasks">
          <el-divider content-position="left">审批轨迹（C-4.14-03 留痕）</el-divider>
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
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { applyVoid, voidPage, voidDetail, salesInvoiceLedger } from '@/api/fin/red-invoice-void'

const query = ref({ status: '', keyword: '' })
const rows = ref([])
const invoiceOptions = ref([])
const applyVisible = ref(false)
const applyForm = ref({})
const detailVisible = ref(false)
const detailRow = ref(null)
const detail = ref(null)

async function load() {
  try {
    const params = { size: 200 }
    if (query.value.status) params.status = query.value.status
    if (query.value.keyword) params.keyword = query.value.keyword
    const { data } = await voidPage(params)
    rows.value = data.records || data
  } catch (e) { /* ignore */ }
}

async function openApply() {
  applyForm.value = { invoiceId: '', reason: '' }
  try {
    const { data } = await salesInvoiceLedger({ size: 200 })
    invoiceOptions.value = (data.records || data || []).filter(inv =>
      !['VOID', 'RED_FULL'].includes(inv.status))
  } catch (e) {
    invoiceOptions.value = []
  }
  applyVisible.value = true
}

async function doApply() {
  try {
    const { data } = await applyVoid(applyForm.value)
    ElMessage.success(`作废申请已提交：${data.applyNo}（审批通过后发票置 VOID）`)
    applyVisible.value = false
    load()
  } catch (e) { /* 422 已提示（三条件未满足项全量列出） */ }
}

async function openDetail(row) {
  detailRow.value = row
  detail.value = null
  detailVisible.value = true
  try {
    const { data } = await voidDetail(row.id)
    detail.value = data
  } catch (e) { /* ignore */ }
}

function statusText(s) {
  return { DRAFT: '审批中', APPROVED: '审批通过', VOIDED: '已作废', REJECTED: '已驳回' }[s] || s
}
function statusTag(s) {
  return { DRAFT: 'warning', APPROVED: 'warning', VOIDED: 'danger', REJECTED: 'info' }[s] || 'info'
}

onMounted(load)
</script>

<style scoped>
.page { padding: 12px; }
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.title { font-size: 15px; font-weight: 600; }
.mb8 { margin-bottom: 8px; }
</style>
