<template>
  <div class="page-wrap">
    <!-- Trace 下钻 -->
    <el-card shadow="never" class="mb12">
      <template #header>
        <div class="card-head">
          <span>调用追踪 Trace（按 X-Request-Id，含被拒请求）</span>
          <span>
            <el-input v-model="reqId" placeholder="X-Request-Id" clearable size="small"
                      style="width:320px" @keyup.enter="loadTrace" />
            <el-button size="small" type="primary" style="margin-left:8px" @click="loadTrace">追踪</el-button>
          </span>
        </div>
      </template>
      <el-table :data="trace" size="small" border v-loading="traceLoading">
        <el-table-column prop="callAt" label="时间" width="165" />
        <el-table-column prop="caller" label="调用方" width="130" />
        <el-table-column prop="httpMethod" label="方法" width="70" align="center" />
        <el-table-column prop="apiPath" label="路径" min-width="220" show-overflow-tooltip />
        <el-table-column prop="respCode" label="响应码" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.respCode < 400 ? 'success' : row.respCode < 500 ? 'warning' : 'danger'">
              {{ row.respCode }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="errCode" label="错误码" min-width="160" show-overflow-tooltip />
        <el-table-column prop="costMs" label="耗时ms" width="90" align="center" />
        <el-table-column prop="env" label="环境" width="90" align="center" />
        <el-table-column prop="paramSummary" label="参数摘要（脱敏）" min-width="200" show-overflow-tooltip />
      </el-table>
      <div class="hint mt12">在下方明细点击 reqId 可直接下钻；401/429/503 被拒请求同样有审计行可追溯。</div>
    </el-card>

    <!-- 聚合分析 -->
    <el-card shadow="never" class="mb12">
      <template #header>
        <div class="card-head">
          <span>审计聚合（调用方 × 接口 × 日：调用量 / 成功率 / P95 / 错误分布）</span>
          <span>
            <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD"
                            start-placeholder="开始" end-placeholder="结束" size="small"
                            style="width:240px" @change="loadAggregates" />
            <el-button size="small" style="margin-left:8px" @click="loadAggregates">查询</el-button>
          </span>
        </div>
      </template>
      <el-table :data="aggregates" size="small" border @row-click="drillAggregate" style="cursor:pointer">
        <el-table-column prop="day" label="日期" width="120" />
        <el-table-column prop="caller" label="调用方" width="140" />
        <el-table-column prop="apiPath" label="接口" min-width="200" show-overflow-tooltip />
        <el-table-column prop="total" label="调用量" width="90" align="center" />
        <el-table-column label="成功率" width="100" align="center">
          <template #default="{ row }">
            <span :style="{ color: successRate(row) >= 99 ? '#67c23a' : successRate(row) >= 90 ? '#e6a23c' : '#f56c6c' }">
              {{ successRate(row).toFixed(2) }}%
            </span>
          </template>
        </el-table-column>
        <el-table-column label="P95(ms)" width="100" align="center">
          <template #default="{ row }">{{ row.p95 ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="e4xx" label="4xx" width="70" align="center" />
        <el-table-column prop="e5xx" label="5xx" width="70" align="center" />
      </el-table>
      <div class="hint mt12">点击行下钻至该 调用方×接口×日 的审计明细；聚合与明细同源（月度 SLA 报告下钻入口）。</div>
    </el-card>

    <!-- 明细增强 + 导出 -->
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span>审计明细（敏感字段脱敏）</span>
          <span>
            <el-input v-model="detail.caller" placeholder="调用方" clearable size="small" style="width:130px" />
            <el-input v-model="detail.path" placeholder="接口路径" clearable size="small"
                      style="width:180px;margin-left:8px" />
            <el-select v-model="detail.respGroup" placeholder="状态码分组" clearable size="small"
                       style="width:130px;margin-left:8px">
              <el-option label="2xx 成功" value="2xx" />
              <el-option label="4xx 客户端" value="4xx" />
              <el-option label="5xx 服务端" value="5xx" />
            </el-select>
            <el-button size="small" style="margin-left:8px" @click="loadDetail">查询</el-button>
            <el-button size="small" type="primary" plain style="margin-left:8px" @click="exportCsv">导出 CSV</el-button>
          </span>
        </div>
      </template>
      <el-table :data="detailRows" size="small" border v-loading="detailLoading">
        <el-table-column prop="callAt" label="时间" width="165" />
        <el-table-column prop="caller" label="调用方" width="130" />
        <el-table-column prop="apiPath" label="路径" min-width="200" show-overflow-tooltip />
        <el-table-column prop="respCode" label="状态码" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.respCode < 400 ? 'success' : row.respCode < 500 ? 'warning' : 'danger'">
              {{ row.respCode }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="costMs" label="耗时ms" width="90" align="center" />
        <el-table-column label="reqId（点击下钻）" width="260">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="traceFromRow(row)">{{ row.reqId }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="mt12" layout="total, prev, pager, next" :total="detailTotal"
                     :page-size="detail.size" :current-page="detail.current" @current-change="onDetailPage" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getTraceApi, getAuditAggregatesApi, getCallLogsApi } from '@/api/proc/interface-call-console'

const reqId = ref('')
const trace = ref([])
const traceLoading = ref(false)

const dateRange = ref(null)
const aggregates = ref([])

const detail = ref({ caller: '', path: '', respGroup: '', current: 1, size: 20 })
const detailRows = ref([])
const detailTotal = ref(0)
const detailLoading = ref(false)

function successRate(row) {
  const total = Number(row.total) || 0
  return total === 0 ? 100 : (Number(row.success) / total) * 100
}

async function loadTrace() {
  if (!reqId.value) { ElMessage.warning('请输入 X-Request-Id'); return }
  traceLoading.value = true
  try {
    const res = await getTraceApi(reqId.value.trim())
    trace.value = res.data || []
    if (!trace.value.length) ElMessage.info('未找到该 reqId 的调用记录')
  } finally {
    traceLoading.value = false
  }
}
function traceFromRow(row) {
  reqId.value = row.reqId
  loadTrace()
}

async function loadAggregates() {
  const params = {}
  if (dateRange.value && dateRange.value[0]) {
    params.dateFrom = dateRange.value[0]
    params.dateTo = dateRange.value[1]
  }
  const res = await getAuditAggregatesApi(params)
  aggregates.value = res.data || []
}
function drillAggregate(row) {
  detail.value.caller = row.caller || ''
  detail.value.path = row.apiPath || ''
  detail.value.respGroup = ''
  detail.value.current = 1
  loadDetail()
}

async function loadDetail() {
  detailLoading.value = true
  try {
    const params = { current: detail.value.current, size: detail.value.size }
    if (detail.value.caller) params.caller = detail.value.caller
    if (detail.value.path) params.path = detail.value.path
    if (detail.value.respGroup) params.respGroup = detail.value.respGroup
    const res = await getCallLogsApi(params)
    detailRows.value = res.data?.records || []
    detailTotal.value = res.data?.total || 0
  } finally {
    detailLoading.value = false
  }
}
function onDetailPage(p) {
  detail.value.current = p
  loadDetail()
}

function exportCsv() {
  const head = ['时间', '调用方', '路径', '状态码', '耗时ms', 'reqId']
  const lines = [head.join(',')]
  for (const r of detailRows.value) {
    lines.push([r.callAt, r.caller, r.apiPath, r.respCode, r.costMs, r.reqId]
      .map(v => `"${String(v ?? '').replace(/"/g, '""')}"`).join(','))
  }
  const blob = new Blob(['\ufeff' + lines.join('\n')], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `api-audit-${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  URL.revokeObjectURL(a.href)
  ElMessage.success('已导出当前筛选明细（CSV）')
}

onMounted(() => {
  loadAggregates()
  loadDetail()
})
</script>

<style scoped>
.page-wrap { padding: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.mb12 { margin-bottom: 12px; }
.mt12 { margin-top: 12px; }
.hint { color: #909399; font-size: 12px; }
</style>
