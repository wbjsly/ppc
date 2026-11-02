<template>
  <div class="page depr-monitor-page">
    <!-- ================= 8.6.1 折旧计提（期间状态只读视图） ================= -->
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
  </div>
</template>

<script setup>
/**
 * 8.6.1 折旧计提（拆分自原折旧监控多 tab 页，design D1）。
 * 本页独有逻辑，金额格式化见 depr-helpers.js。
 */
import { ref, onMounted } from 'vue'
import { periodStatuses, periodDetails } from '@/api/fin/depreciation'
import { fmt } from './depr-helpers'

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

onMounted(() => {
  loadRuns()
})
</script>

<style scoped>
.muted { color: #909399; }
</style>
