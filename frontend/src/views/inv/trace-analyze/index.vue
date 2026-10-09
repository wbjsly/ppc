<template>
  <div class="page">
    <!-- 发起表单（三索引，FR-4.4-8-1） -->
    <el-alert type="info" :closable="false" style="margin-bottom: 8px"
      title="批次追溯（4.13.1）：批次号/序列号/供应商批次号三索引发起 → 五类流向自动物化 → 批量冻结 / 在途拦截 / 召回登记。发起与登记为质量动作，冻结执行复用 4.9 质量冻结链。" />
    <el-form inline @submit.prevent>
      <el-form-item label="索引类型">
        <el-select v-model="form.indexType" style="width: 150px">
          <el-option label="批次号" value="BATCH" />
          <el-option label="序列号" value="SERIAL" />
          <el-option label="供应商批次号" value="SUPPLIER_BATCH" />
        </el-select>
      </el-form-item>
      <el-form-item label="索引值">
        <el-input v-model="form.indexValue" placeholder="批次/序列/供应商批次号" style="width: 220px"
          clearable />
      </el-form-item>
      <el-form-item label="缺陷描述" required>
        <el-input v-model="form.defectReason" placeholder="发现的批次缺陷（必填）" style="width: 300px"
          clearable />
      </el-form-item>
      <el-button type="primary" :loading="analyzing" @click="doAnalyze">发起追溯</el-button>
    </el-form>

    <el-table :data="rows" border v-loading="loading" @row-click="openDetail" row-style="cursor:pointer">
      <el-table-column prop="traceNo" label="追溯单号" width="140" />
      <el-table-column label="索引" width="200">
        <template #default="{ row }">{{ idxText(row.indexType) }}：{{ row.indexValue }}</template>
      </el-table-column>
      <el-table-column prop="batchNo" label="批次" width="130" />
      <el-table-column prop="itemName" label="物料" min-width="150" show-overflow-tooltip />
      <el-table-column prop="defectReason" label="缺陷描述" min-width="180" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'CLOSED' ? 'success' : row.status === 'EXECUTING' ? 'warning' : 'info'">
            {{ statusText(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createBy" label="发起人" width="110" />
      <el-table-column prop="createDate" label="发起时间" width="165" />
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click.stop="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination class="pager" layout="total, prev, pager, next" :total="total"
      :page-size="size" :current-page="current" @current-change="loadPage" />

    <!-- 详情抽屉：五类流向 + 动作 + 审计 -->
    <el-drawer v-model="drawer" :title="`${current2?.traceNo || ''} 追溯详情`" size="72%">
      <div v-if="current2">
        <el-descriptions :column="3" border size="small" style="margin-bottom: 8px">
          <el-descriptions-item label="批次">{{ current2.batchNo }}</el-descriptions-item>
          <el-descriptions-item label="物料">{{ current2.itemCode }} {{ current2.itemName }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ statusText(current2.status) }}</el-descriptions-item>
          <el-descriptions-item label="缺陷" :span="3">{{ current2.defectReason }}</el-descriptions-item>
        </el-descriptions>

        <div style="display: flex; gap: 8px; margin-bottom: 8px; flex-wrap: wrap">
          <el-button type="primary" :loading="acting" @click="doFreeze"
            v-if="current2.status !== 'CLOSED'">批量冻结（含影响面确认）</el-button>
          <el-button @click="loadLogs">审计时间轴</el-button>
          <el-button @click="goRecall">去 4.13.2 召回处理</el-button>
        </div>

        <el-tabs v-model="flowTab">
          <el-tab-pane v-for="(list, tp) in flowsByType" :key="tp" :name="tp"
            :label="`${flowText(tp)}（${list.length}）`">
            <el-table :data="list" border size="small">
              <el-table-column label="状态" width="110">
                <template #default="{ row }">
                  <el-tag size="small" :type="flowStatusType(row.status)">{{ flowStatusText(row.status) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="warehouseCode" label="仓库" width="100" />
              <el-table-column prop="binCode" label="仓位" width="110">
                <template #default="{ row }">{{ row.binCode || '-' }}</template>
              </el-table-column>
              <el-table-column prop="srcDocNo" label="来源单据" width="130">
                <template #default="{ row }">{{ row.srcDocNo || '-' }}</template>
              </el-table-column>
              <el-table-column prop="customerName" label="客户" width="110">
                <template #default="{ row }">{{ row.customerName || '-' }}</template>
              </el-table-column>
              <el-table-column label="数量" width="90" align="right">
                <template #default="{ row }">{{ row.qty }}</template>
              </el-table-column>
              <el-table-column label="应召" width="80" align="right">
                <template #default="{ row }">{{ row.expectQty ?? '-' }}</template>
              </el-table-column>
              <el-table-column label="实退" width="80" align="right">
                <template #default="{ row }">{{ row.actualQty ?? '-' }}</template>
              </el-table-column>
              <el-table-column prop="note" label="备注/核查" min-width="150" show-overflow-tooltip>
                <template #default="{ row }">
                  <el-tag v-if="row.checkFlag === '1'" type="danger" size="small">待人工核查</el-tag>
                  {{ row.note || '-' }}
                </template>
              </el-table-column>
              <el-table-column label="操作" width="230" fixed="right">
                <template #default="{ row }">
                  <template v-if="(row.flowType === 'IN_TRANSIT' || row.flowType === 'OUT_UNSIGNED') && row.status === 'PENDING'">
                    <el-button link type="success" size="small" :loading="acting"
                      @click="doIntercept(row, true)">拦截成功</el-button>
                    <el-button link type="danger" size="small" :loading="acting"
                      @click="doIntercept(row, false)">拦截失败</el-button>
                  </template>
                  <el-button v-else-if="row.flowType === 'OUT_SIGNED' && row.status === 'PENDING'"
                    link type="primary" size="small" :loading="acting"
                    @click="openReturn(row)">召回登记</el-button>
                  <span v-else>-</span>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
        </el-tabs>

        <!-- 审计时间轴 -->
        <el-divider v-if="logs.length" content-position="left">审计时间轴（C-4.4-10）</el-divider>
        <el-timeline v-if="logs.length">
          <el-timeline-item v-for="l in logs" :key="l.id" :timestamp="l.opAt" placement="top">
            <b>{{ l.action }}</b>（{{ l.actionUser }}）
            <span v-if="l.objectId"> · 对象 {{ l.objectId }}</span>
            <div v-if="l.beforeVal || l.afterVal" class="log-detail">
              {{ l.beforeVal || '-' }} → {{ l.afterVal || '-' }}
            </div>
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-drawer>

    <!-- 召回登记对话框 -->
    <el-dialog v-model="returnVisible" title="召回登记（应召量物化定格）" width="460px">
      <el-form label-width="90px">
        <el-form-item label="应召数量">{{ returnRow?.expectQty }}</el-form-item>
        <el-form-item label="实退数量">
          <el-input-number v-model="returnQty" :min="0" :max="Number(returnRow?.expectQty ?? 0)"
            :precision="2" />
        </el-form-item>
        <el-form-item label="退回批次">
          <el-input v-model="returnedBatch" placeholder="客户退回批次（可空=原批次）" />
        </el-form-item>
        <el-form-item label="拒退原因">
          <el-input v-model="rejectReason" type="textarea" rows="2"
            placeholder="填写即登记为拒退（留痕并通知质量主管），实退清零" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="returnVisible = false">取消</el-button>
        <el-button type="primary" :loading="acting" @click="doReturn">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  analyzeTraceApi, getTracesApi, getTraceApi, getTraceLogsApi,
  freezeTraceApi, interceptTraceApi, returnTraceApi
} from '@/api/inv/trace'
import { estimateFreezeApi } from '@/api/inv/freeze'

const router = useRouter()

const form = ref({ indexType: 'BATCH', indexValue: '', defectReason: '' })
const rows = ref([])
const total = ref(0)
const current = ref(1)
const size = 20
const loading = ref(false)
const analyzing = ref(false)
const acting = ref(false)

const drawer = ref(false)
const current2 = ref(null)
const flowsByType = ref({})
const flowTab = ref('')
const logs = ref([])

const returnVisible = ref(false)
const returnRow = ref(null)
const returnQty = ref(0)
const returnedBatch = ref('')
const rejectReason = ref('')

async function loadPage(p = 1) {
  current.value = p
  loading.value = true
  try {
    const res = await getTracesApi({ current: p, size })
    rows.value = res.data.records || []
    total.value = Number(res.data.total || 0)
  } finally {
    loading.value = false
  }
}

async function doAnalyze() {
  if (!form.value.indexValue?.trim()) return ElMessage.warning('索引值必填')
  if (!form.value.defectReason?.trim()) return ElMessage.warning('缺陷描述必填')
  analyzing.value = true
  try {
    const res = await analyzeTraceApi(form.value)
    ElMessage.success(`追溯单 ${res.data.traceNo} 已生成，五类流向已物化`)
    form.value.indexValue = ''
    form.value.defectReason = ''
    await loadPage(1)
    openDetail(res.data)
  } finally {
    analyzing.value = false
  }
}

async function openDetail(row) {
  const res = await getTraceApi(row.id)
  current2.value = res.data
  flowsByType.value = res.data.flowsByType || {}
  flowTab.value = Object.keys(flowsByType.value)[0] || ''
  logs.value = []
  drawer.value = true
}

async function doFreeze() {
  const t = current2.value
  const stockList = (flowsByType.value.STOCK_AVAILABLE || [])
    .filter(f => f.status === 'PENDING')
  if (!stockList.length) return ElMessage.warning('无可冻结的在库可用行')
  const first = stockList[0]
  // 影响面二次确认（FR-4.4-5-3 / task 4.2：先 estimate 后执）
  let impactText = ''
  try {
    const res = await estimateFreezeApi({
      warehouseCode: first.warehouseCode, itemCode: t.itemCode,
      batchNo: t.batchNo, scope: 'BATCH', qty: first.qty
    })
    const d = res.data || {}
    impactText = `\n受影响可用：${d.affectedQty ?? '-'}，关联订单：${d.soCount ?? d.pos?.length ?? 0}`
    if (d.needConfirm) impactText += '\n⚠ 影响面超过阈值，需主管确认'
  } catch { impactText = '\n（影响面预估不可用，按实际库存执行）' }
  await ElMessageBox.confirm(
    `对批次 ${t.batchNo} 按仓库分组执行质量冻结（reason 关联 ${t.traceNo}），并释放该批次全部 ACTIVE 预留。${impactText}`,
    '批量冻结确认', { type: 'warning' })
  acting.value = true
  try {
    const res = await freezeTraceApi(t.id)
    ElMessage.success(`冻结发起 ${res.data.frozenWarehouses} 仓，释放预留 ${res.data.releasedReservations} 条（冻结审批见 4.9/审批中心）`)
    await openDetail(t)
  } finally {
    acting.value = false
  }
}

async function doIntercept(row, success) {
  await ElMessageBox.confirm(
    success ? `确认拦截成功（${row.srcDocNo}）？` :
      `确认拦截失败？该行将留痕为失败终态，并自动升级出应召 ${row.qty} 的召回清单行。`,
    '在途拦截登记', { type: 'warning' })
  acting.value = true
  try {
    const res = await interceptTraceApi(row.id, { success })
    ElMessage.success(success ? '已登记拦截成功' :
      `已登记拦截失败，升级召回行（应召 ${row.qty}）`)
    void res
    await openDetail(current2.value)
  } finally {
    acting.value = false
  }
}

function openReturn(row) {
  returnRow.value = row
  returnQty.value = Number(row.expectQty ?? 0)
  returnedBatch.value = row.batchNo || ''
  rejectReason.value = ''
  returnVisible.value = true
}

async function doReturn() {
  const row = returnRow.value
  acting.value = true
  try {
    await returnTraceApi(row.id, {
      actualQty: rejectReason.value.trim() ? null : returnQty.value,
      returnedBatch: returnedBatch.value,
      rejectReason: rejectReason.value.trim()
    })
    ElMessage.success(rejectReason.value.trim() ? '已登记拒退（已通知质量主管）' : '已登记实退')
    returnVisible.value = false
    await openDetail(current2.value)
  } finally {
    acting.value = false
  }
}

async function loadLogs() {
  const res = await getTraceLogsApi(current2.value.id)
  logs.value = res.data || []
  if (!logs.value.length) ElMessage.info('暂无审计记录')
}

function goRecall() {
  router.push({ path: '/m/4.13.2', query: { traceId: current2.value.id } })
}

function idxText(t) {
  return { BATCH: '批次号', SERIAL: '序列号', SUPPLIER_BATCH: '供应商批次号' }[t] || t
}
function statusText(t) {
  return { ANALYZING: '分析中', EXECUTING: '执行中', CLOSED: '已结案' }[t] || t
}
function flowText(t) {
  return {
    STOCK_AVAILABLE: '在库可用', STOCK_FROZEN: '在库冻结', IN_TRANSIT: '调拨在途',
    OUT_UNSIGNED: '未签收', OUT_SIGNED: '已签收/召回清单'
  }[t] || t
}
function flowStatusText(t) {
  return {
    PENDING: '待处置', FROZEN: '已冻结', LINKED: '已关联', INTERCEPTED: '已拦截',
    FAILED: '拦截失败', RECEIVED: '已退回', REJECTED: '已拒退', DISPOSED: '已处置'
  }[t] || t
}
function flowStatusType(t) {
  return {
    PENDING: 'info', FROZEN: 'warning', LINKED: 'info', INTERCEPTED: 'success',
    FAILED: 'danger', RECEIVED: 'success', REJECTED: 'danger', DISPOSED: 'success'
  }[t] || 'info'
}

onMounted(() => loadPage(1))
</script>

<style scoped>
.page { padding: 4px; }
.pager { margin-top: 12px; justify-content: flex-end; }
.log-detail { color: #909399; font-size: 12px; margin-top: 2px; }
</style>
