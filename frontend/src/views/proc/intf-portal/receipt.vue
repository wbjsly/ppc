<template>
  <div class="intf-page">
    <PageInfo title="报文网关：四级校验、幂等判定、结果回执、死信处理" description="复用 edi-message-processing。校验分传输/语法/映射/业务四级短路；重试 ≤3 次后转死信（BR-5.5-09）。" style="margin-bottom:12px" />

    <!-- 15.4.3 结果回执 -->
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
  </div>
</template>

<script setup>
/**
 * 15.4.3 结果回执（拆分自原报文网关操作台）：回执发送状态跟踪。
 * 共享状态见 useMessages.js，状态标签映射见 message-helpers.js。
 */
import PageInfo from '@/components/PageInfo.vue'
import { onMounted } from 'vue'
import { useMessages } from './useMessages'
import { stTag } from './message-helpers'

const { msgs, load } = useMessages()

onMounted(load)
</script>

<style scoped>
.intf-page { padding: 12px; }
</style>
