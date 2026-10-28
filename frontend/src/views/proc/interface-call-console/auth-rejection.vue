<template>
  <div class="page-wrap">
    <!-- 拒绝原因聚合 + 明细下钻 -->
    <el-card shadow="never" class="mb12">
      <div class="card-head mb12">
        <h4 style="margin:0">认证拒绝原因分析（12.3.1）—— 400/401/403 按可观测信号四类分组</h4>
        <div>
          <el-input v-model="query.caller" placeholder="调用方" clearable size="small"
                    style="width:150px" @keyup.enter="load" />
          <el-select v-model="query.reason" placeholder="拒绝原因" clearable size="small"
                     style="width:200px;margin-left:8px" @change="load">
            <el-option label="时间戳超差（400）" value="TIMESTAMP_SKEW" />
            <el-option label="签名与口令安全事件" value="SECURITY_EVENT" />
            <el-option label="凭证认证失败（其余 401）" value="CREDENTIAL_AUTH_FAILED" />
            <el-option label="Scope 拒绝（403）" value="SCOPE_DENIED" />
          </el-select>
          <el-button size="small" style="margin-left:8px" @click="load">查询</el-button>
        </div>
      </div>

      <el-table :data="groupRows" size="small" border class="mb12"
                @row-click="drillDown" style="cursor:pointer">
        <el-table-column prop="caller" label="调用方" width="160" />
        <el-table-column label="拒绝原因" min-width="220">
          <template #default="{ row }">
            <el-tag size="small" :type="reasonType(row.reason)">{{ reasonName(row.reason) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="total" label="次数" width="100" align="center" />
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click.stop="drillDown(row)">下钻明细</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-table :data="detail" size="small" border v-loading="loading">
        <el-table-column prop="callAt" label="时间" width="165" />
        <el-table-column prop="caller" label="调用方" width="130" />
        <el-table-column prop="apiPath" label="路径" min-width="200" show-overflow-tooltip />
        <el-table-column prop="httpMethod" label="方法" width="70" align="center" />
        <el-table-column prop="respCode" label="状态码" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.respCode === 403 ? 'warning' : 'danger'">{{ row.respCode }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="errCode" label="错误码" min-width="180" show-overflow-tooltip />
        <el-table-column prop="reqId" label="X-Request-Id" width="240" show-overflow-tooltip />
      </el-table>
      <el-pagination class="mt12" layout="total, prev, pager, next" :total="detailTotal"
                     :page-size="query.size" :current-page="query.current" @current-change="onPage" />
      <div class="hint mt12">
        数据源为调用审计台账（<code>erp_intf_call_log</code>），无独立日志。
        Key/Secret 错误与凭证吊销/过期在审计同码（UNAUTHENTICATED）合并为「凭证认证失败」；
        开放入口为 Basic+签名鉴权、不解析 Bearer JWT，"Token 过期"不产生记录。
        签名与口令安全事件另有 SLA 安全告警（<code>SECURITY:*</code>）可旁证。
      </div>
    </el-card>

    <!-- 凭证只读清单 -->
    <el-card shadow="never" class="mb12">
      <template #header>
        <div class="card-head">
          <span>凭证只读清单（签发/轮换/吊销 → 12.1.3 与 2.8.3）</span>
          <span>
            <el-button size="small" @click="$router.push('/m/12.1.3')">前往 12.1.3 通道开通</el-button>
            <el-button size="small" @click="$router.push('/m/2.8.3')">前往 2.8.3 凭证与防护</el-button>
          </span>
        </div>
      </template>
      <el-table :data="credentials" size="small" border>
        <el-table-column prop="apiKey" label="API Key" width="180" show-overflow-tooltip />
        <el-table-column prop="partnerCode" label="伙伴" width="120" />
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="credType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="scope" label="Scope" min-width="160" show-overflow-tooltip />
        <el-table-column prop="rateTier" label="限流档位" width="110" />
        <el-table-column prop="env" label="环境" width="90" align="center" />
        <el-table-column prop="expireAt" label="有效期至" width="165" />
        <el-table-column prop="issueAt" label="签发时间" width="165" />
      </el-table>
    </el-card>

    <!-- 认证规则说明卡 -->
    <el-card shadow="never">
      <template #header><span>认证规则（与 open-api-gateway 实际行为一致）</span></template>
      <el-descriptions :column="2" size="small" border>
        <el-descriptions-item label="令牌换取">API Key + Secret 换取 JWT（有效期 2 小时，过期前 15 分钟可刷新，POST /api/open/token）</el-descriptions-item>
        <el-descriptions-item label="入口鉴权">HTTPS Basic Auth（口令即 Secret）+ HMAC-SHA256 报文签名（X-Signature）</el-descriptions-item>
        <el-descriptions-item label="时间戳">X-Timestamp 允许偏差 ±5 分钟，超差返回 400（BR-4.9-17）</el-descriptions-item>
        <el-descriptions-item label="拒绝语义">401/403/400 一律不降级放行（BR-4.9-20），全部回写审计台账</el-descriptions-item>
        <el-descriptions-item label="签名校验">HMAC-SHA256(RequestPayload, SignKey)，不符即 401 并记安全事件与告警（C-5.5-05）</el-descriptions-item>
        <el-descriptions-item label="Scope">凭证 Scope 以 authorities 注入，不含所请求接口 → 403</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getAuthRejectionsApi, getCredentialsApi } from '@/api/proc/interface-call-console'

const loading = ref(false)
const groupRows = ref([])
const detail = ref([])
const detailTotal = ref(0)
const credentials = ref([])
const query = ref({ caller: '', reason: '', current: 1, size: 20 })

const REASON_NAMES = {
  TIMESTAMP_SKEW: '时间戳超差（400）',
  SECURITY_EVENT: '签名与口令安全事件',
  CREDENTIAL_AUTH_FAILED: '凭证认证失败（401）',
  SCOPE_DENIED: 'Scope 拒绝（403）'
}
function reasonName(r) { return REASON_NAMES[r] || r || '其它' }
function reasonType(r) {
  return { TIMESTAMP_SKEW: 'warning', SECURITY_EVENT: 'danger',
    CREDENTIAL_AUTH_FAILED: 'info', SCOPE_DENIED: 'warning' }[r] || 'info'
}
function credType(s) {
  return { ACTIVE: 'success', DEPRECATED: 'info', REVOKED: 'danger', EXPIRED: 'warning' }[s] || 'info'
}

async function load() {
  loading.value = true
  try {
    const params = { current: query.value.current, size: query.value.size }
    if (query.value.caller) params.caller = query.value.caller
    if (query.value.reason) params.reason = query.value.reason
    const [rej, cred] = await Promise.all([getAuthRejectionsApi(params), getCredentialsApi({ current: 1, size: 50 })])
    groupRows.value = rej.data?.groups || []
    detail.value = rej.data?.detail?.records || []
    detailTotal.value = rej.data?.detail?.total || 0
    credentials.value = cred.data?.records || []
  } finally {
    loading.value = false
  }
}

function drillDown(row) {
  query.value.caller = row.caller || ''
  query.value.reason = row.reason || ''
  query.value.current = 1
  load()
}
function onPage(p) {
  query.value.current = p
  load()
}

onMounted(load)
</script>

<style scoped>
.page-wrap { padding: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.mb12 { margin-bottom: 12px; }
.mt12 { margin-top: 12px; }
.hint { color: #909399; font-size: 12px; line-height: 1.7; }
</style>
