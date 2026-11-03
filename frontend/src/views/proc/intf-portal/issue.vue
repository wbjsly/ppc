<template>
  <div class="intf-page">
    <PageInfo title="凭证管理（15.3）：签发、轮换提醒、紧急吊销、泄露追溯" description="复用 api-credential-management。Secret 仅签发时一次性展示；紧急吊销 3 秒生效（BR-5.5-15），限 ADMIN。" style="margin-bottom:12px" />

    <!-- 15.3.1 凭证签发 -->
    <div class="tab-bar">
      <el-button type="primary" size="small" @click="issueDrawer=true">签发凭证</el-button>
    </div>
    <el-table :data="creds" border size="small">
      <el-table-column prop="apiKey" label="API Key" width="180" />
      <el-table-column prop="partnerCode" label="伙伴" width="120" />
      <el-table-column prop="env" label="环境" width="80" />
      <el-table-column prop="rateTier" label="限流档" width="90" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="stTag(row.status)">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="expireAt" label="到期" width="160" />
    </el-table>
    <el-alert v-if="issued" type="success" :closable="false" style="margin-top:10px"
      :title="`已签发：${issued.apiKey}（Secret 仅此一次展示，请立即保存）`">
      <template #default>
        <el-input type="textarea" :rows="2" :model-value="issued.secret" readonly />
      </template>
    </el-alert>

    <!-- 签发抽屉 -->
    <el-drawer title="签发凭证" size="420px" v-model="issueDrawer">
      <el-form label-width="90px" size="small">
        <el-form-item label="伙伴编码" required><el-input v-model="ifForm.partnerCode" /></el-form-item>
        <el-form-item label="环境" required>
          <el-select v-model="ifForm.env" style="width:100%">
            <el-option label="沙箱 SANDBOX" value="SANDBOX" />
            <el-option label="生产 PROD" value="PROD" />
          </el-select>
        </el-form-item>
        <el-form-item label="限流档" required>
          <el-select v-model="ifForm.rateTier" style="width:100%">
            <el-option label="战略 500" value="STRATEGIC" />
            <el-option label="普通 100" value="NORMAL" />
            <el-option label="新伙伴 30" value="NEW" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联契约">
          <el-input v-model="ifForm.contractNo" placeholder="生产凭证须契约已发布（BR-5.5-01）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="issueDrawer=false">取消</el-button>
        <el-button type="primary" @click="issue">签发</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
/**
 * 15.3.1 凭证签发（拆分自原凭证管理操作台）：签发抽屉 / Secret 一次性展示。
 * 共享状态见 useCredentials.js，状态标签映射见 credential-helpers.js。
 */
import PageInfo from '@/components/PageInfo.vue'
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { issueCredentialApi } from '@/api/proc/interface-integration'
import { useCredentials } from './useCredentials'
import { stTag } from './credential-helpers'

const { creds, load } = useCredentials()

const issueDrawer = ref(false)
const issued = ref(null)
const ifForm = ref({ partnerCode: '', env: 'SANDBOX', rateTier: 'NORMAL', contractNo: '' })

onMounted(load)

async function issue() {
  if (!ifForm.value.partnerCode) { ElMessage.warning('伙伴编码必填'); return }
  try {
    const r = await issueCredentialApi(ifForm.value)
    issued.value = r.data?.data || null
    ElMessage.success('凭证已签发（Secret 仅此一次展示）')
    issueDrawer.value = false
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '签发失败（生产凭证须契约已发布 BR-5.5-01）')
  }
}
</script>

<style scoped>
.intf-page { padding: 12px; }
.tab-bar { margin-bottom: 10px; display: flex; justify-content: flex-end; }
</style>
