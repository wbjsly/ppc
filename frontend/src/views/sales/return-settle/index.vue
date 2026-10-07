<template>
  <div class="page-wrap">
    <el-alert type="info" :closable="false" show-icon class="mb12"
              title="3.10.3 退款换货执行"
              description="审批通过后分流：退款 → 红字发票 + 应收红冲 + 红冲凭证（借 6001 / 贷 1122），支持部分退款支付登记；换货 → 生成关联退货单的新发货单并重走 ATP 批次锁定（库存不足阻断）；实物入库按批次回补 AVAILABLE_QTY（待检入 QC_QTY 不计 ATP）并生成入库凭证（借 1405 / 贷 6401）（spec 12.5~12.7）。" />

    <el-row :gutter="12" class="mb12">
      <el-col :span="6" v-for="k in kpis" :key="k.label">
        <div class="kpi-box">
          <div class="kpi-label">{{ k.label }}</div>
          <div class="kpi-value" :style="{ color: k.color || '#303133' }">{{ k.value }}</div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="12">
      <!-- 待执行 -->
      <el-col :span="10">
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <b>待执行（审批通过 APPROVED）</b>
              <el-button size="small" @click="load">刷新</el-button>
            </div>
          </template>
          <el-empty v-if="!approved.length" description="暂无待执行退货单" :image-size="70" />
          <div v-for="r in approved" :key="r.id" class="queue-item"
               :class="{ active: current && current.id === r.id }" @click="open(r)">
            <div class="q-head">
              <b>{{ r.returnNo }}</b>
              <el-tag size="small" :type="r.judgeHandle === 'REFUND' ? 'warning' : 'primary'">
                {{ r.judgeHandle === 'REFUND' ? '退款' : '换货' }}
              </el-tag>
              <el-tag size="small" :type="r.invoiceFlag === 'INVOICED' ? 'danger' : 'info'">
                {{ r.invoiceFlag === 'INVOICED' ? '已开票（红字）' : '未开票' }}
              </el-tag>
              <el-tag v-if="r.stockIn === '1'" size="small" type="success">已入库</el-tag>
              <el-tag v-else size="small" type="info">未入库</el-tag>
            </div>
            <div class="q-meta">
              {{ r.customerName }} ｜ {{ r.soNo }} ｜ 核定应退 ¥{{ fmt(r.totalAmt) }}
              <span v-if="Number(r.refundAmt) > 0"> ｜ 已退 ¥{{ fmt(r.refundAmt) }}</span>
            </div>
            <div class="q-op">
              <el-button v-if="r.judgeHandle === 'REFUND'" size="small" type="primary"
                         @click.stop="openRefund(r)">退款执行</el-button>
              <el-button v-else size="small" type="primary" :loading="exchanging === r.id"
                         @click.stop="doExchange(r)">换货执行</el-button>
              <el-button size="small" @click.stop="openStock(r)">实物入库</el-button>
            </div>
          </div>
        </el-card>
      </el-col>

      <!-- 已执行 -->
      <el-col :span="14">
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <b>已执行（红字/退款/换货/入库）</b>
              <div>
                <el-select v-model="filterStatus" size="small" style="width:150px" @change="load">
                  <el-option label="已完成 DONE" value="DONE" />
                  <el-option label="审批中 APPROVING" value="APPROVING" />
                  <el-option label="已通过 APPROVED" value="APPROVED" />
                  <el-option label="全部" value="" />
                </el-select>
                <el-button size="small" style="margin-left:8px" @click="load">刷新</el-button>
              </div>
            </div>
          </template>
          <el-table :data="executed" size="small" border @row-click="open" highlight-current-row>
            <el-table-column prop="returnNo" label="退货单" width="160" />
            <el-table-column prop="customerName" label="客户" min-width="130" show-overflow-tooltip />
            <el-table-column label="方式" width="70">
              <template #default="{ row }">
                <el-tag size="small">{{ row.judgeHandle === 'REFUND' ? '退款' : '换货' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="退款额" width="100" align="right">
              <template #default="{ row }">¥{{ fmt(row.refundAmt) }}</template>
            </el-table-column>
            <el-table-column label="红字发票" width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ row.redInvoiceNo || '—' }}</template>
            </el-table-column>
            <el-table-column label="换货发货单" width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ row.exchangeShipNo || '—' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="stTag(row.status)">{{ stName(row.status) }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <!-- 全链详情 -->
    <el-drawer v-model="drawerVisible" size="640px"
               :title="current ? `执行详情 ${current.returnNo}` : '详情'">
      <template v-if="current">
        <el-descriptions :column="2" size="small" border class="mb12">
          <el-descriptions-item label="客户">{{ current.customerName }}</el-descriptions-item>
          <el-descriptions-item label="原 SO">{{ current.soNo }}</el-descriptions-item>
          <el-descriptions-item label="判定方式">
            {{ current.judgeHandle === 'REFUND' ? '退款' : '换货' }}
          </el-descriptions-item>
          <el-descriptions-item label="开票分流">
            {{ current.invoiceFlag === 'INVOICED' ? '已开票' : '未开票' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="current.redInvoiceNo" label="红字发票">
            {{ current.redInvoiceNo }}
          </el-descriptions-item>
          <el-descriptions-item v-if="current.exchangeShipNo" label="换货发货单">
            {{ current.exchangeShipNo }}（已过账 POSTED）
          </el-descriptions-item>
          <el-descriptions-item v-if="current.voucherNo" label="红冲凭证">
            {{ current.voucherNo }}
          </el-descriptions-item>
          <el-descriptions-item label="退款支付登记">
            {{ current.refundPayNo || '—' }}
            <span v-if="Number(current.refundAmt) > 0">（已退 ¥{{ fmt(current.refundAmt) }}）</span>
          </el-descriptions-item>
          <el-descriptions-item label="实物入库" :span="2">
            <el-tag size="small" :type="current.stockIn === '1' ? 'success' : 'info'">
              {{ current.stockIn === '1' ? '已完成' : '未完成' }}
            </el-tag>
            <span v-if="current.stockInBy"> ｜ {{ current.stockInBy }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <h4>退货行（申请 / 核定 / 已入库）</h4>
        <el-table :data="lines" size="small" border class="mb12">
          <el-table-column prop="lineNo" label="#" width="42" />
          <el-table-column prop="itemCode" label="物料" width="110" />
          <el-table-column label="数量" width="140" align="right">
            <template #default="{ row }">
              {{ Number(row.qty) }} / {{ row.judgeQty == null ? '—' : Number(row.judgeQty) }}
              / {{ Number(row.inQty || 0) }}
            </template>
          </el-table-column>
          <el-table-column prop="stockBatchNo" label="回补批次" width="130" />
          <el-table-column label="待检" width="70">
            <template #default="{ row }">
              <el-tag v-if="row.qcFlag === '1'" size="small" type="warning">QC</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
        </el-table>

        <h4>操作留痕</h4>
        <el-timeline>
          <el-timeline-item v-for="l in judgeLogs" :key="l.id" :type="logType(l.opType)">
            [{{ opName(l.opType) }}] {{ l.basis }}
            <div class="meta">{{ l.opBy }} ｜ {{ (l.opAt || '').replace('T', ' ').slice(0, 16) }}</div>
          </el-timeline-item>
        </el-timeline>
      </template>
    </el-drawer>

    <!-- 退款执行 -->
    <el-dialog v-model="refundVisible" title="退款执行" width="540px">
      <el-descriptions v-if="refundRow" :column="2" size="small" border class="mb12">
        <el-descriptions-item label="退货单">{{ refundRow.returnNo }}</el-descriptions-item>
        <el-descriptions-item label="开票分流">
          {{ refundRow.invoiceFlag === 'INVOICED' ? '已开票（红字发票）' : '未开票（直接红冲应收）' }}
        </el-descriptions-item>
        <el-descriptions-item label="核定应退" :span="2">¥{{ fmt(refundDue) }}</el-descriptions-item>
      </el-descriptions>
      <el-form label-width="110px" size="small">
        <el-form-item label="退款金额">
          <el-input-number v-model="refundForm.refundAmt" :min="0" :precision="2"
                           :max="Number(refundDue)" style="width:100%" />
          <span class="hint">缺省 = 全额；小于应退即部分退款（支付登记留痕）</span>
        </el-form-item>
        <el-form-item label="退款日期">
          <el-date-picker v-model="refundForm.refundDate" type="date" value-format="YYYY-MM-DD"
                          style="width:100%" />
        </el-form-item>
        <el-form-item label="支付登记号">
          <el-input v-model="refundForm.payNo" placeholder="如银行付款回单号（可空）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="refundVisible = false">取消</el-button>
        <el-button type="primary" :loading="refunding" @click="doRefund">执行退款</el-button>
      </template>
    </el-dialog>

    <!-- 实物入库 -->
    <el-dialog v-model="stockVisible" title="退货实物入库" width="640px">
      <el-alert type="warning" :closable="false" show-icon class="mb12"
                title="按批次回补：勾选「入待检」的数量计入 QC_QTY，不参与 ATP 承诺；否则回补 AVAILABLE_QTY（可被后续预留占用）" />
      <el-table :data="lines" size="small" border class="mb12">
        <el-table-column prop="itemCode" label="物料" width="110" />
        <el-table-column label="核定/已入" width="110" align="right">
          <template #default="{ row }">
            {{ row.judgeQty == null ? Number(row.qty) : Number(row.judgeQty) }}
            / {{ Number(row.inQty || 0) }}
          </template>
        </el-table-column>
        <el-table-column label="批次号" width="160">
          <template #default="{ row }">
            <el-input v-model="stockForm.map[row.id].batchNo" size="small"
                      placeholder="原批次号（必填）" />
          </template>
        </el-table-column>
        <el-table-column label="入库数量" width="130">
          <template #default="{ row }">
            <el-input-number v-model="stockForm.map[row.id].qty" :min="0" :precision="3"
                             size="small" style="width:100%" />
          </template>
        </el-table-column>
        <el-table-column label="入待检" width="80">
          <template #default="{ row }">
            <el-switch v-model="stockForm.map[row.id].qc" />
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="stockVisible = false">取消</el-button>
        <el-button type="primary" :loading="stocking" @click="doStockIn">确认入库</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getReturnsApi, getReturnDetailApi, refundReturnApi, exchangeReturnApi, stockInReturnApi
} from '@/api/sd/return'

const list = ref([])
const filterStatus = ref('APPROVED')
const current = ref(null)
const lines = ref([])
const judgeLogs = ref([])
const drawerVisible = ref(false)
const refundVisible = ref(false)
const stockVisible = ref(false)
const refundRow = ref(null)
const refundDue = ref(0)
const refunding = ref(false)
const stocking = ref(false)
const exchanging = ref('')
const refundForm = ref({ refundAmt: 0, refundDate: new Date().toISOString().slice(0, 10), payNo: '' })
const stockForm = ref({ map: {} })

const approved = computed(() => list.value.filter(r => r.status === 'APPROVED'))
const executed = computed(() => list.value.filter(r =>
  ['DONE', 'APPROVING'].includes(r.status)))

const kpis = computed(() => {
  const refundPending = approved.value.filter(r => r.judgeHandle === 'REFUND').length
  const exchangePending = approved.value.filter(r => r.judgeHandle === 'EXCHANGE').length
  const refunded = list.value.reduce((s, r) => s + Number(r.refundAmt || 0), 0)
  const stockOut = list.value.filter(r => r.stockIn === '1').length
  return [
    { label: '待退款', value: refundPending, color: '#e6a23c' },
    { label: '待换货', value: exchangePending, color: '#409eff' },
    { label: '累计退款', value: `¥${fmt(refunded)}`, color: '#f56c6c' },
    { label: '已入库单数', value: stockOut, color: '#67c23a' }
  ]
})

function fmt(v) {
  const n = Number(v || 0)
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function stName(s) {
  return ({ DRAFT: '待判定', JUDGED: '已判定', APPROVING: '审批中', APPROVED: '已通过',
    REJECTED: '已驳回', DONE: '已完成', CANCELLED: '已撤销' })[s] || s
}
function stTag(s) {
  return ({ DRAFT: 'info', JUDGED: 'warning', APPROVING: 'warning', APPROVED: 'primary',
    REJECTED: 'danger', DONE: 'success', CANCELLED: 'info' })[s] || 'info'
}
function opName(v) {
  return ({ JUDGE: '判定', JUDGE_REJECT: '判定驳回', SUBMIT: '提交审批',
    STOCK_IN: '实物入库', REFUND: '退款执行', EXCHANGE: '换货执行' })[v] || v
}
function logType(v) {
  return ({ JUDGE_REJECT: 'danger', REFUND: 'success', EXCHANGE: 'primary',
    STOCK_IN: 'warning' })[v] || 'primary'
}

async function load() {
  const res = await getReturnsApi({
    current: 1, size: 100, status: filterStatus.value || undefined
  })
  list.value = res.data.records || res.data || []
}

async function open(r) {
  const res = await getReturnDetailApi(r.id)
  current.value = res.data.return
  lines.value = res.data.lines || []
  judgeLogs.value = res.data.judgeLogs || []
  drawerVisible.value = true
}

function openRefund(r) {
  refundRow.value = r
  current.value = r
  refundDue.value = 0
  getReturnDetailApi(r.id).then(res => {
    lines.value = res.data.lines || []
    current.value = res.data.return
    let due = 0
    for (const l of lines.value) {
      const q = l.judgeQty == null ? Number(l.qty) : Number(l.judgeQty)
      due += Number(l.unitPrice || 0) * q
    }
    refundDue.value = Math.round(due * 100) / 100
    refundForm.value = {
      refundAmt: Math.max(0, refundDue.value - Number(r.refundAmt || 0)),
      refundDate: new Date().toISOString().slice(0, 10),
      payNo: ''
    }
    refundVisible.value = true
  })
}

async function doRefund() {
  refunding.value = true
  try {
    const res = await refundReturnApi(refundRow.value.id, refundForm.value)
    ElMessage.success('退款完成：红字/红冲凭证 ' + (res.data.voucherNo || '')
      + (res.data.redInvoice ? `，红字发票 ${res.data.redInvoice.redNo}` : ''))
    refundVisible.value = false
    load()
  } finally {
    refunding.value = false
  }
}

async function doExchange(r) {
  exchanging.value = r.id
  try {
    const res = await exchangeReturnApi(r.id)
    ElMessage.success('换货发货单已生成并过账：' + res.data.shipment.shipNo
      + '（重走 ATP 锁批）')
    load()
  } finally {
    exchanging.value = ''
  }
}

async function openStock(r) {
  current.value = r
  const res = await getReturnDetailApi(r.id)
  lines.value = res.data.lines || []
  current.value = res.data.return
  const map = {}
  for (const l of lines.value) {
    const cap = l.judgeQty == null ? Number(l.qty) : Number(l.judgeQty)
    map[l.id] = {
      batchNo: '',
      qty: Math.max(0, cap - Number(l.inQty || 0)),
      qc: false
    }
  }
  stockForm.value = { map }
  stockVisible.value = true
}

async function doStockIn() {
  const items = []
  for (const l of lines.value) {
    const f = stockForm.value.map[l.id]
    if (f && Number(f.qty) > 0) {
      if (!f.batchNo) return ElMessage.warning(`行 ${l.lineNo} 批次号必填（按批次回补）`)
      items.push({ lineId: l.id, batchNo: f.batchNo, qty: Number(f.qty), qc: f.qc ? 1 : 0 })
    }
  }
  if (!items.length) return ElMessage.warning('请填写入库数量')
  stocking.value = true
  try {
    const res = await stockInReturnApi(current.value.id, items)
    ElMessage.success('入库完成' + (res.data.voucherNo ? `，入库凭证 ${res.data.voucherNo}` : '')
      + (res.data.stockIn ? '（全部行已入齐）' : '（尚有未入齐行）'))
    stockVisible.value = false
    load()
  } finally {
    stocking.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.page-wrap { padding: 12px; }
.mb12 { margin-bottom: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.kpi-box { background: #f5f7fa; border-radius: 4px; padding: 10px 12px; }
.kpi-label { font-size: 12px; color: #909399; margin-bottom: 4px; }
.kpi-value { font-size: 18px; font-weight: 600; }
.queue-item { border: 1px solid #ebeef5; border-radius: 4px; padding: 8px 10px;
  margin-bottom: 8px; cursor: pointer; }
.queue-item:hover { border-color: #c6e2ff; }
.queue-item.active { border-color: #409eff; background: #f5faff; }
.q-head { display: flex; gap: 6px; align-items: center; margin-bottom: 4px; }
.q-meta { font-size: 12px; color: #909399; margin-bottom: 6px; }
.q-op { display: flex; gap: 8px; }
.meta { font-size: 12px; color: #909399; }
.hint { font-size: 12px; color: #909399; }
</style>
