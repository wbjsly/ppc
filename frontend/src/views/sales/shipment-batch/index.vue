<template>
  <div class="page-wrap">
    <el-alert type="info" :closable="false" show-icon class="mb12"
              title="3.7.3 分批发货"
              description="按 3.4.2 编制的分批交付方案逐行生成发货单，每行独立交期与预留；仅生成计划发货日 ≤ 截止日的到期待发行。拣货作业由库存域拣货复核（4.7）承载，本页为销售侧策略与确认。" />

    <el-card shadow="never" class="mb12">
      <template #header>
        <div class="card-head">
          <span>① 选择有分批方案的订单</span>
          <div>
            <el-date-picker v-model="asOfDate" type="date" value-format="YYYY-MM-DD"
                            size="small" style="width:150px" placeholder="截止计划发货日" />
            <el-button size="small" type="primary" style="margin-left:8px"
                       :disabled="!soId" :loading="generating" @click="generate">
              生成分批发货单
            </el-button>
          </div>
        </div>
      </template>

      <el-select v-model="soId" filterable placeholder="选择 SO" size="small"
                 style="width:380px" @change="loadLines">
        <el-option v-for="s in sos" :key="s.id"
                   :label="`${s.soNo} ${s.customerName} ¥${s.totalAmount}（${s.status}）`"
                   :value="s.id" />
      </el-select>

      <el-table :data="lines" size="small" border class="mt12" v-loading="loading">
        <el-table-column prop="lineNo" label="#" width="45" />
        <el-table-column prop="itemCode" label="SKU" width="150" />
        <el-table-column label="计划发货日" width="130">
          <template #default="{ row }">
            <span :class="dueClass(row)">{{ row.planShipDate || '未分批' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="qty" label="行数量" width="100" align="right" />
        <el-table-column prop="shippedQty" label="已发" width="90" align="right" />
        <el-table-column label="未发余量" width="100" align="right">
          <template #default="{ row }"><b>{{ remain(row) }}</b></template>
        </el-table-column>
        <el-table-column prop="reservedQty" label="预留" width="90" align="right" />
        <el-table-column label="到期待发" width="110">
          <template #default="{ row }">
            <el-tag v-if="due(row)" size="small" type="success">是</el-tag>
            <el-tag v-else-if="row.planShipDate" size="small" type="info">未到日</el-tag>
            <el-tag v-else size="small" type="warning">无分批</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lineStatus" label="行状态" width="100" />
      </el-table>
      <p class="hint">
        到期待发 {{ dueCount }} 行；无 planShipDate 的行请先到 3.4.2 分批交付编制方案。
      </p>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span>② 分批发货单</span>
          <el-button size="small" @click="loadShips">刷新</el-button>
        </div>
      </template>
      <el-table :data="ships" size="small" border>
        <el-table-column prop="shipNo" label="发货单号" width="170" />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.shipType === 'BATCH' ? 'success' : 'info'">
              {{ row.shipType }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="totalQty" label="数量" width="100" align="right" />
        <el-table-column prop="totalAmt" label="金额" width="110" align="right">
          <template #default="{ row }">¥{{ Number(row.totalAmt).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'DRAFT' ? 'info'
              : (row.status === 'POSTED' ? 'warning' : 'success')">
              {{ row.status }}
            </el-tag>
          </template>
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

    <el-dialog v-model="detailVisible" :title="`分批发货单 ${detail?.shipment?.shipNo}`" width="780px">
      <template v-if="detail">
        <el-alert type="info" :closable="false" show-icon class="mb12"
                  :title="detail.pickingNote" />
        <el-table :data="detail.lines" size="small" border>
          <el-table-column prop="lineNo" label="#" width="50" />
          <el-table-column prop="itemCode" label="SKU" width="140" />
          <el-table-column prop="planShipDate" label="独立交期" width="120" />
          <el-table-column prop="qty" label="数量" width="90" align="right" />
          <el-table-column label="出库批次" min-width="180">
            <template #default="{ row }">
              <template v-if="row.batchAlloc">
                <el-tag v-for="(a, i) in parseAlloc(row.batchAlloc)" :key="i" size="small"
                        type="info" class="mr4">{{ a.batchNo || '(空批)' }} × {{ a.qty }}</el-tag>
              </template>
              <span v-else class="muted">未过账</span>
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
  getShipmentsApi, getShipmentDetailApi, generateBatchApi,
  postShipmentApi, cancelShipmentApi
} from '@/api/sd/shipment'
import { getSoPageApi, getSoDetailApi } from '@/api/sd/so'

const sos = ref([])
const soId = ref('')
const asOfDate = ref('')
const lines = ref([])
const ships = ref([])
const loading = ref(false)
const generating = ref(false)
const detailVisible = ref(false)
const detail = ref(null)

const dueCount = computed(() => lines.value.filter(due).length)

function remain(row) {
  return Number(row.qty || 0) - Number(row.shippedQty || 0)
}
function due(row) {
  if (!row.planShipDate || remain(row) <= 0) return false
  return !asOfDate.value || row.planShipDate <= asOfDate.value
}
function dueClass(row) {
  return row.planShipDate ? 'due-date' : 'no-plan'
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
    lines.value = res.data.lines || []
  } finally {
    loading.value = false
  }
}

async function loadShips() {
  const res = await getShipmentsApi({ current: 1, size: 20 })
  ships.value = (res.data.records || []).filter(s => s.shipType === 'BATCH')
}

async function generate() {
  generating.value = true
  try {
    const res = await generateBatchApi(soId.value, asOfDate.value || null)
    ElMessage.success(`分批发货单 ${res.data.shipment.shipNo} 已生成（${res.data.lines.length} 行）`)
    await Promise.all([loadLines(), loadShips()])
  } catch (e) {
    ElMessage.error(e?.message || '生成失败')
  } finally {
    generating.value = false
  }
}

async function doPost(row) {
  try {
    await ElMessageBox.confirm(`出库过账 ${row.shipNo}？`, '出库过账', { type: 'warning' })
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
  await Promise.all([loadSo(), loadShips()])
  if (sos.value.length) {
    soId.value = sos.value[0].id
    await loadLines()
  }
})
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; }
.mb12 { margin-bottom: 12px; }
.mt12 { margin-top: 12px; }
.hint { font-size: 12px; color: #909399; margin-top: 8px; }
.due-date { color: #67c23a; font-weight: 600; }
.no-plan { color: #e6a23c; }
.muted { color: #c0c4cc; }
.mr4 { margin-right: 4px; }
</style>
