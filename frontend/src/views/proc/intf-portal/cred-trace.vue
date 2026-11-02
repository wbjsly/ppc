<template>
  <div class="intf-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px"
      title="凭证管理（15.3）：签发、轮换提醒、紧急吊销、泄露追溯"
      description="复用 api-credential-management。Secret 仅签发时一次性展示；紧急吊销 3 秒生效（BR-5.5-15），限 ADMIN。" />

    <!-- 15.3.4 泄露追溯 -->
    <el-form inline size="small">
      <el-form-item label="伙伴编码">
        <el-input v-model="tracePartner" placeholder="如 PARTNER_A" clearable style="width:180px" />
      </el-form-item>
      <el-button type="primary" size="small" @click="trace">追溯</el-button>
    </el-form>
    <el-table v-if="traceRows.length" :data="traceRows" border size="small">
      <el-table-column prop="callAt" label="调用时间" width="170" />
      <el-table-column prop="caller" label="调用方" width="130" />
      <el-table-column prop="apiKey" label="API Key" width="180" />
      <el-table-column prop="apiPath" label="接口" min-width="180" show-overflow-tooltip />
      <el-table-column prop="respCode" label="状态码" width="90" />
      <el-table-column prop="env" label="环境" width="80" />
    </el-table>
    <el-alert v-if="traceDone && !traceRows.length" type="info" :closable="false" style="margin-top:10px"
              title="该伙伴无调用记录（或凭证未被使用）" />
  </div>
</template>

<script setup>
/**
 * 15.3.4 泄露追溯（拆分自原凭证管理操作台）：按伙伴追溯凭证调用。
 * 本页独有逻辑，未抽 composable。
 * 注：文件名取 cred-trace.vue 而非 manifest 的 trace.vue——与 AuditPage
 * 15.7.2 的调用追踪页在同目录重名，须回写 split-manifest.md。
 */
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getCredentialTraceApi } from '@/api/proc/interface-integration'

const tracePartner = ref('')
const traceRows = ref([])
const traceDone = ref(false)

async function trace() {
  if (!tracePartner.value) { ElMessage.warning('输入伙伴编码'); return }
  traceDone.value = true
  try {
    const r = await getCredentialTraceApi(tracePartner.value)
    traceRows.value = r.data?.data || []
  } catch { traceRows.value = [] }
}
</script>

<style scoped>
.intf-page { padding: 12px; }
</style>
