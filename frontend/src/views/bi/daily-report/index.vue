<template>
  <div class="app-container">
    <el-tabs v-model="activeTab">
      <!-- 14.8.1 订阅配置 -->
      <el-tab-pane label="订阅配置" name="config">
        <el-card shadow="never">
          <div slot="header" class="clearfix">
            <span>日报定义</span>
            <el-button v-if="canConfig" style="float: right; padding: 3px 0" type="primary"
                       size="small" @click="openDef()">新增日报</el-button>
          </div>
          <el-table :data="defs" border size="small">
            <el-table-column prop="code" label="编码" width="120" />
            <el-table-column prop="name" label="名称" min-width="140" />
            <el-table-column prop="pushTime" label="推送时间" width="90" />
            <el-table-column label="指标清单" min-width="200">
              <template #default="{ row }">
                <el-tag v-for="m in parseMetrics(row.metricKeysJson)" :key="m.kpiKey"
                        size="mini" style="margin-right:4px">{{ m.title || m.kpiKey }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="subCount" label="订阅数" width="70" />
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'" size="mini">
                  {{ row.status === 'ENABLED' ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column v-if="canConfig" label="操作" width="200">
              <template #default="{ row }">
                <el-button type="text" size="mini" @click="openDef(row)">编辑</el-button>
                <el-button type="text" size="mini" @click="toggleStatus(row)">
                  {{ row.status === 'ENABLED' ? '停用' : '启用' }}
                </el-button>
                <el-button type="text" size="mini" @click="tryPush(row)">试推</el-button>
                <el-button type="text" size="mini" style="color:#f56c6c" @click="removeDef(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-card shadow="never" style="margin-top:12px">
          <div slot="header"><span>我的订阅</span></div>
          <el-table :data="subs" border size="small">
            <el-table-column prop="reportName" label="日报" min-width="140" />
            <el-table-column label="通道" min-width="180">
              <template #default="{ row }">
                <el-checkbox-group :value="parseChannels(row.channelsJson)" size="mini"
                                   @change="v => changeChannels(row, v)">
                  <el-checkbox label="MAIL">邮件</el-checkbox>
                  <el-checkbox label="WECOM">企微</el-checkbox>
                  <el-checkbox label="NOTICE">站内信</el-checkbox>
                </el-checkbox-group>
              </template>
            </el-table-column>
            <el-table-column prop="lastPushDate" label="最近推送" width="100" />
            <el-table-column prop="lastReadAt" label="最近阅读" width="160" />
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="mini">
                  {{ row.status === 'ACTIVE' ? '订阅中' : '已退订' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button v-if="row.status === 'ACTIVE'" type="text" size="mini"
                           style="color:#f56c6c" @click="doUnsubscribe(row)">退订</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- 14.8.2 自动推送 -->
      <el-tab-pane label="自动推送" name="push">
        <el-card shadow="never">
          <div slot="header"><span>触达统计（当月）</span></div>
          <el-row :gutter="12">
            <el-col :span="4"><el-statistic title="推送总数" :value="stats.total || 0" /></el-col>
            <el-col :span="4"><el-statistic title="送达数" :value="stats.sent || 0" /></el-col>
            <el-col :span="4"><el-statistic title="失败数" :value="stats.failed || 0" /></el-col>
            <el-col :span="4"><el-statistic title="降级数" :value="stats.degraded || 0" /></el-col>
            <el-col :span="4"><el-statistic title="送达率(%)" :value="Number(stats.sendRate || 0)" :precision="2" /></el-col>
            <el-col :span="4"><el-statistic title="阅读率(%)" :value="Number(stats.readRate || 0)" :precision="2" /></el-col>
          </el-row>
        </el-card>

        <el-card shadow="never" style="margin-top:12px">
          <div slot="header"><span>推送日志</span></div>
          <el-form inline size="small" @submit.prevent>
            <el-form-item label="接收人">
              <el-input v-model="logQuery.receiver" placeholder="用户名" clearable style="width:140px" />
            </el-form-item>
            <el-form-item label="状态">
              <el-select v-model="logQuery.status" clearable placeholder="全部" style="width:120px">
                <el-option label="已送达" value="SENT" />
                <el-option label="失败" value="FAILED" />
                <el-option label="降级" value="DEGRADED" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" size="small" @click="loadLogs(1)">查询</el-button>
            </el-form-item>
          </el-form>
          <el-table :data="logs" border size="small">
            <el-table-column prop="pushDate" label="推送日" width="100" />
            <el-table-column prop="receiver" label="接收人" width="110" />
            <el-table-column prop="reportCode" label="日报" width="110" />
            <el-table-column label="通道" width="80">
              <template #default="{ row }">
                {{ { MAIL: '邮件', WECOM: '企微', NOTICE: '站内信' }[row.channel] || row.channel }}
              </template>
            </el-table-column>
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag :type="{ SENT: 'success', FAILED: 'danger', DEGRADED: 'warning' }[row.sendStatus]"
                        size="mini">{{ { SENT: '送达', FAILED: '失败', DEGRADED: '降级' }[row.sendStatus] }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="retryCnt" label="重试" width="60" />
            <el-table-column prop="dataCutoff" label="数据截止" width="100" />
            <el-table-column label="质量" width="70">
              <template #default="{ row }">
                <el-tag v-if="row.qualityFlag === 'Y'" type="danger" size="mini">异常</el-tag>
                <el-tag v-else type="success" size="mini">正常</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="errorMsg" label="错误" min-width="140" show-overflow-tooltip />
          </el-table>
          <el-pagination style="margin-top:10px;text-align:right" layout="total, prev, pager, next"
                         :total="logTotal" :page-size="logQuery.size" :current-page="logQuery.page"
                         @current-change="p => loadLogs(p)" />
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 日报定义编辑抽屉 -->
    <el-drawer :title="defForm.id ? '编辑日报' : '新增日报'" :visible.sync="defDrawer" size="480px">
      <el-form v-if="defForm" label-width="90px" size="small">
        <el-form-item label="编码" required>
          <el-input v-model="defForm.code" :disabled="!!defForm.id" placeholder="唯一编码" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="defForm.name" placeholder="日报名称" />
        </el-form-item>
        <el-form-item label="推送时间">
          <el-time-select v-model="defForm.pushTime" start="05:00" step="00:30" end="10:00"
                          placeholder="07:30" />
          <div class="tip">服务端强制不早于数据就绪时间</div>
        </el-form-item>
        <el-form-item label="指标清单" required>
          <el-select v-model="defForm.metricKeys" multiple filterable placeholder="选择 KPI（来自经营看板）"
                     style="width:100%">
            <el-option v-for="k in kpis" :key="k.kpiKey" :label="k.name" :value="k.kpiKey" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="defForm.remark" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" size="small" @click="saveDef">保存</el-button>
          <el-button size="small" @click="defDrawer = false">取消</el-button>
        </el-form-item>
      </el-form>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getDefsApi, saveDefApi, changeDefStatusApi, deleteDefApi,
  mySubsApi, saveSubApi, updateSubApi, unsubscribeApi,
  getPushLogsApi, getStatsApi, pushNowApi
} from '@/api/bi/daily-report'
import { getDefsApi as getKpisApi } from '@/api/bi/kpi-dashboard'

const activeTab = ref(localStorage.getItem('dr-tab') || 'config')
const defs = ref([])
const subs = ref([])
const kpis = ref([])
const stats = ref({})
const logs = ref([])
const logTotal = ref(0)
const logQuery = ref({ receiver: '', status: '', page: 1, size: 20 })
const defDrawer = ref(false)
const defForm = ref(null)

const canConfig = computed(() => {
  const roles = JSON.parse(localStorage.getItem('roles') || '[]')
  return roles.includes('BI_DESIGNER') || roles.includes('ADMIN') || roles.includes('ROLE_BI_DESIGNER') || roles.includes('ROLE_ADMIN')
})

function parseMetrics(json) {
  try { return JSON.parse(json || '[]') } catch { return [] }
}
function parseChannels(json) {
  try { return JSON.parse(json || '["NOTICE"]') } catch { return ['NOTICE'] }
}

async function loadAll() {
  const [d, s] = await Promise.all([getDefsApi(), mySubsApi()])
  defs.value = d.data?.data || d.data || []
  subs.value = s.data?.data || s.data || []
}
async function loadKpis() {
  try {
    const r = await getKpisApi()
    kpis.value = r.data?.data || r.data || []
  } catch { kpis.value = [] }
}
async function loadStats() {
  try {
    const r = await getStatsApi()
    stats.value = r.data?.data || r.data || {}
  } catch { stats.value = {} }
}
async function loadLogs(page) {
  if (page) logQuery.value.page = page
  try {
    const r = await getPushLogsApi(logQuery.value)
    const d = r.data?.data || r.data || {}
    logs.value = d.rows || []
    logTotal.value = Number(d.total || 0)
  } catch { logs.value = []; logTotal.value = 0 }
}

function openDef(row) {
  defForm.value = row
    ? { id: row.id, code: row.code, name: row.name, pushTime: row.pushTime,
        remark: row.remark, metricKeys: parseMetrics(row.metricKeysJson).map(m => m.kpiKey) }
    : { id: null, code: '', name: '', pushTime: '07:30', remark: '', metricKeys: [] }
  defDrawer.value = true
}
async function saveDef() {
  const f = defForm.value
  if (!f.code || !f.name || !f.metricKeys.length) {
    ElMessage.warning('编码/名称/指标清单必填')
    return
  }
  const metricKeysJson = JSON.stringify(f.metricKeys.map(k => ({
    kpiKey: k, title: (kpis.value.find(x => x.kpiKey === k) || {}).name || k
  })))
  await saveDefApi({ id: f.id, code: f.code, name: f.name, pushTime: f.pushTime,
    remark: f.remark, metricKeysJson })
  ElMessage.success('已保存')
  defDrawer.value = false
  loadAll()
}
async function toggleStatus(row) {
  await changeDefStatusApi(row.id, row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED')
  ElMessage.success('已更新')
  loadAll()
}
async function removeDef(row) {
  await ElMessageBox.confirm(`删除日报定义「${row.name}」？`, '提示', { type: 'warning' })
  await deleteDefApi(row.id)
  ElMessage.success('已删除')
  loadAll()
}
async function tryPush(row) {
  const r = await pushNowApi(row.id)
  const d = r.data?.data || {}
  ElMessage.success(`试推完成：生成 ${d.generated || 0} 份，送达 ${d.sent || 0}，降级 ${d.degraded || 0}，失败 ${d.failed || 0}`)
  loadLogs(1)
  loadStats()
}

async function changeChannels(row, channels) {
  if (!channels.length) {
    ElMessage.warning('至少保留一个通道')
    return
  }
  await updateSubApi(row.id, channels)
  ElMessage.success('通道已更新')
  loadAll()
}
async function doUnsubscribe(row) {
  await ElMessageBox.confirm(`退订「${row.reportName}」后将不再接收日报，确认？`, '提示', { type: 'warning' })
  await unsubscribeApi(row.id)
  ElMessage.success('已退订')
  loadAll()
}

onMounted(() => {
  loadAll()
  loadKpis()
  if (activeTab.value === 'push') {
    loadStats()
    loadLogs(1)
  }
})
</script>

<style scoped>
.tip { color: #909399; font-size: 12px; line-height: 1.6; }
</style>
