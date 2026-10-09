<template>
  <div class="page">
    <el-alert type="info" :closable="false" style="margin-bottom: 8px"
      title="召回处理（4.13.2）：受限区入库（RECALL_IN，仅退货区/残次品区，量进 QC 冻结列）→ 报废处置（qcFirst 核销）→ 结案报告（召回率）。入库与处置为仓库动作。" />

    <!-- 追溯单选择 -->
    <el-form inline>
      <el-form-item label="追溯单">
        <el-select v-model="traceId" filterable placeholder="选择追溯单" style="width: 420px"
          @change="loadDetail">
          <el-option v-for="t in traces" :key="t.id"
            :label="`${t.traceNo}｜批次 ${t.batchNo}｜${statusText(t.status)}｜${t.defectReason}`"
            :value="t.id" />
        </el-select>
      </el-form-item>
      <el-button :disabled="!traceId" @click="loadDetail">刷新</el-button>
    </el-form>

    <el-row :gutter="12" v-if="trace">
      <!-- 待入库登记 -->
      <el-col :span="10">
        <el-card shadow="never">
          <template #header>待入库登记（实退/在途回收 → RECALL_IN）</template>
          <el-table :data="receiveRows" border size="small" max-height="320">
            <el-table-column prop="srcDocNo" label="来源" width="110">
              <template #default="{ row }">{{ row.srcDocNo || '-' }}</template>
            </el-table-column>
            <el-table-column prop="customerName" label="客户" width="90">
              <template #default="{ row }">{{ row.customerName || '-' }}</template>
            </el-table-column>
            <el-table-column prop="actualQty" label="实退" width="70" align="right" />
            <el-table-column label="入库仓位" width="180">
              <template #default="{ row }">
                <el-select v-model="receiveForm[row.id].binCode" size="small"
                  placeholder="受限区仓位" :disabled="row.warehouseCode !== receiveForm[row.id].warehouseCode">
                  <el-option v-for="b in restrictedBins" :key="b.bin.binCode"
                    :label="`${b.bin.binCode}（${b.bin.binType === 'RETURN' ? '退货区' : '残次品区'}）`"
                    :value="b.bin.binCode" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="80">
              <template #default="{ row }">
                <el-button link type="primary" size="small" :loading="acting"
                  @click="doReceive(row)">入库</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="!receiveRows.length" class="empty">无待入库行（先在 4.13.1 登记实退）</div>
          <div class="hint">仅退货区/残次品区可入（BR-4.4-50），入账进 QC 冻结列</div>
        </el-card>
      </el-col>

      <!-- 待处置清单 -->
      <el-col :span="7">
        <el-card shadow="never">
          <template #header>待处置清单（报废联动）</template>
          <el-table :data="disposeRows" border size="small" max-height="320">
            <el-table-column prop="binCode" label="仓位" width="110">
              <template #default="{ row }">{{ row.binCode || '-' }}</template>
            </el-table-column>
            <el-table-column prop="qty" label="数量" width="70" align="right" />
            <el-table-column label="处置" width="80">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="goScrap(row)">去报废</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="!disposeRows.length" class="empty">无待处置行（先完成批量冻结）</div>
          <div class="hint">报废单携带追溯单号，过账 qcFirst 核销 QC 列并回写 DISPOSED</div>
        </el-card>
      </el-col>

      <!-- 结案面板 -->
      <el-col :span="7">
        <el-card shadow="never">
          <template #header>结案报告（8.7）</template>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="状态">{{ statusText(trace.status) }}</el-descriptions-item>
            <el-descriptions-item label="应召合计">{{ stat.expect }}</el-descriptions-item>
            <el-descriptions-item label="实退合计">{{ stat.actual }}</el-descriptions-item>
            <el-descriptions-item label="召回率">{{ stat.rate }}</el-descriptions-item>
            <el-descriptions-item label="未闭环行">{{ stat.open }}</el-descriptions-item>
          </el-descriptions>
          <el-button type="primary" style="margin-top: 10px; width: 100%"
            :loading="acting" :disabled="trace.status === 'CLOSED' || !traceId"
            @click="doClose">
            {{ trace.status === 'CLOSED' ? '已结案' : '生成结案报告' }}
          </el-button>
          <div v-if="closeReport" class="report">
            <el-divider content-position="left">结案报告</el-divider>
            <div>召回率：{{ closeReport.recallRate }}%</div>
            <div v-for="(u, i) in closeReport.unreturned || []" :key="i" class="unret">
              {{ u.customerName || u.srcDocNo }}：应召 {{ u.expectQty }} / 实退 {{ u.actualQty }}
              （缺 {{ u.shortQty }}，{{ u.reason }}）
            </div>
            <div v-if="!(closeReport.unreturned || []).length" class="hint">全部召回闭环</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 全部流向概览 -->
    <el-card v-if="trace" shadow="never" style="margin-top: 12px">
      <template #header>流向闭环概览（含审计入口）</template>
      <el-table :data="flows" border size="small" max-height="340">
        <el-table-column label="流向" width="130">
          <template #default="{ row }">{{ flowText(row.flowType) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'DISPOSED' || row.status === 'RECEIVED'
              || row.status === 'INTERCEPTED' || row.status === 'LINKED' ? 'success'
              : row.status === 'FAILED' || row.status === 'REJECTED' ? 'danger' : 'warning'">
              {{ flowStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="srcDocNo" label="来源单据" width="130">
          <template #default="{ row }">{{ row.srcDocNo || (row.binCode ? `仓位 ${row.binCode}` : '-') }}</template>
        </el-table-column>
        <el-table-column prop="customerName" label="客户" width="110">
          <template #default="{ row }">{{ row.customerName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="qty" label="数量" width="80" align="right" />
        <el-table-column prop="note" label="备注" min-width="160" show-overflow-tooltip />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, reactive, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getTracesApi, getTraceApi, receiveTraceApi, closeTraceApi, getRestrictedBinsApi
} from '@/api/inv/trace'

const route = useRoute()
const router = useRouter()

const traces = ref([])
const traceId = ref('')
const trace = ref(null)
const flows = ref([])
const acting = ref(false)
const receiveForm = reactive({})
const restrictedBins = ref([])
const closeReport = ref(null)

const receiveRows = computed(() => flows.value.filter(f =>
  f.flowType === 'OUT_SIGNED' && f.status === 'RECEIVED'))
const disposeRows = computed(() => flows.value.filter(f =>
  (f.flowType === 'STOCK_AVAILABLE' && f.status === 'FROZEN')
  || (f.flowType === 'STOCK_FROZEN' && f.status === 'FROZEN')
  || (f.flowType === 'OUT_SIGNED' && f.status === 'RECEIVED')))
const stat = computed(() => {
  const signed = flows.value.filter(f => f.flowType === 'OUT_SIGNED')
  const expect = signed.reduce((s, f) => s + Number(f.expectQty || 0), 0)
  const actual = signed.reduce((s, f) => s + Number(f.actualQty || 0), 0)
  const terminal = ['DISPOSED', 'LINKED', 'INTERCEPTED', 'RECEIVED', 'REJECTED', 'FAILED']
  const open = flows.value.filter(f => !terminal.includes(f.status)).length
  return {
    expect, actual, open,
    rate: expect > 0 ? (actual * 100 / expect).toFixed(1) + '%' : '-'
  }
})

async function loadTraces() {
  const res = await getTracesApi({ current: 1, size: 100 })
  traces.value = res.data.records || []
  if (!traceId.value && traces.value.length) {
    traceId.value = traces.value[0].id
    await loadDetail()
  }
}

async function loadDetail() {
  if (!traceId.value) return
  const res = await getTraceApi(traceId.value)
  trace.value = res.data
  flows.value = res.data.flows || []
  closeReport.value = null
  for (const f of receiveRows.value) {
    if (!receiveForm[f.id]) {
      receiveForm[f.id] = reactive({ warehouseCode: f.warehouseCode, binCode: '' })
    }
  }
  // 受限区仓位（RETURN/SCRAP 过滤，binType 过滤为前端双保险）
  const wh = receiveRows.value[0]?.warehouseCode || flows.value[0]?.warehouseCode
  if (wh) {
    try {
      const bins = await getRestrictedBinsApi(wh)
      restrictedBins.value = (bins.data || []).filter(b =>
        b.bin && (b.bin.binType === 'RETURN' || b.bin.binType === 'SCRAP'))
    } catch { restrictedBins.value = [] }
  }
}

async function doReceive(row) {
  const form = receiveForm[row.id] || {}
  if (!form.binCode) return ElMessage.warning('请选择受限区仓位（退货区/残次品区）')
  const qty = row.actualQty ?? row.qty
  await ElMessageBox.confirm(
    `RECALL_IN 过账：批次 ${row.batchNo} × ${qty} → ${form.binCode}（入 QC 冻结列）`,
    '召回受限区入库', { type: 'warning' })
  acting.value = true
  try {
    const res = await receiveTraceApi({
      traceId: traceId.value, flowId: row.id,
      warehouseCode: form.warehouseCode, binCode: form.binCode, qty
    })
    ElMessage.success(`入库成功（QC+${res.data.qty}），流水 ${res.data.txnNos?.join(',') || '-'}`)
    await loadDetail()
  } finally {
    acting.value = false
  }
}

function goScrap(row) {
  router.push({
    path: '/inv/scrap-order',
    query: {
      traceNo: trace.value.traceNo,
      itemCode: row.itemCode,
      batchNo: row.batchNo,
      warehouseCode: row.warehouseCode || trace.value.warehouseCode || ''
    }
  })
}

async function doClose() {
  await ElMessageBox.confirm(
    `生成 ${trace.value.traceNo} 结案报告（召回率 = Σ实退/Σ应召）？未闭环行将 422 阻断。`,
    '结案确认', { type: 'warning' })
  acting.value = true
  try {
    const res = await closeTraceApi(traceId.value)
    const report = res.data.report
    await loadDetail()   // loadDetail 会重置 closeReport，先刷新再回填
    closeReport.value = report
    ElMessage.success(`已结案，召回率 ${report.recallRate}%`)
  } finally {
    acting.value = false
  }
}

function statusText(t) {
  return { ANALYZING: '分析中', EXECUTING: '执行中', CLOSED: '已结案' }[t] || t || '-'
}
function flowText(t) {
  return {
    STOCK_AVAILABLE: '在库可用', STOCK_FROZEN: '在库冻结', IN_TRANSIT: '调拨在途',
    OUT_UNSIGNED: '未签收', OUT_SIGNED: '已签收/召回'
  }[t] || t
}
function flowStatusText(t) {
  return {
    PENDING: '待处置', FROZEN: '已冻结', LINKED: '已关联', INTERCEPTED: '已拦截',
    FAILED: '拦截失败', RECEIVED: '已退回', REJECTED: '已拒退', DISPOSED: '已处置'
  }[t] || t
}

onMounted(async () => {
  if (route.query.traceId) traceId.value = String(route.query.traceId)
  await loadTraces()
})
</script>

<style scoped>
.page { padding: 4px; }
.empty { color: #909399; font-size: 12px; padding: 8px 0; }
.hint { color: #909399; font-size: 12px; margin-top: 6px; }
.report { margin-top: 8px; font-size: 13px; }
.unret { color: #e6a23c; font-size: 12px; margin-top: 4px; }
</style>
