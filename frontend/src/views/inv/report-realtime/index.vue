<template>
  <div class="page">
    <el-alert type="info" :closable="false" style="margin-bottom: 8px"
      title="实时查询（4.14.1）：库存位行明细视角（含库龄列）。四列口径计算与预留/冻结下钻见 4.3.1 可用库存查询。" />

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
      <el-form-item label="批次">
        <el-input v-model="q.batchNo" placeholder="批次号" style="width: 130px" clearable />
      </el-form-item>
      <el-form-item label="ABC">
        <el-select v-model="q.abcClass" clearable placeholder="全部" style="width: 100px">
          <el-option label="A" value="A" />
          <el-option label="B" value="B" />
          <el-option label="C" value="C" />
          <el-option label="未分类" value="NONE" />
        </el-select>
      </el-form-item>
      <el-form-item label="关键字">
        <el-input v-model="q.keyword" placeholder="物料/批次/仓位" style="width: 170px" clearable
          @keyup.enter="load(1)" />
      </el-form-item>
      <el-button type="primary" @click="load(1)">查询</el-button>
      <el-button :loading="exporting" @click="doExport">导出 CSV</el-button>
    </el-form>

    <div class="totals" v-if="totals">
      共 <b>{{ total }}</b> 行位 · 合计：在手 <b>{{ totals.qty }}</b> · 冻结 <b>{{ totals.frozen }}</b>
      · 可用 <b>{{ totals.available }}</b> <span class="as-of">（as of {{ asOf }}）</span>
    </div>

    <el-table :data="rows" border v-loading="loading" height="calc(100vh - 240px)">
      <el-table-column prop="WAREHOUSE_CODE" label="仓库" width="100" />
      <el-table-column prop="ITEM_CODE" label="物料编码" width="130" />
      <el-table-column prop="ITEM_NAME" label="物料名称" min-width="150" show-overflow-tooltip />
      <el-table-column prop="BATCH_NO" label="批次" width="120" />
      <el-table-column prop="BIN_CODE" label="仓位" width="130">
        <template #default="{ row }">{{ row.BIN_CODE || '-' }}</template>
      </el-table-column>
      <el-table-column label="在手" width="90" align="right">
        <template #default="{ row }">{{ row.QTY }}</template>
      </el-table-column>
      <el-table-column label="冻结" width="90" align="right">
        <template #default="{ row }">{{ frozenOf(row) }}</template>
      </el-table-column>
      <el-table-column label="可用" width="90" align="right">
        <template #default="{ row }">{{ row.AVAILABLE_QTY }}</template>
      </el-table-column>
      <el-table-column prop="INBOUND_DATE" label="入库日期" width="110">
        <template #default="{ row }">{{ row.INBOUND_DATE || '-' }}</template>
      </el-table-column>
      <el-table-column label="库龄(天)" width="100" align="right" sortable
        :sort-method="(a, b) => ageOf(b) - ageOf(a)">
        <template #default="{ row }">
          <span v-if="row.AGE_DAYS == null" class="muted">未知</span>
          <el-tag v-else-if="row.AGE_DAYS > 180" type="danger" size="small">{{ row.AGE_DAYS }}</el-tag>
          <span v-else>{{ row.AGE_DAYS }}</span>
        </template>
      </el-table-column>
      <el-table-column label="ABC" width="70" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.ABC_CLASS" size="small"
            :type="row.ABC_CLASS === 'A' ? 'danger' : row.ABC_CLASS === 'B' ? 'warning' : 'info'">
            {{ row.ABC_CLASS }}
          </el-tag>
          <span v-else class="muted">未分类</span>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination class="pager" layout="total, prev, pager, next" :total="total"
      :page-size="q.size" :current-page="q.current" @current-change="load" />
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getRealtimeApi, downloadReportApi, submitBgExportApi } from '@/api/inv/report'
import { getEnabledWarehousesApi } from '@/api/inv/warehouse'

const q = reactive({ warehouseCode: '', itemCode: '', batchNo: '', keyword: '', abcClass: '', current: 1, size: 50 })
const rows = ref([])
const total = ref(0)
const totals = ref(null)
const asOf = ref('')
const loading = ref(false)
const exporting = ref(false)
const warehouses = ref([])

const nvl = v => (v == null ? 0 : Number(v))
const frozenOf = row => nvl(row.QC_QTY) + nvl(row.FIN_QTY)
const ageOf = row => (row.AGE_DAYS == null ? -1 : Number(row.AGE_DAYS))

async function load(p = 1) {
  q.current = p
  loading.value = true
  try {
    const res = await getRealtimeApi(q)
    rows.value = res.data.records || []
    total.value = Number(res.data.total || 0)
    totals.value = res.data.totals || null
    asOf.value = res.data.asOf || ''
  } finally {
    loading.value = false
  }
}

async function doExport() {
  exporting.value = true
  try {
    const params = {
      warehouseCode: q.warehouseCode, itemCode: q.itemCode, batchNo: q.batchNo,
      keyword: q.keyword, abcClass: q.abcClass
    }
    await downloadReportApi('realtime', params)
    ElMessage.success('已导出（或已转后台任务）')
  } catch (e) {
    if (e.overLimit) {
      await submitBgExportApi('realtime', params, 0)
      ElMessage.success('超过行数上限，已转后台导出任务（审批通过后站内通知下载）')
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
  load(1)
})
</script>

<style scoped>
.page { padding: 4px; }
.totals { margin-bottom: 8px; font-size: 13px; color: #303133; }
.as-of { color: #909399; font-size: 12px; margin-left: 8px; }
.muted { color: #909399; font-size: 12px; }
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
