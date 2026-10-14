<template>
  <div class="record-list" v-loading="loading">
    <el-card>
      <template #header>
        <div class="card-header">
          <span class="ttl">{{ title }}</span>
          <el-button size="small" :loading="loading" @click="load">刷新</el-button>
        </div>
      </template>

      <el-alert type="info" :closable="false" style="margin-bottom: 10px;"
                title="检验记录（6.3.2）：全状态历史查询——已放行/冻结/免检/在途批次均可查。"
                description="行点击下钻批次详情工作台；行内「打印报告」出七段版式检验报告（含抽样记录段）。" />

      <div class="filters">
        <el-select v-model="filters.lotType" placeholder="类型" clearable style="width: 120px;" @change="load">
          <el-option label="IQC 来料" value="IQC" />
          <el-option label="IPQC 过程" value="IPQC" />
          <el-option label="OQC 出货" value="OQC" />
        </el-select>
        <el-select v-model="filters.result" placeholder="判定结果" clearable style="width: 130px;" @change="load">
          <el-option label="合格 PASS" value="PASS" />
          <el-option label="不合格 FAIL" value="FAIL" />
          <el-option label="待定 PENDING" value="PENDING" />
        </el-select>
        <el-select v-model="filters.status" placeholder="状态" clearable style="width: 140px;" @change="load">
          <el-option label="已放行" value="RELEASED" />
          <el-option label="不合格冻结" value="FROZEN" />
          <el-option label="免检" value="SKIPPED" />
          <el-option label="无标准阻断" value="BLOCKED" />
          <el-option label="待检" value="PENDING" />
          <el-option label="录入中" value="INPUTTING" />
          <el-option label="边界复核" value="REVIEWING" />
        </el-select>
        <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD"
                        start-placeholder="开始日期" end-placeholder="结束日期" style="width: 240px;"
                        @change="load" />
        <el-input v-model="filters.itemCode" placeholder="物料编码" clearable style="width: 140px;"
                  @keyup.enter="load" @clear="load" />
        <el-input v-model="filters.counterparty" placeholder="供应商/对手方" clearable style="width: 140px;"
                  @keyup.enter="load" @clear="load" />
        <el-input v-model="filters.keyword" placeholder="批号/品名/批次号" clearable style="width: 160px;"
                  @keyup.enter="load" @clear="load" />
        <el-button type="primary" @click="load">查询</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" stripe @row-click="goDetail" style="cursor: pointer;">
        <el-table-column prop="lotNo" label="检验批号" width="170">
          <template #default="{ row }"><b>{{ row.lotNo }}</b></template>
        </el-table-column>
        <el-table-column prop="lotType" label="类型" width="70" />
        <el-table-column label="物料" min-width="150">
          <template #default="{ row }">{{ row.itemCode }} {{ row.itemName }}</template>
        </el-table-column>
        <el-table-column prop="lotQty" label="批量" width="80" align="right" />
        <el-table-column prop="sampleQty" label="样本量" width="80" align="right" />
        <el-table-column prop="supplierName" label="供应商" width="110" show-overflow-tooltip />
        <el-table-column label="判定" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="resultTag(row.result)">{{ resultName(row.result) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ statusName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="inspectorName" label="质检员" width="90" />
        <el-table-column prop="releasedBy" label="放行人" width="90" />
        <el-table-column prop="releasedDate" label="放行时间" width="160" />
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openReport(row)">打印报告</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && !rows.length" description="无匹配的历史检验记录" :image-size="80" />
      <el-pagination style="margin-top: 10px;" layout="total, prev, pager, next" :total="total"
                     :page-size="size" :current-page="page" @current-change="p => { page = p; load() }" />
    </el-card>

    <ReportDialog v-model="reportVisible" :lot-id="reportLotId" />
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getLotHistoryApi } from '@/api/qms/inspection'
import ReportDialog from './components/ReportDialog.vue'

const route = useRoute()
const router = useRouter()
const title = computed(() => route.meta.title || '检验记录')

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(50)
const dateRange = ref(null)
const filters = reactive({ lotType: '', result: '', status: '', itemCode: '', counterparty: '', keyword: '' })
const reportVisible = ref(false)
const reportLotId = ref('')

function resultName(r) {
  return { PASS: '合格', FAIL: '不合格', PENDING: '待定' }[r] || r || '—'
}
function resultTag(r) {
  return { PASS: 'success', FAIL: 'danger', PENDING: 'warning' }[r] || 'info'
}
function statusName(s) {
  return { RELEASED: '已放行', FROZEN: '冻结', SKIPPED: '免检', BLOCKED: '阻断',
           PENDING: '待检', INPUTTING: '录入中', REVIEWING: '复核' }[s] || s
}
function statusTag(s) {
  return { RELEASED: 'success', FROZEN: 'danger', SKIPPED: 'info', BLOCKED: 'danger',
           PENDING: 'primary', INPUTTING: 'warning', REVIEWING: 'warning' }[s] || 'info'
}

async function load() {
  loading.value = true
  try {
    const params = { current: page.value, size: size.value }
    if (filters.lotType) params.lotType = filters.lotType
    if (filters.result) params.result = filters.result
    if (filters.status) params.status = filters.status
    if (filters.itemCode) params.itemCode = filters.itemCode
    if (filters.counterparty) params.counterparty = filters.counterparty
    if (filters.keyword) params.keyword = filters.keyword
    if (dateRange.value?.[0]) params.dateFrom = dateRange.value[0]
    if (dateRange.value?.[1]) params.dateTo = dateRange.value[1]
    const res = await getLotHistoryApi(params)
    rows.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch (e) { /* 拦截器提示 */ } finally { loading.value = false }
}

function goDetail(row) {
  router.push(`/m/6.2.lot/${row.id}`)
}

function openReport(row) {
  reportLotId.value = row.id
  reportVisible.value = true
}

onMounted(load)
</script>

<style scoped>
.record-list { padding: 16px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.ttl { font-weight: bold; font-size: 16px; }
.filters { display: flex; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
</style>
