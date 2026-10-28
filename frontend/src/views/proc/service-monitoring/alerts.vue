<template>
  <div class="page-wrap">
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span>12.7.2 告警升级（Warning / Critical / Emergency，5 分钟聚合去重，连续 3 次 Critical 自动升级）</span>
          <span>
            <el-button size="small" @click="load">刷新</el-button>
            <el-button size="small" @click="$router.push('/m/12.7.1')">指标采集 12.7.1</el-button>
            <el-button size="small" @click="$router.push('/m/12.7.3')">月度报告 12.7.3</el-button>
          </span>
        </div>
      </template>

      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 10px;"
        title="升级链：同一指标连续 3 次 Critical → 自动升级接口运维主管 + 30 分钟响应计时（ESCALATION_RESPONSE_MIN）→ 超时未响应逐级升级供应链协同管理员并留痕（BR-4.9-28）" />
      <div class="hint" style="margin-bottom: 10px;">
        告警确认/响应回执已收口至「事件订阅 12.2.4 告警处理」，本页为升级链只读视图。
      </div>

      <div class="toolbar">
        <el-select v-model="q.level" placeholder="级别" clearable size="small" style="width: 140px;" @change="load">
          <el-option label="Warning" value="WARNING" />
          <el-option label="Critical" value="CRITICAL" />
          <el-option label="Emergency" value="EMERGENCY" />
        </el-select>
        <el-select v-model="q.status" placeholder="状态" clearable size="small" style="width: 130px;" @change="load">
          <el-option label="未响应" value="OPEN" />
          <el-option label="已响应" value="RESPONDED" />
        </el-select>
        <el-button size="small" type="primary" @click="load">查询</el-button>
        <el-button size="small" type="warning" plain @click="$router.push('/m/12.2.4')">前往 12.2.4 确认</el-button>
      </div>

      <el-table :data="rows" size="small" border v-loading="loading">
        <el-table-column prop="alertAt" label="时间" width="165">
          <template #default="{ row }">{{ String(row.alertAt || '').replace('T', ' ').slice(0, 16) }}</template>
        </el-table-column>
        <el-table-column prop="metricKey" label="指标" width="120">
          <template #default="{ row }">{{ row.metricKey }}</template>
        </el-table-column>
        <el-table-column label="级别" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="mini" :type="levelTag(row.level)">{{ row.level }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="实际/阈值" width="150">
          <template #default="{ row }">{{ row.actualValue ?? '—' }} / {{ row.thresholdValue ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="message" label="内容" min-width="240" show-overflow-tooltip />
        <el-table-column prop="escalationLevel" label="升级级" width="80" align="center" />
        <el-table-column prop="escalatedTo" label="升级至" width="150">
          <template #default="{ row }">{{ row.escalatedTo || '—' }}</template>
        </el-table-column>
        <el-table-column label="响应截止" width="165">
          <template #default="{ row }">
            <span v-if="row.responseDueAt" :class="{ overdue: isOverdue(row) }">
              {{ String(row.responseDueAt).replace('T', ' ').slice(0, 16) }}
            </span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="mini" :type="row.status === 'OPEN' ? 'danger' : 'success'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="respondedBy" label="响应人" width="110">
          <template #default="{ row }">{{ row.respondedBy || '—' }}</template>
        </el-table-column>
      </el-table>
      <el-pagination style="margin-top: 10px; justify-content: flex-end;"
        layout="total, prev, pager, next" :total="total"
        v-model:current-page="q.current" :page-size="q.size" @current-change="load" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { getAlertsApi } from '@/api/proc/service-monitoring'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const q = reactive({ level: '', status: 'OPEN', current: 1, size: 20 })

const LEVEL_TAG = { WARNING: 'warning', CRITICAL: 'danger', EMERGENCY: 'danger' }
const levelTag = l => LEVEL_TAG[l] || 'info'

function isOverdue(row) {
  if (!row.responseDueAt || row.status === 'RESPONDED') return false
  return new Date(row.responseDueAt).getTime() < Date.now()
}

async function load() {
  loading.value = true
  try {
    const { data } = await getAlertsApi({
      current: q.current, size: q.size,
      level: q.level || undefined,
      status: q.status || undefined
    })
    rows.value = (data && data.records) || []
    total.value = Number((data && data.total) || 0)
  } finally { loading.value = false }
}

onMounted(load)
</script>

<style scoped>
.page-wrap { padding: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.toolbar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 10px; }
.hint { color: #909399; font-size: 12px; }
.overdue { color: #F56C6C; font-weight: 600; }
</style>
