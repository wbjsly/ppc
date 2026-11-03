<template>
  <WidgetShell title="KPI 趋势" :loading="loading" :error="error" @refresh="load">
    <div class="wk-toolbar">
      <el-select v-model="kpiKey" size="small" filterable @change="load" style="width: 60%">
        <el-option v-for="k in defs" :key="k.kpiKey" :label="k.name || k.kpiKey" :value="k.kpiKey" />
      </el-select>
      <span v-if="target !== null" class="wk-target">目标 {{ target }}</span>
    </div>
    <div v-if="!error && series.length === 0 && !loading" class="wk-empty">暂无趋势数据（每日快照生成后呈现）</div>
    <div ref="chartEl" class="wk-chart" :style="{ display: series.length ? 'block' : 'none' }" />
  </WidgetShell>
</template>

<script setup>
/**
 * KPI 趋势（add-configurable-workbench，段4 指标件）：
 * GET /bi/kpi/summary 取 KPI 目录 → GET /bi/kpi/trend?kpiKey=&days=14 → echarts 折线。
 */
import { ref, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import * as echarts from 'echarts'
import request from '@/utils/request'
import WidgetShell from '../_parts/WidgetShell.vue'

const loading = ref(false)
const error = ref('')
const defs = ref([])
const kpiKey = ref('')
const series = ref([])
const target = ref(null)
const chartEl = ref(null)
let chart = null

function render() {
  if (!chart || !chartEl.value) return
  if (!series.value.length) { chart.clear(); return }
  chart.setOption({
    grid: { left: 40, right: 12, top: 18, bottom: 24 },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: series.value.map(s => String(s.date).slice(5)), axisLabel: { fontSize: 10 } },
    yAxis: { type: 'value', axisLabel: { fontSize: 10 } },
    series: [{
      type: 'line', smooth: true, symbolSize: 5, name: '实际',
      data: series.value.map(s => s.ratio !== null && s.ratio !== undefined ? Number(s.ratio) : null),
      itemStyle: { color: 'var(--el-color-primary)' },
      areaStyle: { opacity: 0.12 },
    }, ...(target.value !== null ? [{
      type: 'line', name: '目标', symbol: 'none', lineStyle: { type: 'dashed' },
      data: series.value.map(() => Number(target.value)),
      itemStyle: { color: 'var(--el-color-danger)' },
    }] : [])],
  }, true)
}

async function load() {
  if (!kpiKey.value) return
  loading.value = true
  error.value = ''
  try {
    const res = await request({
      url: '/bi/kpi/trend', method: 'get',
      params: { kpiKey: kpiKey.value, days: 14 }, silent: true,
    })
    series.value = res.data?.series || []
    target.value = res.data?.target ?? null
    await nextTick()
    render()
  } catch (e) {
    series.value = []
    error.value = e?.response ? `加载失败（${e.response.status}）` : (e.message || '网络异常')
  } finally {
    loading.value = false
  }
}

async function init() {
  try {
    const res = await request({ url: '/bi/kpi/summary', method: 'get', silent: true })
    defs.value = res.data || []
    kpiKey.value = defs.value[0]?.kpiKey || ''
  } catch (e) {
    error.value = e?.response ? `加载失败（${e.response.status}）` : (e.message || '网络异常')
    return
  }
  await nextTick()
  if (chartEl.value) {
    chart = echarts.init(chartEl.value)
    if (kpiKey.value) await load()
  }
}

function onResize() { chart?.resize() }

onMounted(() => {
  init()
  window.addEventListener('resize', onResize)
})
watch(series, () => nextTick(render))
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
.wk-toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.wk-target { font-size: 11px; color: var(--el-text-color-secondary); white-space: nowrap; }
.wk-empty { color: var(--el-text-color-secondary); font-size: 13px; text-align: center; padding: 24px 0; }
.wk-chart { width: 100%; height: 180px; }
</style>
