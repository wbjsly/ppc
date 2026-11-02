<template>
  <div class="app-container">
    <!-- ==================== 16.8.2 临时降权 ==================== -->
    <el-form :inline="true" size="small" @submit.prevent>
      <el-form-item label="状态">
        <el-select v-model="restrictQuery.status" placeholder="全部" clearable style="width:140px">
          <el-option label="降权中" value="ACTIVE" />
          <el-option label="已恢复" value="RESTORED" />
          <el-option label="已禁用" value="DISABLED" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="loadRestrictions(1)">查询</el-button>
        <el-button type="warning" plain @click="doSweep">到期恢复扫描（幂等）</el-button>
      </el-form-item>
    </el-form>
    <el-alert type="info" :closable="false" style="margin-bottom:8px"
      title="降权存续期间：敏感路径（/api/system|mdm|fin|qms/**）一律 403 拒绝，正常业务放行，登录不受影响；默认 7 天到期自动恢复。" />
    <el-table :data="restrictions.rows" size="small" v-loading="restrictions.loading" border>
      <el-table-column prop="targetUsername" label="账号" width="130" show-overflow-tooltip>
        <template #default="{ row }">{{ row.targetUsername || row.targetUserId }}</template>
      </el-table-column>
      <el-table-column prop="triggeredAt" label="触发时间" width="160" />
      <el-table-column prop="expireAt" label="到期时间" width="160" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="mini" :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="restoreReason" label="恢复方式" show-overflow-tooltip />
      <el-table-column prop="restoreAt" label="恢复时间" width="160" />
      <el-table-column label="操作" width="110">
        <template #default="{ row }">
          <el-button v-if="row.status === 'ACTIVE'" size="mini" type="warning"
            @click="doRestore(row)">提前恢复</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination style="margin-top:8px;text-align:right" background layout="total, prev, pager, next"
      :total="restrictions.total" :page-size="restrictions.size" :current-page="restrictions.page"
      @current-change="loadRestrictions" />
  </div>
</template>

<script setup>
/**
 * 16.8.2 临时降权（拆分自原越权检测操作台）：降权名单 / 提前恢复 / 到期恢复扫描。
 * 提前恢复后同步刷新共享告警列表，见 useAuthzDetection.js。
 */
import { reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listIncidents, restoreIncident, sweepRestore } from '@/api/system/authz-detection'
import { useAuthzDetection } from './useAuthzDetection'
import { statusType, statusText } from './authz-helpers'

const { alerts, loadAlerts } = useAuthzDetection()

// ---------- 降权名单 ----------
const restrictQuery = reactive({ status: 'ACTIVE' })
const restrictions = reactive({ rows: [], total: 0, page: 1, size: 50, loading: false })
const loadRestrictions = async (page) => {
  if (page) restrictions.page = page
  restrictions.loading = true
  try {
    const params = { page: restrictions.page, size: restrictions.size }
    if (restrictQuery.status) params.status = restrictQuery.status
    const { data } = await listIncidents(params)
    restrictions.rows = data.rows || []
    restrictions.total = data.total || 0
  } finally {
    restrictions.loading = false
  }
}
const doRestore = async (row) => {
  const { value } = await ElMessageBox.prompt(
    '提前恢复说明（留痕）', '提前恢复',
    { inputPattern: /.{2,}/, inputErrorMessage: '说明 ≥2 字' })
  await restoreIncident(row.id, value)
  ElMessage.success('已恢复，敏感操作即刻放行')
  loadRestrictions(restrictions.page)
  loadAlerts(alerts.page)
}
const doSweep = async () => {
  const { data } = await sweepRestore()
  ElMessage.success(`扫描完成：恢复 ${data.restored} 个`)
  loadRestrictions(1)
}

onMounted(() => {
  loadRestrictions(1)
})
</script>
