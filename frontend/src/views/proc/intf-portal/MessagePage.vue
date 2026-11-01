<template>
  <div class="intf-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px"
      title="报文网关（15.4）：四级校验、幂等判定、结果回执、死信处理"
      description="复用 edi-message-processing。校验分传输/语法/映射/业务四级短路；重试 ≤3 次后转死信（BR-5.5-09）。" />

    <el-tabs v-model="tab">
      <!-- 15.4.1 逐级校验 -->
      <el-tab-pane label="逐级校验" name="check">
        <el-table :data="msgs" border size="small">
          <el-table-column prop="msgNo" label="报文号" width="150" />
          <el-table-column prop="partnerCode" label="伙伴" width="110" />
          <el-table-column prop="msgType" label="类型" width="90" />
          <el-table-column label="传输" width="80">
            <template #default>{{ 'PASS' }}</template>
          </el-table-column>
          <el-table-column prop="syntaxResult" label="语法" width="90">
            <template #default="{ row }"><el-tag size="small" :type="rTag(row.syntaxResult)">{{ row.syntaxResult || '—' }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="mapResult" label="映射" width="90">
            <template #default="{ row }"><el-tag size="small" :type="rTag(row.mapResult)">{{ row.mapResult || '—' }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="bizResult" label="业务" width="90">
            <template #default="{ row }"><el-tag size="small" :type="rTag(row.bizResult)">{{ row.bizResult || '—' }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="lineTotal" label="行数" width="70" align="center" />
          <el-table-column prop="lineFail" label="失败行" width="70" align="center" />
        </el-table>
      </el-tab-pane>

      <!-- 15.4.2 幂等判定 -->
      <el-tab-pane label="幂等判定" name="idempotent">
        <el-table :data="msgs" border size="small">
          <el-table-column prop="msgNo" label="报文号" width="150" />
          <el-table-column prop="idempotencyKey" label="幂等键" min-width="220" show-overflow-tooltip />
          <el-table-column prop="payloadHash" label="载荷指纹" min-width="180" show-overflow-tooltip />
          <el-table-column prop="source" label="来源" width="110" />
          <el-table-column prop="status" label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="stTag(row.status)">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="receivedAt" label="接收时间" width="160" />
        </el-table>
        <el-alert type="info" :closable="false" style="margin-top:10px"
                  title="幂等键去重：同键重复投递按载荷指纹判定（一致=幂等命中不重复处理，冲突=拒绝并留痕）" />
      </el-tab-pane>

      <!-- 15.4.3 结果回执 -->
      <el-tab-pane label="结果回执" name="receipt">
        <el-table :data="msgs" border size="small">
          <el-table-column prop="msgNo" label="报文号" width="150" />
          <el-table-column prop="partnerCode" label="伙伴" width="110" />
          <el-table-column prop="status" label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="stTag(row.status)">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="回执" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.receiptSent === '1' || row.receiptSent === 1 ? 'success' : 'info'">
                {{ row.receiptSent === '1' || row.receiptSent === 1 ? '已回执' : '未回执' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="receiptSentAt" label="回执时间" width="160">
            <template #default="{ row }">{{ row.receiptSentAt || '—' }}</template>
          </el-table-column>
          <el-table-column prop="finishedAt" label="完成时间" width="160">
            <template #default="{ row }">{{ row.finishedAt || '—' }}</template>
          </el-table-column>
        </el-table>
        <el-alert type="info" :closable="false" style="margin-top:10px"
                  title="处理完成后向发送方回执（结论=成功/失败+行级统计），回执发送状态在此跟踪" />
      </el-tab-pane>

      <!-- 15.4.4 死信处理 -->
      <el-tab-pane label="死信处理" name="dlq">
        <el-table :data="dlqRows" border size="small">
          <el-table-column prop="msgNo" label="报文号" width="150" />
          <el-table-column prop="partnerCode" label="伙伴" width="110" />
          <el-table-column prop="status" label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" type="danger">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="retryCount" label="重试" width="60" align="center" />
          <el-table-column prop="checkResult" label="失败原因" min-width="200" show-overflow-tooltip />
          <el-table-column prop="ticketId" label="工单" width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ row.ticketId || '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="170">
            <template #default="{ row }">
              <el-button size="small" type="primary" @click="replay(row)">重放</el-button>
              <el-button size="small" type="danger" @click="abandon(row)">放弃</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-alert v-if="!dlqRows.length" type="info" :closable="false" style="margin-top:10px"
                  title="无死信（重试 ≤3 次后转死信并自动生成运维工单，BR-5.5-09）" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getMessagesApi, getDeliveriesApi, replayDeliveryApi, abandonDeliveryApi } from '@/api/proc/interface-integration'

const route = useRoute()
const tab = ref(route.meta.tab || 'check')

const msgs = ref([])
const deliveries = ref([])

const dlqRows = computed(() => {
  // 死信 = 报文 DLQ/FAILED 优先，其次投递侧 DEAD 的事件
  const fromMsg = msgs.value.filter(m => m.status === 'DLQ' || m.status === 'FAILED')
  if (fromMsg.length) return fromMsg
  return deliveries.value.filter(d => d.status === 'DEAD' || d.status === 'DLQ')
})

function stTag(s) {
  return { SUCCESS: 'success', FAILED: 'danger', PENDING: 'warning', DLQ: 'danger', RECEIVED: 'info', PROCESSED: 'success' }[s] || 'info'
}
function rTag(s) {
  return { PASS: 'success', OK: 'success', FAIL: 'danger', FAILED: 'danger' }[s] || 'info'
}

async function load() {
  try { const r = await getMessagesApi(); msgs.value = r.data?.data || [] } catch { msgs.value = [] }
  try { const r = await getDeliveriesApi(); deliveries.value = r.data?.data || [] } catch { deliveries.value = [] }
}
onMounted(load)

async function replay(row) {
  await ElMessageBox.confirm(`重放报文 ${row.msgNo}？保留原幂等键`, '提示', { type: 'warning' })
  try {
    const id = row.id || row.deliveryId
    if (row.msgNo && msgs.value.includes(row)) {
      const { replayMessageApi } = await import('@/api/proc/interface-integration')
      await replayMessageApi(id)
    } else {
      await replayDeliveryApi(id)
    }
    ElMessage.success('已重放')
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '重放失败')
  }
}

async function abandon(row) {
  const { value } = await ElMessageBox.prompt('放弃原因（写入工单与追踪）', '放弃死信', { type: 'warning' })
  try {
    const id = row.id || row.deliveryId
    await abandonDeliveryApi(id, value)
    ElMessage.success('已放弃并留痕')
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '操作失败')
  }
}
</script>

<style scoped>
.intf-page { padding: 12px; }
</style>
