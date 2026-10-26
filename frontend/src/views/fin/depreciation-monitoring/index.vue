<template>
  <div class="page depr-monitor-page">
    <el-tabs v-model="tab">
      <!-- ================= 8.6.1 折旧计提（期间状态只读视图） ================= -->
      <el-tab-pane label="折旧计提" name="run">
        <el-form inline>
          <el-button type="primary" @click="loadRuns" :loading="runsLoading">刷新</el-button>
        </el-form>
        <el-table :data="runs" border size="small">
          <el-table-column prop="period" label="会计期间" width="100" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.notRun" type="danger" size="small">未计提</el-tag>
              <el-tag v-else-if="row.status === 'POSTED'" type="success" size="small">已计提</el-tag>
              <el-tag v-else-if="row.status === 'REVERSED'" type="info" size="small">已冲回</el-tag>
              <el-tag v-else type="warning" size="small">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="折旧合计" width="120" align="right">
            <template #default="{ row }">
              <span v-if="row.totalDepr != null">¥{{ fmt(row.totalDepr) }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="残值预警" width="90" align="center">
            <template #default="{ row }">
              <span v-if="row.warningCount != null"
                    :style="{ color: Number(row.warningCount) > 0 ? '#f56c6c' : '' }">
                {{ row.warningCount }} 张
              </span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="voucherNo" label="折旧凭证" width="160">
            <template #default="{ row }">
              <span v-if="row.voucherNo">{{ row.voucherNo }}
                <el-tag size="small" type="success">{{ row.voucherStatus }}</el-tag>
              </span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="勾稽差异" width="110" align="right">
            <template #default="{ row }">
              <span v-if="row.reconDiff != null"
                    :style="{ color: Math.abs(Number(row.reconDiff)) > 0.01 ? '#f56c6c' : '' }">
                ¥{{ fmt(row.reconDiff) }}
              </span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button v-if="!row.notRun" link type="primary"
                         @click="onDrill(row.period)">明细</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-alert type="warning" :closable="false" show-icon style="margin-top:8px"
          title="本页为财务关账前只读确认视图：折旧执行与冲回请前往 资产管理→折旧管理（M7-2）。未计提期间以红字标出。" />

        <!-- 明细下钻抽屉 -->
        <el-drawer v-model="drillVisible" :title="`计提明细 ${drillPeriod}`" size="62%">
          <el-table :data="drillItems" border size="small">
            <el-table-column prop="cardNo" label="资产卡号" width="150" />
            <el-table-column prop="assetName" label="资产名称" min-width="130" show-overflow-tooltip />
            <el-table-column label="原值" width="110" align="right">
              <template #default="{ row }">¥{{ fmt(row.assetCost) }}</template>
            </el-table-column>
            <el-table-column label="当期折旧" width="110" align="right">
              <template #default="{ row }">¥{{ fmt(row.periodDepr) }}</template>
            </el-table-column>
            <el-table-column label="累计折旧" width="110" align="right">
              <template #default="{ row }">¥{{ fmt(row.endAccumDepr) }}</template>
            </el-table-column>
            <el-table-column label="NBV" width="110" align="right">
              <template #default="{ row }">¥{{ fmt(row.endNbv) }}</template>
            </el-table-column>
            <el-table-column label="残值预警" width="90" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.residualAlert" type="danger" size="small">预警</el-tag>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="提足截减" width="90" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.capped" type="warning" size="small">截减</el-tag>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="已冲回" width="80" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.reversed" type="info" size="small">冲回</el-tag>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
          </el-table>
        </el-drawer>
      </el-tab-pane>

      <!-- ================= 8.6.2 残值预警（实时扫描） ================= -->
      <el-tab-pane label="残值预警" name="alert">
        <el-form inline>
          <el-button type="primary" @click="loadAlerts" :loading="alertsLoading">重新扫描</el-button>
        </el-form>
        <el-descriptions v-if="alerts" :column="3" border size="small" style="margin-bottom:10px">
          <el-descriptions-item label="预警阈值">
            残值 &gt; NBV × {{ pct(alerts.threshold) }}（C-4.6-04）
          </el-descriptions-item>
          <el-descriptions-item label="扫描在用卡">{{ alerts.scannedCount }} 张</el-descriptions-item>
          <el-descriptions-item label="预警卡数">
            <span :style="{ color: alerts.alertCount > 0 ? '#f56c6c' : '' }">
              {{ alerts.alertCount }} 张
            </span>
          </el-descriptions-item>
        </el-descriptions>
        <el-table :data="alerts ? alerts.items : []" border size="small">
          <el-table-column prop="cardNo" label="资产卡号" width="150" />
          <el-table-column prop="assetName" label="资产名称" min-width="130" show-overflow-tooltip />
          <el-table-column label="原值" width="110" align="right">
            <template #default="{ row }">¥{{ fmt(row.assetCost) }}</template>
          </el-table-column>
          <el-table-column label="累计折旧" width="110" align="right">
            <template #default="{ row }">¥{{ fmt(row.accumDepr) }}</template>
          </el-table-column>
          <el-table-column label="减值" width="90" align="right">
            <template #default="{ row }">¥{{ fmt(row.impairment) }}</template>
          </el-table-column>
          <el-table-column label="NBV" width="110" align="right">
            <template #default="{ row }">¥{{ fmt(row.nbv) }}</template>
          </el-table-column>
          <el-table-column label="残值" width="100" align="right">
            <template #default="{ row }">¥{{ fmt(row.residualValue) }}</template>
          </el-table-column>
          <el-table-column label="残值占比" width="100" align="right">
            <template #default="{ row }">
              <span style="color:#f56c6c">{{ pct(row.residualRatio) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="effectiveDate" label="启用日期" width="110" />
        </el-table>
        <el-alert type="info" :closable="false" show-icon style="margin-top:8px"
          title="实时扫描当前时点在用资产（不含已处置与已提足），不触发也不依赖折旧计提；提示检查资产估值或残值率设置是否合理。" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { periodStatuses, periodDetails, residualAlerts } from '@/api/fin/depreciation'

const route = useRoute()
const tab = ref(route.meta?.tab || 'run')

const fmt = (v) => {
  const n = Number(v || 0)
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
const pct = (v) => (Number(v || 0) * 100).toFixed(2) + '%'

// ---------- 8.6.1 期间状态 ----------
const runs = ref([])
const runsLoading = ref(false)
const drillVisible = ref(false)
const drillPeriod = ref('')
const drillItems = ref([])

async function loadRuns() {
  runsLoading.value = true
  try {
    const res = await periodStatuses()
    runs.value = res.data || []
  } finally {
    runsLoading.value = false
  }
}

async function onDrill(period) {
  drillPeriod.value = period
  const res = await periodDetails(period)
  drillItems.value = res.data?.items || []
  drillVisible.value = true
}

// ---------- 8.6.2 残值预警 ----------
const alerts = ref(null)
const alertsLoading = ref(false)

async function loadAlerts() {
  alertsLoading.value = true
  try {
    const res = await residualAlerts()
    alerts.value = res.data
    if (res.data?.alertCount > 0) {
      ElMessage.warning(`发现 ${res.data.alertCount} 张残值异常资产卡，请检查估值或残值率设置`)
    }
  } finally {
    alertsLoading.value = false
  }
}

onMounted(() => {
  loadRuns()
  if (tab.value === 'alert') loadAlerts()
})
</script>

<style scoped>
.muted { color: #909399; }
</style>
