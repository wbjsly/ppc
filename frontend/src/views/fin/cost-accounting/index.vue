<template>
  <div class="cost-accounting">
    <el-tabs v-model="activeTab">
      <!-- ============ Tab1 期间成本报表（主工作面） ============ -->
      <el-tab-pane label="期间成本报表" name="report">
        <el-card shadow="never">
          <div class="filters">
            <el-date-picker v-model="period" type="month" placeholder="报表期间（必填）"
              value-format="YYYYMM" style="width: 150px" @change="load" />
            <el-input v-model="productCode" placeholder="物料编码" clearable style="width: 160px"
              @keyup.enter="load" />
            <el-select v-model="reportStatus" placeholder="差异报告状态" clearable style="width: 150px">
              <el-option label="待审阅（RAISED）" value="RAISED" />
              <el-option label="已审阅（REVIEWED）" value="REVIEWED" />
            </el-select>
            <el-button type="primary" @click="load">查询</el-button>
            <el-button type="success" :loading="exporting" @click="exportCsv">导出 CSV</el-button>
          </div>

          <!-- 汇总卡：工单数/三要素/总成本/标准/差异/单位成本（=明细列合计，同源聚合） -->
          <div class="cards">
            <div class="card"><div class="num">{{ s.moCount ?? '—' }}</div><div class="label">归集工单</div></div>
            <div class="card"><div class="num">{{ fmt(s.materialCost) }}</div><div class="label">直接材料</div></div>
            <div class="card"><div class="num">{{ fmt(s.laborCost) }}</div><div class="label">直接人工</div></div>
            <div class="card"><div class="num">{{ fmt(s.overheadCost) }}</div><div class="label">制造费用</div></div>
            <div class="card"><div class="num">{{ fmt(s.totalCost) }}</div><div class="label">实际总成本</div></div>
            <div class="card"><div class="num">{{ fmt(s.stdTotalCost) }}</div><div class="label">标准总成本</div></div>
            <div class="card">
              <div class="num" :class="{ danger: overFlag }">{{ fmt(s.varTotal) }}（{{ pct(s.totalVarRate) }}）</div>
              <div class="label">总差异（差异率）</div>
            </div>
            <div class="card"><div class="num">{{ fmt(s.weightedUnitCost) }}</div><div class="label">加权单位成本</div></div>
          </div>
          <div class="chips">
            <el-tag v-if="(s.overToleranceCount || 0) > 0" type="danger" size="large">
              超容差 {{ s.overToleranceCount }} 张（阈值 {{ pct(s.tolerance) }}）
            </el-tag>
            <el-tag v-else type="success" size="large">无超容差工单</el-tag>
            <el-tag v-if="(s.raisedCount || 0) > 0" type="warning" size="large">待审阅差异报告 {{ s.raisedCount }}</el-tag>
            <el-tag v-if="(s.reviewedCount || 0) > 0" type="success" size="large">已审阅 {{ s.reviewedCount }}</el-tag>
            <el-tag v-if="(s.noQtyCount || 0) > 0" type="info" size="large">{{ s.noQtyCount }} 张无产量（不入加权分母）</el-tag>
          </div>

          <el-table :data="rows" border stripe v-loading="loading" max-height="560" style="margin-top: 12px">
            <el-table-column prop="moNo" label="工单号" width="150" fixed />
            <el-table-column prop="productCode" label="物料编码" width="130" />
            <el-table-column prop="productName" label="物料名称" min-width="150" show-overflow-tooltip />
            <el-table-column label="直接材料" width="110" align="right">
              <template #default="{ row }">{{ fmt(row.materialCost) }}</template>
            </el-table-column>
            <el-table-column label="直接人工" width="110" align="right">
              <template #default="{ row }">{{ fmt(row.laborCost) }}</template>
            </el-table-column>
            <el-table-column label="制造费用" width="110" align="right">
              <template #default="{ row }">{{ fmt(row.overheadCost) }}</template>
            </el-table-column>
            <el-table-column label="总成本" width="110" align="right">
              <template #default="{ row }">{{ fmt(row.totalCost) }}</template>
            </el-table-column>
            <el-table-column label="标准成本" width="110" align="right">
              <template #default="{ row }">{{ fmt(row.stdTotalCost) }}</template>
            </el-table-column>
            <el-table-column label="总差异" width="100" align="right">
              <template #default="{ row }">{{ fmt(row.varTotal) }}</template>
            </el-table-column>
            <el-table-column label="差异率" width="100" align="right">
              <template #default="{ row }">
                <!-- 超容差红标（C-0-02 同口径，服务端算好 overTolerance） -->
                <el-tag :type="row.overTolerance ? 'danger' : 'success'" size="small">
                  {{ pct(row.varRate) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="单位成本" width="110" align="right">
              <template #default="{ row }">{{ fmt(row.unitCost) }}</template>
            </el-table-column>
            <el-table-column label="合格产量" width="95" align="right">
              <template #default="{ row }">{{ fmt(row.goodQty) }}</template>
            </el-table-column>
            <el-table-column label="差异报告" width="110" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.reportStatus === 'RAISED'" type="warning" size="small">待审阅</el-tag>
                <el-tag v-else-if="row.reportStatus === 'REVIEWED'" type="success" size="small">已审阅</el-tag>
                <span v-else class="dim">—</span>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="!rows.length && !loading" class="empty">
            该期间暂无归集数据——工单完工归集后自动出现在报表（8.10.1 可核对归集要素）
          </div>
        </el-card>
      </el-tab-pane>

      <!-- ============ Tab2 多期趋势与构成（单期如实单柱，不回填） ============ -->
      <el-tab-pane label="多期趋势与构成" name="trend">
        <el-card shadow="never">
          <div ref="trendRef" class="chart" />
          <div v-if="!trend.length" class="empty">暂无归集期间数据</div>
          <el-table v-else :data="trend" border stripe style="margin-top: 12px">
            <el-table-column prop="period" label="期间" width="100" />
            <el-table-column prop="moCount" label="工单数" width="90" align="right" />
            <el-table-column label="直接材料" width="130" align="right">
              <template #default="{ row }">{{ fmt(row.materialCost) }}</template>
            </el-table-column>
            <el-table-column label="直接人工" width="130" align="right">
              <template #default="{ row }">{{ fmt(row.laborCost) }}</template>
            </el-table-column>
            <el-table-column label="制造费用" width="130" align="right">
              <template #default="{ row }">{{ fmt(row.overheadCost) }}</template>
            </el-table-column>
            <el-table-column label="总成本" min-width="130" align="right">
              <template #default="{ row }">{{ fmt(row.totalCost) }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { costReport, costReportCsv, costTrend } from '@/api/fin/cost-report'

echarts.use([BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const activeTab = ref('report')
const period = ref('')
const productCode = ref('')
const reportStatus = ref('')
const rows = ref([])
const s = ref({})
const loading = ref(false)
const exporting = ref(false)
const trend = ref([])
const trendRef = ref(null)
let chart = null

const overFlag = computed(() => Number(s.value.totalVarRate) > Number(s.value.tolerance))

function fmt(v) {
  if (v === null || v === undefined) return '—'
  return Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function pct(v) {
  if (v === null || v === undefined) return '—'
  return (Number(v) * 100).toFixed(2) + '%'
}

async function load() {
  if (!period.value) {
    ElMessage.warning('请先选择报表期间')
    return
  }
  loading.value = true
  try {
    const params = { period: period.value }
    if (productCode.value) params.productCode = productCode.value
    if (reportStatus.value) params.reportStatus = reportStatus.value
    const res = await costReport(params)
    s.value = res.data?.summary || {}
    rows.value = res.data?.rows || []
  } finally {
    loading.value = false
  }
}

async function exportCsv() {
  if (!period.value) {
    ElMessage.warning('请先选择报表期间')
    return
  }
  exporting.value = true
  try {
    const params = { period: period.value }
    if (productCode.value) params.productCode = productCode.value
    if (reportStatus.value) params.reportStatus = reportStatus.value
    const res = await costReportCsv(params)
    const blob = new Blob([res.data || res], { type: 'text/csv;charset=utf-8' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `成本报表_${period.value}.csv`
    a.click()
    URL.revokeObjectURL(a.href)
  } finally {
    exporting.value = false
  }
}

async function loadTrend() {
  const res = await costTrend()
  trend.value = res.data || []
  await nextTick()
  renderChart()
}

function renderChart() {
  if (!trendRef.value) return
  if (!chart) chart = echarts.init(trendRef.value)
  chart.setOption({
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { data: ['直接材料', '直接人工', '制造费用'] },
    grid: { left: 80, right: 30, top: 40, bottom: 40 },
    xAxis: { type: 'category', data: trend.value.map(t => t.period) },
    yAxis: { type: 'value' },
    series: [
      { name: '直接材料', type: 'bar', stack: 'cost', data: trend.value.map(t => Number(t.materialCost)) },
      { name: '直接人工', type: 'bar', stack: 'cost', data: trend.value.map(t => Number(t.laborCost)) },
      { name: '制造费用', type: 'bar', stack: 'cost', data: trend.value.map(t => Number(t.overheadCost)) }
    ]
  })
}

watch(activeTab, (t) => {
  if (t === 'trend') loadTrend()
})

function onResize() { chart && chart.resize() }
onMounted(() => {
  window.addEventListener('resize', onResize)
  loadTrend()
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart && chart.dispose()
  chart = null
})
</script>

<style scoped>
.filters { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 12px; }
.cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
.card { background: #f7f9fc; border: 1px solid #e4eaf2; border-radius: 6px; padding: 12px 14px; }
.card .num { font-size: 17px; font-weight: 600; color: #1f2d3d; }
.card .num.danger { color: #f56c6c; }
.card .label { font-size: 12px; color: #8a96a8; margin-top: 4px; }
.chips { display: flex; gap: 8px; margin-top: 10px; flex-wrap: wrap; }
.chart { width: 100%; height: 340px; }
.empty { text-align: center; color: #909399; padding: 32px 0; }
.dim { color: #c0c4cc; }
</style>
