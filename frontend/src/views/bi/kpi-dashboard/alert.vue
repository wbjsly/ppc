<template>
  <div class="kpi-wrap">
    <!-- ========== 14.7.3 异常预警 ========== -->
    <div class="toolbar">
      <el-radio-group v-model="alertStatus" size="small" @change="loadAlerts">
        <el-radio-button value="ACTIVE">当前预警</el-radio-button>
        <el-radio-button value="RECOVERED">历史恢复</el-radio-button>
      </el-radio-group>
    </div>
    <el-table :data="alerts" size="small" border>
      <el-table-column prop="kpiName" label="KPI" />
      <el-table-column prop="dimValue" label="维度值" width="140">
        <template #default="{ row }">{{ row.dimValue || '（全局）' }}</template>
      </el-table-column>
      <el-table-column prop="daysBreached" label="连续天数" width="90" align="center" />
      <el-table-column prop="firstBreachDate" label="首次低于" width="110" />
      <el-table-column prop="recoverDate" label="恢复日期" width="110" />
      <el-table-column label="操作" width="140">
        <template #default="{ row }">
          <el-button link type="primary" size="small"
                     @click="drillByKpi(row.kpiKey)">查看原因分析</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 下钻抽屉（跨页共用） -->
    <DrillDrawer />
  </div>
</template>

<script setup>
/**
 * 14.7.3 异常预警（拆分自原 KPI 看板操作台）：当前/历史预警列表与原因分析下钻。
 * KPI 卡片与下钻状态见 useKpiDashboard.js（指标达成页加载，本页下钻定位依赖它）。
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getAlertsApi } from '@/api/bi/kpi-dashboard'
import { useKpiDashboard } from './useKpiDashboard'
import DrillDrawer from './_parts/DrillDrawer.vue'

const { cards, loadSummary, openDrill } = useKpiDashboard()

const alerts = ref([])
const alertStatus = ref('ACTIVE')

async function loadAlerts() {
  alerts.value = (await getAlertsApi(alertStatus.value)).data || []
}

function drillByKpi(kpiKey) {
  const c = cards.value.find(x => x.kpiKey === kpiKey)
  if (c?.hasDrill) {
    openDrill(c.drillTemplateId, c.name)
  } else {
    ElMessage.info('该 KPI 未挂下钻模板（无法查看原因分析）')
  }
}

onMounted(() => {
  if (!cards.value.length) loadSummary()
  loadAlerts()
})
</script>

<style scoped>
.kpi-wrap { padding: 4px; }
.toolbar { margin-bottom: 12px; display: flex; gap: 8px; align-items: center; }
</style>
