<template>
  <div class="intf-page">
    <PageInfo title="报文网关（15.4）：四级校验、幂等判定、结果回执、死信处理" description="复用 edi-message-processing。校验分传输/语法/映射/业务四级短路；重试 ≤3 次后转死信（BR-5.5-09）。" style="margin-bottom:12px" />

    <!-- 15.4.4 死信处理 -->
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
  </div>
</template>

<script setup>
/**
 * 15.4.4 死信处理（拆分自原报文网关操作台）：死信重放 / 放弃留痕。
 * 共享状态见 useMessages.js。
 */
import PageInfo from '@/components/PageInfo.vue'
import { computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { replayDeliveryApi, abandonDeliveryApi } from '@/api/proc/interface-integration'
import { useMessages } from './useMessages'

const { msgs, deliveries, load } = useMessages()

const dlqRows = computed(() => {
  // 死信 = 报文 DLQ/FAILED 优先，其次投递侧 DEAD 的事件
  const fromMsg = msgs.value.filter(m => m.status === 'DLQ' || m.status === 'FAILED')
  if (fromMsg.length) return fromMsg
  return deliveries.value.filter(d => d.status === 'DEAD' || d.status === 'DLQ')
})

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
