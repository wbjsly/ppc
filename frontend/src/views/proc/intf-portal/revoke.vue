<template>
  <div class="intf-page">
    <PageInfo title="凭证管理：签发、轮换提醒、紧急吊销、泄露追溯" description="复用 api-credential-management。Secret 仅签发时一次性展示；紧急吊销 3 秒生效（BR-5.5-15），限 ADMIN。" style="margin-bottom:12px" />

    <!-- 15.3.3 紧急吊销 -->
    <el-table :data="creds" border size="small">
      <el-table-column prop="apiKey" label="API Key" width="180" />
      <el-table-column prop="partnerCode" label="伙伴" width="120" />
      <el-table-column prop="env" label="环境" width="80" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="stTag(row.status)">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="expireAt" label="到期" width="160" />
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button size="small" type="danger" :disabled="row.status==='REVOKED'"
                     @click="revoke(row)">紧急吊销</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-alert type="warning" :closable="false" style="margin-top:10px"
              title="紧急吊销 3 秒内全部在途请求返回 401（BR-5.5-15），限 ADMIN" />
  </div>
</template>

<script setup>
/**
 * 15.3.3 紧急吊销（拆分自原凭证管理操作台）：凭证吊销。
 * 共享状态见 useCredentials.js，状态标签映射见 credential-helpers.js。
 */
import PageInfo from '@/components/PageInfo.vue'
import { onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { revokeCredentialApi } from '@/api/proc/interface-integration'
import { useCredentials } from './useCredentials'
import { stTag } from './credential-helpers'

const { creds, load } = useCredentials()

onMounted(load)

async function revoke(row) {
  await ElMessageBox.confirm(`紧急吊销凭证 ${row.apiKey}？3 秒内全部在途请求返回 401（限 ADMIN）`, '提示', { type: 'warning' })
  try {
    await revokeCredentialApi(row.id, '管理员紧急吊销（15.3）')
    ElMessage.success('已吊销')
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '吊销失败（限 ADMIN）')
  }
}
</script>

<style scoped>
.intf-page { padding: 12px; }
</style>
