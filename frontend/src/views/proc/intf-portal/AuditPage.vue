<template>
  <div class="intf-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px"
      title="调用审计（15.7）：审计日志、调用追踪 Trace"
      description="复用 interface-call-console。审计写入失败 fail-closed 阻断业务调用（BR-5.5-11）；保留 ≥ AUDIT_RETENTION_DAYS。" />

    <el-tabs v-model="tab">
      <!-- 15.7.1 审计日志 -->
      <el-tab-pane label="审计日志" name="audit">
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
      </el-tab-pane>

      <!-- 15.7.2 调用追踪 -->
      <el-tab-pane label="调用追踪" name="trace">
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
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getCallLogsApi } from '@/api/proc/interface-integration'
import { getTraceApi } from '@/api/proc/interface-call-console'

const route = useRoute()
const tab = ref(route.meta.tab || 'audit')

const reqId = ref('')
const logs = ref([])
const traceRows = ref([])
const traceSearched = ref(false)

const traceHit = computed(() => traceRows.value[0] || null)

async function load() {
  try { const r = await getCallLogsApi(); logs.value = r.data?.data || [] } catch { logs.value = [] }
}
async function loadTrace() {
  if (!reqId.value) { ElMessage.warning('输入 Request-Id'); return }
  traceSearched.value = true
  try {
    const r = await getTraceApi(reqId.value)
    const d = r.data?.data
    traceRows.value = d ? (Array.isArray(d) ? d : [d]) : []
  } catch { traceRows.value = [] }
}
onMounted(load)
</script>

<style scoped>
.intf-page { padding: 12px; }
</style>
