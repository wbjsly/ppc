<template>
  <div class="intf-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px"
      title="认证鉴权（15.2）：令牌认证、HMAC 签名校验、SSO 单点登录（本系统作 IdP）"
      description="签名不匹配 401（C-5.5-05）、时间戳 ±5 分钟防重放（BR-5.5-04）。SSO 断言为限定 audience 的短令牌。" />

    <!-- 15.2.3 单点登录 -->
    <el-card shadow="never" style="margin-bottom:12px">
      <template #header>
        <div class="card-head">
          <span>兄弟系统（IdP 注册）</span>
          <el-button type="primary" size="small" @click="regDrawer=true">注册兄弟系统</el-button>
        </div>
      </template>
      <el-table :data="ssoApps" border size="small">
        <el-table-column prop="appId" label="appId" width="150" />
        <el-table-column prop="appName" label="名称" min-width="140" />
        <el-table-column prop="audience" label="audience" width="140" />
        <el-table-column prop="redirectUri" label="登出回调" min-width="180" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status==='ACTIVE'?'success':'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="issue(row)">签发断言</el-button>
            <el-button type="primary" link size="small" @click="toggleApp(row)">
              {{ row.status==='ACTIVE'?'停用':'启用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
    <el-card v-if="assertion" shadow="never">
      <template #header><span>SSO 断言（{{ assertion.audience }}，{{ assertion.expireSec }}s）</span></template>
      <el-input type="textarea" :rows="4" :model-value="assertion.assertion" readonly />
    </el-card>

    <el-drawer title="注册兄弟系统" size="420px" v-model="regDrawer">
      <el-form label-width="90px" size="small">
        <el-form-item label="appId" required><el-input v-model="regForm.appId" /></el-form-item>
        <el-form-item label="名称" required><el-input v-model="regForm.appName" /></el-form-item>
        <el-form-item label="audience" required><el-input v-model="regForm.audience" /></el-form-item>
        <el-form-item label="clientSecret"><el-input v-model="regForm.clientSecret" show-password /></el-form-item>
        <el-form-item label="登出回调"><el-input v-model="regForm.redirectUri" placeholder="https://..." /></el-form-item>
        <el-form-item label="验签公钥"><el-input v-model="regForm.publicKey" type="textarea" :rows="2" /></el-form-item>
        <el-form-item>
          <el-button type="primary" size="small" @click="saveApp">保存（ADMIN）</el-button>
          <el-button size="small" @click="regDrawer=false">取消</el-button>
        </el-form-item>
      </el-form>
    </el-drawer>
  </div>
</template>

<script setup>
/**
 * 15.2.3 单点登录（拆分自原认证鉴权操作台）：IdP 注册 / 签发断言 / 启停。
 * 本页独有逻辑，未抽 composable。
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getSsoAppsApi, registerSsoAppApi, changeSsoAppStatusApi, issueAssertionApi } from '@/api/proc/intf-portal'

const ssoApps = ref([])
const assertion = ref(null)
const regDrawer = ref(false)
const regForm = ref({ appId: '', appName: '', audience: '', clientSecret: '', redirectUri: '', publicKey: '' })

async function load() {
  try { const r = await getSsoAppsApi(); ssoApps.value = r.data?.data || [] } catch { ssoApps.value = [] }
}
onMounted(load)

async function saveApp() {
  await registerSsoAppApi(regForm.value)
  ElMessage.success('已注册（ADMIN）')
  regDrawer.value = false
  load()
}
async function toggleApp(row) {
  await changeSsoAppStatusApi(row.id, row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE')
  ElMessage.success('状态已更新')
  load()
}
async function issue(row) {
  const r = await issueAssertionApi(row.appId)
  assertion.value = r.data?.data || null
  ElMessage.success('断言已签发（短令牌）')
}
</script>

<style scoped>
.intf-page { padding: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
</style>
