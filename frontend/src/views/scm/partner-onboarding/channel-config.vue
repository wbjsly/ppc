<template>
  <div class="page-wrap">
    <!-- 入驻状态聚合 + 通道列表 -->
    <el-card shadow="never" class="mb12">
      <div class="card-head mb12">
        <h4 style="margin:0">通道开通（12.1.3）—— 配置动作唯一入口</h4>
        <div>
          <el-input v-model="partnerCode" placeholder="伙伴编码" clearable size="small"
                    style="width:160px" @keyup.enter="load" />
          <el-button size="small" style="margin-left:8px" @click="load">查询</el-button>
          <el-button size="small" type="primary" style="margin-left:8px"
                     @click="openCreate">创建通道</el-button>
        </div>
      </div>
      <el-table :data="channels" size="small" border v-loading="loading">
        <el-table-column prop="partnerCode" label="伙伴编码" width="120" />
        <el-table-column prop="partnerName" label="伙伴名称" min-width="150" />
        <el-table-column label="关联供应商" width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.supplierId || '—' }}</template>
        </el-table-column>
        <el-table-column prop="channelType" label="类型" width="70" align="center" />
        <el-table-column prop="protocol" label="协议" width="90" align="center" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'PROD' ? 'success'
              : row.status === 'SANDBOX' ? 'info' : 'warning'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="限流档位" width="150">
          <template #default="{ row }">
            <span>{{ rateName(row.rateTier) }}（{{ row.limitPerMin }}/min）</span>
          </template>
        </el-table-column>
        <el-table-column prop="certExpireDate" label="证书到期" width="110" />
        <el-table-column prop="circuitState" label="熔断" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.circuitState === 'CLOSED' ? 'success' : 'danger'">
              {{ row.circuitState }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="openKey(row)">生成密钥</el-button>
            <el-button size="small" text type="primary" @click="openRate(row)">限流档位</el-button>
            <el-button size="small" text type="primary" @click="openCert(row)">证书</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="hint mt12">
        生产态切换须走「接口对接 2.8.3 → 接入治理」的放行会签（沙箱通过率 100% + 双人会签），
        本页新建通道默认 SANDBOX。熔断/探测/紧急吊销等运行态动作在 2.8.3 处理。
      </div>
    </el-card>

    <!-- 创建通道 -->
    <el-dialog v-model="createVisible" title="创建通道（默认 SANDBOX）" width="540px">
      <el-form :model="createForm" label-width="110px" size="small">
        <el-form-item label="伙伴编码" required>
          <el-input v-model="createForm.partnerCode" placeholder="取自供应商档案 SUP 编码" />
        </el-form-item>
        <el-form-item label="伙伴名称" required>
          <el-input v-model="createForm.partnerName" />
        </el-form-item>
        <el-form-item label="关联供应商">
          <el-select v-model="createForm.supplierId" clearable filterable
                     placeholder="用于门户「服务等级」章节隔离（迁移 182）" style="width:100%">
            <el-option v-for="s in suppliers" :key="s.id" :label="s.supplierName" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="通道类型" required>
          <el-radio-group v-model="createForm.channelType">
            <el-radio-button value="API">API</el-radio-button>
            <el-radio-button value="EDI">EDI</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="协议" required>
          <el-select v-model="createForm.protocol" style="width:100%">
            <el-option label="HTTPS（REST）" value="HTTPS" />
            <el-option label="AS2" value="AS2" />
            <el-option label="OFTP2" value="OFTP2" />
            <el-option label="SFTP" value="SFTP" />
          </el-select>
        </el-form-item>
        <el-form-item label="回调 URL">
          <el-input v-model="createForm.callbackUrl" placeholder="https://（事件推送）" />
        </el-form-item>
        <el-form-item label="伙伴等级">
          <el-select v-model="createForm.partnerTier" style="width:100%">
            <el-option label="新伙伴（30 次/分钟）" value="NEW" />
            <el-option label="普通伙伴（100 次/分钟）" value="NORMAL" />
            <el-option label="战略伙伴（500 次/分钟）" value="STRATEGIC" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="doCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 生成密钥 -->
    <el-dialog v-model="keyVisible" title="生成 API Key + Secret" width="540px">
      <el-form label-width="90px" size="small">
        <el-form-item label="scope" required>
          <el-input v-model="keyForm.scope" placeholder="如 po:read,asn:write,inv:read" />
        </el-form-item>
        <el-form-item label="有效期(天)">
          <el-input-number v-model="keyForm.ttlDays" :min="1" :max="365" />
          <span class="hint">默认 90 天</span>
        </el-form-item>
      </el-form>
      <!-- 明文结果（仅本次） -->
      <el-alert v-if="keyResult" type="warning" :closable="false" class="mb12"
                title="明文仅本次展示，关闭后不可再查（落库为加盐哈希）" />
      <template v-if="keyResult">
        <el-form label-width="90px" size="small">
          <el-form-item label="API Key">
            <el-input :model-value="keyResult.apiKey" readonly />
          </el-form-item>
          <el-form-item label="Secret">
            <el-input :model-value="keyResult.secret" readonly />
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="keyVisible = false">{{ keyResult ? '关闭（我已保存）' : '取消' }}</el-button>
        <el-button v-if="!keyResult" type="primary" @click="doKey">生成</el-button>
      </template>
    </el-dialog>

    <!-- 限流档位 -->
    <el-dialog v-model="rateVisible" title="配置限流档位" width="420px">
      <el-radio-group v-model="rateForm.tier" class="rate-group">
        <el-radio-button value="STRATEGIC">战略 500/min</el-radio-button>
        <el-radio-button value="NORMAL">普通 100/min</el-radio-button>
        <el-radio-button value="NEW">新伙伴 30/min</el-radio-button>
      </el-radio-group>
      <div class="hint mt12">连续 3 个统计周期触发 429 将被系统自动降至最低档（BR-4.9-19）。</div>
      <template #footer>
        <el-button @click="rateVisible = false">取消</el-button>
        <el-button type="primary" @click="doRate">保存</el-button>
      </template>
    </el-dialog>

    <!-- 证书 -->
    <el-dialog v-model="certVisible" title="更新通道证书（登记台账）" width="460px">
      <el-form label-width="110px" size="small">
        <el-form-item label="证书指纹" required>
          <el-input v-model="certForm.certFingerprint" placeholder="AA:BB:CC:..." />
        </el-form-item>
        <el-form-item label="有效期至">
          <el-date-picker v-model="certForm.certExpireDate" type="date"
                          value-format="YYYY-MM-DD" placeholder="须晚于当前日期" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="certVisible = false">取消</el-button>
        <el-button type="primary" @click="doCert">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import * as api from '@/api/scm/partner-onboarding'
import { getSupplierPageApi } from '@/api/mdm/supplier-admission'

const loading = ref(false)
const channels = ref([])
const suppliers = ref([])
const partnerCode = ref('')

const createVisible = ref(false)
const createForm = reactive({
  partnerCode: '', partnerName: '', supplierId: '', channelType: 'API', protocol: 'HTTPS',
  callbackUrl: '', partnerTier: 'NEW'
})

const keyVisible = ref(false)
const keyRow = ref(null)
const keyForm = reactive({ scope: '', ttlDays: 90 })
const keyResult = ref(null)

const rateVisible = ref(false)
const rateRow = ref(null)
const rateForm = reactive({ tier: 'NORMAL' })

const certVisible = ref(false)
const certRow = ref(null)
const certForm = reactive({ certFingerprint: '', certExpireDate: '' })

onMounted(() => {
  load()
  loadSuppliers()
})

async function loadSuppliers() {
  try {
    const { data } = await getSupplierPageApi({ current: 1, size: 500 })
    suppliers.value = (data && data.records) || []
  } catch { suppliers.value = [] }
}

async function load() {
  loading.value = true
  try {
    const { data } = await api.channelList(partnerCode.value || undefined)
    channels.value = Array.isArray(data) ? data : []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(createForm, { partnerCode: '', partnerName: '', supplierId: '', channelType: 'API',
    protocol: 'HTTPS', callbackUrl: '', partnerTier: 'NEW' })
  createVisible.value = true
}

async function doCreate() {
  if (!createForm.partnerCode || !createForm.partnerName) {
    return ElMessage.warning('伙伴编码与名称必填')
  }
  try {
    await api.createChannel({ ...createForm })
    ElMessage.success('通道已创建（SANDBOX 态，生产须走放行会签）')
    createVisible.value = false
    load()
  } catch (e) {
    ElMessage.error(e?.message || '创建失败')
  }
}

function openKey(row) {
  keyRow.value = row
  keyForm.scope = ''
  keyForm.ttlDays = 90
  keyResult.value = null
  keyVisible.value = true
}

async function doKey() {
  if (!keyForm.scope.trim()) return ElMessage.warning('scope 必填')
  try {
    const { data } = await api.generateKey({
      channelId: keyRow.value.id, scope: keyForm.scope, ttlDays: keyForm.ttlDays
    })
    keyResult.value = data
    ElMessage.success('已生成（明文仅本次可见）')
  } catch (e) {
    ElMessage.error(e?.message || '生成失败')
  }
}

function openRate(row) {
  rateRow.value = row
  rateForm.tier = row.rateTier || 'NORMAL'
  rateVisible.value = true
}

async function doRate() {
  try {
    await api.updateRateTier(rateRow.value.id, rateForm.tier)
    ElMessage.success('限流档位已更新')
    rateVisible.value = false
    load()
  } catch (e) {
    ElMessage.error(e?.message || '更新失败')
  }
}

function openCert(row) {
  certRow.value = row
  certForm.certFingerprint = row.certFingerprint || ''
  certForm.certExpireDate = row.certExpireDate || ''
  certVisible.value = true
}

async function doCert() {
  if (!certForm.certFingerprint.trim()) return ElMessage.warning('证书指纹必填')
  try {
    await api.updateCertInfo(certRow.value.id, { ...certForm })
    ElMessage.success('证书台账已更新')
    certVisible.value = false
    load()
  } catch (e) {
    ElMessage.error(e?.message || '更新失败')
  }
}

function rateName(t) {
  return { STRATEGIC: '战略', NORMAL: '普通', NEW: '新伙伴', LOWEST: '最低档' }[t] || t
}
</script>

<style scoped>
.page-wrap { padding: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.mb12 { margin-bottom: 12px; }
.mt12 { margin-top: 12px; }
.hint { color: #909399; font-size: 12px; }
.rate-group { display: flex; gap: 8px; }
</style>
