<template>
  <div class="intf-page">
    <PageInfo title="调用审计（15.7）：审计日志、调用追踪 Trace" description="复用 interface-call-console。审计写入失败 fail-closed 阻断业务调用（BR-5.5-11）；保留 ≥ AUDIT_RETENTION_DAYS。" style="margin-bottom:12px" />

    <!-- 15.7.1 审计日志 -->
    <el-table :data="logs" border size="small">
      <el-table-column prop="callAt" label="时间" width="170" />
      <el-table-column prop="caller" label="调用方" width="130" />
      <el-table-column prop="apiPath" label="接口" min-width="200" show-overflow-tooltip />
      <el-table-column prop="respCode" label="状态码" width="90" />
      <el-table-column prop="costMs" label="耗时ms" width="90" />
      <el-table-column prop="reqId" label="X-Request-Id" min-width="240" show-overflow-tooltip />
    </el-table>
    <el-alert v-if="!logs.length" type="info" :closable="false" style="margin-top:10px"
              title="暂无审计记录（业务调用会实时写入，写入失败即阻断调用）" />
  </div>
</template>

<script setup>
/**
 * 15.7.1 审计日志（拆分自原调用审计操作台）：审计记录列表。
 * 与调用追踪页无共享状态，未抽 composable。
 */
import PageInfo from '@/components/PageInfo.vue'
import { ref, onMounted } from 'vue'
import { getCallLogsApi } from '@/api/proc/interface-integration'

const logs = ref([])

async function load() {
  try { const r = await getCallLogsApi(); logs.value = r.data?.data || [] } catch { logs.value = [] }
}
onMounted(load)
</script>

<style scoped>
.intf-page { padding: 12px; }
</style>
