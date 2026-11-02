<template>
  <div class="intf-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px"
      title="报文网关（15.4）：四级校验、幂等判定、结果回执、死信处理"
      description="复用 edi-message-processing。校验分传输/语法/映射/业务四级短路；重试 ≤3 次后转死信（BR-5.5-09）。" />

    <!-- 15.4.2 幂等判定 -->
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
  </div>
</template>

<script setup>
/**
 * 15.4.2 幂等判定（拆分自原报文网关操作台）：幂等键/载荷指纹观测。
 * 共享状态见 useMessages.js，状态标签映射见 message-helpers.js。
 */
import { onMounted } from 'vue'
import { useMessages } from './useMessages'
import { stTag } from './message-helpers'

const { msgs, load } = useMessages()

onMounted(load)
</script>

<style scoped>
.intf-page { padding: 12px; }
</style>
