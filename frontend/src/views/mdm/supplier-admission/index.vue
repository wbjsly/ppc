<template>
  <div class="supplier-admission">
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">供应商准入审核</span>
          <div class="header-actions">
            <el-input v-model="query.keyword" placeholder="编码/名称/税号" clearable
                      :prefix-icon="Search" style="width: 200px;" @keyup.enter="loadData(1)" />
            <el-select v-model="query.status" placeholder="状态" clearable style="width: 130px;" @change="loadData(1)">
              <el-option label="待审核" value="PENDING" />
              <el-option label="合格" value="QUALIFIED" />
              <el-option label="冻结" value="FROZEN" />
              <el-option label="停用" value="DISABLED" />
              <el-option label="证照过期" value="CERT_EXPIRED" />
              <el-option label="已合并" value="MERGED" />
            </el-select>
            <el-button type="primary" @click="loadData(1)">查询</el-button>
            <el-button type="primary" :icon="Plus" @click="openCreate">新建供应商</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="supplierCode" label="编码" width="110" />
        <el-table-column prop="supplierName" label="名称" min-width="170" show-overflow-tooltip />
        <el-table-column label="法人主体" width="130">
          <template #default="{ row }">{{ leName(row.legalEntityId) || '—' }}</template>
        </el-table-column>
        <el-table-column prop="taxNo" label="税号" width="120">
          <template #default="{ row }">{{ row.taxNo || '—' }}</template>
        </el-table-column>
        <el-table-column label="黑名单" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.blacklistResult === 'HIT' ? 'danger' : 'success'" size="small">
              {{ row.blacklistResult === 'HIT' ? '命中' : '未命中' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近到期证照" width="150">
          <template #default="{ row }">
            <template v-if="earliestOf(row)">
              {{ earliestOf(row).expireDate }}
              <el-tag v-if="earliestOf(row).nearExpiry" type="warning" size="small">临期</el-tag>
            </template>
            <span v-else style="color: #c0c4cc;">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="380" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button v-if="row.status !== 'CERT_EXPIRED' && row.status !== 'MERGED'" link type="primary" @click="openChange(row)">变更</el-button>
            <el-button v-if="row.status === 'PENDING'" link type="success" @click="openReview(row, 'APPROVED')">审核通过</el-button>
            <el-button v-if="row.status === 'PENDING'" link type="warning" @click="openReview(row, 'REJECTED')">驳回</el-button>
            <el-button v-if="row.status !== 'MERGED'" link type="primary" @click="openCerts(row)">证照</el-button>
            <el-button link type="primary" @click="openImpact(row)">影响分析</el-button>
            <template v-if="row.status === 'QUALIFIED'">
              <el-button link type="warning" @click="confirmStatus(row, 'FROZEN', '冻结')">冻结</el-button>
              <el-button link type="danger" @click="confirmStatus(row, 'DISABLED', '停用')">停用</el-button>
            </template>
            <el-button v-if="row.status === 'FROZEN'" link type="success" @click="confirmStatus(row, 'QUALIFIED', '解冻')">解冻</el-button>
            <el-button v-if="row.status === 'DISABLED'" link type="success" @click="confirmStatus(row, 'QUALIFIED', '启用')">启用</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        style="margin-top: 14px; justify-content: flex-end;"
        layout="total, prev, pager, next"
        :total="total"
        :page-size="query.size"
        :current-change="loadData"
      />
    </el-card>

    <!-- 建档/变更 -->
    <SupplierFormDialog v-model:visible="formVisible" :row="formRow" :legal-entities="legalEntities"
                        @saved="loadData()" />

    <!-- 证照 -->
    <CertDialog v-model:visible="certVisible" :supplier="certSupplier" @saved="loadData()" />

    <!-- 审核（通过/驳回，原因必填） -->
    <el-dialog v-model="reviewVisible" :title="`${reviewKind === 'APPROVED' ? '审核通过' : '驳回'}：${reviewRow?.supplierName || ''}`" width="520px">
      <el-alert :type="reviewKind === 'APPROVED' ? 'success' : 'warning'" :closable="false" style="margin-bottom: 10px;"
        :title="reviewKind === 'APPROVED' ? '通过后状态进入「合格」，可被下游业务引用（options 可见）。' : '驳回后保持「待审核」，留痕驳回原因，可再次提交审核。'" />
      <el-form label-width="90px">
        <el-form-item label="审核原因" required>
          <el-input v-model="reviewReason" type="textarea" :rows="3" maxlength="200" show-word-limit
            placeholder="必填（≥2 字）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!reviewReason.trim()" :loading="saving"
                   @click="submitReview">确认{{ reviewKind === 'APPROVED' ? '通过' : '驳回' }}</el-button>
      </template>
    </el-dialog>

    <!-- 状态确认（冻结/解冻/停用/启用，原因必填） -->
    <el-dialog v-model="statusVisible" :title="`${statusMeta.title}：${statusRow?.supplierName || ''}`" width="520px">
      <el-alert v-if="statusMeta.to === 'FROZEN'" type="warning" :closable="false" style="margin-bottom: 10px;"
        title="冻结后不可被新业务引用（下游 PO 阻断为桩：采购域未接入）。" />
      <el-form label-width="80px">
        <el-form-item label="原因" required>
          <el-input v-model="statusReason" type="textarea" :rows="3" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="statusVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!statusReason.trim()" :loading="saving" @click="submitStatus">
          确认{{ statusMeta.title }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 影响分析抽屉 -->
    <el-drawer v-model="impactVisible" :title="`影响分析：${impactRow?.supplierName || ''}`" size="460px">
      <template v-if="impactData">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="当前状态">
            <el-tag :type="statusTag(impactData.status)" size="small">{{ statusName(impactData.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="最早到期证照">
            {{ impactData.earliestExpire || '无证照' }}
            <el-tag v-if="impactData.nearExpiry" type="warning" size="small">临期（≤30 天）</el-tag>
          </el-descriptions-item>
        </el-descriptions>
        <h4 style="margin-top: 14px;">已过期证照</h4>
        <el-table v-if="impactData.expiredCerts.length" :data="impactData.expiredCerts" size="small">
          <el-table-column prop="certType" label="类型" width="100" />
          <el-table-column prop="certNo" label="编号" width="130" />
          <el-table-column prop="expireDate" label="到期日" />
        </el-table>
        <el-empty v-else description="无过期证照" :image-size="60" />
        <h4 style="margin-top: 14px;">下游引用（PO / 询价）</h4>
        <el-alert type="info" :closable="false" :title="impactData.downstreamNote"
          description="采购域模块未接入：非合格状态的 PO 创建/过账阻断（BR-4.2-18）与证照交期比对（C-4.1-13）待采购域回补。" />
      </template>
      <el-skeleton v-else :rows="5" animated />
    </el-drawer>

    <!-- 详情抽屉（版本历史+对比） -->
    <el-drawer v-model="detailVisible" :title="`供应商详情：${detail.supplierName || ''}`" size="620px">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="编码">{{ detail.supplierCode }}</el-descriptions-item>
        <el-descriptions-item label="名称">{{ detail.supplierName }}</el-descriptions-item>
        <el-descriptions-item label="地址">{{ detail.address || '-' }}</el-descriptions-item>
        <el-descriptions-item label="联系人">{{ detail.contactName || '-' }} {{ detail.contactPhone || '' }}</el-descriptions-item>
        <el-descriptions-item label="法人主体">{{ leName(detail.legalEntityId) || '集团级' }}</el-descriptions-item>
        <el-descriptions-item label="财务">{{ detail.bankName || '-' }} / {{ detail.taxNo || '-' }} / {{ detail.paymentTerms || '-' }}</el-descriptions-item>
        <el-descriptions-item label="合规">
          {{ detail.blacklistResult === 'HIT' ? '黑名单命中' : '黑名单未命中' }} / ESG {{ detail.esgRating || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="statusTag(detail.status)" size="small">{{ statusName(detail.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="审核原因">{{ detail.reviewReason || '-' }}</el-descriptions-item>
        <el-descriptions-item label="当前版本">V{{ detail.verNo }}</el-descriptions-item>
      </el-descriptions>

      <el-divider content-position="left">版本历史</el-divider>
      <div class="diff-bar">
        <el-select v-model="diffFrom" placeholder="起始版本" style="width: 110px;">
          <el-option v-for="v in versions" :key="v.versionNo" :label="`V${v.versionNo}`" :value="v.versionNo" />
        </el-select>
        <span>→</span>
        <el-select v-model="diffTo" placeholder="目标版本" style="width: 110px;">
          <el-option v-for="v in versions" :key="v.versionNo" :label="`V${v.versionNo}`" :value="v.versionNo" />
        </el-select>
        <el-button type="primary" plain :disabled="!diffFrom || !diffTo || diffFrom === diffTo" @click="loadDiff">对比</el-button>
      </div>
      <el-table :data="versions" size="small" stripe>
        <el-table-column prop="versionNo" label="版本" width="60">
          <template #default="{ row }">V{{ row.versionNo }}</template>
        </el-table-column>
        <el-table-column prop="opType" label="操作" width="110">
          <template #default="{ row }">{{ opMap[row.opType] || row.opType }}</template>
        </el-table-column>
        <el-table-column prop="changeReason" label="原因" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.changeReason || '-' }}</template>
        </el-table-column>
        <el-table-column prop="diffSummary" label="摘要" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.diffSummary || '初始版本' }}</template>
        </el-table-column>
        <el-table-column prop="createDate" label="时间" width="155">
          <template #default="{ row }">{{ (row.createDate || '').replace('T', ' ') }}</template>
        </el-table-column>
      </el-table>

      <template v-if="diffFields.length">
        <el-divider content-position="left">差异对比</el-divider>
        <el-table :data="diffFields" size="small" border>
          <el-table-column prop="field" label="字段" width="140" />
          <el-table-column prop="from" label="起始值" show-overflow-tooltip />
          <el-table-column prop="to" label="目标值" show-overflow-tooltip />
        </el-table>
      </template>
      <el-empty v-else-if="diffRequested" description="两版本无差异" :image-size="60" />
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Plus } from '@element-plus/icons-vue'
import {
  getSupplierPageApi, getSupplierApi, reviewSupplierApi, changeSupplierStatusApi,
  getImpactApi, getSupplierVersionsApi, getSupplierDiffApi
} from '@/api/mdm/supplier-admission'
import { getLegalEntityPageApi } from '@/api/mdm/legal-entity'
import SupplierFormDialog from './SupplierFormDialog.vue'
import CertDialog from './CertDialog.vue'

const opMap = {
  CREATE: '新建', UPDATE: '变更', REVIEW: '审核通过', REJECTED: '驳回',
  FROZEN: '冻结', UNFROZEN: '解冻', DISABLED: '停用', ENABLED: '启用',
  CERT_EXPIRED: '证照过期', CERT_RENEWED: '核验解除'
}

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const total = ref(0)
const query = ref({ keyword: '', status: '', current: 1, size: 10 })
const legalEntities = ref([])
/** 每行的最早到期证照信息（列表临期黄标用） */
const earliestMap = ref({})

async function loadData(page) {
  if (page) query.value.current = page
  loading.value = true
  try {
    const res = await getSupplierPageApi(query.value)
    rows.value = res.data.records
    total.value = res.data.total
    // 批量取影响分析的最早到期（列表列）；量小直接并行
    const pairs = await Promise.all(rows.value.map(async r => {
      try {
        const im = await getImpactApi(r.id)
        return [r.id, im.data]
      } catch (e) {
        return [r.id, null]
      }
    }))
    earliestMap.value = Object.fromEntries(pairs)
  } finally {
    loading.value = false
  }
}

function earliestOf(row) {
  const im = earliestMap.value[row.id]
  if (!im || !im.earliestExpire) return null
  return { expireDate: im.earliestExpire, nearExpiry: im.nearExpiry }
}

function statusName(s) {
  return { PENDING: '待审核', QUALIFIED: '合格', FROZEN: '冻结', DISABLED: '停用',
    CERT_EXPIRED: '证照过期', MERGED: '已合并' }[s] || s
}
function statusTag(s) {
  return { PENDING: 'info', QUALIFIED: 'success', FROZEN: 'warning', DISABLED: 'danger',
    CERT_EXPIRED: 'danger', MERGED: 'info' }[s]
}
function leName(id) {
  if (!id) return ''
  const le = legalEntities.value.find(x => x.id === id)
  return le ? `${le.leCode} ${le.leName}` : id
}

// ---------- 建档/变更 ----------
const formVisible = ref(false)
const formRow = ref(null)
function openCreate() {
  formRow.value = null
  formVisible.value = true
}
function openChange(row) {
  formRow.value = row
  formVisible.value = true
}

// ---------- 审核 ----------
const reviewVisible = ref(false)
const reviewRow = ref(null)
const reviewKind = ref('APPROVED')
const reviewReason = ref('')

function openReview(row, kind) {
  reviewRow.value = row
  reviewKind.value = kind
  reviewReason.value = ''
  reviewVisible.value = true
}
async function submitReview() {
  saving.value = true
  try {
    await reviewSupplierApi(reviewRow.value.id, reviewKind.value, reviewReason.value.trim())
    ElMessage.success(reviewKind.value === 'APPROVED' ? '审核通过，状态进入合格' : '已驳回，保持待审核')
    reviewVisible.value = false
    loadData()
  } catch (e) {
    // 422 已单点提示
  } finally {
    saving.value = false
  }
}

// ---------- 状态操作 ----------
const statusVisible = ref(false)
const statusRow = ref(null)
const statusTo = ref('')
const statusTitle = ref('')
const statusReason = ref('')

function confirmStatus(row, to, title) {
  statusRow.value = row
  statusTo.value = to
  statusTitle.value = title
  statusReason.value = ''
  statusVisible.value = true
}
const statusMeta = computed(() => ({ to: statusTo.value, title: statusTitle.value }))

async function submitStatus() {
  saving.value = true
  try {
    await changeSupplierStatusApi(statusRow.value.id, statusTo.value, statusReason.value.trim())
    ElMessage.success(`${statusMeta.value.title}成功`)
    statusVisible.value = false
    loadData()
  } catch (e) {
    // 矩阵 422 已提示
  } finally {
    saving.value = false
  }
}

// ---------- 证照 ----------
const certVisible = ref(false)
const certSupplier = ref(null)
function openCerts(row) {
  certSupplier.value = row
  certVisible.value = true
}

// ---------- 影响分析 ----------
const impactVisible = ref(false)
const impactRow = ref(null)
const impactData = ref(null)
async function openImpact(row) {
  impactRow.value = row
  impactData.value = null
  impactVisible.value = true
  const res = await getImpactApi(row.id)
  impactData.value = res.data
}

// ---------- 详情与版本 ----------
const detailVisible = ref(false)
const detail = ref({})
const versions = ref([])
const diffFrom = ref(null)
const diffTo = ref(null)
const diffFields = ref([])
const diffRequested = ref(false)

async function openDetail(row) {
  const res = await getSupplierApi(row.id)
  detail.value = res.data
  versions.value = await getSupplierVersionsApi(row.id).then(r => r.data)
  diffFrom.value = null
  diffTo.value = null
  diffFields.value = []
  diffRequested.value = false
  detailVisible.value = true
}
async function loadDiff() {
  const res = await getSupplierDiffApi(detail.value.id, diffFrom.value, diffTo.value)
  diffFields.value = res.data.fields
  diffRequested.value = true
}

onMounted(async () => {
  loadData()
  const le = await getLegalEntityPageApi({ current: 1, size: 100 })
  legalEntities.value = le.data.records
})
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.header-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.diff-bar { margin-bottom: 10px; display: flex; gap: 8px; align-items: center; }
</style>
