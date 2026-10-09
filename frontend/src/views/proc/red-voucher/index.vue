<template>
  <div class="rv-page">
    <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
      title="红字凭证（2.6.3）：已出库退货的红字入库凭证台账（只读）——凭证号 RV + 退货单 + 供应商 + 物料明细 + 出库追溯"
      description="数据来源：2.6.1 质量退货与 2.6.2 其他退货出库动作自动生成（BR-4.2-34）；本台账仅归集查询，不提供编辑或作废。应付冲减为 4.6 财务域桩（D4）。" />

    <el-card shadow="never">
      <div class="toolbar">
        <el-input v-model="filters.redDocNo" placeholder="凭证号 RV" clearable
          style="width: 190px;" @keyup.enter="load" @clear="load" />
        <el-input v-model="filters.returnNo" placeholder="退货单号" clearable
          style="width: 170px;" @keyup.enter="load" @clear="load" />
        <el-select v-model="filters.sourceType" placeholder="来源" clearable style="width: 130px;" @change="load">
          <el-option label="质量退货(NCR)" value="NCR" />
          <el-option label="其他退货(MANUAL)" value="MANUAL" />
        </el-select>
        <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD"
          start-placeholder="出库开始" end-placeholder="出库结束" style="width: 250px;"
          @change="load" />
        <el-button type="primary" @click="load">查询</el-button>
      </div>

      <el-table :data="rows" size="small" border v-loading="loading">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div style="padding: 8px 16px;">
              <h4 class="sec">退货行明细</h4>
              <el-table :data="row.lines || []" size="mini" border>
                <el-table-column prop="lineNo" label="#" width="45" />
                <el-table-column prop="itemCode" label="物料" width="140" />
                <el-table-column prop="itemName" label="名称" min-width="120" show-overflow-tooltip />
                <el-table-column prop="batchNo" label="批次" width="110" />
                <el-table-column prop="qty" label="数量" width="80" align="right" />
                <el-table-column prop="unitPrice" label="单价" width="90" align="right" />
                <el-table-column prop="amount" label="金额" width="100" align="right" />
              </el-table>
              <div style="margin-top: 8px; font-size: 12px; color: #606266;">
                关联链：原 PO {{ row.poNo || '—' }} · 收货单 {{ row.grNo || '—' }} ·
                原入库凭证 {{ row.originDocNo || '—' }}
                <template v-if="row.ncrNo"> · NCR {{ row.ncrNo }}</template>
                · 出库 {{ row.outDate || '—' }}（{{ row.outBy || '—' }}）
                <el-button link type="primary" size="small" style="margin-left: 8px;"
                  @click="goReturn(row)">查看退货单 →</el-button>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="redDocNo" label="红字凭证号" width="165">
          <template #default="{ row }"><b style="color:#67C23A;">{{ row.redDocNo }}</b></template>
        </el-table-column>
        <el-table-column prop="returnNo" label="退货单号" width="155" />
        <el-table-column label="来源" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.sourceType === 'NCR' ? 'danger' : 'info'">
              {{ row.sourceType === 'NCR' ? '质量退货' : '其他退货' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="supplierName" label="供应商" width="140" show-overflow-tooltip />
        <el-table-column prop="poNo" label="原 PO" width="150" show-overflow-tooltip />
        <el-table-column prop="totalQty" label="数量" width="90" align="right" />
        <el-table-column prop="totalAmt" label="金额" width="110" align="right" />
        <el-table-column prop="outDate" label="出库时间" width="165" />
        <el-table-column prop="outBy" label="出库人" width="110" />
      </el-table>
      <el-pagination style="margin-top: 10px; justify-content: flex-end;"
        layout="total, prev, pager, next" :total="total"
        v-model:current-page="filters.current" :page-size="filters.size" @current-change="load" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getRedVoucherPageApi } from '@/api/proc/red-voucher'

const router = useRouter()
const loading = ref(false)
const rows = ref([])
const total = ref(0)
const dateRange = ref([])
const filters = reactive({ redDocNo: '', returnNo: '', sourceType: '', current: 1, size: 10 })

async function load() {
  loading.value = true
  try {
    const res = await getRedVoucherPageApi({
      current: filters.current, size: filters.size,
      redDocNo: filters.redDocNo || undefined,
      returnNo: filters.returnNo || undefined,
      sourceType: filters.sourceType || undefined,
      dateFrom: (dateRange.value && dateRange.value[0]) || undefined,
      dateTo: (dateRange.value && dateRange.value[1]) || undefined
    })
    rows.value = (res.data && res.data.records) || []
    total.value = (res.data && res.data.total) || 0
  } finally {
    loading.value = false
  }
}

function goReturn(row) {
  router.push(row.sourceType === 'NCR' ? '/m/2.6.1' : '/m/2.6.2')
}

onMounted(load)
</script>

<style scoped>
.rv-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.sec { margin: 4px 0 8px; font-size: 13px; color: #303133; }
</style>
