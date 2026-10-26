<template>
  <div class="page">
    <div class="head">
      <span class="title">红字冲销（申请 → 分级审批 → 红字信息单 → 编号门禁 → 开红票）</span>
      <div>
        <el-select v-model="query.status" placeholder="状态" clearable style="width: 130px; margin-right: 8px" size="small">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="审批中/通过" value="APPROVED" />
          <el-option label="信息单已生成" value="INFO_ISSUED" />
          <el-option label="已开票" value="ISSUED" />
          <el-option label="已驳回" value="REJECTED" />
        </el-select>
        <el-input v-model="query.keyword" placeholder="申请单号/原票号/客户" clearable style="width: 170px; margin-right: 8px" size="small" />
        <el-button size="small" @click="load">查询</el-button>
        <el-button type="primary" size="small" @click="openCreate">发起红冲申请</el-button>
      </div>
    </div>

    <el-alert type="info" :closable="false" class="mb8"
      title="口径说明：购方认证抵扣以系内「该客户存在已确认发票」为代理判定（无税局接口），真实抵扣状态线下把关" />

    <el-table :data="rows" size="small" border>
      <el-table-column prop="applyNo" label="申请单号" width="170" />
      <el-table-column prop="originInvoiceNo" label="原蓝票号" width="140" />
      <el-table-column prop="customerName" label="客户" min-width="130" show-overflow-tooltip />
      <el-table-column prop="amount" label="红冲金额" width="100" />
      <el-table-column label="原因" width="90">
        <template #default="{ row }">{{ reasonText(row.reasonType) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="跨期" width="70">
        <template #default="{ row }">
          <el-tag v-if="row.crossPeriodFlag === '1'" type="warning" size="small">跨期</el-tag>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="频繁升级" width="80">
        <template #default="{ row }">
          <el-tag v-if="row.freqFlag === '1'" type="danger" size="small">升级</el-tag>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="infoSheetNo" label="信息表编号" width="130" />
      <el-table-column prop="redNo" label="红票号" width="140" />
      <el-table-column label="操作" width="250">
        <template #default="{ row }">
          <el-button v-if="row.status === 'DRAFT'" link type="primary" size="small" @click="doSubmit(row)">提交审批</el-button>
          <el-button v-if="row.status === 'INFO_ISSUED'" link type="warning" size="small" @click="openInfoNo(row)">登记信息表编号</el-button>
          <el-button v-if="row.status === 'INFO_ISSUED' && row.infoSheetNo" link type="success" size="small" @click="doIssue(row)">开红票</el-button>
          <el-button v-if="row.status === 'ISSUED'" link type="danger" size="small" @click="openReopen(row)">红冲后重开</el-button>
          <el-button link type="info" size="small" @click="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 发起红冲申请 -->
    <el-dialog v-model="createVisible" title="红冲申请（C-4.14-02 金额卡控 / C-4.14-12 蓝票校验）" width="560px">
      <el-form label-width="110px" size="small">
        <el-form-item label="原蓝票" required>
          <el-select v-model="createForm.originInvoiceId" filterable placeholder="选择销项发票（仅正常且有可红冲余额）" style="width: 100%">
            <el-option v-for="inv in invoiceOptions" :key="inv.id"
              :label="`${inv.invoiceNo}｜${inv.customerName || ''}｜${inv.amount}（已冲 ${inv.redAmount || 0}）`"
              :value="inv.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="红冲金额" required>
          <el-input-number v-model="createForm.amount" :min="0.01" :precision="2" :step="1000" style="width: 200px" />
          <span class="muted" style="margin-left: 8px">不得超过剩余可红冲金额</span>
        </el-form-item>
        <el-form-item label="原因类型" required>
          <el-select v-model="createForm.reasonType" style="width: 200px">
            <el-option label="退货" value="RETURN" />
            <el-option label="折让" value="DISCOUNT" />
            <el-option label="开票错误" value="ERROR" />
          </el-select>
        </el-form-item>
        <el-form-item label="红冲原因" required>
          <el-input v-model="createForm.reason" type="textarea" :rows="2" placeholder="退货退款 / 折让协议号 / 开票错误描述…" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="doCreate">创建并待提交</el-button>
      </template>
    </el-dialog>

    <!-- 信息表编号登记（编号门禁） -->
    <el-dialog v-model="infoNoVisible" title="红字信息表编号登记（BR-4.14-25：取得编号后方可开红票）" width="480px">
      <el-alert v-if="infoNoRow && infoNoRow.infoSheetFiller === 'BUYER'" type="warning" :closable="false" class="mb8"
        title="原票购方已认证抵扣：信息表由购方在综合服务平台填开，请跟进编号回传" />
      <el-alert v-else type="info" :closable="false" class="mb8" title="信息表填开方：销方" />
      <el-form label-width="110px" size="small">
        <el-form-item label="信息表编号" required>
          <el-input v-model="infoNoForm.infoSheetNo" placeholder="税控系统审核通过的信息表编号" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="infoNoVisible = false">取消</el-button>
        <el-button type="primary" @click="doRecordInfoNo">登记</el-button>
      </template>
    </el-dialog>

    <!-- 红冲后重开（BR-4.14-29） -->
    <el-dialog v-model="reopenVisible" title="红冲后重开（BR-4.14-29：强制关联红冲单号 + 重开原因）" width="520px">
      <el-alert type="info" :closable="false" class="mb8"
        :title="`将复活原开票申请并强制关联红冲单 ${reopenRow ? reopenRow.applyNo : ''}；金额/购方与红冲不一致时提交需书面说明`" />
      <el-form label-width="90px" size="small">
        <el-form-item label="重开原因" required>
          <el-input v-model="reopenForm.reopenNote" type="textarea" :rows="2" placeholder="如：税收分类编码误用，按正确编码重开" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reopenVisible = false">取消</el-button>
        <el-button type="primary" @click="doReopen">发起重开</el-button>
      </template>
    </el-dialog>

    <!-- 详情（审批轨迹 + 信息单 + 红票） -->
    <el-drawer v-model="detailVisible" :title="`红冲申请详情 ${detailRow ? detailRow.applyNo : ''}`" size="520px">
      <template v-if="detail">
        <el-descriptions :column="1" size="small" border>
          <el-descriptions-item label="状态">{{ statusText(detail.apply.status) }}</el-descriptions-item>
          <el-descriptions-item label="原蓝票">{{ detail.apply.originInvoiceNo }}</el-descriptions-item>
          <el-descriptions-item label="客户">{{ detail.apply.customerName }}</el-descriptions-item>
          <el-descriptions-item label="红冲金额">{{ detail.apply.amount }}（税率 {{ detail.apply.taxRate }}，税额 {{ detail.apply.taxAmount }}）</el-descriptions-item>
          <el-descriptions-item label="原因">{{ reasonText(detail.apply.reasonType) }}：{{ detail.apply.reason }}</el-descriptions-item>
          <el-descriptions-item label="信息表编号">{{ detail.apply.infoSheetNo || '未登记（开票门禁）' }}</el-descriptions-item>
          <el-descriptions-item label="填开方">{{ detail.apply.infoSheetFiller === 'BUYER' ? '购方（已认证抵扣）' : detail.apply.infoSheetFiller === 'SELLER' ? '销方' : '—' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.apply.crossPeriodFlag === '1'" label="跨期评估">
            {{ detail.apply.crossPeriodNote || '（待审批意见含更正申报评估）' }}
          </el-descriptions-item>
          <el-descriptions-item label="重开关联">{{ detail.apply.reopenApplyNo || '—' }}</el-descriptions-item>
        </el-descriptions>
        <template v-if="detail.redInvoice">
          <el-divider content-position="left">红字发票</el-divider>
          <el-descriptions :column="1" size="small" border>
            <el-descriptions-item label="红票号">{{ detail.redInvoice.redNo }}</el-descriptions-item>
            <el-descriptions-item label="金额">{{ detail.redInvoice.amount }}（税额 {{ detail.redInvoice.taxAmount }}）</el-descriptions-item>
          </el-descriptions>
        </template>
        <template v-if="detail.approval && detail.approval.tasks">
          <el-divider content-position="left">审批轨迹（C-4.14-03 全程留痕）</el-divider>
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
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createRedFlush, submitRedFlush, redFlushPage, redFlushDetail,
  recordInfoSheetNo, issueRedFlush, reopenFromRedFlush, salesInvoiceLedger
} from '@/api/fin/red-invoice-void'

const query = ref({ status: '', keyword: '' })
const rows = ref([])
const invoiceOptions = ref([])

const createVisible = ref(false)
const createForm = ref({})
const infoNoVisible = ref(false)
const infoNoRow = ref(null)
const infoNoForm = ref({})
const reopenVisible = ref(false)
const reopenRow = ref(null)
const reopenForm = ref({})
const detailVisible = ref(false)
const detailRow = ref(null)
const detail = ref(null)

async function load() {
  try {
    const params = { size: 200 }
    if (query.value.status) params.status = query.value.status
    if (query.value.keyword) params.keyword = query.value.keyword
    const { data } = await redFlushPage(params)
    rows.value = data.records || data
  } catch (e) { /* ignore */ }
}

async function openCreate() {
  createForm.value = { originInvoiceId: '', amount: undefined, reasonType: 'ERROR', reason: '' }
  try {
    const { data } = await salesInvoiceLedger({ size: 200 })
    invoiceOptions.value = (data.records || data || []).filter(inv =>
      !['VOID', 'RED_FULL'].includes(inv.status))
  } catch (e) {
    invoiceOptions.value = []
  }
  createVisible.value = true
}

async function doCreate() {
  try {
    const { data } = await createRedFlush(createForm.value)
    ElMessage.success(`红冲申请已创建：${data.applyNo}${data.crossPeriod ? '（跨期，审批意见须含更正申报评估）' : ''}`)
    createVisible.value = false
    load()
  } catch (e) { /* 422 已提示 */ }
}

async function doSubmit(row) {
  try {
    const { data } = await submitRedFlush(row.id)
    ElMessage.success(`已提交审批：${data.apprNo}${data.freqUpgrade ? '（频繁红冲已升级财务经理）' : ''}${data.overThreshold ? '（超阈值双级审批）' : ''}`)
    load()
  } catch (e) { /* ignore */ }
}

function openInfoNo(row) {
  infoNoRow.value = row
  infoNoForm.value = { infoSheetNo: '' }
  infoNoVisible.value = true
}

async function doRecordInfoNo() {
  try {
    await recordInfoSheetNo(infoNoRow.value.id, infoNoForm.value.infoSheetNo)
    ElMessage.success('信息表编号已登记，可开红票')
    infoNoVisible.value = false
    load()
  } catch (e) { /* ignore */ }
}

async function doIssue(row) {
  try {
    await ElMessageBox.confirm(`确认开具红字发票？（申请 ${row.applyNo}，金额 ${row.amount}）`, '开红票', { type: 'warning' })
  } catch (e) { return }
  try {
    const { data } = await issueRedFlush(row.id)
    ElMessage.success(`红票已开具：${data.redNo}（凭证草稿已生成，待审核过账）`)
    load()
  } catch (e) { /* ignore */ }
}

function openReopen(row) {
  reopenRow.value = row
  reopenForm.value = { reopenNote: '' }
  reopenVisible.value = true
}

async function doReopen() {
  try {
    const { data } = await reopenFromRedFlush(reopenRow.value.id, reopenForm.value.reopenNote)
    ElMessage.success(`重开申请已复活：${data.applyNo}（已关联红冲单，可在 9.2.1 继续提交）`)
    reopenVisible.value = false
  } catch (e) { /* ignore */ }
}

async function openDetail(row) {
  detailRow.value = row
  detail.value = null
  detailVisible.value = true
  try {
    const { data } = await redFlushDetail(row.id)
    detail.value = data
  } catch (e) { /* ignore */ }
}

function statusText(s) {
  return { DRAFT: '草稿', APPROVED: '审批通过', INFO_ISSUED: '信息单已生成', ISSUED: '已开票', REJECTED: '已驳回' }[s] || s
}
function statusTag(s) {
  return { DRAFT: 'info', APPROVED: 'warning', INFO_ISSUED: 'warning', ISSUED: 'success', REJECTED: 'danger' }[s] || 'info'
}
function reasonText(t) {
  return { RETURN: '退货', DISCOUNT: '折让', ERROR: '开票错误' }[t] || t
}

onMounted(load)
</script>

<style scoped>
.page { padding: 12px; }
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.title { font-size: 15px; font-weight: 600; }
.mb8 { margin-bottom: 8px; }
.muted { color: #909399; }
</style>
