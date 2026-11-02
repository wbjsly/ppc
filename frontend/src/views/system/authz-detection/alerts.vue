<template>
  <div class="app-container">
    <!-- ==================== 16.8.1 拒绝告警 ==================== -->
    <el-form :inline="true" size="small" @submit.prevent>
      <el-form-item label="状态">
        <el-select v-model="alertQuery.status" placeholder="全部" clearable style="width:140px">
          <el-option label="降权中" value="ACTIVE" />
          <el-option label="已恢复" value="RESTORED" />
          <el-option label="已禁用" value="DISABLED" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="loadAlerts(1)">查询</el-button>
      </el-form-item>
    </el-form>
    <el-alert type="info" :closable="false" style="margin-bottom:8px"
      title="同一账号 5 分钟内 403 拒绝达阈值（AUTHZ_DENY_BURST_THRESHOLD，默认 10 次）自动触发：临时降权 7 天 + 站内告警至安全管理员与内审 + SECURITY_EVENT 审计。处置三动作：核实误用 / 提前恢复 / 确认恶意（联动账号停用）。" />
    <el-table :data="alerts.rows" size="small" v-loading="alerts.loading" border>
      <el-table-column prop="triggeredAt" label="触发时间" width="160" />
      <el-table-column prop="targetUsername" label="账号" width="130" show-overflow-tooltip>
        <template #default="{ row }">{{ row.targetUsername || row.targetUserId }}</template>
      </el-table-column>
      <el-table-column label="拒绝次数" width="110">
        <template #default="{ row }">{{ row.hitCount }} / 阈值 {{ row.threshold }}</template>
      </el-table-column>
      <el-table-column prop="expireAt" label="降权到期" width="160" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="mini" :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="处置" width="110">
        <template #default="{ row }">
          <el-tag v-if="row.disposition" size="mini" :type="dispType(row.disposition)">
            {{ dispText(row.disposition) }}
          </el-tag>
          <el-tag v-else size="mini" type="warning">待处置</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="conclusion" label="处置结论" show-overflow-tooltip />
      <el-table-column label="操作" width="110">
        <template #default="{ row }">
          <el-button size="mini" type="primary" @click="openDispose(row)">
            {{ row.disposition ? '详情' : '处置' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination style="margin-top:8px;text-align:right" background layout="total, prev, pager, next"
      :total="alerts.total" :page-size="alerts.size" :current-page="alerts.page"
      @current-change="loadAlerts" />

    <!-- 处置抽屉 -->
    <el-drawer v-model="disposeDrawer" :title="`越权事件处置（${current.targetUsername || current.targetUserId}）`" size="480px">
      <el-descriptions :column="1" size="small" border>
        <el-descriptions-item label="触发">{{ current.hitCount }} 次 / 阈值 {{ current.threshold }}</el-descriptions-item>
        <el-descriptions-item label="触发时间">{{ current.triggeredAt }}</el-descriptions-item>
        <el-descriptions-item label="降权到期">{{ current.expireAt }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ statusText(current.status) }}</el-descriptions-item>
        <el-descriptions-item v-if="current.conclusion" label="处置结论">{{ current.conclusion }}</el-descriptions-item>
        <el-descriptions-item v-if="current.restoreReason" label="恢复方式">{{ current.restoreReason }}</el-descriptions-item>
      </el-descriptions>

      <template v-if="!current.disposition">
        <el-divider content-position="left">处置动作</el-divider>
        <el-radio-group v-model="disposeForm.action">
          <el-radio label="MISUSE">核实为误用（保留降权至到期）</el-radio>
          <el-radio label="RESTORE">提前恢复（立即解除降权）</el-radio>
          <el-radio label="MALICIOUS">确认恶意（联动停用账号）</el-radio>
        </el-radio-group>
        <el-form label-width="90px" size="small" style="margin-top:12px">
          <el-form-item label="处置结论" required>
            <el-input v-model="disposeForm.conclusion" type="textarea" :rows="3"
              placeholder="≥10 字：核实过程与依据（如联系本人核实为批量脚本未加过滤条件）" />
          </el-form-item>
          <el-form-item v-if="disposeForm.action === 'MISUSE'" label="数据安全培训">
            <el-switch v-model="disposeForm.trainingFlag" active-value="1" inactive-value="0" />
          </el-form-item>
        </el-form>
        <el-button type="primary" size="small" @click="submitDispose">提交处置</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
/**
 * 16.8.1 拒绝告警（拆分自原越权检测操作台）：403 拒绝告警台账 + 越权事件处置。
 * 共享告警列表见 useAuthzDetection.js（临时降权页提前恢复后亦刷新该列表）。
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { disposeIncident } from '@/api/system/authz-detection'
import { useAuthzDetection } from './useAuthzDetection'
import { statusType, statusText } from './authz-helpers'

const { alertQuery, alerts, loadAlerts } = useAuthzDetection()

// ---------- 处置 ----------
const disposeDrawer = ref(false)
const current = reactive({})
const disposeForm = reactive({ action: 'MISUSE', conclusion: '', trainingFlag: '0' })

const openDispose = (row) => {
  Object.assign(current, row)
  disposeForm.action = 'MISUSE'
  disposeForm.conclusion = ''
  disposeForm.trainingFlag = '0'
  disposeDrawer.value = true
}
const submitDispose = async () => {
  if (!disposeForm.conclusion || disposeForm.conclusion.trim().length < 10) {
    ElMessage.warning('处置结论不少于 10 字（须写明核实过程）')
    return
  }
  const tip = disposeForm.action === 'MALICIOUS'
    ? '确认恶意将联动停用该账号（STATUS=0），不可轻易撤销。确认提交？'
    : '确认提交该处置结论？'
  await ElMessageBox.confirm(tip, '处置确认', { type: 'warning' })
  await disposeIncident(current.id, { ...disposeForm })
  ElMessage.success('处置完成（SECURITY_EVENT 已留痕）')
  disposeDrawer.value = false
  loadAlerts(alerts.page)
}

// ---------- 展示辅助（本页独有） ----------
const dispType = (d) => ({ MISUSE: 'warning', RESTORE: 'success', MALICIOUS: 'danger' }[d] || 'info')
const dispText = (d) => ({ MISUSE: '误用', RESTORE: '提前恢复', MALICIOUS: '恶意' }[d] || d)

onMounted(() => {
  loadAlerts(1)
})
</script>
