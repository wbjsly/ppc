<template>
  <div class="intf-page">
    <PageInfo title="调用审计：审计日志、调用追踪 Trace" description="复用 interface-call-console。审计写入失败 fail-closed 阻断业务调用（BR-5.5-11）；保留 ≥ AUDIT_RETENTION_DAYS。" style="margin-bottom:12px" />

    <!-- 15.7.2 调用追踪 -->
    <el-form inline size="small" @submit.prevent>
      <el-form-item label="Request-Id">
        <el-input v-model="reqId" placeholder="X-Request-Id" clearable style="width:300px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" size="small" @click="loadTrace">查询 Trace</el-button>
      </el-form-item>
    </el-form>
    <el-table :data="traceRows" border size="small">
      <el-table-column prop="callAt" label="时间" width="170" />
      <el-table-column prop="caller" label="调用方" width="130" />
      <el-table-column prop="apiPath" label="接口" min-width="200" show-overflow-tooltip />
      <el-table-column prop="respCode" label="状态码" width="90" />
      <el-table-column prop="costMs" label="耗时ms" width="90" />
      <el-table-column prop="errCode" label="错误码" width="130">
        <template #default="{ row }">{{ row.errCode || '—' }}</template>
      </el-table-column>
    </el-table>
    <el-alert v-if="traceSearched && !traceRows.length" type="info" :closable="false" style="margin-top:10px"
              title="未命中该 Request-Id 的调用链" />
    <el-alert v-if="traceHit" type="success" :closable="false" style="margin-top:10px"
              :title="`Trace 命中：${traceHit.apiPath || ''} · ${traceHit.costMs || '-'}ms · ${traceHit.respCode || ''}`" />
  </div>
</template>

<script setup>
/**
 * 15.7.2 调用追踪（拆分自原调用审计操作台）：按 Request-Id 查询调用链。
 * 与审计日志页无共享状态，未抽 composable。
 * 注：文件名取 audit-trace.vue 而非 manifest 的 trace.vue——与 CredentialPage
 * 15.3.4 的 trace.vue 在同目录重名，须回写 split-manifest.md。
 */
import PageInfo from '@/components/PageInfo.vue'
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { getTraceApi } from '@/api/proc/interface-call-console'

const reqId = ref('')
const traceRows = ref([])
const traceSearched = ref(false)

const traceHit = computed(() => traceRows.value[0] || null)

async function loadTrace() {
  if (!reqId.value) { ElMessage.warning('输入 Request-Id'); return }
  traceSearched.value = true
  try {
    const r = await getTraceApi(reqId.value)
    const d = r.data?.data
    traceRows.value = d ? (Array.isArray(d) ? d : [d]) : []
  } catch { traceRows.value = [] }
}
</script>

<style scoped>
.intf-page { padding: 12px; }
</style>
