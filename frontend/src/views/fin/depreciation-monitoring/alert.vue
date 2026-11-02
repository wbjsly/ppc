<template>
  <div class="page depr-monitor-page">
    <!-- ================= 8.6.2 残值预警（实时扫描） ================= -->
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
  </div>
</template>

<script setup>
/**
 * 8.6.2 残值预警（拆分自原折旧监控多 tab 页，design D1）。
 * 本页独有逻辑，金额/百分比格式化见 depr-helpers.js。
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { residualAlerts } from '@/api/fin/depreciation'
import { fmt, pct } from './depr-helpers'

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
  loadAlerts()
})
</script>
