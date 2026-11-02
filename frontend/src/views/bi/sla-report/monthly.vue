<template>
  <div class="app-container">
    <!-- 14.9.1 月度报告 -->
    <el-card shadow="never">
      <div slot="header" class="clearfix">
        <span>月度报表 SLA 报告</span>
        <div style="float: right">
          <el-date-picker v-model="period" type="month" value-format="yyyy-MM"
                          placeholder="选择月份" size="small" style="width:130px"
                          @change="loadAll" />
          <el-button size="small" style="margin-left:8px" @click="doCollect">采集指标</el-button>
          <el-button size="small" type="primary" @click="doGenerate">生成报告</el-button>
        </div>
      </div>

      <!-- 五指标概览卡 -->
      <el-row :gutter="12" v-if="metrics.length">
        <el-col :span="4" v-for="m in metrics" :key="m.metricKey">
          <el-card shadow="hover" :body-style="{ padding: '12px', textAlign: 'center' }">
            <div style="color:#909399;font-size:12px">{{ metricName(m.metricKey) }}</div>
            <div :style="{ fontSize: '22px', fontWeight: 'bold', color: statusColor(m) }">
              {{ m.metricValue == null ? '-' : m.metricValue }}{{ m.unit || '' }}
            </div>
            <div style="color:#909399;font-size:12px">
              目标 {{ m.target == null ? '-' : m.target }}{{ m.unit || '' }}
            </div>
            <el-tag size="mini" :type="statusTag(m)" style="margin-top:4px">{{ statusLabel(m) }}</el-tag>
            <el-tag v-if="m.partialFlag === 'Y'" size="mini" type="warning" style="margin-top:4px">部分数据</el-tag>
          </el-card>
        </el-col>
      </el-row>
      <el-alert v-else title="该月指标未采集，点击「采集指标」获取" type="info" :closable="false"
                style="margin-bottom:12px" />

      <!-- 近 6 月趋势 -->
      <div ref="trendChart" style="height:280px;margin-top:12px"></div>

      <!-- 报告列表 -->
      <el-table :data="reports" border size="small" style="margin-top:12px">
        <el-table-column prop="period" label="月份" width="100" />
        <el-table-column prop="version" label="版本" width="70" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="stTag(row.status)" size="mini">{{ stText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="archiveAt" label="归档时间" width="160" />
        <el-table-column prop="publishAt" label="发布" width="160" />
      </el-table>
    </el-card>

    <!-- 未达标三要素编辑 -->
    <el-card v-if="report && report.status === 'DRAFT'" shadow="never" style="margin-top:12px">
      <div slot="header"><span>未达标分析（三要素）与改进计划</span></div>
      <el-table :data="unachieved" border size="small">
        <el-table-column prop="metricKey" label="指标" width="160"
                         :formatter="r => metricName(r.metricKey)" />
        <el-table-column label="根因分析" min-width="200">
          <template #default="{ row }">
            <el-input v-model="row.rootCause" size="mini" placeholder="根因分析（必填）" />
          </template>
        </el-table-column>
        <el-table-column label="责任人" width="140">
          <template #default="{ row }">
            <el-input v-model="row.owner" size="mini" placeholder="责任人（必填）" />
          </template>
        </el-table-column>
        <el-table-column label="完成时间" width="150">
          <template #default="{ row }">
            <el-date-picker v-model="row.dueDate" type="date" value-format="yyyy-MM-dd"
                            size="mini" placeholder="完成时间" style="width:130px" />
          </template>
        </el-table-column>
      </el-table>
      <el-form label-width="90px" size="small" style="margin-top:12px">
        <el-form-item label="改进措施">
          <el-input v-model="improve.measures" type="textarea" :rows="2" placeholder="改进措施" />
        </el-form-item>
        <el-form-item label="下月计划">
          <el-input v-model="improve.nextPlan" type="textarea" :rows="2" placeholder="下月计划" />
        </el-form-item>
        <el-form-item>
          <el-button size="small" @click="doSave">保存</el-button>
          <el-button size="small" type="primary" @click="doSubmit">提交审核</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
/**
 * 14.9.1 月度报告（拆分自原 SLA 报告操作台）：指标采集 / 报告生成 / 未达标三要素与改进计划。
 * 所选月份、当月报告与未达标三要素见 useSlaReport.js（归档发布页共用），状态映射见 sla-helpers.js。
 */
import { ref, onMounted, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import {
  getMetricsApi, collectApi, getReportsApi, generateApi,
  saveRootCauseApi, saveImproveApi, submitApi
} from '@/api/bi/sla-report'
import { useSlaReport } from './useSlaReport'
import { stText, stTag } from './sla-helpers'

const { period, report, unachieved, improve, loadReport } = useSlaReport()
const metrics = ref([])
const reports = ref([])
const trendChart = ref(null)

const METRIC_NAMES = {
  AVAILABILITY: '报表可用率', QUERY_P95: '查询P95', REFRESH_ONTIME: '刷新及时率',
  EXPORT_SUCCESS: '导出成功率', DAILY_REACH: '日报触达率'
}
function metricName(k) { return METRIC_NAMES[k] || k }

function statusColor(m) {
  if (m.metricValue == null) return '#909399'
  return { ACHIEVED: '#67c23a', WARN: '#e6a23c', BELOW: '#f56c6c', INFO: '#909399' }[judge(m)] || '#909399'
}
function statusTag(m) {
  return { ACHIEVED: 'success', WARN: 'warning', BELOW: 'danger', INFO: 'info' }[judge(m)] || 'info'
}
function statusLabel(m) {
  return { ACHIEVED: '达标', WARN: '预警', BELOW: '未达标', INFO: '参考', UNKNOWN: '未采集' }[judge(m)] || '-'
}
function judge(m) {
  if (m.metricValue == null) return 'UNKNOWN'
  if (m.metricKey === 'DAILY_REACH') return 'INFO'
  const v = Number(m.metricValue); const t = Number(m.target)
  if (m.metricKey === 'QUERY_P95') return v <= t ? 'ACHIEVED' : 'BELOW'
  return v >= t ? 'ACHIEVED' : (v >= t * 0.95 ? 'WARN' : 'BELOW')
}

async function loadAll() {
  try {
    const r = await getMetricsApi(period.value)
    metrics.value = r.data?.data?.metrics || []
    renderTrend(r.data?.data?.trend || [])
  } catch { metrics.value = [] }
  try {
    const r2 = await getReportsApi()
    reports.value = r2.data?.data || []
  } catch { reports.value = [] }
  loadReport()
}

function renderTrend(trend) {
  nextTick(() => {
    if (!trendChart.value) return
    const chart = echarts.init(trendChart.value)
    const periods = [...new Set(trend.map(t => t.period))].sort()
    const series = Object.keys(METRIC_NAMES).map(k => ({
      name: METRIC_NAMES[k], type: 'line',
      data: periods.map(p => {
        const row = trend.find(t => t.period === p && t.metricKey === k)
        return row?.metricValue == null ? null : Number(row.metricValue)
      })
    }))
    chart.setOption({
      title: { text: '近 6 月 SLA 指标趋势', textStyle: { fontSize: 14 } },
      tooltip: { trigger: 'axis' },
      legend: { top: 24 },
      grid: { left: 50, right: 30, top: 70, bottom: 30 },
      xAxis: { type: 'category', data: periods },
      yAxis: { type: 'value' },
      series
    })
  })
}

async function doCollect() {
  const r = await collectApi(period.value)
  ElMessage.success(`采集完成：成功 ${r.data?.data?.success} 项`)
  loadAll()
}
async function doGenerate() {
  await generateApi(period.value)
  ElMessage.success('报告已生成（草稿）')
  loadAll()
}
async function doSave() {
  await saveRootCauseApi(period.value, unachieved.value)
  await saveImproveApi(period.value, improve.value)
  ElMessage.success('已保存')
  loadReport()
}
async function doSubmit() {
  await doSave()
  await submitApi(period.value)
  ElMessage.success('已提交三方审核')
  loadAll()
}

onMounted(() => { loadAll() })
</script>
