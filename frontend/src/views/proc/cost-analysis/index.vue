<template>
  <div class="pa-page">
    <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
      title="成本分析（2.9.1）"
      description="双口径成本构成（PO承诺 vs 发票实付+差异）、价格趋势（移动均价+异动标红+协议价线）、比价与降本（环比/同比自动基准）。聚合数据为每日 01:00 抽取快照，异动与价差实时。" />
    <el-tabs v-model="tab">
      <!-- ============ Tab1 成本构成 ============ -->
      <el-tab-pane label="成本构成" name="composition">
        <el-form inline size="mini">
          <el-form-item label="月份"><el-input v-model="q.monthTag" placeholder="yyyyMM" style="width:110px" @keyup.enter="loadComp" /></el-form-item>
          <el-form-item label="物料"><el-input v-model="q.itemCode" placeholder="物料编码" style="width:140px" @keyup.enter="loadComp" /></el-form-item>
          <el-form-item label="供应商"><el-input v-model="q.supplierId" placeholder="供应商" style="width:130px" @keyup.enter="loadComp" /></el-form-item>
          <el-form-item><el-button type="primary" size="mini" @click="loadComp">查询</el-button></el-form-item>
          <el-form-item><el-button size="mini" @click="exportCost">导出 CSV（后台）</el-button></el-form-item>
        </el-form>

        <el-alert v-if="ready && !ready.ready" type="warning" :closable="false" title="数据待就绪" style="margin-bottom:8px" />
        <el-alert v-else-if="ready" type="info" :closable="false" :title="`数据截止：${ready.finishedAt || ready.batchDate}（${ready.todayReady ? '今日批次已就绪' : 'T-1 快照'}）`" style="margin-bottom:8px" />

        <el-row :gutter="10" style="margin-bottom:10px">
          <el-col :span="4" v-for="k in summaryCards" :key="k.label">
            <el-card shadow="never" body-style="padding:10px">
              <div style="font-size:12px;color:#909399">{{ k.label }}</div>
              <div style="font-size:18px;font-weight:600">{{ k.value }}</div>
              <div v-if="k.hint" style="font-size:11px;color:#E6A23C">{{ k.hint }}</div>
            </el-card>
          </el-col>
        </el-row>

        <el-table :data="records" size="mini" border v-loading="loading">
          <el-table-column prop="monthTag" label="月份" width="70" />
          <el-table-column prop="categoryCode" label="品类" width="90" />
          <el-table-column prop="itemCode" label="物料" width="130" />
          <el-table-column prop="supplierId" label="供应商" width="120" />
          <el-table-column prop="buyer" label="采购员" width="90" />
          <el-table-column label="PO承诺(含税)" width="110" align="right">
            <template #default="{ row }">{{ fmt(row.poAmt) }}</template>
          </el-table-column>
          <el-table-column label="发票实付" width="110" align="right">
            <template #default="{ row }">
              <span v-if="row.estimating" style="color:#E6A23C">暂估中</span>
              <span v-else>{{ fmt(row.invAmt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="差异" width="100" align="right">
            <template #default="{ row }">
              <span :style="{color: diffColor(row.diffAmt)}">{{ row.diffAmt == null ? '-' : fmt(row.diffAmt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="税额" width="90" align="right"><template #default="{ row }">{{ fmt(row.taxAmt) }}</template></el-table-column>
          <el-table-column label="退货冲减" width="90" align="right"><template #default="{ row }">{{ fmt(row.returnAmt) }}</template></el-table-column>
          <el-table-column label="口径切换" width="150">
            <template #default="{ row }">
              <el-button type="text" size="mini" @click="openDiff(row)">价差明细</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination style="margin-top:8px" layout="total, prev, pager, next" :total="total"
          :page-size="q.size" :current-page.sync="q.current" @current-change="loadComp" />

        <!-- 价差下钻 -->
        <el-dialog :title="`价差明细（剔除暂估 ${diff.excludedEstimating || 0} 行）`" :visible.sync="diffVisible" width="860px">
          <el-table :data="diff.records || []" size="mini" border max-height="420">
            <el-table-column prop="monthTag" label="月份" width="70" />
            <el-table-column prop="itemCode" label="物料" width="130" />
            <el-table-column prop="supplierId" label="供应商" width="120" />
            <el-table-column label="PO承诺" align="right"><template #default="{ row }">{{ fmt(row.poAmt) }}</template></el-table-column>
            <el-table-column label="发票实付" align="right"><template #default="{ row }">{{ fmt(row.invAmt) }}</template></el-table-column>
            <el-table-column label="差异" align="right">
              <template #default="{ row }"><span :style="{color: diffColor(row.diffAmt)}">{{ fmt(row.diffAmt) }}</span></template>
            </el-table-column>
          </el-table>
        </el-dialog>
      </el-tab-pane>

      <!-- ============ Tab2 价格趋势 ============ -->
      <el-tab-pane label="价格趋势" name="trend">
        <el-form inline size="mini">
          <el-form-item label="物料"><el-input v-model="trendQ.itemCode" placeholder="必填" style="width:160px" /></el-form-item>
          <el-form-item label="口径">
            <el-radio-group v-model="trendQ.basis" size="mini">
              <el-radio-button label="PO">PO 承诺价</el-radio-button>
              <el-radio-button label="INVOICE">发票实付价</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item><el-button type="primary" size="mini" @click="loadTrend">查询</el-button></el-form-item>
          <el-form-item>
            <el-button size="mini" type="warning" @click="doScan">立即扫描异动</el-button>
          </el-form-item>
        </el-form>
        <div ref="trendChart" style="height:340px" v-show="trend.series && trend.series.length" />
        <el-empty v-if="!trend.series || !trend.series.length" description="输入物料编码查询趋势（需先有抽取快照）" />
        <el-table :data="trend.series || []" size="mini" border style="margin-top:10px" v-if="trend.series && trend.series.length">
          <el-table-column prop="monthTag" label="月份" width="80" />
          <el-table-column label="加权均价" align="right"><template #default="{ row }">{{ row.avgPrice }}</template></el-table-column>
          <el-table-column label="环比%" align="center">
            <template #default="{ row }">
              <el-tag size="mini" :type="alertTag(row.mom)">{{ row.mom == null ? '-' : row.mom }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="同比%" align="center"><template #default="{ row }">{{ row.yoy == null ? '-' : row.yoy }}</template></el-table-column>
          <el-table-column label="MA3" align="right"><template #default="{ row }">{{ row.ma3 == null ? '-' : row.ma3 }}</template></el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ============ Tab3 比价与降本 ============ -->
      <el-tab-pane label="比价与降本" name="savings">
        <el-form inline size="mini">
          <el-form-item label="月份"><el-input v-model="saveQ.monthTag" placeholder="yyyyMM（默认上月）" style="width:150px" /></el-form-item>
          <el-form-item label="维度">
            <el-radio-group v-model="saveQ.dimension" size="mini">
              <el-radio-button label="SUPPLIER">供应商</el-radio-button>
              <el-radio-button label="CATEGORY">品类</el-radio-button>
              <el-radio-button label="BUYER">采购员</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item><el-button type="primary" size="mini" @click="loadSavings">查询</el-button></el-form-item>
        </el-form>
        <el-alert type="info" :closable="false" style="margin-bottom:8px"
          title="降本 = （上期月均价 − 本期月均价）× 本期量，系统自动计算（无申报入口），按月快照归档" />
        <el-row :gutter="10" style="margin-bottom:10px">
          <el-col :span="6"><el-card shadow="never" body-style="padding:10px">
            <div style="font-size:12px;color:#909399">环比降本合计</div>
            <div style="font-size:20px;font-weight:600" :style="{color: savings.totalSaveMom >= 0 ? '#67C23A' : '#F56C6C'}">{{ fmt(savings.totalSaveMom) }}</div>
          </el-card></el-col>
          <el-col :span="6"><el-card shadow="never" body-style="padding:10px">
            <div style="font-size:12px;color:#909399">同比降本合计</div>
            <div style="font-size:20px;font-weight:600" :style="{color: savings.totalSaveYoy >= 0 ? '#67C23A' : '#F56C6C'}">{{ fmt(savings.totalSaveYoy) }}</div>
          </el-card></el-col>
        </el-row>
        <el-table :data="savings.records || []" size="mini" border>
          <el-table-column prop="key" :label="dimLabel" width="180" />
          <el-table-column label="环比节约额" align="right">
            <template #default="{ row }"><span :style="{color: row.saveMom >= 0 ? '#67C23A' : '#F56C6C'}">{{ fmt(row.saveMom) }}</span></template>
          </el-table-column>
          <el-table-column label="同比节约额" align="right">
            <template #default="{ row }"><span :style="{color: row.saveYoy >= 0 ? '#67C23A' : '#F56C6C'}">{{ fmt(row.saveYoy) }}</span></template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { getCompositionApi, getPriceDiffApi, getTrendApi, getSavingsApi, getReadyApi,
  scanNowApi, submitExportApi } from '@/api/proc/analysis'

echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const tab = ref('composition')
const loading = ref(false)
const records = ref([])
const total = ref(0)
const ready = ref(null)
const summary = ref({})
const estimatingCount = ref(0)
const q = reactive({ monthTag: '', itemCode: '', supplierId: '', current: 1, size: 10 })

const summaryCards = computed(() => [
  { label: 'PO 承诺成本', value: fmt(summary.value.poAmt) },
  { label: '发票实付成本', value: fmt(summary.value.invAmt) },
  { label: '口径差异', value: fmt(summary.value.diffAmt) },
  { label: '税额', value: fmt(summary.value.taxAmt) },
  { label: '退货冲减', value: fmt(summary.value.returnAmt) },
  { label: '暂估中行数', value: estimatingCount.value, hint: '发票口径为空不冒充实付' }
])

function fmt(v) {
  if (v == null || v === '') return '-'
  const n = Number(v)
  return isNaN(n) ? v : n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function diffColor(v) {
  if (v == null) return '#909399'
  return Number(v) > 0 ? '#F56C6C' : (Number(v) < 0 ? '#67C23A' : '#909399')
}
function alertTag(mom) {
  if (mom == null) return 'info'
  const v = Math.abs(Number(mom))
  return v >= 10 ? 'danger' : (v >= 5 ? 'warning' : 'success')
}

async function loadComp() {
  loading.value = true
  try {
    const { data } = await getCompositionApi({ ...q })
    records.value = data.records || []
    total.value = Number(data.total) || 0
    summary.value = data.summary || {}
    estimatingCount.value = Number(data.estimatingCount) || 0
    ready.value = data.ready
  } finally {
    loading.value = false
  }
}

const diffVisible = ref(false)
const diff = ref({})
async function openDiff(row) {
  const { data } = await getPriceDiffApi({ monthTag: row.monthTag, itemCode: row.itemCode, supplierId: row.supplierId })
  diff.value = data
  diffVisible.value = true
}

const trendQ = reactive({ itemCode: '', basis: 'PO' })
const trend = ref({ series: [], agreementPrices: [] })
const trendChart = ref(null)
let chart = null
async function loadTrend() {
  if (!trendQ.itemCode) { ElMessage.warning('请输入物料编码'); return }
  const { data } = await getTrendApi(trendQ.itemCode, trendQ.basis)
  trend.value = data
  await nextTick()
  drawTrend()
}
function drawTrend() {
  const el = trendChart.value
  if (!el) return
  if (chart) { chart.dispose(); chart = null }
  chart = echarts.init(el)
  const s = trend.value.series || []
  const ag = trend.value.agreementPrices || []
  const series = [
    { name: '加权均价', type: 'line', smooth: true, data: s.map(r => r.avgPrice) },
    { name: 'MA3', type: 'line', smooth: true, data: s.map(r => r.ma3), lineStyle: { type: 'dashed' } },
    { name: '环比%', type: 'line', yAxisIndex: 1, data: s.map(r => r.mom) }
  ]
  if (ag.length) {
    series.push({ name: '协议价参考', type: 'line', markLine: { silent: true, data: ag.map(a => ({ yAxis: Number(a.unitPrice) })) }, data: [] })
  }
  chart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: series.map(x => x.name) },
    grid: { left: 60, right: 60, top: 40, bottom: 40 },
    xAxis: { type: 'category', data: s.map(r => r.monthTag) },
    yAxis: [
      { type: 'value', name: '价格' },
      { type: 'value', name: '环比%', splitLine: { show: false } }
    ],
    series
  })
}
async function doScan() {
  const { data } = await scanNowApi()
  ElMessage.success(`补算完成：${data.monthTag} 检出 ${data.detected} 条（已处置不覆盖）`)
}

const saveQ = reactive({ monthTag: '', dimension: 'SUPPLIER' })
const savings = ref({})
const dimLabel = computed(() => ({ SUPPLIER: '供应商', CATEGORY: '品类', BUYER: '采购员' }[saveQ.dimension]))
async function loadSavings() {
  const { data } = await getSavingsApi(saveQ.monthTag, saveQ.dimension)
  savings.value = data
  saveQ.monthTag = data.monthTag
}

async function exportCost() {
  const est = total.value
  const { data } = await submitExportApi({ dataset: 'cost', estRows: est,
    params: { monthTag: q.monthTag || undefined, itemCode: q.itemCode || undefined,
      supplierId: q.supplierId || undefined } })
  ElMessage.success(data.hint || '已提交导出')
}

onMounted(loadComp)
</script>
