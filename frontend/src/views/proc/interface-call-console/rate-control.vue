<template>
  <div class="page-wrap">
    <!-- 状态总览 -->
    <el-card shadow="never" class="mb12">
      <div class="card-head mb12">
        <h4 style="margin:0">限流状态总览（12.3.2）—— 档位 / 令牌桶 / 熔断态（与网关实时判定同源）</h4>
        <el-button size="small" @click="load">刷新</el-button>
      </div>
      <el-table :data="channels" size="small" border>
        <el-table-column prop="partnerCode" label="伙伴" width="140" />
        <el-table-column label="限流档位" width="120" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.rateTier === 'LOWEST' ? 'danger' : row.rateTier === 'STRATEGIC' ? 'success' : 'info'">
              {{ row.rateTier }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="limitPerMin" label="限值(次/分)" width="110" align="center" />
        <el-table-column prop="bucketCapacity" label="桶容量(×1.5)" width="130" align="center" />
        <el-table-column label="熔断状态" width="120" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.circuitState === 'CLOSED' ? 'success' : 'danger'">{{ row.circuitState }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近降档" min-width="200">
          <template #default="{ row }">
            <span v-if="row.downgradeFrom">{{ row.downgradeFrom }} → {{ row.rateTier }}（{{ row.downgradeAt }}）</span>
            <span v-else class="hint">—</span>
          </template>
        </el-table-column>
      </el-table>

      <el-table :data="windows" size="small" border class="mt12">
        <el-table-column prop="caller" label="调用方" width="160" />
        <el-table-column prop="windowMin" label="统计窗口" width="140" />
        <el-table-column prop="hitCount" label="命中数" width="90" align="center" />
        <el-table-column prop="overCount" label="本窗口 429" width="110" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.overCount > 0" size="small" type="danger">{{ row.overCount }}</el-tag>
            <span v-else>0</span>
          </template>
        </el-table-column>
        <el-table-column prop="limitPerMin" label="限值" width="90" align="center" />
        <el-table-column prop="lastOverAt" label="最近超限" width="165" />
      </el-table>
    </el-card>

    <!-- 429 趋势 -->
    <el-card shadow="never" class="mb12">
      <template #header>
        <div class="card-head">
          <span>429 触发趋势（总数与调用审计明细可对账）</span>
          <span>
            <el-select v-model="trendHours" size="small" style="width:130px" @change="loadTrend">
              <el-option label="近 6 小时" :value="6" />
              <el-option label="近 24 小时" :value="24" />
              <el-option label="近 7 天" :value="168" />
              <el-option label="近 30 天" :value="720" />
            </el-select>
            <el-input v-model="trendCaller" placeholder="调用方（可选）" clearable size="small"
                      style="width:160px;margin-left:8px" @keyup.enter="loadTrend" />
            <el-button size="small" style="margin-left:8px" @click="loadTrend">查询</el-button>
          </span>
        </div>
      </template>
      <div ref="chartEl" style="height:280px" />
      <div class="hint mt12">窗口合计 <b>{{ trendTotal }}</b> 次 429；与同条件审计明细行数一致（spec 对账口径）。</div>
    </el-card>

    <!-- 档位变更历史 -->
    <el-card shadow="never" class="mb12">
      <template #header>
        <div class="card-head">
          <span>档位变更历史（自动降档 + 手动调档同源留痕，不可改删）</span>
          <el-input v-model="historyCaller" placeholder="调用方（可选）" clearable size="small"
                    style="width:180px" @keyup.enter="loadHistory" />
        </div>
      </template>
      <el-table :data="history" size="small" border>
        <el-table-column prop="createDate" label="时间" width="165" />
        <el-table-column prop="caller" label="调用方" width="150" />
        <el-table-column label="类型" width="130" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.changeType === 'AUTO_DOWNGRADE' ? 'danger' : 'info'">
              {{ row.changeType === 'AUTO_DOWNGRADE' ? '自动降档' : '手动调整' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="档位变化" width="180" align="center">
          <template #default="{ row }">{{ row.tierFrom }} → {{ row.tierTo }}</template>
        </el-table-column>
        <el-table-column label="限值变化" width="140" align="center">
          <template #default="{ row }">{{ row.limitFrom ?? '—' }} → {{ row.limitTo ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="operator" label="操作者" width="130" />
        <el-table-column prop="triggerReason" label="触发原因" min-width="240" show-overflow-tooltip />
      </el-table>
      <el-pagination class="mt12" layout="total, prev, pager, next" :total="historyTotal"
                     :page-size="historySize" :current-page="historyPage" @current-change="onHistoryPage" />
    </el-card>

    <!-- 限流参数只读 -->
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span>限流参数（只读；编辑入口在 12.1.3 通道开通）</span>
          <el-button size="small" type="primary" plain @click="$router.push('/m/12.1.3')">前往 12.1.3 调整</el-button>
        </div>
      </template>
      <el-descriptions :column="3" size="small" border>
        <el-descriptions-item label="战略伙伴 STRATEGIC">500 次/分钟（桶容量 750）</el-descriptions-item>
        <el-descriptions-item label="普通伙伴 NORMAL">100 次/分钟（桶容量 150）</el-descriptions-item>
        <el-descriptions-item label="新伙伴 NEW / 降档 LOWEST">30 次/分钟（桶容量 45）</el-descriptions-item>
        <el-descriptions-item label="超限响应">429 + Retry-After（BR-4.9-18）</el-descriptions-item>
        <el-descriptions-item label="自动降档">连续 3 个统计周期 429 → 降至最低档并通知运维（BR-4.9-19）</el-descriptions-item>
        <el-descriptions-item label="熔断">错误率超阈值熔断，半开探测连续 10 笔成功恢复（C-5.5-09）</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { getRateOverviewApi, getRateTrendApi, getTierChangesApi } from '@/api/proc/interface-call-console'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([LineChart, GridComponent, TooltipComponent, CanvasRenderer])

const channels = ref([])
const windows = ref([])
const trendHours = ref(24)
const trendCaller = ref('')
const trendTotal = ref(0)
const history = ref([])
const historyTotal = ref(0)
const historyPage = ref(1)
const historySize = 20
const historyCaller = ref('')
const chartEl = ref(null)
let chart = null

async function load() {
  const res = await getRateOverviewApi()
  channels.value = res.data?.channels || []
  windows.value = res.data?.windows || []
}

async function loadTrend() {
  const params = { hours: trendHours.value }
  if (trendCaller.value) params.caller = trendCaller.value
  const res = await getRateTrendApi(params)
  const buckets = res.data?.buckets || []
  trendTotal.value = res.data?.total || 0
  await nextTick()
  if (!chart && chartEl.value) {
    chart = echarts.init(chartEl.value)
  }
  if (chart) {
    chart.setOption({
      grid: { left: 50, right: 20, top: 30, bottom: 40 },
      tooltip: { trigger: 'axis' },
      xAxis: { type: 'category', data: buckets.map(b => b.bucket) },
      yAxis: { type: 'value', minInterval: 1 },
      series: [{ name: '429 次数', type: 'line', smooth: true,
        data: buckets.map(b => Number(b.total)) }]
    }, true)
  }
}

async function loadHistory() {
  const params = { current: historyPage.value, size: historySize }
  if (historyCaller.value) params.caller = historyCaller.value
  const res = await getTierChangesApi(params)
  history.value = res.data?.records || []
  historyTotal.value = res.data?.total || 0
}
function onHistoryPage(p) {
  historyPage.value = p
  loadHistory()
}

function resize() { chart && chart.resize() }

onMounted(() => {
  load()
  loadTrend()
  loadHistory()
  window.addEventListener('resize', resize)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  if (chart) { chart.dispose(); chart = null }
})
</script>

<style scoped>
.page-wrap { padding: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.mb12 { margin-bottom: 12px; }
.mt12 { margin-top: 12px; }
.hint { color: #909399; font-size: 12px; }
</style>
