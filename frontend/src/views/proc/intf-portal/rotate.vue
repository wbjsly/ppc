<template>
  <div class="intf-page">
    <PageInfo title="凭证管理：签发、轮换提醒、紧急吊销、泄露追溯" description="复用 api-credential-management。Secret 仅签发时一次性展示；紧急吊销 3 秒生效（BR-5.5-15），限 ADMIN。" style="margin-bottom:12px" />

    <!-- 15.3.2 定期轮换 -->
    <div class="tab-bar">
      <el-button type="primary" size="small" @click="sweep">轮换到期扫描</el-button>
    </div>
    <el-table :data="creds" border size="small">
      <el-table-column prop="apiKey" label="API Key" width="180" />
      <el-table-column prop="partnerCode" label="伙伴" width="120" />
      <el-table-column prop="env" label="环境" width="80" />
      <el-table-column prop="expireAt" label="到期" width="160">
        <template #default="{ row }">
          <span :style="{ color: isNear(row.expireAt) ? '#e6a23c' : '' }">{{ row.expireAt }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="stTag(row.status)">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110">
        <template #default="{ row }">
          <el-button size="small" type="primary" :disabled="row.status!=='ACTIVE'"
                     @click="rotate(row)">轮换</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-alert type="info" :closable="false" style="margin-top:10px"
              title="到期 7 天内标橙；轮换生成新 Secret（一次性展示），旧凭证按宽限期失效" />
  </div>
</template>

<script setup>
/**
 * 15.3.2 定期轮换（拆分自原凭证管理操作台）：到期扫描 / 凭证轮换。
 * 共享状态见 useCredentials.js，状态标签映射见 credential-helpers.js。
 */
import PageInfo from '@/components/PageInfo.vue'
import { onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { rotateCredentialApi, sweepCredentialsApi } from '@/api/proc/interface-integration'
import { useCredentials } from './useCredentials'
import { stTag } from './credential-helpers'

const { creds, load } = useCredentials()

function isNear(d) {
  if (!d) return false
  const t = new Date(String(d).replace('T', ' ').slice(0, 19)).getTime()
  return !Number.isNaN(t) && t - Date.now() < 7 * 86400000
}

onMounted(load)

async function rotate(row) {
  await ElMessageBox.confirm(`轮换凭证 ${row.apiKey}？新 Secret 一次性展示，旧凭证进入宽限期`, '提示', { type: 'warning' })
  try {
    await rotateCredentialApi(row.id)
    ElMessage.success('已轮换')
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '轮换失败')
  }
}

async function sweep() {
  try {
    const r = await sweepCredentialsApi()
    ElMessage.success(`轮换扫描完成（处理 ${r.data?.data ?? 0} 条到期提醒）`)
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '扫描失败')
  }
}
</script>

<style scoped>
.intf-page { padding: 12px; }
.tab-bar { margin-bottom: 10px; display: flex; justify-content: flex-end; }
</style>
