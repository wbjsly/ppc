<template>
  <div class="page-wrap">
    <!-- 类型聚合卡 -->
    <el-row :gutter="12" class="mb12">
      <el-col :span="4" v-for="c in cards" :key="c.label">
        <el-card shadow="never">
          <div class="stat-label">{{ c.label }}</div>
          <div class="stat-value" :style="{ color: c.color }">{{ c.value }}</div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="never">
          <div class="stat-label">操作</div>
          <el-button size="small" type="primary" plain @click="doScan">立即扫描投递</el-button>
        </el-card>
      </el-col>
    </el-row>

    <!-- 投递台账 -->
    <el-card shadow="never" class="mb12">
      <div class="card-head mb12">
        <h4 style="margin:0">消费监控（12.2.3）—— 投递台账按事件类型视角</h4>
        <div>
          <el-input v-model="query.eventType" placeholder="事件类型（如 PROC.）" clearable size="small"
                    style="width:200px" @keyup.enter="load" />
          <el-select v-model="query.status" placeholder="状态" clearable size="small"
                     style="width:140px;margin-left:8px" @change="load">
            <el-option v-for="s in STATUSES" :key="s" :label="s" :value="s" />
          </el-select>
          <el-button size="small" style="margin-left:8px" @click="load">查询</el-button>
        </div>
      </div>
      <el-table :data="rows" size="small" border v-loading="loading">
        <el-table-column prop="eventType" label="事件类型" width="190" />
        <el-table-column prop="bizCode" label="业务键" min-width="160" show-overflow-tooltip />
        <el-table-column prop="partnerCode" label="伙伴" width="110" />
        <el-table-column label="目标 URL" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.targetUrl">{{ row.targetUrl }}</span>
            <el-tag v-else size="small" type="info">通道/台账</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="重试" width="90" align="center">
          <template #default="{ row }">{{ row.retryCount }}<span v-if="row.retryMax">/{{ row.retryMax }}</span></template>
        </el-table-column>
        <el-table-column prop="costMs" label="耗时ms" width="80" align="center" />
        <el-table-column prop="failReason" label="失败原因" min-width="160" show-overflow-tooltip />
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button v-if="row.status === 'DEAD' || row.status === 'RETRYING' || row.status === 'STAGED'"
                       size="small" text type="primary" @click="doReplay(row)">重放</el-button>
            <el-button v-if="row.status === 'DEAD'" size="small" text type="danger"
                       @click="doAbandon(row)">放弃</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="mt12" layout="total, prev, pager, next" :total="total"
                     :page-size="query.size" :current-page="query.current" @current-change="onPage" />
    </el-card>

    <!-- 死信工单 -->
    <el-card shadow="never">
      <h4 style="margin:0 0 12px">死信工单（{{ tickets.length }}）</h4>
      <el-table :data="tickets" size="small" border>
        <el-table-column prop="ticketNo" label="工单号" width="170" />
        <el-table-column prop="title" label="标题" min-width="200" />
        <el-table-column prop="partnerCode" label="伙伴" width="110" />
        <el-table-column prop="priority" label="优先级" width="80" align="center" />
        <el-table-column prop="status" label="状态" width="100" align="center" />
        <el-table-column prop="assignTo" label="处理人" width="110" />
        <el-table-column prop="dueAt" label="期限" width="160" />
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <template v-if="row.status === 'OPEN' || row.status === 'PROCESSING'">
              <el-button size="small" text type="primary" @click="handleTicket(row, true)">处理并重放</el-button>
              <el-button size="small" text type="success" @click="handleTicket(row, false)">关闭</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
      <div class="hint mt12">
        重放复用原幂等键（不产生重复业务效果），REPLAY_COUNT+1；放弃仅管理员可执行并留痕。
        状态机/退避/死信判定由 interface-event-delivery 承接，本页不重造。
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getDeliveriesApi, getDeliveryStatsApi, scanDeliveriesApi, replayDeliveryApi,
  abandonDeliveryApi, getTicketsApi, handleTicketApi } from '@/api/proc/event-subscription'

const STATUSES = ['PENDING', 'DELIVERING', 'DELIVERED', 'RETRYING', 'STAGED', 'DEAD', 'ABANDONED', 'CONFLICT_DROPPED']

const loading = ref(false)
const rows = ref([])
const stats = ref({})
const tickets = ref([])
const total = ref(0)
const query = ref({ eventType: '', status: '', current: 1, size: 20 })

const cards = computed(() => [
  { label: '已投递', value: stats.value.delivered ?? '-', color: '#67c23a' },
  { label: '在途/重试', value: stats.value.retrying ?? '-', color: '#e6a23c' },
  { label: '死信', value: stats.value.dead ?? '-', color: '#f56c6c' },
  { label: '暂存队列', value: stats.value.staged ?? '-', color: '#909399' },
  { label: '未结工单', value: stats.value.openTickets ?? '-', color: '#409eff' }
])

function statusType(s) {
  return { DELIVERED: 'success', DEAD: 'danger', RETRYING: 'warning', STAGED: 'warning',
    PENDING: 'info', DELIVERING: 'info' }[s] || 'info'
}

async function load() {
  loading.value = true
  try {
    const [d, s, t] = await Promise.all([
      getDeliveriesApi({ ...query.value }),
      getDeliveryStatsApi(),
      getTicketsApi('OPEN')
    ])
    rows.value = d.data?.records || []
    total.value = d.data?.total || 0
    stats.value = s.data || {}
    tickets.value = t.data || []
  } finally {
    loading.value = false
  }
}

function onPage(p) {
  query.value.current = p
  load()
}

async function doScan() {
  const n = await scanDeliveriesApi()
  ElMessage.success(`已扫描处理 ${n.data ?? 0} 条`)
  load()
}

async function doReplay(row) {
  await replayDeliveryApi(row.id)
  ElMessage.success('已重放（复用原幂等键）')
  load()
}

async function doAbandon(row) {
  const { value } = await ElMessageBox.prompt('请输入放弃原因（高危：仅管理员）', '放弃死信')
  await abandonDeliveryApi(row.id, value)
  ElMessage.success('已放弃并留痕')
  load()
}

async function handleTicket(row, replay) {
  let note = ''
  if (!replay) {
    const r = await ElMessageBox.prompt('请输入处理说明', '关闭工单')
    note = r.value
  }
  await handleTicketApi(row.id, { replay, note })
  ElMessage.success(replay ? '已处理并重放' : '工单已关闭')
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
</style>
