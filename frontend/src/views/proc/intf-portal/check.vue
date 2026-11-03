<template>
  <div class="intf-page">
    <PageInfo title="报文网关（15.4）：四级校验、幂等判定、结果回执、死信处理" description="复用 edi-message-processing。校验分传输/语法/映射/业务四级短路；重试 ≤3 次后转死信（BR-5.5-09）。" style="margin-bottom:12px" />

    <!-- 15.4.1 逐级校验 -->
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
  </div>
</template>

<script setup>
/**
 * 15.4.1 逐级校验（拆分自原报文网关操作台）：传输/语法/映射/业务四级校验结果。
 * 共享状态见 useMessages.js；校验结果标签为本页独有，留页内。
 */
import PageInfo from '@/components/PageInfo.vue'
import { onMounted } from 'vue'
import { useMessages } from './useMessages'

const { msgs, load } = useMessages()

function rTag(s) {
  return { PASS: 'success', OK: 'success', FAIL: 'danger', FAILED: 'danger' }[s] || 'info'
}

onMounted(load)
</script>

<style scoped>
.intf-page { padding: 12px; }
</style>
