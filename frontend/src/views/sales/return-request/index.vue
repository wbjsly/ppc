<template>
  <div class="page-wrap">
    <el-alert type="info" :closable="false" show-icon class="mb12"
              title="3.10.1 退货申请"
              description="按 SO 行发起退货：退货数量不得超过该行已发可退余量（已发 − 已退）；按开票状态自动分流——未开票走「未开票退货」、已开票走「已开票退货」（判定通过后要求红字发票）；客户拒收自动带入原发货单（FR-4.3-68 关联原 SO，BR-4.3-68）。" />

    <el-row :gutter="12" class="mb12">
      <!-- 发起申请 -->
      <el-col :span="10">
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <b>发起退货申请</b>
              <el-select v-model="soId" filterable placeholder="选择销售订单" size="small"
                         style="width:230px" @change="loadReturnables">
                <el-option v-for="s in sos" :key="s.id"
                           :label="`${s.soNo} ${s.customerName || ''}`" :value="s.id" />
              </el-select>
            </div>
          </template>

          <el-table v-if="rows.length" :data="rows" size="small" border class="mb12"
                    @selection-change="onSel">
            <el-table-column type="selection" width="42" :selectable="r => Number(r.retirableQty) > 0" />
            <el-table-column prop="lineNo" label="#" width="42" />
            <el-table-column prop="itemCode" label="物料" width="110" show-overflow-tooltip />
            <el-table-column label="已发/已退/可退" width="140">
              <template #default="{ row }">
                {{ Number(row.shippedQty) }} / {{ Number(row.returnedQty) }} /
                <b :style="{ color: Number(row.retirableQty) > 0 ? '#67c23a' : '#c0c4cc' }">
                  {{ Number(row.retirableQty) }}</b>
              </template>
            </el-table-column>
            <el-table-column label="开票" width="70">
              <template #default="{ row }">
                <el-tag v-if="row.invoiced" size="small" type="warning">已开票</el-tag>
                <el-tag v-else size="small" type="info">未开票</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="退货数量" width="120">
              <template #default="{ row }">
                <el-input-number v-model="form.qtyMap[row.soLineId]" :min="0"
                                 :max="Number(row.retirableQty)" :precision="3" :controls="false"
                                 size="small" style="width:100%"
                                 :disabled="Number(row.retirableQty) <= 0" />
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-else-if="soId" description="该订单无可退行（无已发量或已全退）" :image-size="60" />
          <el-empty v-else description="请先选择销售订单" :image-size="60" />

          <el-form v-if="rows.length" label-width="92px" size="small">
            <el-form-item label="原因分类" required>
              <el-select v-model="form.reasonType" style="width:100%">
                <el-option label="质量责任" value="QUALITY" />
                <el-option label="客户原因" value="CUSTOMER" />
                <el-option label="物流破损" value="LOGISTICS" />
                <el-option label="发错/多发" value="WRONG" />
              </el-select>
            </el-form-item>
            <el-form-item label="退货原因" required>
              <el-input v-model="form.returnReason" type="textarea" :rows="2"
                        placeholder="必填：质量问题描述 / 发错货说明等" />
            </el-form-item>
            <el-form-item label="期望处理" required>
              <el-radio-group v-model="form.handleType">
                <el-radio value="REFUND">退款</el-radio>
                <el-radio value="EXCHANGE">换货</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="凭证说明" required>
              <el-input v-model="form.evidence" type="textarea" :rows="2"
                        placeholder="必填：现场照片/质检报告/客户函件等凭证说明" />
            </el-form-item>
            <el-form-item>
              <el-tag v-if="invoiceFlag" type="warning" size="small" class="mr8">
                已开票退货（判定通过后走红字发票）
              </el-tag>
              <el-tag v-else type="info" size="small" class="mr8">未开票退货（不涉红冲）</el-tag>
              <el-button type="primary" :loading="submitting" @click="doCreate">提交申请</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <!-- 申请列表 -->
      <el-col :span="14">
        <el-card shadow="never" style="height:100%">
          <template #header>
            <div class="card-head">
              <b>退货申请列表</b>
              <div>
                <el-select v-model="filterStatus" clearable placeholder="状态" size="small"
                           style="width:160px" @change="load">
                  <el-option label="草稿 DRAFT" value="DRAFT" />
                  <el-option label="已判定 JUDGED" value="JUDGED" />
                  <el-option label="审批中 APPROVING" value="APPROVING" />
                  <el-option label="已通过 APPROVED" value="APPROVED" />
                  <el-option label="已驳回 REJECTED" value="REJECTED" />
                  <el-option label="已完成 DONE" value="DONE" />
                  <el-option label="已撤销 CANCELLED" value="CANCELLED" />
                </el-select>
                <el-button size="small" style="margin-left:8px" @click="load">刷新</el-button>
              </div>
            </div>
          </template>
          <el-table :data="list" size="small" border @row-click="openDetail" highlight-current-row
                    v-loading="loading">
            <el-table-column prop="returnNo" label="退货单号" width="160" />
            <el-table-column prop="customerName" label="客户" min-width="130" show-overflow-tooltip />
            <el-table-column prop="soNo" label="原 SO" width="150" show-overflow-tooltip />
            <el-table-column label="方式" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="row.handleType === 'REFUND' ? 'warning' : 'primary'">
                  {{ row.handleType === 'REFUND' ? '退款' : '换货' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="开票分流" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.invoiceFlag === 'INVOICED' ? 'danger' : 'info'">
                  {{ row.invoiceFlag === 'INVOICED' ? '已开票' : '未开票' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="金额" width="100" align="right">
              <template #default="{ row }">¥{{ fmt(row.totalAmt) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="stTag(row.status)">{{ stName(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="80">
              <template #default="{ row }">
                <el-button size="small" @click.stop="openDetail(row)">详情</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <!-- 全链路详情 -->
    <el-drawer v-model="drawerVisible" size="640px"
               :title="detail.return ? `退货单 ${detail.return.returnNo}` : '详情'">
      <template v-if="detail.return">
        <el-steps :active="stepActive" simple class="mb12" finish-status="success">
          <el-step title="申请" />
          <el-step title="判定" />
          <el-step title="审批" />
          <el-step :title="detail.return.handleType === 'REFUND' ? '退款' : '换货'" />
          <el-step title="入库" />
        </el-steps>

        <el-descriptions :column="2" size="small" border class="mb12">
          <el-descriptions-item label="客户">{{ detail.return.customerName }}</el-descriptions-item>
          <el-descriptions-item label="原 SO">{{ detail.return.soNo }}</el-descriptions-item>
          <el-descriptions-item label="原因分类">{{ detail.return.reasonType }}</el-descriptions-item>
          <el-descriptions-item label="期望处理">
            {{ detail.return.handleType === 'REFUND' ? '退款' : '换货' }}
          </el-descriptions-item>
          <el-descriptions-item label="开票分流" :span="2">
            <el-tag size="small" :type="detail.return.invoiceFlag === 'INVOICED' ? 'danger' : 'info'">
              {{ detail.return.invoiceFlag === 'INVOICED'
                ? '已开票退货（红字发票环节）' : '未开票退货（不涉红冲）' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="退货原因" :span="2">{{ detail.return.returnReason }}</el-descriptions-item>
          <el-descriptions-item label="凭证说明" :span="2">{{ detail.return.remark }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.return.liability" label="判定责任方">
            {{ liabilityName(detail.return.liability) }}
            <el-tag v-if="detail.return.overdue === '1'" size="small" type="danger">超期</el-tag>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.return.judgeHandle" label="判定处理">
            {{ detail.return.judgeHandle === 'REFUND' ? '退款' : '换货' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.return.redInvoiceNo" label="红字发票">
            {{ detail.return.redInvoiceNo }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.return.exchangeShipNo" label="换货发货单">
            {{ detail.return.exchangeShipNo }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.return.voucherNo" label="凭证">
            {{ detail.return.voucherNo }}
          </el-descriptions-item>
          <el-descriptions-item label="实物入库">
            <el-tag size="small" :type="detail.return.stockIn === '1' ? 'success' : 'info'">
              {{ detail.return.stockIn === '1' ? '已完成' : '未完成' }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <h4>退货行</h4>
        <el-table :data="detail.lines || []" size="small" border class="mb12">
          <el-table-column prop="lineNo" label="#" width="42" />
          <el-table-column prop="itemCode" label="物料" width="110" />
          <el-table-column label="申请/核定/入库" width="150">
            <template #default="{ row }">
              {{ Number(row.qty) }} /
              <b>{{ row.judgeQty == null ? '—' : Number(row.judgeQty) }}</b> /
              {{ Number(row.inQty || 0) }}
              <el-tag v-if="row.qcFlag === '1'" size="small" type="warning">待检</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="金额" align="right">
            <template #default="{ row }">¥{{ fmt(row.amount) }}</template>
          </el-table-column>
        </el-table>

        <h4>全链路留痕（判定人/时间/依据）</h4>
        <el-timeline>
          <el-timeline-item v-for="l in detail.judgeLogs || []" :key="l.id"
                            :type="logType(l.opType)">
            [{{ opName(l.opType) }}] {{ l.basis }}
            <div class="meta">{{ l.opBy }} ｜ {{ (l.opAt || '').replace('T', ' ').slice(0, 16) }}
              <span v-if="l.judgeQty != null"> ｜ 核定 {{ Number(l.judgeQty) }}</span>
            </div>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-if="!(detail.judgeLogs || []).length" description="尚无操作留痕"
                  :image-size="60" />
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getReturnablesApi, createReturnApi, getReturnsApi, getReturnDetailApi, cancelReturnApi
} from '@/api/sd/return'
import { getSoPageApi } from '@/api/sd/so'

const loading = ref(false)
const submitting = ref(false)
const sos = ref([])
const soId = ref('')
const rows = ref([])
const list = ref([])
const filterStatus = ref('')
const drawerVisible = ref(false)
const detail = ref({})

const form = reactive({
  qtyMap: {},
  reasonType: 'QUALITY',
  returnReason: '',
  handleType: 'REFUND',
  evidence: ''
})
const selected = ref([])

const invoiceFlag = computed(() => rows.value.some(r => r.invoiced))

const stepActive = computed(() => {
  const st = (detail.value.return || {}).status
  return ({ DRAFT: 1, REJECTED: 1, JUDGED: 2, APPROVING: 3,
    APPROVED: 3, DONE: 5 })[st] ?? 1
})

function fmt(v) {
  const n = Number(v || 0)
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function stName(s) {
  return ({ DRAFT: '草稿', JUDGED: '已判定', APPROVING: '审批中', APPROVED: '已通过',
    REJECTED: '已驳回', DONE: '已完成', CANCELLED: '已撤销' })[s] || s
}
function stTag(s) {
  return ({ DRAFT: 'info', JUDGED: 'warning', APPROVING: 'warning', APPROVED: 'success',
    REJECTED: 'danger', DONE: '', CANCELLED: 'info' })[s] || 'info'
}
function liabilityName(v) {
  return ({ OUR_QUALITY: '我方质量', CUSTOMER: '客户原因', LOGISTICS: '物流破损' })[v] || v
}
function opName(v) {
  return ({ JUDGE: '判定', JUDGE_REJECT: '判定驳回', SUBMIT: '提交审批',
    STOCK_IN: '实物入库', REFUND: '退款执行', EXCHANGE: '换货执行' })[v] || v
}
function logType(v) {
  return ({ JUDGE_REJECT: 'danger', REFUND: 'success', EXCHANGE: 'primary',
    STOCK_IN: 'warning' })[v] || 'primary'
}

async function loadSos() {
  const res = await getSoPageApi({ current: 1, size: 100 })
  sos.value = res.data.records || res.data || []
}

async function loadReturnables() {
  if (!soId.value) return
  const res = await getReturnablesApi(soId.value)
  rows.value = res.data.lines || []
  form.qtyMap = {}
  for (const r of rows.value) form.qtyMap[r.soLineId] = undefined
}

function onSel(sel) {
  selected.value = sel
}

async function doCreate() {
  const lines = []
  for (const r of rows.value) {
    const q = Number(form.qtyMap[r.soLineId] || 0)
    if (q > 0) lines.push({ soLineId: r.soLineId, qty: q })
  }
  if (!lines.length) return ElMessage.warning('请为至少一行填写退货数量')
  if (!form.returnReason.trim()) return ElMessage.warning('退货原因必填')
  if (!form.evidence.trim()) return ElMessage.warning('凭证说明必填（spec 12.2）')
  submitting.value = true
  try {
    const res = await createReturnApi({
      soId: soId.value, lines,
      reasonType: form.reasonType, returnReason: form.returnReason,
      handleType: form.handleType, evidence: form.evidence
    })
    ElMessage.success(`退货申请已创建：${res.data.returnNo}`
      + `（${res.data.invoiceFlag === 'INVOICED' ? '已开票退货' : '未开票退货'}）`)
    form.returnReason = ''
    form.evidence = ''
    loadReturnables()
    load()
  } finally {
    submitting.value = false
  }
}

async function load() {
  loading.value = true
  try {
    const res = await getReturnsApi({
      current: 1, size: 100, status: filterStatus.value || undefined
    })
    list.value = res.data.records || res.data || []
  } finally {
    loading.value = false
  }
}

async function openDetail(row) {
  const res = await getReturnDetailApi(row.id)
  detail.value = res.data || {}
  drawerVisible.value = true
}

onMounted(() => {
  loadSos()
  load()
})
</script>

<style scoped>
.page-wrap { padding: 12px; }
.mb12 { margin-bottom: 12px; }
.mr8 { margin-right: 8px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.meta { font-size: 12px; color: #909399; }
</style>
