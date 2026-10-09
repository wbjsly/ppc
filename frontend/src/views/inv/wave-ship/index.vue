<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>4.8.2 集货发运</span>
          <div class="header-actions">
            <el-select v-model="waveId" filterable placeholder="选择波次（SORTING/STAGING/SHIPPING）"
              style="width: 300px;" @change="loadDetail">
              <el-option v-for="w in waves" :key="w.id"
                :label="`${w.waveNo}（${STATUS[w.status] || w.status} · ${w.docCount} 单）`" :value="w.id" />
            </el-select>
            <el-button type="success" :disabled="!wave || wave.status !== 'SHIPPING'" :loading="shipping"
              @click="doShip">发运确认（逐单过账）</el-button>
          </div>
        </div>
      </template>

      <el-alert v-if="wave" :type="alertType" :closable="false" show-icon style="margin-bottom: 12px;"
        :title="`波次 ${wave.waveNo}：${STATUS[wave.status] || wave.status}`"
        :description="stepsHint" />

      <el-steps :active="stepActive" finish-status="success" style="margin-bottom: 16px;">
        <el-step title="拣货（4.7.2 执行）" description="WAVE 任务逐行扫码" />
        <el-step title="分播复核" description="按订单实点，不平落 WAVE_SORT 差异" />
        <el-step title="装车确认" description="扫描 vs 复核快照，不一致阻断该单" />
        <el-step title="发运过账" description="逐单独立过账，失败单独重试" />
      </el-steps>

      <el-table :data="docs" size="small" border v-loading="loading">
        <el-table-column prop="shipNo" label="发货单" width="140" />
        <el-table-column label="归属" width="90">
          <template #default="{ row }">
            <el-tag :type="row.bindStatus === 'BOUND' ? 'success' : 'info'" size="small">
              {{ row.bindStatus === 'BOUND' ? '在波次' : '已拆出' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="① 分播复核" width="220">
          <template #default="{ row }">
            <template v-if="row.bindStatus === 'BOUND'">
              <el-tag v-if="row.sortStatus === 'PASSED'" type="success" size="small">已通过</el-tag>
              <template v-else>
                <el-button size="small" type="primary" plain :disabled="wave?.status !== 'SORTING'"
                  @click="openSort(row)">分播复核</el-button>
              </template>
            </template>
          </template>
        </el-table-column>
        <el-table-column label="② 装车确认" width="220">
          <template #default="{ row }">
            <template v-if="row.bindStatus === 'BOUND'">
              <el-tag v-if="row.loadStatus === 'LOADED'" type="success" size="small">已装车</el-tag>
              <!-- SORTING 也允许点：差异闭环后的首次装车由后端 maybeAdvance 推进 STAGING，
                   未就绪时后端 422 提示（前端锁 STAGING 会与后端死锁） -->
              <el-button v-else size="small"
                :disabled="wave?.status !== 'STAGING' && wave?.status !== 'SORTING'"
                @click="openLoad(row)">装车确认</el-button>
            </template>
          </template>
        </el-table-column>
        <el-table-column label="③ 发运状态" width="250">
          <template #default="{ row }">
            <template v-if="row.bindStatus === 'BOUND'">
              <el-tag v-if="postedOf(row)" type="success" size="small">POSTED</el-tag>
              <template v-else-if="row.shipErr">
                <el-tooltip :content="row.shipErr" placement="top">
                  <el-tag type="danger" size="small">过账失败</el-tag>
                </el-tooltip>
                <el-button size="small" text type="primary"
                  :disabled="wave?.status !== 'SHIPPING'" @click="doRetry(row)">重试</el-button>
              </template>
              <span v-else class="tip">待发运</span>
            </template>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button v-if="hasSortDiff(row)" size="small" text type="warning"
              @click="showDiff(row)">查看分播差异</el-button>
          </template>
        </el-table-column>
      </el-table>

      <p class="tip" style="margin-top: 10px;">
        口径：分播差异落 4.7.4 差异台账（DIFF_TYPE=WAVE_SORT，闭环解锁过账门闩）；
        装车不一致 422 阻断该订单、波次内其他订单照常（C-4.4-06）；
        发运逐单独立事务过账，失败单独重试（BR-4.4-46）。
      </p>
    </el-card>

    <!-- 分播复核 -->
    <el-dialog v-model="sortVisible" :title="`分播复核：${sortDoc?.shipNo || ''}`" width="760px">
      <p class="tip">按订单逐项实点（与分配段比对）：品种/批次/数量全平 → 通过；不平 → 登记 WAVE_SORT 差异。</p>
      <el-table :data="sortRows" size="mini" border>
        <el-table-column prop="itemCode" label="物料" width="130" />
        <el-table-column prop="batchNo" label="批次" width="130" />
        <el-table-column label="应分播" width="90">
          <template #default="{ row }">{{ row.expectQty }}</template>
        </el-table-column>
        <el-table-column label="实点数量" width="140">
          <template #default="{ row }">
            <el-input-number v-model="row.actualQty" :min="0" :precision="3" size="small" style="width: 120px;" />
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="sortVisible = false">取消</el-button>
        <el-button type="primary" :loading="opLoading" @click="submitSort">提交复核</el-button>
      </template>
    </el-dialog>

    <!-- 装车确认 -->
    <el-dialog v-model="loadVisible" :title="`装车确认：${loadDoc?.shipNo || ''}`" width="760px">
      <p class="tip">扫描明细须与分播复核结果完全一致，否则 422 阻断该订单发运（C-4.4-06，不生成差异单）。</p>
      <el-table :data="loadRows" size="mini" border>
        <el-table-column prop="itemCode" label="物料" width="130" />
        <el-table-column prop="batchNo" label="批次" width="130" />
        <el-table-column label="复核数" width="90">
          <template #default="{ row }">{{ row.expectQty }}</template>
        </el-table-column>
        <el-table-column label="装车扫描数" width="140">
          <template #default="{ row }">
            <el-input-number v-model="row.actualQty" :min="0" :precision="3" size="small" style="width: 120px;" />
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="loadVisible = false">取消</el-button>
        <el-button type="primary" :loading="opLoading" @click="submitLoad">确认装车</el-button>
      </template>
    </el-dialog>

    <!-- 分播差异查看 -->
    <el-dialog v-model="diffVisible" :title="`分播差异：${diffShipNo}`" width="640px">
      <el-table :data="diffRows" size="small" border>
        <el-table-column prop="diffKind" label="类型" width="80" />
        <el-table-column prop="expectQty" label="应分播" width="90" />
        <el-table-column prop="actualQty" label="实点" width="90" />
        <el-table-column prop="deltaQty" label="差额" width="80" />
        <el-table-column prop="diffNote" label="说明" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'PENDING' ? 'danger' : 'success'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <p class="tip">闭环在 4.7.4 差异处理（处理说明必填）；闭环后本单恢复装车/发运。</p>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getWavePageApi, getWaveDetailApi, sortConfirmApi, loadConfirmApi,
  shipConfirmApi, retryShipApi
} from '@/api/inv/wave'
import { getOutboundQueueApi } from '@/api/inv/outbound-workbench'
import { getPickDiffsApi } from '@/api/inv/pick'

const STATUS = {
  CREATED: '已生成', ALLOCATED: '已分配', PICKING: '拣货中', SORTING: '分播中',
  STAGING: '装车中', SHIPPING: '发运中', CLOSED: '已关闭', CANCELLED: '已作废'
}

const waves = ref([])
const waveId = ref('')
const wave = ref(null)
const docs = ref([])
const loading = ref(false)
const shipping = ref(false)
const opLoading = ref(false)
const shipStatusMap = ref({})
const detailLines = ref([])   // 波次分配段（分播/装车比对基准）

const stepActive = computed(() => ({
  ALLOCATED: 0, PICKING: 0, SORTING: 1, STAGING: 2, SHIPPING: 3, CLOSED: 4
}[wave.value?.status] ?? -1))
const alertType = computed(() => {
  const s = wave.value?.status
  if (s === 'CLOSED') return 'success'
  if (s === 'CANCELLED') return 'info'
  return 'info'
})
const stepsHint = computed(() => {
  const s = wave.value?.status
  if (s === 'SORTING') return '拣货完成：请逐单执行分播复核；全部通过后进入装车'
  if (s === 'STAGING') return '分播就绪：请逐单扫描装车（不一致将阻断该订单）'
  if (s === 'SHIPPING') return '装车完成：点击「发运确认」逐单过账，失败单可单独重试'
  if (s === 'CLOSED') return '全部订单过账完成，波次关闭'
  return '等待拣货完成（4.7.2 扫码执行 WAVE 任务）'
})

async function loadWaves() {
  const res = await getWavePageApi({ current: 1, size: 100 })
  waves.value = (res.data.records || []).filter(w =>
    ['ALLOCATED', 'PICKING', 'SORTING', 'STAGING', 'SHIPPING', 'CLOSED'].includes(w.status))
}

async function loadDetail() {
  if (!waveId.value) return
  loading.value = true
  try {
    const res = await getWaveDetailApi(waveId.value)
    wave.value = res.data.wave
    docs.value = (res.data.docs || []).filter(d => d.bindStatus === 'BOUND')
    detailLines.value = res.data.lines || []   // 分配段（分播/装车比对基准）
    await loadShipStatus()
  } catch (e) { /* 拦截器已 toast（422/409 等），吞掉防 unhandled rejection */
  } finally {
    loading.value = false
  }
}

/** 发货单过账状态（POSTED 判定） */
async function loadShipStatus() {
  const map = {}
  for (const d of docs.value) {
    try {
      const res = await getOutboundQueueApi('SALES_OUT',
        { queue: '', keyword: d.shipNo, current: 1, size: 5 })
      const list = res.data.rows || res.data.records || []
      const hit = list.find(r => r.shipNo === d.shipNo)
      if (hit) map[d.shipNo] = hit.status
    } catch { /* 查询失败不阻断 */ }
  }
  shipStatusMap.value = map
}
const postedOf = row => shipStatusMap.value[row.shipNo] === 'POSTED'

function hasSortDiff(row) {
  return row.bindStatus === 'BOUND' && row.sortStatus !== 'PASSED'
}

// ---------- 分播 ----------
const sortVisible = ref(false)
const sortDoc = ref(null)
const sortRows = ref([])

function openSort(row) {
  sortDoc.value = row
  // 应分播基准 = 该单分配段（detail.lines 里按 shipId 过滤的段不存在——lines 有 shipId）
  const segs = (detailLines.value || []).filter(l => l.shipId === row.shipId)
  const agg = {}
  for (const s of segs) {
    const k = `${s.itemCode}|${s.batchNo}`
    agg[k] = agg[k] || { itemCode: s.itemCode, batchNo: s.batchNo, expectQty: 0 }
    agg[k].expectQty = Number(agg[k].expectQty) + Number(s.qty)
  }
  sortRows.value = Object.values(agg).map(a => ({ ...a, actualQty: a.expectQty }))
  sortVisible.value = true
}

async function submitSort() {
  opLoading.value = true
  try {
    const res = await sortConfirmApi(waveId.value, sortDoc.value.shipId,
      sortRows.value.map(r => ({ itemCode: r.itemCode, batchNo: r.batchNo, qty: r.actualQty })))
    if (res.data.passed) {
      ElMessage.success('分播复核通过')
      sortVisible.value = false
    } else {
      ElMessage.warning(res.data.message)
      sortVisible.value = false
    }
    await loadDetail()
  } catch (e) { /* 拦截器已 toast（422/409 等），吞掉防 unhandled rejection */
  } finally {
    opLoading.value = false
  }
}

// ---------- 装车 ----------
const loadVisible = ref(false)
const loadDoc = ref(null)
const loadRows = ref([])

function openLoad(row) {
  loadDoc.value = row
  let base = []
  if (row.sortResult) {
    try {
      const snap = JSON.parse(row.sortResult)
      base = Object.entries(snap).map(([k, qty]) => {
        const [itemCode, batchNo] = k.split('|')
        return { itemCode, batchNo, expectQty: Number(qty), actualQty: Number(qty) }
      })
    } catch { base = [] }
  }
  if (!base.length) {
    // 无快照（差异闭环路径）：以分配段为基准
    const segs = (detailLines.value || []).filter(l => l.shipId === row.shipId)
    const agg = {}
    for (const s of segs) {
      const k = `${s.itemCode}|${s.batchNo}`
      agg[k] = agg[k] || { itemCode: s.itemCode, batchNo: s.batchNo, expectQty: 0 }
      agg[k].expectQty = Number(agg[k].expectQty) + Number(s.qty)
    }
    base = Object.values(agg).map(a => ({ ...a, actualQty: a.expectQty }))
  }
  loadRows.value = base
  loadVisible.value = true
}

async function submitLoad() {
  opLoading.value = true
  try {
    await loadConfirmApi(waveId.value, loadDoc.value.shipId,
      loadRows.value.map(r => ({ itemCode: r.itemCode, batchNo: r.batchNo, qty: r.actualQty })))
    ElMessage.success('装车确认完成')
    loadVisible.value = false
    await loadDetail()
  } catch (e) { /* 拦截器已 toast（422/409 等），吞掉防 unhandled rejection */
  } finally {
    opLoading.value = false
  }
}

// ---------- 发运 ----------
async function doShip() {
  shipping.value = true
  try {
    const res = await shipConfirmApi(waveId.value)
    const d = res.data
    if (d.failed > 0) {
      ElMessage.warning(d.message)
    } else {
      ElMessage.success(d.message)
    }
    await loadDetail()
    await loadWaves()
  } catch (e) { /* 拦截器已 toast（422/409 等），吞掉防 unhandled rejection */
  } finally {
    shipping.value = false
  }
}

async function doRetry(row) {
  const res = await retryShipApi(waveId.value, row.shipId)
  if (res.data.posted) {
    ElMessage.success(`${row.shipNo} 重试过账成功` + (res.data.waveStatus === 'CLOSED' ? '，波次已关闭' : ''))
  } else {
    ElMessage.error(`${row.shipNo} 仍失败：${res.data.error}`)
  }
  await loadDetail()
}

// ---------- 分播差异 ----------
const diffVisible = ref(false)
const diffShipNo = ref('')
const diffRows = ref([])

async function showDiff(row) {
  diffShipNo.value = row.shipNo
  const res = await getPickDiffsApi({ docNo: row.shipNo, current: 1, size: 20 }).catch(() => ({ data: {} }))
  diffRows.value = (res.data.records || []).filter(r => r.diffType === 'WAVE_SORT')
  diffVisible.value = true
}

onMounted(async () => {
  await loadWaves()
  if (waves.value.length) {
    waveId.value = waves.value[0].id
    await loadDetail()
  }
})
</script>

<style scoped>
.page { padding: 4px; }
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.header-actions { display: flex; gap: 8px; align-items: center; }
.tip { color: #909399; font-size: 12px; }
</style>
