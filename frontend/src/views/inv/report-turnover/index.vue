<template>
  <div class="page">
    <el-alert type="info" :closable="false" style="margin-bottom: 8px"
      title="周转分析（4.14.2）：M-WMS-001 周转率 = 销售成本 / 平均库存余额（日结快照均值）。分子可切换全部出库口径（非指标字典口径）。" />

    <el-form inline @submit.prevent>
      <el-form-item label="期间">
        <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD"
          start-placeholder="开始" end-placeholder="结束" style="width: 260px" />
      </el-form-item>
      <el-form-item label="分子口径">
        <el-select v-model="scope" style="width: 230px">
          <el-option label="销售成本（指标字典口径）" value="SALES_OUT" />
          <el-option label="全部出库（非指标字典口径）" value="ALL_OUT" />
        </el-select>
      </el-form-item>
      <el-button type="primary" :loading="loading" @click="loadAll">查询</el-button>
      <el-button :loading="backfilling" @click="doBackfill">日结补数</el-button>
      <el-button :loading="exporting" @click="doExport">导出汇总 CSV</el-button>
    </el-form>

    <!-- 指标卡 -->
    <el-row :gutter="12" style="margin-bottom: 8px">
      <el-col :span="6">
        <el-card shadow="never" class="kpi">
          <div class="kpi-label">周转率</div>
          <div class="kpi-value">{{ rateText }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="kpi">
          <div class="kpi-label">周转天数</div>
          <div class="kpi-value">{{ daysText }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="kpi">
          <div class="kpi-label">呆滞库存占比</div>
          <div class="kpi-value">{{ staleRatioText }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="kpi">
          <div class="kpi-label">分母口径</div>
          <div class="kpi-value small">{{ denomText }}</div>
          <div v-if="turnover?.degraded" class="kpi-note">数据积累中</div>
        </el-card>
      </el-col>
    </el-row>

    <el-alert v-if="turnover?.degraded" type="warning" :closable="false" style="margin-bottom: 8px"
      :title="`降级口径：${turnover.degradedReason}`"
      :description="`快照天数 ${turnover.snapshotDays ?? 0}；点击「日结补数」回补最近一个缺失日快照。分子口径：${turnover.scopeLabel}`" />

    <el-row :gutter="12">
      <!-- 汇总表 -->
      <el-col :span="13">
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <span>汇总</span>
              <el-radio-group v-model="groupBy" size="small" @change="loadSummary">
                <el-radio-button value="ITEM">按物料</el-radio-button>
                <el-radio-button value="WAREHOUSE">按仓库</el-radio-button>
                <el-radio-button value="ABC">按 ABC</el-radio-button>
              </el-radio-group>
            </div>
          </template>
          <el-table :data="summary" border size="small" max-height="380" v-loading="loading">
            <el-table-column prop="groupKey" label="分组" min-width="130" />
            <el-table-column label="出库成本" width="110" align="right">
              <template #default="{ row }">{{ fmt(row.outCost) }}</template>
            </el-table-column>
            <el-table-column label="日均库存" width="110" align="right">
              <template #default="{ row }">
                <span v-if="row.avgStockValue == null" class="muted">无快照</span>
                <span v-else>{{ fmt(row.avgStockValue) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="分母口径" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.denominatorMode === 'SNAPSHOT' ? 'success'
                  : row.denominatorMode === 'DEGRADED' ? 'warning' : 'info'">
                  {{ modeText(row.denominatorMode) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="周转率" width="90" align="right">
              <template #default="{ row }">{{ row.rate == null ? '不适用' : (row.rate * 100).toFixed(1) + '%' }}</template>
            </el-table-column>
            <el-table-column label="周转天数" width="90" align="right">
              <template #default="{ row }">{{ row.turnoverDays == null ? '不适用' : row.turnoverDays.toFixed(1) }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <!-- 趋势图 -->
      <el-col :span="11">
        <el-card shadow="never">
          <template #header>跨月周转趋势（缺快照期间断开，非插值）</template>
          <div ref="trendRef" style="height: 300px" />
          <div v-if="!trend.length" class="muted" style="text-align: center; padding: 40px 0">
            暂无日结快照——补数并逐日累积后自动生成趋势
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import {
  getTurnoverApi, getTurnoverSummaryApi, getTurnoverTrendApi,
  backfillDayCloseApi, downloadReportApi, submitBgExportApi
} from '@/api/inv/report'

echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const today = new Date().toISOString().slice(0, 10)
const monthAgo = new Date(Date.now() - 29 * 86400000).toISOString().slice(0, 10)
const range = ref([monthAgo, today])
const scope = ref('SALES_OUT')
const groupBy = ref('ITEM')
const loading = ref(false)
const backfilling = ref(false)
const exporting = ref(false)
const turnover = ref(null)
const summary = ref([])
const trend = ref([])
const trendRef = ref(null)
let chart = null

const fmt = v => (v == null ? '-' : Number(v).toFixed(2))
const modeText = m => ({ SNAPSHOT: '快照均值', DEGRAVED: '降级', NONE: '不适用' }[m] || m)
const rateText = computed(() => (turnover.value?.rate != null
  ? (turnover.value.rate * 100).toFixed(1) + '%' : '不适用'))
const daysText = computed(() => (turnover.value?.turnoverDays != null
  ? turnover.value.turnoverDays.toFixed(1) + ' 天' : '不适用'))
const staleRatioText = computed(() => (turnover.value?.staleRatio != null
  ? Number(turnover.value.staleRatio).toFixed(1) + '%' : '-'))
const denomText = computed(() => (turnover.value ? modeText(turnover.value.denominatorMode) : '-'))

function params() {
  return { from: range.value?.[0], to: range.value?.[1], scope: scope.value }
}

async function loadAll() {
  if (!range.value || range.value.length !== 2) {
    ElMessage.warning('请选择期间')
    return
  }
  loading.value = true
  try {
    const [t, s, tr] = await Promise.all([
      getTurnoverApi(params()),
      getTurnoverSummaryApi({ ...params(), groupBy: groupBy.value }),
      getTurnoverTrendApi(6)
    ])
    turnover.value = t.data
    summary.value = s.data || []
    trend.value = tr.data || []
    await nextTick()
    renderChart()
  } finally {
    loading.value = false
  }
}

async function loadSummary() {
  const s = await getTurnoverSummaryApi({ ...params(), groupBy: groupBy.value })
  summary.value = s.data || []
}

function renderChart() {
  if (!trendRef.value) return
  if (!chart) {
    chart = echarts.init(trendRef.value)
  }
  chart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['周转率%', '周转天数'] },
    grid: { left: 50, right: 40, top: 36, bottom: 30 },
    xAxis: { type: 'category', data: trend.value.map(p => p.period) },
    yAxis: [
      { type: 'value', name: '%' },
      { type: 'value', name: '天' }
    ],
    series: [
      {
        name: '周转率%', type: 'line', smooth: false,
        connectNulls: false,   // 缺快照区间断开（spec 趋势断开）
        data: trend.value.map(p => p.rate == null ? null : +(p.rate * 100).toFixed(2))
      },
      {
        name: '周转天数', type: 'line', yAxisIndex: 1, smooth: false,
        connectNulls: false,
        data: trend.value.map(p => p.days == null ? null : +Number(p.days).toFixed(1))
      }
    ]
  }, true)
}

async function doBackfill() {
  await ElMessageBox.confirm('回补最近一个缺失日的日结快照（幂等，重复执行安全）？', '日结补数',
    { type: 'info' })
  backfilling.value = true
  try {
    const res = await backfillDayCloseApi()
    const d = res.data || {}
    ElMessage.success(d.skipped ? '最近日结已存在（无需补数）' : `已补 ${d.inserted} 行日结快照`)
    await loadAll()
  } finally {
    backfilling.value = false
  }
}

async function doExport() {
  exporting.value = true
  try {
    const p = { ...params(), groupBy: groupBy.value }
    await downloadReportApi('turnover', p)
    ElMessage.success('已导出')
  } catch (e) {
    if (e.overLimit) {
      await submitBgExportApi('turnover', params(), 0)
      ElMessage.success('已转后台导出任务')
    } else {
      ElMessage.error(e.message || '导出失败')
    }
  } finally {
    exporting.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped>
.page { padding: 4px; }
.kpi { text-align: center; }
.kpi-label { color: #909399; font-size: 12px; }
.kpi-value { font-size: 26px; font-weight: 600; margin-top: 6px; }
.kpi-value.small { font-size: 16px; margin-top: 10px; }
.kpi-note { color: #e6a23c; font-size: 12px; margin-top: 4px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.muted { color: #909399; font-size: 12px; }
</style>
