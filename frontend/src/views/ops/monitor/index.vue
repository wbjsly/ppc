<template>
  <div class="page">
    <el-tabs v-model="tab">
      <!-- 18.2.1 延迟采集 -->
      <el-tab-pane label="延迟采集" name="delay">
        <el-button type="primary" @click="collect">手动采集一轮</el-button>
        <el-table :data="alerts.filter(a => a.source === 'sync-delay')" border size="small" style="margin-top: 10px">
          <el-table-column prop="alertNo" label="告警号" width="200" />
          <el-table-column prop="title" label="标题" />
          <el-table-column prop="metricValue" label="延迟(分)" width="100" />
          <el-table-column prop="threshold" label="阈值" width="80" />
          <el-table-column prop="createDate" label="时间" width="160">
            <template #default="{ row }">{{ fmt(row.createDate) }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 18.2.2 阈值告警（含风暴聚合视图） -->
      <el-tab-pane label="阈值告警" name="alert">
        <el-form inline>
          <el-form-item label="级别">
            <el-select v-model="level" clearable style="width: 120px" @change="load">
              <el-option label="P1" value="P1" /><el-option label="P2" value="P2" /><el-option label="P3" value="P3" />
            </el-select>
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="status" clearable style="width: 140px" @change="load">
              <el-option label="待处理" value="OPEN" /><el-option label="已认领" value="CLAIMED" />
              <el-option label="已升级" value="ESCALATED" /><el-option label="已处置" value="RESOLVED" />
            </el-select>
          </el-form-item>
          <el-button @click="load">查询</el-button>
        </el-form>
        <el-table :data="alerts" border size="small">
          <el-table-column prop="alertNo" label="告警号" width="190" />
          <el-table-column prop="level" label="级别" width="70">
            <template #default="{ row }">
              <el-tag size="small" :type="row.level === 'P1' ? 'danger' : row.level === 'P2' ? 'warning' : 'info'">{{ row.level }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="title" label="标题" />
          <el-table-column prop="status" label="状态" width="100" />
          <el-table-column prop="escalationLevel" label="升级" width="60" />
          <el-table-column label="操作" width="160">
            <template #default="{ row }">
              <el-button v-if="row.status === 'OPEN'" size="small" @click="claim(row)">认领</el-button>
              <el-button v-if="row.status !== 'RESOLVED'" size="small" type="success" @click="resolve(row)">处置</el-button>
            </template>
          </el-table-column>
        </el-table>
        <h4 style="margin: 14px 0 6px">风暴聚合根因（BR-5.8-08）</h4>
        <el-table :data="storm" border size="small">
          <el-table-column prop="title" label="根因事件" />
          <el-table-column prop="source" label="来源" width="120" />
          <el-table-column prop="affectedCnt" label="影响面" width="80" />
          <el-table-column prop="createDate" label="时间" width="160">
            <template #default="{ row }">{{ fmt(row.createDate) }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 18.2.3 趋势分析 -->
      <el-tab-pane label="趋势分析" name="trend">
        <el-form inline>
          <el-form-item label="天数">
            <el-input-number v-model="days" :min="7" :max="90" @change="loadTrend" />
          </el-form-item>
          <el-button @click="loadTrend">查询</el-button>
        </el-form>
        <el-table :data="trend" border size="small">
          <el-table-column prop="metricDate" label="日期" width="120" />
          <el-table-column prop="category" label="类别" width="100">
            <template #default="{ row }">{{ catLabel(row.category) }}</template>
          </el-table-column>
          <el-table-column prop="avgDelayMin" label="平均延迟(分)" width="130" />
          <el-table-column prop="cnt" label="样本数" width="90" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listAlertsApi, claimAlertApi, resolveAlertApi, stormRootsApi,
  collectDelayApi, delayTrendApi } from '@/api/ops/ops-sla'

const route = useRoute()
const tab = ref(route.meta.tab || 'delay')
const level = ref('')
const status = ref('')
const days = ref(14)
const alerts = ref([])
const storm = ref([])
const trend = ref([])

onMounted(() => { load(); loadStorm(); loadTrend() })

async function load() {
  const r = await listAlertsApi({ level: level.value || undefined, status: status.value || undefined, page: 1, size: 50 })
  alerts.value = r.data || []
}
async function loadStorm() {
  const r = await stormRootsApi(20)
  storm.value = r.data || []
}
async function loadTrend() {
  const r = await delayTrendApi(days.value)
  trend.value = r.data || []
}
async function collect() {
  const r = await collectDelayApi()
  ElMessage.success(`采集完成（${r.data.collected} 类）`)
  load()
}
async function claim(row) {
  await claimAlertApi(row.id)
  ElMessage.success('已认领')
  load()
}
async function resolve(row) {
  await resolveAlertApi(row.id, '页面处置')
  ElMessage.success('已处置')
  load()
}
function catLabel(c) { return { ORDER: '订单', STOCK: '库存', RECON: '对账' }[c] || c }
function fmt(t) { return t ? String(t).replace('T', ' ').slice(0, 19) : '' }
</script>
