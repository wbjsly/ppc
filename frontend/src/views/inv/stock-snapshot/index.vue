<template>
  <div class="page">
    <el-tabs v-model="activeTab">
      <!-- ============ 4.3.1 可用库存 ============ -->
      <el-tab-pane label="可用库存" name="available">
        <el-form :inline="true" class="filter-bar" @submit.prevent>
          <el-form-item label="仓库">
            <el-select v-model="query.warehouseCode" clearable placeholder="全部" style="width: 160px"
              filterable>
              <el-option v-for="w in warehouses" :key="w.id" :label="`${w.whCode} ${w.whName}`"
                :value="w.whCode" />
            </el-select>
          </el-form-item>
          <el-form-item label="物料">
            <el-input v-model="query.itemCode" placeholder="物料编码" clearable style="width: 150px" />
          </el-form-item>
          <el-form-item label="批次">
            <el-input v-model="query.batchNo" placeholder="批次号" clearable style="width: 140px" />
          </el-form-item>
          <el-form-item label="关键字">
            <el-input v-model="query.keyword" placeholder="物料/名称/批次" clearable style="width: 160px" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="loadData">查询</el-button>
          </el-form-item>
          <span v-if="asOf" class="as-of">数据截至 {{ asOf }}</span>
        </el-form>

        <el-table :data="rows" v-loading="loading" border stripe
          :default-sort="{ prop: 'itemCode', order: 'ascending' }">
          <el-table-column prop="itemCode" label="物料编码" width="130" />
          <el-table-column prop="itemName" label="物料名称" min-width="140" show-overflow-tooltip />
          <el-table-column prop="batchNo" label="批次" width="130">
            <template #default="{ row }">{{ row.batchNo || '-' }}</template>
          </el-table-column>
          <el-table-column prop="binCode" label="仓位" width="140">
            <template #default="{ row }">
              <span v-if="row.binCode">{{ row.binCode }}</span>
              <span v-else class="muted">（未分配）</span>
            </template>
          </el-table-column>
          <el-table-column prop="warehouseCode" label="仓库" width="110" />
          <el-table-column prop="qty" label="在手" width="100" align="right">
            <template #default="{ row }">{{ fmt(row.qty) }}</template>
          </el-table-column>
          <el-table-column label="冻结" width="110" align="right">
            <template #default="{ row }">
              <el-link v-if="Number(row.frozen) > 0" type="primary" :underline="false"
                @click="openFreezeDetail(row)">{{ fmt(row.frozen) }}</el-link>
              <span v-else>{{ fmt(row.frozen) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="预留（批次级）" width="120" align="right">
            <template #default="{ row }">
              <el-link v-if="Number(row.reserved) > 0" type="primary" :underline="false"
                @click="openReservedDetail(row)">{{ fmt(row.reserved) }}</el-link>
              <span v-else>{{ fmt(row.reserved) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="可用（批次级）" width="120" align="right">
            <template #default="{ row }">
              <b>{{ fmt(row.available) }}</b>
            </template>
          </el-table-column>
          <el-table-column prop="inboundDate" label="入库日期" width="110" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag v-if="row.pendingVerify" type="danger" size="small">待核实</el-tag>
              <el-tag v-else type="success" size="small">正常</el-tag>
            </template>
          </el-table-column>
        </el-table>

        <div class="totals">
          合计（当前筛选）：在手 <b>{{ fmt(totals.qty) }}</b> ／ 冻结 <b>{{ fmt(totals.frozen) }}</b>
          ／ 预留 <b>{{ fmt(totals.reserved) }}</b> ／ 可用 <b>{{ fmt(totals.available) }}</b>
          <span class="formula-tip">可用 = 在手 − 冻结 − 预留（批次级预留不因多仓位行重复计，
            合计按批次汇总，BR-4.4-14；明细行 = SKU+仓库+批次+仓位）</span>
        </div>
      </el-tab-pane>

      <!-- ============ 4.3.3 在制库存（空态骨架，偏差 D5） ============ -->
      <el-tab-pane label="在制库存" name="wip">
        <el-empty description="暂无在制数据 —— 待工单管理（5.4）上线后自动接入，接口契约已就位">
          <template #image>
            <el-icon :size="64" color="#c0c4cc"><Coin /></el-icon>
          </template>
        </el-empty>
      </el-tab-pane>
    </el-tabs>

    <!-- 预留下钻弹窗 -->
    <el-dialog v-model="reservedVisible" title="预留明细（ACTIVE）" width="640px">
      <el-table :data="reservedRows" border size="small" v-loading="detailLoading">
        <el-table-column prop="soNo" label="SO 单号" width="150" />
        <el-table-column prop="lineNo" label="行号" width="70" align="center" />
        <el-table-column prop="lockAt" label="锁定时间" width="170" />
        <el-table-column prop="qty" label="数量" align="right">
          <template #default="{ row }">{{ fmt(row.qty) }}</template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="reservedVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 冻结下钻弹窗 -->
    <el-dialog v-model="freezeVisible" title="冻结明细（生效中台账）" width="720px">
      <el-table :data="freezeRows" border size="small" v-loading="detailLoading">
        <el-table-column prop="freezeNo" label="冻结单号" width="150" />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag :type="row.freezeType === 'QUALITY' ? 'warning' : 'danger'" size="small">
              {{ row.freezeType === 'QUALITY' ? '质量' : '财务' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="qty" label="数量" width="90" align="right">
          <template #default="{ row }">{{ fmt(row.qty) }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" min-width="150" show-overflow-tooltip />
        <el-table-column label="来源" width="90">
          <template #default="{ row }">{{ row.source === 'NCR' ? 'NCR' : '手工' }}</template>
        </el-table-column>
        <el-table-column prop="createDate" label="时间" width="160" />
      </el-table>
      <template #footer>
        <el-button @click="freezeVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRoute } from 'vue-router'
import { Coin } from '@element-plus/icons-vue'
import { getSnapshotApi, getReservedDetailApi, getFreezeDetailApi, getWipStockApi } from '@/api/inv/snapshot'
import { getWarehousesApi } from '@/api/inv/warehouse'

const route = useRoute()
// 同组件双路由：/m/4.3.3 打开时直接落在在制 Tab（spec stock-snapshot D8）
const activeTab = ref(route.path === '/m/4.3.3' ? 'wip' : 'available')

const loading = ref(false)
const detailLoading = ref(false)
const rows = ref([])
const totals = ref({ qty: 0, frozen: 0, reserved: 0, available: 0 })
const asOf = ref('')
const warehouses = ref([])
const query = ref({ warehouseCode: '', itemCode: '', batchNo: '', keyword: '' })

const reservedVisible = ref(false)
const freezeVisible = ref(false)
const reservedRows = ref([])
const freezeRows = ref([])

function fmt(v) {
  if (v === null || v === undefined) return '0'
  const n = Number(v)
  return Number.isInteger(n) ? String(n) : n.toFixed(4).replace(/0+$/, '').replace(/\.$/, '')
}

function fmtTime(v) {
  if (!v) return ''
  return String(v).replace('T', ' ').slice(0, 19)
}

async function loadData() {
  loading.value = true
  try {
    const params = {}
    Object.entries(query.value).forEach(([k, v]) => { if (v) params[k] = v })
    const res = await getSnapshotApi(params)
    rows.value = res.data.rows || []
    totals.value = res.data.totals || { qty: 0, frozen: 0, reserved: 0, available: 0 }
    asOf.value = fmtTime(res.data.asOf)
  } finally {
    loading.value = false
  }
}

async function openReservedDetail(row) {
  reservedVisible.value = true
  detailLoading.value = true
  reservedRows.value = []
  try {
    const res = await getReservedDetailApi({
      warehouseCode: row.warehouseCode, itemCode: row.itemCode, batchNo: row.batchNo || ''
    })
    reservedRows.value = (res.data || []).map(r => ({ ...r, lockAt: fmtTime(r.lockAt) }))
  } finally {
    detailLoading.value = false
  }
}

async function openFreezeDetail(row) {
  freezeVisible.value = true
  detailLoading.value = true
  freezeRows.value = []
  try {
    const res = await getFreezeDetailApi({
      warehouseCode: row.warehouseCode, itemCode: row.itemCode, batchNo: row.batchNo || ''
    })
    freezeRows.value = (res.data || []).map(r => ({ ...r, createDate: fmtTime(r.createDate) }))
  } finally {
    detailLoading.value = false
  }
}

onMounted(async () => {
  loadData()
  try {
    const res = await getWarehousesApi({ page: 1, size: 200 })
    warehouses.value = res.data?.records || res.data?.rows || res.data || []
  } catch {
    warehouses.value = []
  }
  if (activeTab.value === 'wip') {
    // 预热契约接口（恒空），5.x 接入时无需改页面
    getWipStockApi().catch(() => {})
  }
})
</script>

<style scoped>
.page { padding: 4px; }
.filter-bar { margin-bottom: 4px; }
.as-of { color: #909399; font-size: 12px; line-height: 32px; }
.totals { margin-top: 12px; padding: 10px 14px; background: #f5f7fa; border-radius: 4px; color: #303133; }
.formula-tip { margin-left: 16px; color: #909399; font-size: 12px; }
.muted { color: #c0c4cc; }
</style>
