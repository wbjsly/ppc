<template>
  <div class="page">
    <el-alert type="info" :closable="false" style="margin-bottom: 8px"
      :title="`库龄分析（4.14.3）：按入库日期分桶，呆滞阈值 ${buckets.staleThreshold ?? 180} 天（SLOW_MOVING_AGE_DAYS）。呆滞行可一键发起呆滞报废（三方会签链）。`" />

    <el-form inline @submit.prevent>
      <el-form-item label="仓库">
        <el-select v-model="q.warehouseCode" clearable placeholder="全部" style="width: 160px">
          <el-option v-for="w in warehouses" :key="w.whCode" :label="`${w.whName}（${w.whCode}）`"
            :value="w.whCode" />
        </el-select>
      </el-form-item>
      <el-form-item label="物料">
        <el-input v-model="q.itemCode" placeholder="物料编码" style="width: 140px" clearable />
      </el-form-item>
      <el-form-item label="ABC">
        <el-select v-model="q.abcClass" clearable placeholder="全部" style="width: 100px">
          <el-option label="A" value="A" />
          <el-option label="B" value="B" />
          <el-option label="C" value="C" />
          <el-option label="未分类" value="NONE" />
        </el-select>
      </el-form-item>
      <el-button type="primary" :loading="loading" @click="loadAll">查询</el-button>
      <el-button :loading="scanning" @click="doScan">触发呆滞扫描</el-button>
      <el-button :loading="exporting" @click="doExport">导出 CSV</el-button>
    </el-form>

    <!-- 分桶卡片 -->
    <div class="bucket-row">
      <div v-for="b in bucketList" :key="b.bucket" class="bucket-card" :class="{ stale: b.stale }">
        <div class="bucket-label">{{ b.label }}</div>
        <div class="bucket-count">{{ b.count }} 行</div>
        <div class="bucket-amt">{{ fmt(b.amount) }}</div>
      </div>
      <div class="bucket-card ratio">
        <div class="bucket-label">呆滞库存占比</div>
        <div class="bucket-count" :class="{ danger: stalePercent > 0 }">{{ stalePercent }}%</div>
        <div class="bucket-amt">呆滞金额 {{ fmt(buckets.staleAmount) }}</div>
      </div>
    </div>
    <div class="muted" v-if="Number(buckets.unknownCount) > 0">
      未知库龄 {{ buckets.unknownCount }} 行（无入库日期，不计入分桶）
    </div>

    <!-- 明细 / 呆滞清单 -->
    <el-tabs v-model="tab" @tab-change="loadList">
      <el-tab-pane label="库龄明细" name="all" />
      <el-tab-pane name="stale">
        <template #label>呆滞清单<span v-if="staleTotal">（{{ staleTotal }}）</span></template>
      </el-tab-pane>
    </el-tabs>

    <el-table :data="list" border size="small" v-loading="loading" max-height="calc(100vh - 420px)">
      <el-table-column prop="WAREHOUSE_CODE" label="仓库" width="100" />
      <el-table-column prop="ITEM_CODE" label="物料" width="130" />
      <el-table-column prop="ITEM_NAME" label="名称" min-width="140" show-overflow-tooltip />
      <el-table-column prop="BATCH_NO" label="批次" width="120" />
      <el-table-column prop="BIN_CODE" label="仓位" width="130">
        <template #default="{ row }">{{ row.BIN_CODE || '-' }}</template>
      </el-table-column>
      <el-table-column prop="QTY" label="数量" width="90" align="right" />
      <el-table-column prop="INBOUND_DATE" label="入库日期" width="110" />
      <el-table-column label="库龄(天)" width="100" align="right">
        <template #default="{ row }">
          <span v-if="row.AGE_DAYS == null" class="muted">未知</span>
          <el-tag v-else-if="row.AGE_DAYS > (buckets.staleThreshold ?? 180)" type="danger" size="small">
            {{ row.AGE_DAYS }}
          </el-tag>
          <span v-else>{{ row.AGE_DAYS }}</span>
        </template>
      </el-table-column>
      <el-table-column label="ABC" width="70" align="center">
        <template #default="{ row }">{{ row.ABC_CLASS || '未分类' }}</template>
      </el-table-column>
      <el-table-column label="金额" width="100" align="right">
        <template #default="{ row }">{{ fmt(row.AMT) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button v-if="isStale(row)" link type="danger" size="small"
            @click="goScrap(row)">发起呆滞报废</el-button>
          <span v-else class="muted">-</span>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination class="pager" layout="total, prev, pager, next" :total="total"
      :page-size="q.size" :current-page="q.current" @current-change="loadList" />
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  getAgingApi, getAgingListApi, scanSlowMovingApi,
  downloadReportApi, submitBgExportApi
} from '@/api/inv/report'
import { getEnabledWarehousesApi } from '@/api/inv/warehouse'

const router = useRouter()
const q = reactive({ warehouseCode: '', itemCode: '', abcClass: '', current: 1, size: 50 })
const buckets = ref({})
const list = ref([])
const total = ref(0)
const staleTotal = ref(0)
const tab = ref('all')
const loading = ref(false)
const scanning = ref(false)
const exporting = ref(false)
const warehouses = ref([])

const bucketList = computed(() => buckets.value.buckets || [])
const stalePercent = computed(() =>
  buckets.value.staleRatio == null ? '0.0' : Number(buckets.value.staleRatio).toFixed(1))
const threshold = () => Number(buckets.value.staleThreshold ?? 180)
const isStale = row => row.AGE_DAYS != null && Number(row.AGE_DAYS) > threshold()
const fmt = v => (v == null ? '-' : Number(v).toFixed(2))

async function loadAll() {
  loading.value = true
  try {
    const [b, l] = await Promise.all([
      getAgingApi({ warehouseCode: q.warehouseCode, itemCode: q.itemCode, abcClass: q.abcClass }),
      getAgingListApi(listParams())
    ])
    buckets.value = b.data || {}
    list.value = l.data.records || []
    total.value = Number(l.data.total || 0)
    staleTotal.value = total.value
  } finally {
    loading.value = false
  }
}

function listParams() {
  return {
    warehouseCode: q.warehouseCode, itemCode: q.itemCode, abcClass: q.abcClass,
    staleOnly: tab.value === 'stale', current: q.current, size: q.size
  }
}

async function loadList() {
  q.current = 1
  loading.value = true
  try {
    const l = await getAgingListApi(listParams())
    list.value = l.data.records || []
    total.value = Number(l.data.total || 0)
    if (tab.value === 'stale') staleTotal.value = total.value
  } finally {
    loading.value = false
  }
}

function goScrap(row) {
  // 预填呆滞原因与批次维度（报废页 openCreate 读 query；STALE 走三方会签，C-4.4-14 过账卡控既有）
  router.push({
    path: '/inv/scrap-order',
    query: {
      reason: 'STALE',
      itemCode: row.ITEM_CODE,
      warehouseCode: row.WAREHOUSE_CODE
    }
  })
}

async function doScan() {
  scanning.value = true
  try {
    const res = await scanSlowMovingApi()
    ElMessage.success(`扫描完成，通知 ${res.data.notified} 个呆滞物料（同日幂等）`)
  } finally {
    scanning.value = false
  }
}

async function doExport() {
  exporting.value = true
  try {
    await downloadReportApi('aging', {
      warehouseCode: q.warehouseCode, itemCode: q.itemCode, abcClass: q.abcClass,
      staleOnly: tab.value === 'stale'
    })
    ElMessage.success('已导出')
  } catch (e) {
    if (e.overLimit) {
      await submitBgExportApi('aging', {
        warehouseCode: q.warehouseCode, itemCode: q.itemCode, abcClass: q.abcClass,
        staleOnly: tab.value === 'stale'
      }, 0)
      ElMessage.success('已转后台导出任务')
    } else {
      ElMessage.error(e.message || '导出失败')
    }
  } finally {
    exporting.value = false
  }
}

onMounted(async () => {
  try {
    const res = await getEnabledWarehousesApi()
    warehouses.value = res.data || []
  } catch { warehouses.value = [] }
  loadAll()
})
</script>

<style scoped>
.page { padding: 4px; }
.bucket-row { display: flex; gap: 10px; margin-bottom: 8px; flex-wrap: wrap; }
.bucket-card {
  flex: 1; min-width: 130px; border: 1px solid #ebeef5; border-radius: 6px;
  padding: 10px 12px; text-align: center; background: #fff;
}
.bucket-card.stale { border-color: #fbc4c4; background: #fef0f0; }
.bucket-card.ratio { background: #fdf6ec; border-color: #f5dab1; }
.bucket-label { font-size: 12px; color: #909399; }
.bucket-count { font-size: 20px; font-weight: 600; margin-top: 4px; }
.bucket-count.danger { color: #f56c6c; }
.bucket-amt { font-size: 12px; color: #606266; margin-top: 2px; }
.muted { color: #909399; font-size: 12px; }
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
