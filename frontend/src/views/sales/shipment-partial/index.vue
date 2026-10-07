<template>
  <div class="page-wrap">
    <el-alert type="info" :closable="false" show-icon class="mb12"
              title="3.7.1 部分发货"
              description="按 SO 行未发余量生成发货单，可多次执行；行数量不超过未发余量与可锁预留量（超限拒绝并提示剩余可发）。拣货作业由库存域拣货复核（4.7）承载，本页为销售侧策略与确认。" />

    <el-card shadow="never" class="mb12">
      <template #header>
        <div class="card-head">
          <span>① 选择已确认订单</span>
          <el-select v-model="soId" filterable placeholder="选择 SO" size="small"
                     style="width:360px" @change="loadLines">
            <el-option v-for="s in sos" :key="s.id"
                       :label="`${s.soNo} ${s.customerName} ¥${s.totalAmount}（${s.status}）`"
                       :value="s.id" />
          </el-select>
        </div>
      </template>

      <el-table :data="lines" size="small" border v-loading="loading">
        <el-table-column type="selection" width="45" :selectable="row => shipable(row) > 0" />
        <el-table-column prop="lineNo" label="#" width="45" />
        <el-table-column prop="itemCode" label="SKU" width="150" />
        <el-table-column prop="itemName" label="名称" min-width="110" show-overflow-tooltip />
        <el-table-column prop="qty" label="订单量" width="90" align="right" />
        <el-table-column prop="shippedQty" label="已发" width="90" align="right" />
        <el-table-column label="未发余量" width="100" align="right">
          <template #default="{ row }"><b>{{ remain(row) }}</b></template>
        </el-table-column>
        <el-table-column prop="reservedQty" label="预留" width="90" align="right" />
        <el-table-column label="可发" width="90" align="right">
          <template #default="{ row }">
            <span :class="shipable(row) > 0 ? 'ok' : 'bad'">{{ shipable(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="本次发货量" width="150">
          <template #default="{ row }">
            <el-input-number v-model="row.shipQty" :min="0" :max="shipable(row)"
                             :precision="3" size="small" controls-position="right"
                             style="width:135px" :disabled="shipable(row) <= 0" />
          </template>
        </el-table-column>
        <el-table-column label="行状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.lineStatus === 'SHIPPED' ? 'success'
              : (row.lineStatus === 'PARTIAL' ? 'warning' : 'info')">
              {{ row.lineStatus || 'OPEN' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>

      <div class="bar">
        <span>已选 {{ selectedLines.length }} 行，合计 {{ selectedQty.toFixed(3) }}</span>
        <el-button type="primary" size="small" :disabled="!selectedLines.length || !soId"
                   :loading="generating" @click="generate">
          生成发货单
        </el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span>② 本单发货单</span>
          <el-button size="small" @click="loadShips">刷新</el-button>
        </div>
      </template>
      <el-table :data="ships" size="small" border>
        <el-table-column prop="shipNo" label="发货单号" width="170" />
        <el-table-column prop="shipType" label="类型" width="90">
          <template #default="{ row }">
            <el-tag size="small">{{ typeMap[row.shipType] || row.shipType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="totalQty" label="数量" width="100" align="right" />
        <el-table-column prop="totalAmt" label="金额" width="110" align="right">
          <template #default="{ row }">¥{{ Number(row.totalAmt).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ statusName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createDate" label="创建时间" width="160">
          <template #default="{ row }">{{ (row.createDate || '').replace('T', ' ') }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230">
          <template #default="{ row }">
            <template v-if="row.status === 'DRAFT'">
              <el-button size="small" type="primary" @click="doPost(row)">出库过账</el-button>
              <el-button size="small" type="danger" link @click="doCancel(row)">取消</el-button>
            </template>
            <el-button v-else size="small" link @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 详情（来源引用 + 批次分配 + 拣货边界） -->
    <el-dialog v-model="detailVisible" :title="`发货单 ${detail?.shipment?.shipNo}`" width="820px">
      <template v-if="detail">
        <el-alert type="info" :closable="false" show-icon class="mb12"
                  :title="detail.pickingNote" />
        <el-descriptions :column="3" size="small" border class="mb12">
          <el-descriptions-item label="状态">{{ statusName(detail.shipment.status) }}</el-descriptions-item>
          <el-descriptions-item label="客户">{{ detail.shipment.customerName }}</el-descriptions-item>
          <el-descriptions-item label="仓库">{{ detail.shipment.warehouseCode }}</el-descriptions-item>
          <el-descriptions-item label="物流">{{ detail.shipment.logisticsCo || '—' }} {{ detail.shipment.logisticsNo || '' }}</el-descriptions-item>
          <el-descriptions-item label="发货时间">{{ (detail.shipment.shipAt || '').replace('T', ' ') || '—' }}</el-descriptions-item>
          <el-descriptions-item label="签收时限">{{ detail.shipment.signDueDate || '—' }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="detail.lines" size="small" border>
          <el-table-column label="来源" width="200">
            <template #default="{ row }">{{ row.soNo }} 行{{ row.soLineNo }}</template>
          </el-table-column>
          <el-table-column prop="itemCode" label="SKU" width="140" />
          <el-table-column prop="qty" label="数量" width="90" align="right" />
          <el-table-column label="出库批次（FIFO 选批）" min-width="220">
            <template #default="{ row }">
              <template v-if="row.batchAlloc">
                <el-tag v-for="(a, i) in parseAlloc(row.batchAlloc)" :key="i" size="small"
                        type="info" class="mr4">
                  {{ a.batchNo || '(空批)' }} × {{ a.qty }}
                </el-tag>
              </template>
              <span v-else class="muted">未过账</span>
            </template>
          </el-table-column>
          <el-table-column label="行状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.lineStatus === 'POSTED' ? 'success' : 'info'">
                {{ row.lineStatus }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getShipmentsApi, getShipmentDetailApi, generatePartialApi,
  postShipmentApi, cancelShipmentApi
} from '@/api/sd/shipment'
import { getSoPageApi, getSoDetailApi } from '@/api/sd/so'

const sos = ref([])
const soId = ref('')
const lines = ref([])
const ships = ref([])
const loading = ref(false)
const generating = ref(false)
const detailVisible = ref(false)
const detail = ref(null)

const typeMap = { PARTIAL: '部分', MERGE: '合并', BATCH: '分批', EXCHANGE: '换货' }

const selectedLines = computed(() =>
  lines.value.filter(l => Number(l.shipQty) > 0))
const selectedQty = computed(() =>
  selectedLines.value.reduce((s, l) => s + Number(l.shipQty || 0), 0))

function remain(row) {
  return Number(row.qty || 0) - Number(row.shippedQty || 0)
}
function shipable(row) {
  // 前端近似：min(未发, 预留)；在途占用由后端精确校验
  return Math.max(0, Math.min(remain(row), Number(row.reservedQty || 0)))
}
function statusName(s) {
  return { DRAFT: '草稿', POSTED: '已过账', CONFIRMED: '已发货', SIGNED: '已签收',
    REJECTED: '拒收', CANCELLED: '已取消' }[s] || s
}
function statusTag(s) {
  return { DRAFT: 'info', POSTED: 'warning', CONFIRMED: 'success',
    SIGNED: 'success', REJECTED: 'danger' }[s] || ''
}
function parseAlloc(json) {
  try { return JSON.parse(json) } catch (e) { return [] }
}

async function loadSo() {
  const [a, b] = await Promise.all([
    getSoPageApi({ current: 1, size: 50, status: 'CONFIRMED' }),
    getSoPageApi({ current: 1, size: 50, status: 'PARTIAL_SHIPPED' })
  ])
  sos.value = [...(a.data.records || []), ...(b.data.records || [])]
}

async function loadLines() {
  if (!soId.value) return
  loading.value = true
  try {
    const res = await getSoDetailApi(soId.value)
    lines.value = (res.data.lines || []).map(l => ({ ...l, shipQty: 0 }))
  } catch (e) {
    ElMessage.error(e?.message || '加载明细失败')
  } finally {
    loading.value = false
  }
}

async function loadShips() {
  const res = await getShipmentsApi({ current: 1, size: 20, keyword: '' })
  ships.value = res.data.records || []
}

async function generate() {
  generating.value = true
  try {
    const res = await generatePartialApi(soId.value,
      selectedLines.value.map(l => ({ soLineId: l.id, qty: Number(l.shipQty) })))
    ElMessage.success(`发货单 ${res.data.shipment.shipNo} 已生成`)
    await Promise.all([loadLines(), loadShips()])
  } catch (e) {
    ElMessage.error(e?.message || '生成失败')
  } finally {
    generating.value = false
  }
}

async function doPost(row) {
  try {
    await ElMessageBox.confirm(
      `出库过账 ${row.shipNo}？将按 FIFO 扣减库存、消耗预留并发布应收确认事件。`,
      '出库过账', { type: 'warning' })
    await postShipmentApi(row.id)
    ElMessage.success('出库过账完成')
    await Promise.all([loadShips(), loadLines()])
  } catch (e) {
    if (e !== 'cancel' && e?.message) ElMessage.error(e.message)
  }
}

async function doCancel(row) {
  try {
    const { value } = await ElMessageBox.prompt('取消原因（必填）', `取消 ${row.shipNo}`,
      { inputPattern: /\S+/, inputErrorMessage: '原因必填' })
    await cancelShipmentApi(row.id, value)
    ElMessage.success('已取消')
    await loadShips()
  } catch (e) {
    if (e !== 'cancel' && e?.message) ElMessage.error(e.message)
  }
}

async function openDetail(row) {
  const res = await getShipmentDetailApi(row.id)
  detail.value = res.data
  detailVisible.value = true
}

onMounted(async () => {
  await loadSo()
  if (sos.value.length) {
    soId.value = sos.value[0].id
    await loadLines()
  }
  await loadShips()
})
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; }
.mb12 { margin-bottom: 12px; }
.bar { display: flex; justify-content: space-between; align-items: center; margin-top: 10px; font-size: 13px; }
.ok { color: #67c23a; font-weight: 600; }
.bad { color: #f56c6c; font-weight: 600; }
.muted { color: #c0c4cc; }
.mr4 { margin-right: 4px; }
</style>
