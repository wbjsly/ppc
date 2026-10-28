<template>
  <div class="page-wrap">
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span>12.6.1 对账匹配（登记 → 系统匹配 → 差异标红，S-4.9-06）</span>
          <span>
            <el-button size="small" type="warning" plain @click="$router.push('/m/12.6.2')">差异处理 12.6.2</el-button>
            <el-button size="small" @click="load">刷新</el-button>
          </span>
        </div>
      </template>

      <div class="toolbar">
        <el-button v-if="canOperate" type="primary" @click="openRegister">登记对账单</el-button>
        <el-select v-model="filters.status" placeholder="状态" clearable size="small" style="width: 150px;" @change="load">
          <el-option label="容差内(ACCEPTED)" value="ACCEPTED" />
          <el-option label="差异冻结(EXCEPTION)" value="EXCEPTION" />
          <el-option label="双签关闭(CLOSED)" value="CLOSED" />
        </el-select>
        <el-select v-model="filters.supplierId" placeholder="供应商" clearable filterable size="small"
          style="width: 200px;" @change="load">
          <el-option v-for="s in suppliers" :key="s.id" :label="s.supplierName" :value="s.id" />
        </el-select>
        <el-button size="small" type="primary" @click="load">查询</el-button>
        <span class="hint">比对基准 = 该供应商 OPEN 暂估余额合计；差异率 &gt; 0.5%（TOLERANCE_DEFAULT）→ 冻结付款并转 12.6.2 双签</span>
      </div>

      <el-table :data="rows" size="small" border v-loading="loading">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand-box">
              比对基准（OPEN 暂估余额）<b>{{ row.baseAmount }}</b> ·
              对账金额 <b>{{ row.stmtAmount }}</b> ·
              差异 <b :style="{ color: row.status === 'EXCEPTION' ? '#F56C6C' : '#67C23A' }">{{ row.diffAmount }}</b>
              （差异率 {{ pct(row.diffRate) }}，容差 {{ row.tolerance || '0.5%' }}）
              <div v-if="row.status === 'CLOSED'" class="mt8">
                双签记录：采购员 {{ row.pmConfirmBy }}（{{ row.pmOpinion }}）· 财务 {{ row.finConfirmBy }}（{{ row.finOpinion }}）
              </div>
              <div v-else-if="row.remark" class="mt8">备注：{{ row.remark }}</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="stmtNo" label="对账单号" width="150">
          <template #default="{ row }"><b>{{ row.stmtNo }}</b></template>
        </el-table-column>
        <el-table-column prop="supplierName" label="供应商" min-width="140" show-overflow-tooltip />
        <el-table-column prop="stmtDate" label="对账日期" width="110" />
        <el-table-column prop="stmtAmount" label="对账金额" width="100" align="right" />
        <el-table-column prop="baseAmount" label="暂估基准" width="100" align="right" />
        <el-table-column label="差异" width="100" align="right">
          <template #default="{ row }">
            <span :style="{ color: row.status === 'EXCEPTION' ? '#F56C6C' : '#67C23A' }">{{ row.diffAmount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="差异率" width="90" align="right">
          <template #default="{ row }">{{ pct(row.diffRate) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="stTag(row.status)">{{ stText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="匹配留痕" width="170">
          <template #default="{ row }">
            <span v-if="row.matchAt">{{ row.matchBy }} @ {{ String(row.matchAt).replace('T', ' ').slice(0, 16) }}</span>
            <el-tag v-else size="mini" type="info">未匹配</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canOperate" link type="primary" size="small"
              @click="doMatch(row)">系统匹配</el-button>
            <el-button link size="small" @click="openDetail(row)">差异清单</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination style="margin-top: 10px; justify-content: flex-end;"
        layout="total, prev, pager, next" :total="total"
        v-model:current-page="filters.current" :page-size="filters.size" @current-change="load" />
    </el-card>

    <!-- 登记对账单 -->
    <el-dialog v-model="regDlg" title="登记对账单（自动与 OPEN 暂估比对）" width="480px">
      <el-form label-width="100px">
        <el-form-item label="供应商" required>
          <el-select v-model="regForm.supplierId" filterable style="width: 100%;"
            placeholder="选择供应商" @change="previewBase">
            <el-option v-for="s in suppliers" :key="s.id" :label="s.supplierName" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="OPEN 暂估基准">
          <span v-if="basePreview != null"><b>{{ basePreview }}</b>
            <span class="hint">（{{ baseCount }} 笔 OPEN 暂估，登记时服务端复算）</span></span>
          <span v-else class="hint">选择供应商后带出</span>
        </el-form-item>
        <el-form-item label="对账日期" required>
          <el-date-picker v-model="regForm.stmtDate" type="date" value-format="YYYY-MM-DD"
            :clearable="false" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="对账金额" required>
          <el-input-number v-model="regForm.stmtAmount" :min="0" :precision="2" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="预计差异率">
          <span v-if="basePreview != null" :style="{ color: previewRate > 0.005 ? '#F56C6C' : '#67C23A' }">
            {{ pct(previewRate) }}{{ previewRate > 0.005 ? '（超容差，将冻结并转双签）' : '（容差内）' }}
          </span>
          <span v-else class="hint">—</span>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="regForm.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="regDlg = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitRegister">登记</el-button>
      </template>
    </el-dialog>

    <!-- 匹配结果 / 差异清单 -->
    <el-dialog v-model="resDlg" :title="`匹配结果 - ${current.stmtNo || ''}`" width="860px">
      <template v-if="matchView">
        <el-descriptions :column="4" border size="small" style="margin-bottom: 10px;">
          <el-descriptions-item label="ERP 侧合计">{{ matchView.erpTotal }}</el-descriptions-item>
          <el-descriptions-item label="供应商侧合计">{{ matchView.supplierTotal }}</el-descriptions-item>
          <el-descriptions-item label="差异">{{ matchView.diffAmount }}</el-descriptions-item>
          <el-descriptions-item label="差异率">{{ pct(matchView.diffRate) }}</el-descriptions-item>
        </el-descriptions>
        <div class="sub-title">逐笔匹配（{{ (matchView.matched || []).length }} 笔一致）</div>
        <el-table :data="matchView.matched || []" size="small" border max-height="180">
          <el-table-column prop="supplierDesc" label="供应商侧" min-width="140" />
          <el-table-column prop="supplierAmount" label="金额" width="100" />
          <el-table-column prop="accrualNo" label="ERP 暂估单" width="160" />
          <el-table-column prop="postingDocNo" label="凭证号" width="140" />
        </el-table>
        <div class="sub-title danger-title" style="margin-top: 10px;">
          差异清单（标红 {{ matchView.redCount || 0 }} 笔，ERP 侧 / 供应商侧并排）
        </div>
        <el-table :data="redRows" size="small" border max-height="220">
          <el-table-column prop="flag" label="类型" width="140">
            <template #default="{ row }">
              <el-tag type="danger" size="small">{{ row.flag === 'ERP_ONLY' ? '仅 ERP 侧' : '仅供应商侧' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="desc" label="说明" min-width="160">
            <template #default="{ row }">{{ row.desc || row.accrualNo || '—' }}</template>
          </el-table-column>
          <el-table-column label="金额" width="110">
            <template #default="{ row }">{{ row.amount != null ? row.amount : row.openAmount }}</template>
          </el-table-column>
          <el-table-column prop="accrualNo" label="ERP 暂估单" width="150">
            <template #default="{ row }">{{ row.accrualNo || '—' }}</template>
          </el-table-column>
          <el-table-column prop="postingDocNo" label="凭证号" width="140">
            <template #default="{ row }">{{ row.postingDocNo || '—' }}</template>
          </el-table-column>
        </el-table>
        <div class="hint mt8">匹配人 {{ matchView.matchBy }} @ {{ matchView.matchAt }}</div>
      </template>
      <el-empty v-else description="尚未执行系统匹配" />
      <template #footer>
        <el-button v-if="canOperate && current.id" type="primary" :loading="submitting"
          @click="doMatch(current)">执行系统匹配</el-button>
        <el-button @click="resDlg = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'
import {
  getStatementPageApi, getStatementDetailApi, createStatementApi,
  matchStatementApi
} from '@/api/proc/reconciliation-console'
import { getAccrualPageApi } from '@/api/fin/accrual'
import { getSupplierPageApi } from '@/api/mdm/supplier-admission'

const userStore = useUserStore()
const roles = computed(() => (userStore.userInfo && userStore.userInfo.roles) || [])
// 登记与系统匹配 = ADMIN+PM（/api/fin/statements 兜底口径）；FINANCE_MGR 只读
const canOperate = computed(() =>
  roles.value.includes('ROLE_ADMIN') || roles.value.includes('ROLE_PM'))

const loading = ref(false)
const submitting = ref(false)
const rows = ref([])
const total = ref(0)
const suppliers = ref([])
const filters = reactive({ supplierId: '', status: '', current: 1, size: 10 })

const ST_TEXT = { ACCEPTED: '容差内', EXCEPTION: '差异冻结', CLOSED: '双签关闭' }
const ST_TAG = { ACCEPTED: 'success', EXCEPTION: 'danger', CLOSED: 'info' }
const stText = s => ST_TEXT[s] || s
const stTag = s => ST_TAG[s] || 'info'
function pct(v) {
  if (v == null) return '—'
  return (Number(v) * 100).toFixed(2) + '%'
}

async function load() {
  loading.value = true
  try {
    const { data } = await getStatementPageApi({
      current: filters.current, size: filters.size,
      supplierId: filters.supplierId || undefined,
      status: filters.status || undefined
    })
    rows.value = (data && data.records) || []
    total.value = Number((data && data.total) || 0)
  } finally { loading.value = false }
}

async function loadSuppliers() {
  try {
    const { data } = await getSupplierPageApi({ current: 1, size: 500 })
    suppliers.value = (data && data.records) || []
  } catch { suppliers.value = [] }
}

// ---------------- 登记（比对基准预览 = Σ OPEN amount−offsetted，与服务端同口径） ----------------
const regDlg = ref(false)
const regForm = reactive({ supplierId: '', stmtDate: '', stmtAmount: 0, remark: '' })
const basePreview = ref(null)
const baseCount = ref(0)
const previewRate = computed(() => {
  if (basePreview.value == null) return null
  const base = Number(basePreview.value)
  const diff = Number(regForm.stmtAmount || 0) - base
  if (base > 0) return Math.abs(diff) / base
  return Number(regForm.stmtAmount || 0) > 0 ? 1 : 0
})

function todayLocal() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function openRegister() {
  regForm.supplierId = ''
  regForm.stmtDate = todayLocal()
  regForm.stmtAmount = 0
  regForm.remark = ''
  basePreview.value = null
  baseCount.value = 0
  regDlg.value = true
}

async function previewBase() {
  basePreview.value = null
  baseCount.value = 0
  if (!regForm.supplierId) return
  try {
    const { data } = await getAccrualPageApi({
      current: 1, size: 500, supplierId: regForm.supplierId, status: 'OPEN'
    })
    const recs = (data && data.records) || []
    let sum = 0
    for (const r of recs) {
      sum += Number(r.amount || 0) - Number(r.offsettedAmount || 0)
    }
    basePreview.value = sum.toFixed(2)
    baseCount.value = recs.length
  } catch { /* 预览失败不阻塞登记（服务端仍复算） */ }
}

async function submitRegister() {
  if (!regForm.supplierId) { ElMessage.warning('请选择供应商'); return }
  if (!regForm.stmtDate) { ElMessage.warning('请选择对账日期'); return }
  if (!regForm.stmtAmount) { ElMessage.warning('请填写对账金额'); return }
  const sup = suppliers.value.find(s => s.id === regForm.supplierId)
  submitting.value = true
  try {
    const res = await createStatementApi({
      ...regForm, supplierName: sup ? sup.supplierName : ''
    })
    const s = res.data || {}
    ElMessage[s.status === 'EXCEPTION' ? 'error' : 'success'](
      s.status === 'EXCEPTION'
        ? `登记成功：基准 ${s.baseAmount}，差异 ${s.diffAmount}（${pct(s.diffRate)}）已冻结付款，转 12.6.2 双签`
        : `登记成功：基准 ${s.baseAmount}，差异 ${s.diffAmount}（${pct(s.diffRate)}）在容差内`)
    regDlg.value = false
    await load()
  } finally { submitting.value = false }
}

// ---------------- 系统匹配与差异清单 ----------------
const resDlg = ref(false)
const current = ref({})
const matchView = ref(null)
const redRows = computed(() => [
  ...(matchView.value?.supplierOnly || []),
  ...(matchView.value?.erpOnly || [])
])

async function doMatch(row) {
  submitting.value = true
  try {
    const { data } = await matchStatementApi(row.id, {})
    matchView.value = data
    current.value = row
    resDlg.value = true
    ElMessage.success(`匹配完成：差异 ${data.diffAmount}，标红 ${data.redCount} 笔`)
    await load()
  } finally { submitting.value = false }
}

async function openDetail(row) {
  current.value = row
  const { data } = await getStatementDetailApi(row.id)
  matchView.value = null
  if (data && data.matchResult) {
    try { matchView.value = JSON.parse(data.matchResult) } catch { matchView.value = null }
  }
  resDlg.value = true
}

onMounted(() => { load(); loadSuppliers() })
</script>

<style scoped>
.page-wrap { padding: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.toolbar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 10px; }
.hint { color: #909399; font-size: 12px; }
.mt8 { margin-top: 8px; }
.expand-box { padding: 8px 16px; font-size: 12px; color: #606266; }
.sub-title { font-size: 13px; font-weight: 600; color: #303133; }
.danger-title { color: #F56C6C; }
</style>
