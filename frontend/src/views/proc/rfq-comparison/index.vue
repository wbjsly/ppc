<template>
  <div class="rfq-comparison">
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">询价比价</span>
          <div class="header-actions">
            <el-select v-model="listQuery.status" placeholder="状态" clearable style="width: 160px;" @change="loadList(1)">
              <el-option v-for="(n, v) in statusNames" :key="v" :label="n" :value="v" />
            </el-select>
            <el-input v-model="listQuery.keyword" placeholder="RFQ 单号" clearable style="width: 170px;" @keyup.enter="loadList(1)" />
            <el-button type="primary" @click="loadList(1)">查询</el-button>
            <el-button v-if="isAdmin" type="primary" :icon="Plus" @click="openCreate">创建询价单</el-button>
          </div>
        </div>
      </template>

      <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
        title="询价比价（FR-4.2-2-1/2）：对「待询价」PR 创建 RFQ，合格供应商 ≥ MIN_QUOTE_COUNT（默认 3）；紧急放行可放宽至 1 家（C-4.2-01）。"
        description="截止日到自动锁价；±20% 异常须确认/剔除（BR-4.2-12）；谈判双轨留存（BR-4.2-14）；矩阵展示、权重调整与定标归档已迁移至 2.2.4 比价矩阵；PO 生成属 2.3。" />

      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="rfqNo" label="RFQ 单号" width="170">
          <template #default="{ row }"><b>{{ row.rfqNo }}</b></template>
        </el-table-column>
        <el-table-column prop="prNo" label="关联 PR" width="150" />
        <el-table-column label="状态" width="140">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="标识" width="130">
          <template #default="{ row }">
            <el-tag v-if="row.emergencyFlag === '1' || row.emergencyFlag === 1" type="warning" size="small">紧急</el-tag>
            <el-tag v-if="row.insufficientFlag === '1'" type="danger" size="small" style="margin-left:2px;">不足</el-tag>
            <span v-if="row.emergencyFlag !== '1' && row.insufficientFlag !== '1'" style="color:#c0c4cc;">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="quoteDeadline" label="报价截止" width="110" />
        <el-table-column label="报价进度" width="100" align="center">
          <template #default="{ row }">{{ row.quotedCount }} / {{ row.supplierCount }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination layout="total, prev, pager, next" style="margin-top: 12px; justify-content: flex-end;"
        :total="total" :page-size="listQuery.size" :current-change="loadList" />
    </el-card>

    <!-- 创建对话框 -->
    <el-dialog v-model="createVisible" title="创建询价单（RFQ）" width="640px">
      <el-alert v-if="createMsg" type="error" show-icon :closable="false" :title="createMsg" style="margin-bottom: 10px;" />
      <el-form label-width="120px">
        <el-form-item label="关联 PR" required>
          <el-select v-model="form.prId" filterable placeholder="选择待询价 PR" style="width: 100%;">
            <el-option v-for="c in prCandidates" :key="c.prId"
                       :label="`${c.prNo}${c.hasOpenRfq ? '（已有进行中RFQ）' : ''}${c.emergencyClear ? '【紧急放行】' : ''}`"
                       :value="c.prId" :disabled="c.hasOpenRfq" />
          </el-select>
          <div class="tip">仅「待询价」状态 PR；【紧急放行】= 关联 PR 有有效特批，最低 1 家</div>
        </el-form-item>
        <el-form-item label="邀请供应商" required>
          <el-select v-model="form.supplierIds" multiple filterable placeholder="选择合格（QUALIFIED）供应商"
                     style="width: 100%;">
            <el-option v-for="s in qualifiedSuppliers" :key="s.id" :label="s.supplierName" :value="s.id" />
          </el-select>
          <div class="tip">当前最少 {{ minQuoteCount }} 家（MIN_QUOTE_COUNT，C-4.2-01）；紧急放行最低 1 家</div>
        </el-form-item>
        <el-form-item label="报价截止日">
          <el-date-picker v-model="form.quoteDeadline" type="date" value-format="YYYY-MM-DD" style="width: 100%;"
                          placeholder="默认 = 最早需求日 − 5 个工作日" />
        </el-form-item>
        <el-form-item label="含税税码编号">
          <el-input v-model="form.taxCodeNo" maxlength="32" placeholder="可选（如 VAT-13），矩阵含税单价按此试算" style="text-transform: uppercase;" />
        </el-form-item>
        <el-form-item label="发送模式">
          <el-radio-group v-model="form.sendMode">
            <el-radio value="OFFLINE">线下发送</el-radio>
            <el-radio value="ONLINE">在线发送（桩）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="技术标准/图纸">
          <el-input v-model="form.techNote" type="textarea" :rows="2" maxlength="255" placeholder="附件上传为桩，文本说明替代" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="dtVisible" :title="`询价单：${dt.rfq.rfqNo || ''}`" size="86%">
      <el-descriptions :column="4" border size="small" style="margin-bottom: 8px;">
        <el-descriptions-item label="状态">
          <el-tag :type="statusTag(dt.rfq.status)" size="small">{{ statusName(dt.rfq.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="关联 PR">{{ dt.prNo || '—' }}</el-descriptions-item>
        <el-descriptions-item label="报价截止">{{ dt.rfq.quoteDeadline || '—' }}</el-descriptions-item>
        <el-descriptions-item label="标识">
          <el-tag v-if="dt.rfq.emergencyFlag === '1'" type="warning" size="small">紧急放行</el-tag>
          <el-tag v-if="dt.rfq.insufficientFlag === '1'" type="danger" size="small">报价不足</el-tag>
          <span v-if="dt.rfq.emergencyFlag !== '1' && dt.rfq.insufficientFlag !== '1'">—</span>
        </el-descriptions-item>
        <el-descriptions-item label="发送">{{ dt.rfq.sendMode }} / {{ dt.rfq.sendStatus || '未发出' }}</el-descriptions-item>
        <el-descriptions-item label="含税税码">{{ dt.rfq.taxCodeNo || '未指定（待税率）' }}</el-descriptions-item>
        <el-descriptions-item label="中选" :span="2">
          {{ dt.rfq.awardSupplierId ? `${supplierName(dt.rfq.awardSupplierId)} @ ${dt.rfq.awardPrice}` : '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="行快照" :span="4">
          <span v-for="l in dt.lines" :key="l.lineNo" style="margin-right: 12px;">
            {{ l.itemCode }} × {{ l.qty }}（{{ l.reqDate }}）
          </span>
        </el-descriptions-item>
      </el-descriptions>

      <!-- 操作栏 -->
      <div class="toolbar" v-if="isAdmin">
        <el-button v-if="dt.rfq.status === 'DRAFT'" type="primary" @click="doSend">发出询价</el-button>
        <template v-if="['DRAFT','SENT','QUOTING'].includes(dt.rfq.status)">
          <el-button type="warning" plain @click="doPostpone">延期截止</el-button>
          <el-button type="primary" plain @click="openAddSuppliers">追加供应商</el-button>
        </template>
        <el-button type="primary" @click="goMatrix">前往比价矩阵</el-button>
        <el-button v-if="!['AWARDED','CLOSED'].includes(dt.rfq.status)" type="danger" plain @click="doClose">
          {{ dt.rfq.insufficientFlag === '1' ? '作废重询' : '关闭' }}
        </el-button>
      </div>
      <el-alert v-if="dt.rfq.insufficientFlag === '1'" type="error" :closable="false" style="margin: 8px 0;"
        title="报价不足（BR-4.2-13）：延期/追加已禁用，处置 = 作废重询（关闭后可从 PR 重建）" />

      <!-- 供应商清单 -->
      <el-divider content-position="left">邀请供应商（{{ dt.suppliers.length }} 家）</el-divider>
      <el-table :data="dt.suppliers" size="small">
        <el-table-column prop="supplierName" label="供应商" min-width="180" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.supplierStatus === 'QUALIFIED' ? 'success' : 'warning'">
              {{ row.supplierStatus }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="已报价" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.hasQuote" type="success" size="small">已报价</el-tag>
            <span v-else style="color:#c0c4cc;">—</span>
          </template>
        </el-table-column>
      </el-table>

      <!-- 报价录入（锁价前） -->
      <template v-if="isAdmin && ['SENT','QUOTING'].includes(dt.rfq.status)">
        <el-divider content-position="left">报价录入（线下）</el-divider>
        <el-table :data="dt.suppliers" size="small" border>
          <el-table-column prop="supplierName" label="供应商" min-width="150" />
          <el-table-column label="单价" width="140">
            <template #default="{ row }">
              <el-input-number v-model="quoteForm[row.supplierId].unitPrice" :min="0.0001" :precision="4"
                               size="small" style="width: 125px;" />
            </template>
          </el-table-column>
          <el-table-column label="交期(天)" width="110">
            <template #default="{ row }">
              <el-input-number v-model="quoteForm[row.supplierId].leadTimeDays" :min="1" :precision="0"
                               size="small" style="width: 95px;" />
            </template>
          </el-table-column>
          <el-table-column label="MOQ" width="120">
            <template #default="{ row }">
              <el-input-number v-model="quoteForm[row.supplierId].moq" :min="0.0001" :precision="2"
                               size="small" style="width: 105px;" />
            </template>
          </el-table-column>
          <el-table-column label="付款条件" width="130">
            <template #default="{ row }">
              <el-select v-model="quoteForm[row.supplierId].paymentTerms" size="small" style="width: 115px;">
                <el-option label="NET30" value="NET30" />
                <el-option label="NET60" value="NET60" />
                <el-option label="NET90" value="NET90" />
                <el-option label="款到发货" value="PREPAY" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="报价有效期" width="160">
            <template #default="{ row }">
              <el-date-picker v-model="quoteForm[row.supplierId].quoteValidDate" type="date"
                              value-format="YYYY-MM-DD" size="small" style="width: 145px;" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="230">
            <template #default="{ row }">
              <el-button link type="primary" @click="submitQuote(row.supplierId)">提交</el-button>
              <template v-if="isAdmin && quoteOf(row.supplierId)">
                <el-button v-if="quoteOf(row.supplierId).anomalyFlag === '1' && quoteOf(row.supplierId).anomalyConfirmed !== '1'"
                           link type="warning" @click="doConfirmAnomaly(quoteOf(row.supplierId))">确认异常</el-button>
                <el-button link type="danger" @click="doExclude(quoteOf(row.supplierId))">剔除</el-button>
                <el-button link type="primary" @click="doNegotiate(quoteOf(row.supplierId))">谈判</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <!-- 矩阵已迁移至 2.2.4 工作台（change add-price-comparison-matrix，design D1/D5） -->
      <template v-if="dt.quotes && dt.quotes.length">
        <el-divider content-position="left">比价矩阵</el-divider>
        <el-alert type="info" :closable="false" style="margin-bottom: 8px;"
          title="矩阵展示、四维权重、异常处置与定标归档已集中至「比价矩阵（2.2.4）」"
          description="本页保留询价流程动作：报价录入、异常确认/剔除、谈判双轨。定标请前往 2.2.4（成功后自动生成比价结果快照）。" />
        <el-button type="primary" size="small" @click="goMatrix">前往比价矩阵 →</el-button>
      </template>
    </el-drawer>

    <!-- 简单原因弹窗（延期/关闭/剔除/确认共用） -->
    <el-dialog v-model="reasonVisible" :title="reasonTitle" width="480px">
      <el-input v-model="reasonText" type="textarea" :rows="3" maxlength="255" :placeholder="reasonPlaceholder" />
      <template #footer>
        <el-button @click="reasonVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitReason">确定</el-button>
      </template>
    </el-dialog>

    <!-- 追加供应商 -->
    <el-dialog v-model="addVisible" title="追加供应商（未锁价；追加后须重新发出）" width="520px">
      <el-select v-model="addIds" multiple filterable placeholder="选择合格供应商" style="width: 100%;">
        <el-option v-for="s in qualifiedSuppliers" :key="s.id" :label="s.supplierName" :value="s.id" />
      </el-select>
      <template #footer>
        <el-button @click="addVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitAdd">追加</el-button>
      </template>
    </el-dialog>

  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { useUserStore } from '@/store/user'
import {
  getRfqPageApi, getRfqDetailApi, getRfqPrCandidatesApi, createRfqApi, sendRfqApi,
  saveQuoteApi, postponeRfqApi, addRfqSuppliersApi, closeRfqApi,
  confirmAnomalyApi, excludeQuoteApi, negotiateQuoteApi
} from '@/api/proc/rfq'
import { getSupplierPageApi } from '@/api/mdm/supplier-admission'
import { useRouter } from 'vue-router'

const router = useRouter()
const userStore = useUserStore()
const isAdmin = computed(() => (userStore.userInfo?.roles || []).includes('ROLE_ADMIN'))
const loading = ref(false)
const saving = ref(false)

const statusNames = {
  DRAFT: '草稿', SENT: '已发出', QUOTING: '报价中', QUOTED_CLOSED: '已锁价',
  AWARDED: '已定标', CLOSED: '已关闭'
}
function statusName(s) { return statusNames[s] || s }
function statusTag(s) {
  return { DRAFT: 'info', SENT: 'primary', QUOTING: 'warning',
    QUOTED_CLOSED: 'danger', AWARDED: 'success', CLOSED: 'info' }[s]
}

// ---------- 列表 ----------
const listQuery = ref({ status: '', keyword: '', current: 1, size: 10 })
const rows = ref([])
const total = ref(0)

async function loadList(page) {
  if (page) listQuery.value.current = page
  loading.value = true
  try {
    const res = await getRfqPageApi(listQuery.value)
    rows.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

// ---------- 供应商 ----------
const qualifiedSuppliers = ref([])
async function loadSuppliers() {
  const res = await getSupplierPageApi({ current: 1, size: 200, status: 'QUALIFIED' })
  qualifiedSuppliers.value = res.data.records
}
function supplierName(id) {
  const s = qualifiedSuppliers.value.find(x => x.id === id)
  return s ? s.supplierName : id
}

// ---------- 创建 ----------
const createVisible = ref(false)
const createMsg = ref('')
const prCandidates = ref([])
const minQuoteCount = ref(3)
const form = ref({})

async function openCreate() {
  form.value = { prId: '', supplierIds: [], quoteDeadline: '', taxCodeNo: '', sendMode: 'OFFLINE', techNote: '' }
  createMsg.value = ''
  createVisible.value = true
  const res = await getRfqPrCandidatesApi()
  prCandidates.value = res.data.candidates
  minQuoteCount.value = res.data.minQuoteCount
}

async function submitCreate() {
  createMsg.value = ''
  saving.value = true
  try {
    const res = await createRfqApi(form.value)
    ElMessage.success(`已创建 ${res.data.rfq.rfqNo}${res.data.emergencyClear ? '（紧急放行 1 家起）' : ''}`)
    createVisible.value = false
    loadList(1)
  } catch (e) {
    if (e?.message) createMsg.value = e.message
  } finally {
    saving.value = false
  }
}

// ---------- 详情 ----------
const dtVisible = ref(false)
const dt = ref({ rfq: {}, lines: [], suppliers: [], quotes: [] })
const quoteForm = reactive({})

async function openDetail(row) {
  const res = await getRfqDetailApi(row.id)
  dt.value = res.data
  // 报价表单初始化
  for (const s of res.data.suppliers) {
    quoteForm[s.supplierId] = quoteForm[s.supplierId] || {
      unitPrice: null, leadTimeDays: 7, moq: 1, paymentTerms: 'NET30', quoteValidDate: ''
    }
  }
  dtVisible.value = true
}

async function doSend() {
  try {
    await sendRfqApi(dt.value.rfq.id, dt.value.rfq.sendMode || 'OFFLINE')
    ElMessage.success('已发出（在线模式为状态桩）')
    refresh()
  } catch (e) {
    ElMessage.error(e?.message || '发出失败')
  }
}

async function submitQuote(supplierId) {
  saving.value = true
  try {
    await saveQuoteApi(dt.value.rfq.id, { supplierId, ...quoteForm[supplierId] })
    ElMessage.success('报价已提交')
    refresh()
  } catch (e) {
    ElMessage.error(e?.message || '提交失败')
  } finally {
    saving.value = false
  }
}

async function refresh() {
  const res = await getRfqDetailApi(dt.value.rfq.id)
  dt.value = res.data
  for (const s of res.data.suppliers) {
    quoteForm[s.supplierId] = quoteForm[s.supplierId] || {
      unitPrice: null, leadTimeDays: 7, moq: 1, paymentTerms: 'NET30', quoteValidDate: ''
    }
  }
  loadList()
}

// ---------- 共用原因弹窗 ----------
const reasonVisible = ref(false)
const reasonTitle = ref('')
const reasonText = ref('')
const reasonPlaceholder = ref('')
const reasonAction = ref(null)

function askReason(title, placeholder, action) {
  reasonTitle.value = title
  reasonPlaceholder.value = placeholder
  reasonText.value = ''
  reasonAction.value = action
  reasonVisible.value = true
}
async function submitReason() {
  if (!reasonText.value || reasonText.value.trim().length < 2) {
    ElMessage.warning('原因必填（≥2 字）')
    return
  }
  saving.value = true
  try {
    await reasonAction.value(reasonText.value.trim())
    reasonVisible.value = false
  } finally {
    saving.value = false
  }
}

function doPostpone() {
  askReason('延期报价截止日（未锁价；新截止日晚于当前值）', '新截止日 yyyy-MM-dd，逗号后接原因，如 2026-12-31,客户等待窗口', async (text) => {
    const [dl, ...rest] = text.split(',')
    try {
      await postponeRfqApi(dt.value.rfq.id, (dl || '').trim(), rest.join(',').trim())
      ElMessage.success('已延期')
      refresh()
    } catch (e) {
      ElMessage.error(e?.message || '延期失败')
    }
  })
}

function doClose() {
  const insufficient = dt.value.rfq.insufficientFlag === '1'
  askReason(insufficient ? '作废重询（关闭后可从 PR 重建）' : '关闭询价单', '原因（≥2 字）', async (reason) => {
    try {
      await closeRfqApi(dt.value.rfq.id, reason)
      ElMessage.success(insufficient ? '已作废，可重建' : '已关闭')
      refresh()
    } catch (e) {
      ElMessage.error(e?.message || '关闭失败')
    }
  })
}

function doExclude(row) {
  askReason('剔除报价（不参与均值/加权/定标）', '剔除原因（≥2 字）', async (reason) => {
    try {
      await excludeQuoteApi(row.quoteId, reason)
      ElMessage.success('已剔除（不参与均值/加权/定标）')
      refresh()
    } catch (e) {
      ElMessage.error(e?.message || '剔除失败')
    }
  })
}

async function doConfirmAnomaly(row) {
  try {
    await confirmAnomalyApi(row.quoteId)
    ElMessage.success('已确认保留该异常报价')
    refresh()
  } catch (e) {
    ElMessage.error(e?.message || '确认失败')
  }
}

function doNegotiate(row) {
  askReason('谈判改价（原始报价保留，双轨留存）', '格式：新单价,谈判说明（如 95.5,年度量换价）', async (text) => {
    const [price, ...rest] = text.split(',')
    try {
      await negotiateQuoteApi(row.quoteId, Number(price), rest.join(',').trim())
      ElMessage.success('已记录谈判后报价')
      refresh()
    } catch (e) {
      ElMessage.error(e?.message || '谈判记录失败')
    }
  })
}

// ---------- 追加供应商 ----------
const addVisible = ref(false)
const addIds = ref([])
function openAddSuppliers() {
  addIds.value = []
  addVisible.value = true
}
async function submitAdd() {
  saving.value = true
  try {
    const res = await addRfqSuppliersApi(dt.value.rfq.id, addIds.value)
    ElMessage.success(`追加 ${res.data.added} 家，共 ${res.data.totalSuppliers} 家（须重新发出）`)
    addVisible.value = false
    refresh()
  } catch (e) {
    ElMessage.error(e?.message || '追加失败')
  } finally {
    saving.value = false
  }
}

// ---------- 比价矩阵跳转（矩阵展示/权重/定标/快照归档已迁移至 2.2.4，design D1/D5） ----------
function goMatrix() {
  const id = dt.value.rfq && dt.value.rfq.id
  router.push({ path: '/m/2.2.4', query: id ? { rfqId: id } : {} })
}

/** 该供应商已录入的报价（异常确认/剔除/谈判动作取 quoteId） */
function quoteOf(supplierId) {
  const q = (dt.value.quotes || []).find(x => x.supplierId === supplierId)
  return q ? { ...q, quoteId: q.id } : null
}

onMounted(() => { loadList(1); loadSuppliers() })
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
.header-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 10px; flex-wrap: wrap; }
.tip { font-size: 12px; color: #909399; }
</style>
