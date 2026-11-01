<template>
  <div class="intf-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px"
      title="凭证管理（15.3）：签发、轮换提醒、紧急吊销、泄露追溯"
      description="复用 api-credential-management。Secret 仅签发时一次性展示；紧急吊销 3 秒生效（BR-5.5-15），限 ADMIN。" />

    <el-tabs v-model="tab">
      <!-- 15.3.1 凭证签发 -->
      <el-tab-pane label="凭证签发" name="issue">
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
      </el-tab-pane>

      <!-- 15.3.2 定期轮换 -->
      <el-tab-pane label="定期轮换" name="rotate">
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
      </el-tab-pane>

      <!-- 15.3.3 紧急吊销 -->
      <el-tab-pane label="紧急吊销" name="revoke">
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
      </el-tab-pane>

      <!-- 15.3.4 泄露追溯 -->
      <el-tab-pane label="泄露追溯" name="trace">
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
      </el-tab-pane>
    </el-tabs>

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
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getCredentialsApi, issueCredentialApi, rotateCredentialApi,
  revokeCredentialApi, sweepCredentialsApi, getCredentialTraceApi } from '@/api/proc/interface-integration'

const route = useRoute()
const tab = ref(route.meta.tab || 'issue')

const creds = ref([])
const issueDrawer = ref(false)
const issued = ref(null)
const tracePartner = ref('')
const traceRows = ref([])
const traceDone = ref(false)
const ifForm = ref({ partnerCode: '', env: 'SANDBOX', rateTier: 'NORMAL', contractNo: '' })

function stTag(s) {
  return { ACTIVE: 'success', PENDING: 'warning', REVOKED: 'danger', EXPIRED: 'info', DEPRECATED: 'info' }[s] || 'info'
}
function isNear(d) {
  if (!d) return false
  const t = new Date(String(d).replace('T', ' ').slice(0, 19)).getTime()
  return !Number.isNaN(t) && t - Date.now() < 7 * 86400000
}

async function load() {
  try { const r = await getCredentialsApi(); creds.value = r.data?.data || [] } catch { creds.value = [] }
}
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

async function rotate(row) {
  await ElMessageBox.confirm(`轮换凭证 ${row.apiKey}？新 Secret 一次性展示，旧凭证进入宽限期`, '提示', { type: 'warning' })
  try {
    const r = await rotateCredentialApi(row.id)
    issued.value = r.data?.data || null
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
.tab-bar { margin-bottom: 10px; display: flex; justify-content: flex-end; }
</style>
