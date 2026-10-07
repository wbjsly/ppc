<template>
  <div class="pm-page">
    <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
      title="价格监测（2.9.3）— 监测工作台（分析页看图、本页管事）"
      description="价格异动处置状态机（处置/忽略留痕，补算不覆盖已处置）、阈值参数在线配置（留痕）、协议价偏离监控、比价异常清单（BR-4.2-12）。阈值调整仅管理员，其余角色可处置异动。" />
    <el-tabs v-model="tab">
      <!-- ===== 异动清单 ===== -->
      <el-tab-pane label="价格异动" name="alerts">
        <el-form inline size="mini">
          <el-form-item label="月份"><el-input v-model="aq.monthTag" placeholder="yyyyMM" style="width:110px" /></el-form-item>
          <el-form-item label="状态">
            <el-select v-model="aq.status" clearable placeholder="全部" style="width:110px">
              <el-option label="待处置" value="OPEN" /><el-option label="已处置" value="HANDLED" /><el-option label="已忽略" value="IGNORED" />
            </el-select>
          </el-form-item>
          <el-form-item label="物料"><el-input v-model="aq.itemCode" style="width:140px" /></el-form-item>
          <el-form-item><el-button type="primary" size="mini" @click="loadAlerts">查询</el-button></el-form-item>
          <el-form-item><el-button size="mini" @click="exportAlerts">导出（后台）</el-button></el-form-item>
        </el-form>
        <el-table :data="alerts" size="mini" border>
          <el-table-column prop="alertNo" label="异动单号" width="170" />
          <el-table-column prop="monthTag" label="月份" width="70" />
          <el-table-column prop="itemCode" label="物料" width="140" />
          <el-table-column prop="pct" label="环比%" width="90" align="center">
            <template #default="{row}"><el-tag size="mini" :type="Math.abs(Number(row.pct)) >= 20 ? 'danger' : 'warning'">{{ row.pct }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="thresholdPct" label="阈值%" width="80" align="center" />
          <el-table-column label="均价(prev→curr)" width="180">
            <template #default="{row}">{{ row.prevPrice }} → {{ row.currPrice }}</template>
          </el-table-column>
          <el-table-column prop="source" label="来源" width="80" />
          <el-table-column label="状态" width="90">
            <template #default="{row}">
              <el-tag size="mini" :type="{ OPEN: 'danger', HANDLED: 'success', IGNORED: 'info' }[row.status]">
                {{ { OPEN: '待处置', HANDLED: '已处置', IGNORED: '已忽略' }[row.status] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="处置" width="240">
            <template #default="{row}">
              <template v-if="row.status === 'OPEN'">
                <el-button type="text" size="mini" @click="handle(row, 'HANDLED')">处置</el-button>
                <el-button type="text" size="mini" @click="handle(row, 'IGNORED')">忽略</el-button>
              </template>
              <span v-else style="font-size:12px;color:#909399">{{ row.handleBy }} {{ (row.handleAt || '').slice(0, 16) }}<br/>{{ row.handleNote }}</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ===== 协议价偏离 ===== -->
      <el-tab-pane label="协议价偏离" name="deviation">
        <el-alert type="info" :closable="false" style="margin-bottom:8px"
          :title="`最近采购均价 vs 框架协议价，超容差(${deviation.tolerancePct || 5}%)高亮；只读，不改变价控执行`" />
        <el-table :data="deviation.records || []" size="mini" border>
          <el-table-column prop="monthTag" label="月份" width="70" />
          <el-table-column prop="itemCode" label="物料" width="140" />
          <el-table-column prop="agreementNo" label="协议号" width="140" />
          <el-table-column label="最近采购均价" width="120" align="right"><template #default="{row}">{{ row.lastPrice }}</template></el-table-column>
          <el-table-column label="协议价" width="100" align="right"><template #default="{row}">{{ row.agreementPrice }}</template></el-table-column>
          <el-table-column label="偏离%" width="90" align="center">
            <template #default="{row}">
              <el-tag size="mini" :type="row.overTolerance ? 'danger' : 'success'">{{ row.deviationPct }}%</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="价控日志" min-width="200">
            <template #default="{row}">
              <span v-if="row.priceControl">{{ row.priceControl.poNo }} · {{ row.priceControl.checkLevel }} · {{ row.priceControl.result }}</span>
              <span v-else style="color:#909399">-</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ===== 比价异常 ===== -->
      <el-tab-pane label="比价异常" name="anomalies">
        <el-alert type="warning" :closable="false" style="margin-bottom:8px"
          :title="`BR-4.2-12：报价偏离同 RFQ 有效报价均值 ±20% 集中呈现（${anomalies.count || 0} 条）——只读，跳转比价矩阵处置`" />
        <el-table :data="anomalies.records || []" size="mini" border>
          <el-table-column prop="rfqId" label="RFQ" width="220" />
          <el-table-column prop="supplierId" label="供应商" width="140" />
          <el-table-column label="报价" width="110" align="right"><template #default="{row}">{{ row.unitPrice }}</template></el-table-column>
          <el-table-column label="有效均值" width="110" align="right"><template #default="{row}">{{ num2(row.avgPrice) }}</template></el-table-column>
          <el-table-column label="偏离%" width="90" align="center">
            <template #default="{row}"><el-tag size="mini" type="danger">{{ num2(row.deviationPct) }}%</el-tag></template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{row}">
              <el-tag size="mini" :type="row.anomalyConfirmed ? 'success' : 'warning'">{{ row.anomalyConfirmed ? '已确认' : '未确认' }}</el-tag>
              <el-tag v-if="row.excluded" size="mini" type="info">已剔除</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="140">
            <template #default="{row}">
              <el-button type="text" size="mini" @click="$router.push('/m/2.2.4')">去比价矩阵</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ===== 阈值参数 ===== -->
      <el-tab-pane label="阈值参数" name="thresholds">
        <el-table :data="thresholds" size="mini" border>
          <el-table-column prop="key" label="参数键" width="240" />
          <el-table-column prop="value" label="当前值" width="110" align="center" />
          <el-table-column prop="remark" label="说明" />
          <el-table-column prop="source" label="来源" width="90" />
          <el-table-column label="操作" width="200">
            <template #default="{row}">
              <el-button v-if="isAdmin" type="text" size="mini" @click="editThreshold(row)">调整</el-button>
              <el-button type="text" size="mini" @click="showHistory(row)">历史</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-dialog title="调整历史" :visible.sync="historyVisible" width="640px">
          <el-table :data="historyRows" size="mini" border>
            <el-table-column prop="at" label="时间" width="160" />
            <el-table-column prop="old" label="原值" width="80" />
            <el-table-column prop="new" label="新值" width="80" />
            <el-table-column prop="by" label="操作人" />
          </el-table>
          <div style="font-size:12px;color:#909399;margin-top:6px">调整对后续判定生效，已生成异动不追溯重算</div>
        </el-dialog>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import { getAlertsApi, handleAlertApi, getThresholdApi, setThresholdApi,
  getDeviationApi, getQuoteAnomaliesApi, submitExportApi } from '@/api/proc/analysis'

const userStore = useUserStore()
const isAdmin = computed(() => (userStore.userInfo?.roles || []).includes('ROLE_ADMIN'))
const tab = ref('alerts')

// ---- 异动 ----
const alerts = ref([])
const aq = reactive({ monthTag: '', status: '', itemCode: '' })
async function loadAlerts() {
  const { data } = await getAlertsApi({ ...aq })
  alerts.value = data.records || []
}
async function handle(row, action) {
  let note = ''
  if (action === 'HANDLED') {
    const { value } = await ElMessageBox.prompt('处置备注（必填，留痕不可改）', '处置异动',
      { inputPattern: /.{2,}/, inputErrorMessage: '不少于 2 字' })
    note = value
  } else {
    await ElMessageBox.confirm('忽略该异动？（留痕不可改）', '忽略确认', { type: 'warning' })
  }
  await handleAlertApi(row.id, { action, note })
  ElMessage.success(action === 'HANDLED' ? '已处置' : '已忽略')
  loadAlerts()
}
async function exportAlerts() {
  const { data } = await submitExportApi({ dataset: 'alerts', estRows: alerts.value.length,
    params: { monthTag: aq.monthTag || undefined, status: aq.status || undefined } })
  ElMessage.success(data.hint || '已提交导出')
}

// ---- 协议价偏离 / 比价异常 ----
const deviation = ref({})
const anomalies = ref({})
async function loadLists() {
  const [d, a] = await Promise.all([getDeviationApi(), getQuoteAnomaliesApi()])
  deviation.value = d.data
  anomalies.value = a.data
}

// ---- 阈值 ----
const thresholds = ref([])
const historyVisible = ref(false)
const historyRows = ref([])
async function loadThresholds() {
  const keys = ['PRICE_TREND_ALERT_PCT', 'PRICE_TOLERANCE_PCT']
  const rs = await Promise.all(keys.map(k => getThresholdApi(k)))
  thresholds.value = rs.map(r => r.data)
}
async function editThreshold(row) {
  const { value } = await ElMessageBox.prompt(
    `调整 ${row.key}（当前 ${row.value}），调整对后续判定生效、不追溯`, '阈值调整',
    { inputPattern: /^-?\d+(\.\d+)?$/, inputErrorMessage: '须为数值' })
  const { value: remark } = await ElMessageBox.prompt('调整原因（留痕）', '调整原因',
    { inputPattern: /.{2,}/, inputErrorMessage: '不少于 2 字' })
  await setThresholdApi(row.key, { value, remark })
  ElMessage.success(`已调整为 ${value}（留痕）`)
  loadThresholds()
}
function showHistory(row) {
  historyRows.value = Array.isArray(row.history) ? row.history : []
  historyVisible.value = true
}
function num2(v) { return v == null ? '-' : Number(v).toFixed(2) }

onMounted(() => { loadAlerts(); loadLists(); loadThresholds() })
</script>
