<template>
  <div class="page-wrap">
    <!-- 死信/暂存告警入口（BR-4.9-15/16） -->
    <el-row :gutter="12" class="mb12">
      <el-col :span="6" v-for="c in cards" :key="c.label">
        <el-card shadow="never">
          <div class="stat-label">{{ c.label }}</div>
          <div class="stat-value" :style="{ color: c.color }">{{ c.value }}</div>
          <div class="stat-sub">{{ c.sub }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never">
          <div class="stat-label">操作</div>
          <el-button size="small" type="primary" plain @click="doCollect">采集一轮 SLA 指标</el-button>
          <el-button size="small" type="primary" plain style="margin-top:6px"
                     @click="$router.push('/m/12.2.3')">前往消费监控（死信工单）</el-button>
        </el-card>
      </el-col>
    </el-row>

    <!-- 告警列表 -->
    <el-card shadow="never">
      <div class="card-head mb12">
        <h4 style="margin:0">告警处理（12.2.4）—— 三级告警聚合展示 + 确认收口</h4>
        <div>
          <el-select v-model="query.level" placeholder="级别" clearable size="small" style="width:130px" @change="load">
            <el-option label="Warning" value="WARNING" />
            <el-option label="Critical" value="CRITICAL" />
            <el-option label="Emergency" value="EMERGENCY" />
          </el-select>
          <el-select v-model="query.status" placeholder="状态" clearable size="small"
                     style="width:120px;margin-left:8px" @change="load">
            <el-option label="未处理" value="OPEN" />
            <el-option label="已确认" value="ACK" />
            <el-option label="已解决" value="RESOLVED" />
          </el-select>
        </div>
      </div>
      <el-table :data="rows" size="small" border v-loading="loading">
        <el-table-column label="级别" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="levelType(row.level)">{{ levelName(row.level) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="metricKey" label="指标" width="170" />
        <el-table-column prop="partnerCode" label="伙伴" width="110" />
        <el-table-column prop="message" label="告警内容" min-width="220" show-overflow-tooltip />
        <el-table-column label="实际/阈值" width="130" align="center">
          <template #default="{ row }">{{ row.actualValue }} / {{ row.thresholdValue }}</template>
        </el-table-column>
        <el-table-column prop="alertAt" label="首次触发" width="160" />
        <el-table-column label="升级" width="110" align="center">
          <template #default="{ row }">
            <span v-if="row.escalationLevel > 1">L{{ row.escalationLevel }} {{ row.escalatedTo || '' }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'OPEN' ? 'danger' : row.status === 'ACK' ? 'warning' : 'success'">
              {{ { OPEN: '未处理', ACK: '已确认', RESOLVED: '已解决' }[row.status] || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="respondedBy" label="确认人" width="100" />
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button v-if="row.status === 'OPEN'" size="small" text type="primary"
                       @click="doRespond(row)">确认</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="mt12" layout="total, prev, pager, next" :total="total"
                     :page-size="query.size" :current-page="query.current" @current-change="onPage" />
      <div class="hint mt12">
        告警计算（5 分钟聚合去重、连续 3 次 Critical 升级链）与响应计时由 interface-sla-monitoring 承接，
        本页为聚合展示 + 确认收口；确认后 5 分钟聚合窗口内不再重复推送。
        事件域死信/暂存上限告警的工单处置在 <a href="javascript:;" @click="$router.push('/m/12.2.3')">12.2.3 消费监控</a>。
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getAlertsApi, respondAlertApi, collectSlaApi, getDeliveryStatsApi } from '@/api/proc/event-subscription'

const loading = ref(false)
const rows = ref([])
const stats = ref({})
const total = ref(0)
const query = ref({ level: '', status: 'OPEN', current: 1, size: 20 })

const cards = computed(() => [
  { label: '未处理告警', value: stats.value.openAlerts ?? '-', sub: 'Warning/Critical/Emergency', color: '#e6a23c' },
  { label: '死信积压', value: stats.value.dead ?? '-', sub: '阈值 1000 触发紧急升级', color: '#f56c6c' },
  { label: '暂存队列', value: stats.value.staged ?? '-', sub: '上限 10000 触发紧急升级', color: '#909399' }
])

function levelType(l) {
  return { WARNING: 'warning', CRITICAL: 'danger', EMERGENCY: 'danger' }[l] || 'info'
}

function levelName(l) {
  return { WARNING: 'Warning', CRITICAL: 'Critical', EMERGENCY: 'Emergency' }[l] || l
}

async function load() {
  loading.value = true
  try {
    const [a, s] = await Promise.all([getAlertsApi({ ...query.value }), getDeliveryStatsApi()])
    rows.value = a.data?.records || []
    total.value = a.data?.total || 0
    stats.value = s.data || {}
  } finally {
    loading.value = false
  }
}

function onPage(p) {
  query.value.current = p
  load()
}

async function doCollect() {
  const n = await collectSlaApi()
  ElMessage.success(`已采集 ${n.data ?? 0} 条指标`)
  load()
}

async function doRespond(row) {
  await respondAlertApi(row.id)
  ElMessage.success('告警已确认（记录确认人与时间，窗口内不再重复推送）')
  load()
}

onMounted(load)
</script>

<style scoped>
.page-wrap { padding: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.mb12 { margin-bottom: 12px; }
.mt12 { margin-top: 12px; }
.hint { color: #909399; font-size: 12px; }
.stat-label { color: #909399; font-size: 12px; }
.stat-value { font-size: 22px; font-weight: 600; margin-top: 4px; }
.stat-sub { color: #c0c4cc; font-size: 11px; margin-top: 2px; }
</style>
