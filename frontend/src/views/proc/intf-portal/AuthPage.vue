<template>
  <div class="intf-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px"
      title="认证鉴权（15.2）：令牌认证、HMAC 签名校验、SSO 单点登录（本系统作 IdP）"
      description="签名不匹配 401（C-5.5-05）、时间戳 ±5 分钟防重放（BR-5.5-04）。SSO 断言为限定 audience 的短令牌。" />
    <el-tabs v-model="tab">
      <!-- 15.2.1 令牌认证 -->
      <el-tab-pane label="令牌认证" name="auth">
        <el-table :data="rejections" border size="small">
          <el-table-column prop="callAt" label="时间" width="170" />
          <el-table-column prop="caller" label="调用方" width="140" />
          <el-table-column prop="errCode" label="错误码" width="130" />
          <el-table-column prop="apiPath" label="接口" min-width="200" show-overflow-tooltip />
        </el-table>
        <el-alert v-if="!rejections.length" type="info" :closable="false" style="margin-top:10px"
                  title="近期无认证拒绝记录（令牌缺失/过期/无效的调用会被拒绝并记录在此）" />
      </el-tab-pane>

      <!-- 15.2.2 签名校验（观测面 = 拒绝分析 + 错误类型筛选） -->
      <el-tab-pane label="签名校验" name="sign">
        <el-alert type="warning" :closable="false" style="margin-bottom:10px"
                  title="HMAC 签名与防重放观测"
                  description="签名校验在网关侧执行：签名不匹配 401（C-5.5-05）、时间戳超出 ±5 分钟拒绝（BR-5.5-04）。本页按错误类型筛选拒绝记录定位问题调用方。" />
        <el-form inline size="small">
          <el-form-item label="错误类型">
            <el-select v-model="signFilter" clearable style="width: 220px" placeholder="全部">
              <el-option label="签名不匹配" value="SIGN_MISMATCH" />
              <el-option label="时间戳过期（防重放）" value="TIMESTAMP_SKEW" />
              <el-option label="令牌无效" value="INVALID_TOKEN" />
            </el-select>
          </el-form-item>
        </el-form>
        <el-table :data="signRows" border size="small">
          <el-table-column prop="callAt" label="时间" width="170" />
          <el-table-column prop="caller" label="调用方" width="140" />
          <el-table-column prop="errCode" label="错误码" width="150" />
          <el-table-column prop="apiPath" label="接口" min-width="200" show-overflow-tooltip />
        </el-table>
        <el-alert v-if="!signRows.length" type="success" :closable="false" style="margin-top:10px"
                  title="无签名校验失败记录" />
      </el-tab-pane>

      <!-- 15.2.3 单点登录 -->
      <el-tab-pane label="单点登录" name="sso">
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
      </el-tab-pane>
    </el-tabs>

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
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getAuthRejectionsApi } from '@/api/proc/interface-call-console'
import { getSsoAppsApi, registerSsoAppApi, changeSsoAppStatusApi, issueAssertionApi } from '@/api/proc/intf-portal'

const route = useRoute()
const tab = ref(route.meta.tab || 'auth')
const rejections = ref([])
const ssoApps = ref([])
const assertion = ref(null)
const regDrawer = ref(false)
const signFilter = ref('')
const regForm = ref({ appId: '', appName: '', audience: '', clientSecret: '', redirectUri: '', publicKey: '' })

/** 签名校验观测：按错误类型筛选（签名/时间戳/令牌） */
const signRows = computed(() => {
  if (!signFilter.value) return rejections.value
  const kw = { SIGN_MISMATCH: 'SIGN', TIMESTAMP_SKEW: 'TIMESTAMP', INVALID_TOKEN: 'TOKEN' }[signFilter.value]
  return rejections.value.filter(r => String(r.errCode || '').toUpperCase().includes(kw))
})

async function load() {
  try { const r = await getAuthRejectionsApi(); rejections.value = r.data?.data || [] } catch { rejections.value = [] }
  try { const r = await getSsoAppsApi(); ssoApps.value = r.data?.data || [] } catch { ssoApps.value = [] }
}
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
onMounted(load)
</script>

<style scoped>
.intf-page { padding: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
</style>
