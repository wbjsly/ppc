<template>
  <div class="app-container">
    <el-tabs v-model="tab">
      <!-- ==================== 16.8.1 拒绝告警 ==================== -->
      <el-tab-pane label="拒绝告警" name="alerts">
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
      </el-tab-pane>

      <!-- ==================== 16.8.2 临时降权 ==================== -->
      <el-tab-pane label="临时降权" name="restrictions">
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
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, reactive, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listIncidents, disposeIncident, restoreIncident, sweepRestore } from '@/api/system/authz-detection'

const route = useRoute()

// ---------- Tab 定位（沿 16.1~16.7 模式）----------
const TAB_WHITELIST = ['alerts', 'restrictions']
const pickTab = () => {
  const m = route.meta?.tab
  const q = route.query?.tab
  if (TAB_WHITELIST.includes(m)) return m
  if (TAB_WHITELIST.includes(q)) return q
  return 'alerts'
}
const tab = ref(pickTab())
watch(() => route.meta?.tab, () => { tab.value = pickTab() })

// ---------- 告警 ----------
const alertQuery = reactive({ status: '' })
const alerts = reactive({ rows: [], total: 0, page: 1, size: 50, loading: false })
const loadAlerts = async (page) => {
  if (page) alerts.page = page
  alerts.loading = true
  try {
    const params = { page: alerts.page, size: alerts.size }
    if (alertQuery.status) params.status = alertQuery.status
    const { data } = await listIncidents(params)
    alerts.rows = data.rows || []
    alerts.total = data.total || 0
  } finally {
    alerts.loading = false
  }
}

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

// ---------- 展示辅助 ----------
const statusType = (s) => ({ ACTIVE: 'danger', RESTORED: 'success', DISABLED: 'info' }[s] || 'info')
const statusText = (s) => ({ ACTIVE: '降权中', RESTORED: '已恢复', DISABLED: '已禁用' }[s] || s)
const dispType = (d) => ({ MISUSE: 'warning', RESTORE: 'success', MALICIOUS: 'danger' }[d] || 'info')
const dispText = (d) => ({ MISUSE: '误用', RESTORE: '提前恢复', MALICIOUS: '恶意' }[d] || d)

// ---------- 挂载 ----------
watch(tab, (t) => {
  if (t === 'alerts' && !alerts.rows.length) loadAlerts(1)
  if (t === 'restrictions' && !restrictions.rows.length) loadRestrictions(1)
})
onMounted(() => {
  loadAlerts(1)
  loadRestrictions(1)
})
</script>
