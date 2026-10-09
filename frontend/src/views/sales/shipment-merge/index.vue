<template>
  <div class="page-wrap">
    <el-alert type="info" :closable="false" show-icon class="mb12"
              title="3.7.2 合并发货"
              description="同一客户、同一仓库的多张已确认 SO 合并为一张发货单，行级保留来源 SO 引用（过账与确认时各自回写）。拣货作业由库存域拣货复核（4.7）承载，本页为销售侧策略与确认。" />

    <el-card shadow="never" class="mb12">
      <template #header>
        <div class="card-head">
          <span>① 勾选同客户同仓的已确认订单（≥2 张）</span>
          <div>
            <el-select v-model="warehouseCode" size="small" style="width:150px">
              <el-option v-for="w in warehouses" :key="w.whCode"
                         :label="`${w.whCode} ${w.whName}`" :value="w.whCode" />
            </el-select>
            <el-button size="small" type="primary" style="margin-left:8px"
                       :disabled="selected.length < 2" :loading="generating"
                       @click="generate">
              合并生成发货单（{{ selected.length }} 张）
            </el-button>
          </div>
        </div>
      </template>

      <el-table :data="sos" size="small" border @selection-change="onSelect"
                v-loading="loading" :row-key="r => r.id">
        <el-table-column type="selection" width="45" />
        <el-table-column prop="soNo" label="订单号" width="170" />
        <el-table-column prop="customerName" label="客户" min-width="150" show-overflow-tooltip />
        <el-table-column prop="totalAmount" label="金额" width="120" align="right">
          <template #default="{ row }">¥{{ Number(row.totalAmount).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'CONFIRMED' ? 'success' : 'warning'">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="客户一致性" width="130">
          <template #default="{ row }">
            <el-tag v-if="!selected.length || row.customerId === selected[0]?.customerId"
                    size="small" type="success">同客户 ✔</el-tag>
            <el-tag v-else size="small" type="danger">客户不同</el-tag>
          </template>
        </el-table-column>
      </el-table>

      <el-alert v-if="mixedCustomer" type="error" :closable="false" show-icon class="mt8"
                title="所选订单客户不一致，合并发货要求同一客户" />
      <p class="hint">提示：默认按各行「未发余量与可锁预留」的可发量整行纳入；无可用余量的行自动跳过。</p>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span>② 合并发货单</span>
          <el-button size="small" @click="loadShips">刷新</el-button>
        </div>
      </template>
      <el-table :data="ships" size="small" border>
        <el-table-column prop="shipNo" label="发货单号" width="170" />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.shipType === 'MERGE' ? 'warning' : 'info'">
              {{ row.shipType }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="customerName" label="客户" min-width="140" show-overflow-tooltip />
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
        <el-table-column label="操作" width="240">
          <template #default="{ row }">
            <template v-if="row.status === 'DRAFT'">
              <el-button size="small" type="primary" @click="doPost(row)">出库过账</el-button>
              <el-button size="small" type="danger" link @click="doCancel(row)">取消</el-button>
            </template>
            <template v-else>
              <el-button size="small" link @click="openDetail(row)">来源明细</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 来源明细（多 SO 行级引用） -->
    <el-dialog v-model="detailVisible" :title="`合并发货单 ${detail?.shipment?.shipNo}`" width="820px">
      <template v-if="detail">
        <el-alert type="info" :closable="false" show-icon class="mb12"
                  :title="detail.pickingNote" />
        <el-table :data="detail.lines" size="small" border>
          <el-table-column label="来源 SO" width="170">
            <template #default="{ row }">{{ row.soNo }}</template>
          </el-table-column>
          <el-table-column prop="soLineNo" label="行" width="50" />
          <el-table-column prop="itemCode" label="SKU" width="140" />
          <el-table-column prop="qty" label="数量" width="90" align="right" />
          <el-table-column label="金额" width="100" align="right">
            <template #default="{ row }">¥{{ Number(row.amount || 0).toFixed(2) }}</template>
          </el-table-column>
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
  getShipmentsApi, getShipmentDetailApi, generateMergeApi,
  postShipmentApi, cancelShipmentApi
} from '@/api/sd/shipment'
import { getSoPageApi } from '@/api/sd/so'
import { getEnabledWarehousesApi } from '@/api/inv/warehouse'

const sos = ref([])
const selected = ref([])
const warehouses = ref([])
const warehouseCode = ref('WH-MAIN')
const ships = ref([])
const loading = ref(false)
const generating = ref(false)
const detailVisible = ref(false)
const detail = ref(null)

const mixedCustomer = computed(() =>
  selected.value.length >= 2 &&
  new Set(selected.value.map(s => s.customerId)).size > 1)

function onSelect(rows) {
  selected.value = rows
}
function parseAlloc(json) {
  try { return JSON.parse(json) } catch (e) { return [] }
}

async function loadSo() {
  loading.value = true
  try {
    const [a, b] = await Promise.all([
      getSoPageApi({ current: 1, size: 50, status: 'CONFIRMED' }),
      getSoPageApi({ current: 1, size: 50, status: 'PARTIAL_SHIPPED' })
    ])
    sos.value = [...(a.data.records || []), ...(b.data.records || [])]
  } finally {
    loading.value = false
  }
}

async function loadShips() {
  const res = await getShipmentsApi({ current: 1, size: 20 })
  ships.value = (res.data.records || []).filter(s => s.shipType === 'MERGE')
}

async function generate() {
  if (mixedCustomer.value) return ElMessage.error('所选订单客户不一致')
  generating.value = true
  try {
    const res = await generateMergeApi(selected.value.map(s => s.id), warehouseCode.value)
    ElMessage.success(`合并发货单 ${res.data.shipment.shipNo} 已生成（${res.data.lines.length} 行）`)
    await loadShips()
  } catch (e) {
    ElMessage.error(e?.message || '生成失败')
  } finally {
    generating.value = false
  }
}

async function doPost(row) {
  try {
    await ElMessageBox.confirm(`出库过账 ${row.shipNo}？各来源 SO 行将分别回写已发量。`,
      '出库过账', { type: 'warning' })
    await postShipmentApi(row.id)
    ElMessage.success('出库过账完成')
    await loadShips()
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
  try {
    const w = await getEnabledWarehousesApi()
    warehouses.value = w.data || []
  } catch (e) { /* 忽略 */ }
})
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; }
.mb12 { margin-bottom: 12px; }
.mt8 { margin-top: 8px; }
.hint { font-size: 12px; color: #909399; margin-top: 8px; }
.muted { color: #c0c4cc; }
.mr4 { margin-right: 4px; }
</style>
