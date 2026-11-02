<template>
  <div class="page">
    <el-form inline>
      <el-form-item label="事件类型">
        <el-select v-model="evtType" clearable placeholder="全部" style="width: 240px" @change="loadEvents(1)">
          <el-option v-for="t in EVENT_TYPES" :key="t" :label="t" :value="t" />
        </el-select>
      </el-form-item>
      <el-form-item label="实体ID">
        <el-input v-model="evtEntity" clearable style="width: 220px" placeholder="entityId 筛选" @change="loadEvents(1)" />
      </el-form-item>
      <el-button @click="loadEvents(1)">查询</el-button>
    </el-form>
    <el-table :data="events" border size="small" v-loading="evtLoading">
      <el-table-column prop="eventId" label="事件ID" width="200" show-overflow-tooltip />
      <el-table-column prop="eventType" label="事件类型" width="200" />
      <el-table-column prop="recordVersion" label="版本" width="70" />
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'PENDING' ? 'info' : 'success'">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="idempotencyKey" label="幂等键" width="180" show-overflow-tooltip />
      <el-table-column prop="occurredAt" label="发生时间" width="160">
        <template #default="{ row }">{{ formatTime(row.occurredAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button size="small" @click="viewPayload(row)">载荷</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination style="margin-top: 10px" layout="total, prev, pager, next"
                   :total="evtTotal" :page-size="20" @current-change="loadEvents" />

    <el-drawer v-model="payloadVisible" title="事件载荷" size="50%">
      <pre class="payload-pre">{{ payloadJson }}</pre>
    </el-drawer>
  </div>
</template>

<script setup>
/**
 * 1.8.1 变更事件台账（拆分自原事件同步操作台）：outbox MDM 视图查询与载荷查看。
 */
import { ref, onMounted } from 'vue'
import { getEventsApi } from '@/api/mdm/version-sync'
import { formatTime } from './es-helpers'

const TYPES = ['ITEM', 'CUSTOMER_GROUP', 'CUSTOMER_VIEW', 'SUPPLIER', 'EXCHANGE_RATE', 'TAX_CODE',
  'ORG_UNIT', 'COST_CENTER', 'PROFIT_CENTER', 'LEGAL_ENTITY', 'ITEM_CATEGORY', 'PRICE_AGREEMENT', 'TAX_POLICY']
const OPS = ['CREATED', 'UPDATED', 'DISABLED', 'MERGED']
const EVENT_TYPES = TYPES.flatMap(t => OPS.map(o => `MDM.${t}.${o}`))

const evtType = ref('')
const evtEntity = ref('')
const events = ref([])
const evtTotal = ref(0)
const evtLoading = ref(false)
const payloadVisible = ref(false)
const payloadJson = ref('')

async function loadEvents(page) {
  evtLoading.value = true
  try {
    const r = await getEventsApi({ type: evtType.value || undefined, entityId: evtEntity.value || undefined, page, size: 20 })
    events.value = r.data.rows || []
    evtTotal.value = r.data.total || 0
  } finally {
    evtLoading.value = false
  }
}

function viewPayload(row) {
  try {
    payloadJson.value = JSON.stringify(JSON.parse(row.payload), null, 2)
  } catch (e) {
    payloadJson.value = row.payload || ''
  }
  payloadVisible.value = true
}

onMounted(() => loadEvents(1))
</script>

<style scoped>
.payload-pre { background: #f5f7fa; padding: 12px; border-radius: 4px; max-height: 70vh; overflow: auto; font-size: 12px; }
</style>
