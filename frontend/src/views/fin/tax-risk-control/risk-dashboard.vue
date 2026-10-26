<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>税务风险预警（9.6.1）</span>
          <el-button type="primary" size="small" @click="loadAll">刷新</el-button>
        </div>
      </template>
      <el-tabs v-model="tab">
        <!-- ============ 风险看板 ============ -->
        <el-tab-pane label="风险看板" name="dashboard">
          <el-alert v-if="dash" :closable="false" type="info" show-icon class="mb8"
            :title="`共 ${dash.count} 条信号（8 类信号聚合：本叶 3 类 + 跨叶只读 5 类，单源失败降级）`" />
          <el-table :data="signals" size="small" border>
            <el-table-column prop="level" label="等级" width="70">
              <template #default="{ row }">
                <el-tag :type="row.level === 'L2' ? 'danger' : row.level === 'L3' ? 'warning' : 'info'" size="small">
                  {{ row.level }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="type" label="信号类型" width="170" />
            <el-table-column prop="source" label="来源模块" width="140">
              <template #default="{ row }">{{ sourceName(row.source) }}</template>
            </el-table-column>
            <el-table-column prop="object" label="对象" min-width="160" show-overflow-tooltip />
            <el-table-column prop="detail" label="详情" min-width="180" show-overflow-tooltip />
            <el-table-column prop="suggestedAction" label="建议动作" min-width="180" show-overflow-tooltip />
          </el-table>
          <div v-if="dash && dash.sources" class="muted mt8">
            数据源状态：
            <el-tag v-for="(st, src) in dash.sources" :key="src" size="small" class="mr4"
              :type="st === 'OK' ? 'success' : 'info'">{{ sourceName(src) }} {{ st }}</el-tag>
          </div>
        </el-tab-pane>

        <!-- ============ 税控/数字证书 ============ -->
        <el-tab-pane label="税控/数字证书" name="certs">
          <el-alert v-if="certWarn" :closable="false" type="warning" show-icon class="mb8"
            :title="`到期≤${certWarn.warnDays} 天 ${certWarn.expiringCount} 个；通道不可用 ${certWarn.unusableCount} 个（C-4.14-14：过期阻断税控/离线通道，数电票通道不受影响）`" />
          <el-form inline class="mb8">
            <el-form-item label="名称"><el-input v-model="certForm.certName" size="small" style="width:160px" /></el-form-item>
            <el-form-item label="编号"><el-input v-model="certForm.certNo" size="small" style="width:160px" /></el-form-item>
            <el-form-item label="到期日"><el-date-picker v-model="certForm.expireDate" type="date" size="small" value-format="YYYY-MM-DD" style="width:140px" /></el-form-item>
            <el-form-item label="通道"><el-input v-model="certForm.channel" size="small" style="width:120px" placeholder="税控通道（可空）" /></el-form-item>
            <el-form-item><el-button type="primary" size="small" @click="registerCert">登记</el-button></el-form-item>
          </el-form>
          <el-table :data="certRows" size="small" border>
            <el-table-column prop="cert.certNo" label="编号" width="160" />
            <el-table-column prop="cert.certName" label="名称" min-width="140" />
            <el-table-column prop="cert.certType" label="类型" width="110">
              <template #default="{ row }">{{ row.cert.certType === 'TAX_DISK' ? '税控盘' : '数字证书' }}</template>
            </el-table-column>
            <el-table-column prop="cert.expireDate" label="到期日" width="110" />
            <el-table-column prop="daysToExpire" label="剩余天数" width="90">
              <template #default="{ row }">
                <span :style="{ color: row.daysToExpire < 0 ? '#F56C6C' : row.daysToExpire <= 30 ? '#E6A23C' : '' }">
                  {{ row.daysToExpire }}
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="cert.status" label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.cert.status === 'VALID' ? 'success' : 'danger'" size="small">{{ row.cert.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="cert.channel" label="绑定通道" width="120" />
            <el-table-column label="操作" width="110">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="renewCert(row.cert)">更新到期日</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- ============ 离线开票 ============ -->
        <el-tab-pane label="离线开票" name="offline">
          <el-alert :closable="false" type="info" show-icon class="mb8"
            title="离线开票为手工登记留桩；超过 48 小时未补传 → L4 告警（口径：系统无真实税控通道，时间以登记为准）" />
          <el-form inline class="mb8">
            <el-form-item label="原因"><el-input v-model="offlineForm.reason" size="small" style="width:220px" /></el-form-item>
            <el-form-item label="通道"><el-input v-model="offlineForm.channel" size="small" style="width:130px" /></el-form-item>
            <el-form-item><el-button type="primary" size="small" @click="registerOffline">登记离线开票</el-button></el-form-item>
          </el-form>
          <el-table :data="offlineRows" size="small" border>
            <el-table-column prop="offlineNo" label="离线单号" width="140" />
            <el-table-column prop="openTime" label="开票时间" width="160" />
            <el-table-column prop="reason" label="原因" min-width="160" show-overflow-tooltip />
            <el-table-column prop="uploadTime" label="补传时间" width="160">
              <template #default="{ row }">{{ row.uploadTime || '—' }}</template>
            </el-table-column>
            <el-table-column prop="alertFlag" label="告警" width="80">
              <template #default="{ row }">
                <el-tag v-if="row.alertFlag === '1'" type="danger" size="small">L4</el-tag>
                <span v-else>—</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="100">
              <template #default="{ row }">
                <el-button v-if="!row.uploadTime" link type="primary" size="small" @click="uploadOffline(row)">补传</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- ============ 税负率 ============ -->
        <el-tab-pane label="税负率监控" name="burden">
          <el-form inline class="mb8">
            <el-form-item label="截至所属期">
              <el-input v-model="burdenPeriod" size="small" style="width:110px" placeholder="yyyyMM" />
            </el-form-item>
            <el-form-item><el-button type="primary" size="small" @click="loadBurden">计算</el-button></el-form-item>
            <span class="muted">增值税税负率 =（销项−进项+转出）÷不含税销售额；下限与环比波动阈值见系统参数</span>
          </el-form>
          <el-table :data="burdenRows" size="small" border>
            <el-table-column prop="period" label="所属期" width="90" />
            <el-table-column prop="vatBurden" label="增值税税负率" width="120">
              <template #default="{ row }">{{ row.vatBurden != null ? (Number(row.vatBurden) * 100).toFixed(2) + '%' : '不适用' }}</template>
            </el-table-column>
            <el-table-column prop="momChange" label="环比" width="100">
              <template #default="{ row }">{{ row.momChange != null ? (Number(row.momChange) * 100).toFixed(2) + '%' : '—' }}</template>
            </el-table-column>
            <el-table-column prop="outputTax" label="销项税" width="100" />
            <el-table-column prop="inputTax" label="进项税" width="100" />
            <el-table-column prop="transferOut" label="转出" width="90" />
            <el-table-column prop="netSales" label="不含税销售额" width="120" />
            <el-table-column prop="citContribution" label="所得税贡献率" width="120">
              <template #default="{ row }">{{ row.citContribution != null ? (Number(row.citContribution) * 100).toFixed(2) + '%' : '不适用' }}</template>
            </el-table-column>
            <el-table-column label="超界" min-width="200">
              <template #default="{ row }">
                <template v-if="row.breaches && row.breaches.length">
                  <el-tag v-for="(b, i) in row.breaches" :key="i" type="danger" size="small" class="mr4">{{ b.message }}</el-tag>
                </template>
                <span v-else class="muted">正常</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  riskDashboard, certList, certRegister, certUpdate, certWarnings,
  offlineList, offlineRegister, offlineUpload, burdenMonthly
} from '@/api/fin/tax-risk-control'

const tab = ref('dashboard')
const dash = ref(null)
const certWarn = ref(null)
const certRows = ref([])
const offlineRows = ref([])
const burdenRows = ref([])
const burdenPeriod = ref(new Date().toISOString().slice(0, 7).replace('-', ''))

const certForm = ref({ certType: 'DIGITAL_CERT', certName: '', certNo: '', expireDate: '', channel: '' })
const offlineForm = ref({ reason: '', channel: '' })

const signals = computed(() => (dash.value && dash.value.signals) || [])

const SOURCE_NAMES = {
  'tax-cert': '税控/证书', 'tax-preference': '优惠备案', 'tax-burden': '税负率',
  'input-cert-expiry': '进项认证(M9-2)', 'export-refund': '出口退税(M9-5)',
  'overdue-return': '逾期申报(M9-5)', 'pending-adjust': '跨期调整(M9-5)',
  'frequent-red-flush': '频繁红冲(M9-4)'
}
const sourceName = (s) => SOURCE_NAMES[s] || s

async function loadAll() {
  try { dash.value = (await riskDashboard({})).data } catch (e) { /* 降级 */ }
  try { certWarn.value = (await certWarnings()).data } catch (e) { /* ignore */ }
  try { certRows.value = (await certList({})).data || [] } catch (e) { /* ignore */ }
  try { offlineRows.value = (await offlineList()).data || [] } catch (e) { /* ignore */ }
}

async function registerCert() {
  try {
    await certRegister(certForm.value)
    ElMessage.success('证书登记成功')
    certForm.value = { certType: 'DIGITAL_CERT', certName: '', certNo: '', expireDate: '', channel: '' }
    loadAll()
  } catch (e) { /* 拦截器已提示 */ }
}

async function renewCert(cert) {
  const { value } = await ElMessageBox.prompt('新到期日（yyyy-MM-dd）', '更新证书', {
    inputPattern: /^\d{4}-\d{2}-\d{2}$/, inputErrorMessage: '格式 yyyy-MM-dd'
  })
  try {
    await certUpdate(cert.id, { expireDate: value })
    ElMessage.success('已更新，通道阻断解除')
    loadAll()
  } catch (e) { /* ignore */ }
}

async function registerOffline() {
  try {
    await offlineRegister(offlineForm.value)
    ElMessage.success('离线开票已登记')
    offlineForm.value = { reason: '', channel: '' }
    loadAll()
  } catch (e) { /* ignore */ }
}

async function uploadOffline(row) {
  try {
    await offlineUpload(row.id)
    ElMessage.success('已补传，告警消除')
    loadAll()
  } catch (e) { /* ignore */ }
}

async function loadBurden() {
  try {
    const { data } = await burdenMonthly({ periodEnd: burdenPeriod.value, months: 6 })
    burdenRows.value = data.rows || []
  } catch (e) { /* ignore */ }
}

import { ElMessageBox } from 'element-plus'
onMounted(() => { loadAll(); loadBurden() })
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
.mb8 { margin-bottom: 8px; }
.mt8 { margin-top: 8px; }
.mr4 { margin-right: 4px; }
.muted { color: #909399; font-size: 12px; }
</style>
