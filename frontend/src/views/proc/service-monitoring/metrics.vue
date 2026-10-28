<template>
  <div class="page-wrap">
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span>12.7.1 指标采集（每分钟：可用率 / P95 / 错误率 / 端到端延迟；伙伴维度行 + 全局行）</span>
          <span>
            <el-button size="small" type="primary" @click="doCollect">立即采集</el-button>
            <el-button size="small" @click="$router.push('/m/12.7.2')">告警升级 12.7.2</el-button>
            <el-button size="small" @click="$router.push('/m/12.7.3')">月度报告 12.7.3</el-button>
          </span>
        </div>
      </template>

      <div class="toolbar">
        <el-select v-model="q.metricKey" placeholder="指标" clearable size="small" style="width: 170px;" @change="load">
          <el-option label="可用率" value="AVAILABILITY" />
          <el-option label="P95 响应" value="P95" />
          <el-option label="错误率" value="ERROR_RATE" />
          <el-option label="端到端延迟" value="E2E_DELAY" />
        </el-select>
        <el-select v-model="q.partnerCode" placeholder="伙伴维度" clearable size="small" style="width: 190px;" @change="load">
          <el-option label="全部（全局+伙伴）" value="__ALL__" />
          <el-option label="仅全局行" value="" />
          <el-option v-for="p in partnerOptions" :key="p" :label="p" :value="p" />
        </el-select>
        <el-input v-model="q.monthTag" placeholder="月份 yyyyMM" clearable size="small" style="width: 130px;" @keyup.enter="load" />
        <el-button size="small" type="primary" @click="load">查询</el-button>
        <span class="hint">数据缺失（MISSING）为数据源不可达，不填零、不参与当月达成率（BR-4.9-30）</span>
      </div>

      <el-table :data="rows" size="small" border v-loading="loading">
        <el-table-column prop="metricKey" label="指标" width="130">
          <template #default="{ row }">{{ metricName(row.metricKey) }}</template>
        </el-table-column>
        <el-table-column label="伙伴" width="140">
          <template #default="{ row }">
            <el-tag v-if="row.partnerCode" size="mini">{{ row.partnerCode }}</el-tag>
            <el-tag v-else size="mini" type="info">全局</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="实际值" width="130" align="right">
          <template #default="{ row }">
            <span v-if="row.dataStatus === 'MISSING'" class="missing">数据缺失</span>
            <span v-else>{{ row.metricValue }} {{ row.unit }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="targetValue" label="目标值" width="110" align="right">
          <template #default="{ row }">{{ row.targetValue }} {{ row.unit }}</template>
        </el-table-column>
        <el-table-column label="级别" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="mini" :type="levelTag(row.level)">{{ levelText(row.level) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sampleCount" label="样本" width="80" align="right" />
        <el-table-column prop="windowStart" label="窗口起" width="165">
          <template #default="{ row }">{{ String(row.windowStart || '').replace('T', ' ').slice(0, 16) }}</template>
        </el-table-column>
        <el-table-column prop="monthTag" label="月份" width="80" />
        <el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip />
      </el-table>
      <el-pagination style="margin-top: 10px; justify-content: flex-end;"
        layout="total, prev, pager, next" :total="total"
        v-model:current-page="q.current" :page-size="q.size" @current-change="load" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getMetricsApi, collectSlaApi } from '@/api/proc/service-monitoring'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const partnerOptions = ref([])
const q = reactive({ metricKey: '', partnerCode: '__ALL__', monthTag: '', current: 1, size: 20 })

const NAMES = { AVAILABILITY: '可用率', P95: 'P95 响应', ERROR_RATE: '错误率', E2E_DELAY: '端到端延迟' }
const metricName = k => NAMES[k] || k
const LEVEL_TAG = { OK: 'success', WARNING: 'warning', CRITICAL: 'danger', MISSING: 'info' }
const LEVEL_TEXT = { OK: '达标', WARNING: '预警', CRITICAL: '严重', MISSING: '数据缺失' }
const levelTag = l => LEVEL_TAG[l] || 'info'
const levelText = l => LEVEL_TEXT[l] || l

function partnerParam() {
  if (q.partnerCode === '__ALL__') return undefined // 全部：不传参
  if (q.partnerCode === '') return ''                 // 全局行：空串
  return q.partnerCode
}

async function load() {
  loading.value = true
  try {
    const { data } = await getMetricsApi({
      current: q.current, size: q.size,
      metricKey: q.metricKey || undefined,
      monthTag: q.monthTag || undefined,
      partnerCode: partnerParam()
    })
    rows.value = (data && data.records) || []
    total.value = Number((data && data.total) || 0)
  } finally { loading.value = false }
}

/** 伙伴下拉选项：宽取一页（500）distinct——演示规模足够 */
async function loadPartners() {
  try {
    const { data } = await getMetricsApi({ current: 1, size: 500 })
    const set = new Set()
    for (const r of (data && data.records) || []) {
      if (r.partnerCode) set.add(r.partnerCode)
    }
    partnerOptions.value = [...set].sort()
  } catch { partnerOptions.value = [] }
}

async function doCollect() {
  const n = await collectSlaApi()
  ElMessage.success(`采集完成，本轮写入 ${n} 条指标（含伙伴维度行）`)
  await Promise.all([load(), loadPartners()])
}

onMounted(() => { load(); loadPartners() })
</script>

<style scoped>
.page-wrap { padding: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.toolbar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 10px; }
.hint { color: #909399; font-size: 12px; }
.missing { color: #909399; font-style: italic; }
</style>
