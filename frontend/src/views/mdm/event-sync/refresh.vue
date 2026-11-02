<template>
  <div class="page">
    <el-radio-group v-model="refreshTab" style="margin-bottom: 12px">
      <el-radio-button value="internal">内部监听执行</el-radio-button>
      <el-radio-button value="external">外部投递状态</el-radio-button>
    </el-radio-group>

    <template v-if="refreshTab === 'internal'">
      <el-form inline>
        <el-form-item label="状态">
          <el-select v-model="logStatus" clearable placeholder="全部" style="width: 140px" @change="loadListeners">
            <el-option label="成功" value="SUCCESS" />
            <el-option label="重试中" value="RETRYING" />
            <el-option label="失败" value="FAILED" />
          </el-select>
        </el-form-item>
        <el-button @click="loadListeners">查询</el-button>
        <el-button type="warning" :disabled="!isAdmin" @click="doCacheInvalidate">缓存强刷（ADMIN）</el-button>
      </el-form>
      <el-table :data="listeners" border size="small" v-loading="logLoading">
        <el-table-column prop="listenerName" label="监听器" width="150">
          <template #default="{ row }">{{ listenerLabel(row.listenerName) }}</template>
        </el-table-column>
        <el-table-column prop="eventType" label="事件类型" width="190" />
        <el-table-column prop="entityId" label="实体" width="140" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'SUCCESS' ? 'success' : row.status === 'FAILED' ? 'danger' : 'warning'">
              {{ row.status === 'SUCCESS' ? '成功' : row.status === 'FAILED' ? '失败' : '重试中' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="retryCnt" label="重试" width="60" />
        <el-table-column prop="execMs" label="耗时ms" width="80" />
        <el-table-column prop="errorMsg" label="明细/错误" show-overflow-tooltip />
        <el-table-column prop="workTicketId" label="工单" width="120" show-overflow-tooltip />
        <el-table-column prop="createDate" label="时间" width="150">
          <template #default="{ row }">{{ formatTime(row.createDate) }}</template>
        </el-table-column>
      </el-table>
    </template>

    <template v-else>
      <el-form inline>
        <el-form-item label="投递状态">
          <el-select v-model="dlvStatus" clearable placeholder="全部" style="width: 140px" @change="loadDeliveries(1)">
            <el-option label="待投递" value="PENDING" />
            <el-option label="投递中" value="DELIVERING" />
            <el-option label="已确认" value="ACKED" />
            <el-option label="失败" value="FAILED" />
            <el-option label="死信" value="DEAD" />
          </el-select>
        </el-form-item>
        <el-button @click="loadDeliveries(1)">查询</el-button>
      </el-form>
      <el-table :data="deliveries" border size="small" v-loading="dlvLoading">
        <el-table-column prop="eventId" label="事件ID" width="180" show-overflow-tooltip />
        <el-table-column prop="eventType" label="事件类型" width="190" />
        <el-table-column prop="partnerCode" label="订阅方" width="130" />
        <el-table-column prop="status" label="投递状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="dlvTag(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="retryCnt" label="重试" width="60" />
        <el-table-column prop="idempotencyKey" label="幂等键" width="180" show-overflow-tooltip />
        <el-table-column prop="errorMsg" label="失败原因" show-overflow-tooltip />
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button v-if="row.status === 'DEAD' || row.status === 'FAILED'"
                       size="small" type="warning" :disabled="!isAdmin"
                       @click="doReplay(row)">重放</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination style="margin-top: 10px" layout="total, prev, pager, next"
                     :total="dlvTotal" :page-size="20" @current-change="loadDeliveries" />
    </template>
  </div>
</template>

<script setup>
/**
 * 1.8.2 下游刷新（拆分自原事件同步操作台）：内部监听执行 + 外部投递状态
 * （页内 radio 子视图保留）。共享 formatTime 见 es-helpers.js。
 */
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getListenerLogsApi, getDeliveriesApi, replayDeliveryApi, cacheInvalidateApi } from '@/api/mdm/version-sync'
import { formatTime } from './es-helpers'

const refreshTab = ref('internal')
const isAdmin = ref(false)

const logStatus = ref('')
const listeners = ref([])
const logLoading = ref(false)

const dlvStatus = ref('')
const deliveries = ref([])
const dlvTotal = ref(0)
const dlvLoading = ref(false)

onMounted(() => {
  try {
    const roles = JSON.parse(localStorage.getItem('roles') || '[]')
    isAdmin.value = roles.includes('ROLE_ADMIN') || roles.includes('ADMIN')
  } catch (e) { isAdmin.value = false }
  loadListeners()
  loadDeliveries(1)
})

async function loadListeners() {
  logLoading.value = true
  try {
    const r = await getListenerLogsApi({ status: logStatus.value || undefined, limit: 100 })
    listeners.value = r.data || []
  } finally {
    logLoading.value = false
  }
}

async function loadDeliveries(page) {
  dlvLoading.value = true
  try {
    const r = await getDeliveriesApi({ status: dlvStatus.value || undefined, page, size: 20 })
    deliveries.value = r.data.rows || []
    dlvTotal.value = r.data.total || 0
  } finally {
    dlvLoading.value = false
  }
}

async function doReplay(row) {
  await ElMessageBox.confirm(`重放投递 ${row.eventId}？保留原幂等键`, '提示', { type: 'warning' })
  await replayDeliveryApi(row.id)
  ElMessage.success('已重放，投递状态回到 PENDING')
  loadDeliveries(1)
}

async function doCacheInvalidate() {
  await ElMessageBox.confirm('强刷全部 MDM 本地缓存（13 类只读缓存整清）？', '提示', { type: 'warning' })
  await cacheInvalidateApi()
  ElMessage.success('缓存已全部失效')
}

function listenerLabel(n) {
  return { priceRecalcMark: '价目重算提示', creditRefresh: '信用基线刷新', taxSyncHint: '税务同步提示' }[n] || n
}
function dlvTag(s) {
  return { PENDING: 'info', DELIVERING: 'warning', ACKED: 'success', FAILED: 'danger', DEAD: 'danger' }[s] || 'info'
}
</script>
