<template>
  <div>
    <!-- 14.8.1 订阅配置 -->
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
/**
 * 14.8.1 订阅配置（拆分自原日报订阅操作台）：日报定义 CRUD / 我的订阅 / 试推。
 * 触达统计与推送日志（试推后刷新）见 useDailyReport.js（自动推送页共用）。
 */
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getDefsApi, saveDefApi, changeDefStatusApi, deleteDefApi,
  mySubsApi, updateSubApi, unsubscribeApi, pushNowApi
} from '@/api/bi/daily-report'
import { getDefsApi as getKpisApi } from '@/api/bi/kpi-dashboard'
import { useDailyReport } from './useDailyReport'

const { loadLogs, loadStats } = useDailyReport()
const defs = ref([])
const subs = ref([])
const kpis = ref([])
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
})
</script>

<style scoped>
.tip { color: #909399; font-size: 12px; line-height: 1.6; }
</style>
