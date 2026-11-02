<template>
  <div class="kpi-wrap">
    <!-- ========== 14.7.2 趋势展示 ========== -->
    <div class="toolbar">
      <el-select v-model="trendKpi" placeholder="选择 KPI" style="width: 220px"
                 filterable @change="onTrendKpi">
        <el-option v-for="c in cards" :key="c.kpiKey" :value="c.kpiKey" :label="c.name" />
      </el-select>
      <el-select v-if="trendDims.length" v-model="trendDim" placeholder="维度值"
                 style="width: 180px" filterable @change="loadTrend">
        <el-option v-for="d in trendDims" :key="d.dimValue" :value="d.dimValue"
                   :label="d.dimValue || '（全局）'" />
      </el-select>
    </div>
    <div ref="trendChart" class="trend-chart" v-loading="trendLoading" />
  </div>
</template>

<script setup>
/**
 * 14.7.2 趋势展示（拆分自原 KPI 看板操作台）：KPI 实际值 vs 目标折线（按维度切换）。
 * KPI 卡片列表见 useKpiDashboard.js（指标达成页加载，本页选择器与维度值依赖它）。
 */
import { ref, onMounted, nextTick } from 'vue'
import * as echarts from 'echarts'
import { getTrendApi } from '@/api/bi/kpi-dashboard'
import { useKpiDashboard } from './useKpiDashboard'

const { cards, loadSummary } = useKpiDashboard()

const trendChart = ref(null)
const trendKpi = ref('')
const trendDim = ref('')
const trendDims = ref([])
const trendLoading = ref(false)
let chart = null

function onTrendKpi() {
  const c = cards.value.find(x => x.kpiKey === trendKpi.value)
  trendDims.value = c?.values || []
  trendDim.value = trendDims.value.length ? (trendDims.value[0].dimValue || '') : ''
  loadTrend()
}

async function loadTrend() {
  if (!trendKpi.value) return
  trendLoading.value = true
  try {
    const res = await getTrendApi(trendKpi.value, trendDim.value)
    const d = res.data || {}
    await nextTick()
    if (!chart) {
      chart = echarts.init(trendChart.value)
    }
    const series = d.series || []
    chart.setOption({
      tooltip: { trigger: 'axis' },
      grid: { left: 60, right: 30, top: 30, bottom: 40 },
      xAxis: { type: 'category', data: series.map(s => s.date) },
      yAxis: { type: 'value' },
      series: [
        {
          name: '实际值', type: 'line', connectNulls: false, smooth: false,
          data: series.map(s => s.actual)
        },
        {
          name: '目标', type: 'line', symbol: 'none', silent: true,
          lineStyle: { type: 'dashed', color: '#E6A23C' },
          data: series.map(() => d.target)
        }
      ]
    }, true)
  } finally {
    trendLoading.value = false
  }
}

onMounted(() => {
  if (!cards.value.length) loadSummary()
})
</script>

<style scoped>
.kpi-wrap { padding: 4px; }
.toolbar { margin-bottom: 12px; display: flex; gap: 8px; align-items: center; }
.trend-chart { width: 100%; height: 420px; }
</style>
