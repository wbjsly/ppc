<template>
  <div class="copq-page">
    <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
      title="质量成本 COPQ（6.7）：四类归集（预防/鉴定/内部失败/外部失败）→ 财务确认进正式报表 → 损失分析"
      description="单笔 ≥10× 近 3 月月均标记异常阻断入表（BR-4.12-39）；未确认以草稿单列（BR-4.12-40）；外部失败占比 >30% 红标改善建议（BR-4.12-41）；可追溯至供应商维度（BR-4.12-42）。" />

    <!-- KPI 卡片 -->
    <el-row :gutter="12" style="margin-bottom: 12px;">
      <el-col :span="6">
        <el-card shadow="hover" class="kpi">
          <div class="kpi-label">正式报表合计（已确认）</div>
          <div class="kpi-value">¥ {{ fmt(rep.confirmedTotal) }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="kpi">
          <div class="kpi-label">草稿待确认</div>
          <div class="kpi-value" style="color:#E6A23C;">¥ {{ fmt(rep.draftTotal) }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="kpi">
          <div class="kpi-label">外部失败占比</div>
          <div class="kpi-value" :style="{ color: rep.externalAlert ? '#F56C6C' : '#67C23A' }">
            {{ rep.externalRatio || 0 }}%
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="kpi">
          <div class="kpi-label">外部失败金额</div>
          <div class="kpi-value">¥ {{ fmt(rep.externalFailure) }}</div>
        </el-card>
      </el-col>
    </el-row>
    <el-alert v-if="rep.externalAlert" type="error" :closable="false" style="margin-bottom: 12px;"
      :title="rep.suggestion || '外部失败成本占比超 30%（BR-4.12-41）'" />

    <el-card shadow="never">
      <div class="toolbar">
        <el-select v-model="filters.category" placeholder="成本类别" clearable style="width: 160px;" @change="load">
          <el-option label="预防成本" value="PREVENTION" />
          <el-option label="鉴定成本" value="APPRAISAL" />
          <el-option label="内部失败" value="INTERNAL_FAILURE" />
          <el-option label="外部失败" value="EXTERNAL_FAILURE" />
        </el-select>
        <el-select v-model="filters.status" placeholder="状态" clearable style="width: 150px;" @change="load">
          <el-option label="待财务确认" value="PENDING_FINANCE" />
          <el-option label="已确认" value="CONFIRMED" />
          <el-option label="异常待核" value="ANOMALY_HOLD" />
        </el-select>
        <el-input v-model="filters.keyword" placeholder="单号 / 来源单 / 供应商" clearable
          style="width: 210px;" @keyup.enter="load" @clear="load" />
        <el-button type="primary" @click="load">查询</el-button>
        <span class="spacer" />
        <el-button v-if="canConfirm" type="success" @click="openCreate">手工归集</el-button>
      </div>

      <el-table :data="rows" size="small" border v-loading="loading">
        <el-table-column prop="entryNo" label="归集单号" width="150">
          <template #default="{ row }"><b>{{ row.entryNo }}</b></template>
        </el-table-column>
        <el-table-column label="类别" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="{
              PREVENTION: 'success', APPRAISAL: 'primary',
              INTERNAL_FAILURE: 'warning', EXTERNAL_FAILURE: 'danger'
            }[row.category]">
              {{ CAT_TEXT[row.category] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="amount" label="金额" width="110" align="right">
          <template #default="{ row }">¥ {{ row.amount }}</template>
        </el-table-column>
        <el-table-column label="来源" width="140">
          <template #default="{ row }">
            {{ SRC_TEXT[row.sourceType] }}{{ row.sourceNo ? ' · ' + row.sourceNo : '' }}
          </template>
        </el-table-column>
        <el-table-column prop="supplierName" label="供应商" width="130" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.supplierName">{{ row.supplierName }}</span>
            <el-tag v-else size="small" type="info">未归因</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="occurDate" label="发生日" width="105" />
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="{
              PENDING_FINANCE: 'warning', CONFIRMED: 'success',
              ANOMALY_HOLD: 'danger', CANCELLED: 'info'
            }[row.status]">
              {{ { PENDING_FINANCE: '待确认', CONFIRMED: '已确认',
                   ANOMALY_HOLD: '异常待核', CANCELLED: '已作废' }[row.status] || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING_FINANCE'">
              <el-button v-if="canConfirm" link type="primary" @click="doConfirm(row)">财务确认</el-button>
            </template>
            <template v-else-if="row.status === 'ANOMALY_HOLD'">
              <el-button v-if="canConfirm" link type="warning" @click="doResolve(row)">异常核实</el-button>
            </template>
            <span v-else style="color:#909399;font-size:12px;">—</span>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination style="margin-top: 10px; justify-content: flex-end;"
        layout="total, prev, pager, next" :total="total"
        v-model:current-page="filters.current" :page-size="filters.size" @current-change="load" />
    </el-card>

    <!-- 损失分析（6.7.2） -->
    <el-card shadow="never" style="margin-top:12px;" v-if="tab === 'analysis' || showAnalysis">
      <template #header>
        <div style="display:flex;align-items:center;gap:10px;">
          <b>损失分析（{{ rep.period || '当期' }}）</b>
          <el-date-picker v-model="reportMonth" type="month" value-format="YYYY-MM"
            style="width:130px;" @change="loadReport" />
        </div>
      </template>
      <el-row :gutter="20">
        <el-col :span="12">
          <h4 class="sec">四类成本（正式 vs 草稿）</h4>
          <table class="bar-table">
            <tbody>
              <tr v-for="cat in CATS" :key="cat">
                <td class="cat">{{ CAT_TEXT[cat] }}</td>
                <td class="bar-cell">
                  <div class="bar confirmed" :style="{ width: barWidth(rep.confirmed[cat]) }" />
                  <div class="bar draft" :style="{ width: barWidth(rep.draft[cat]) }" />
                </td>
                <td class="num">¥ {{ fmt(rep.confirmed[cat]) }}</td>
                <td class="num draft-num">（草稿 ¥ {{ fmt(rep.draft[cat]) }}）</td>
              </tr>
            </tbody>
          </table>
        </el-col>
        <el-col :span="12">
          <h4 class="sec">供应商维度（已确认，含未归因）</h4>
          <el-table :data="supplierRows" size="mini" border max-height="240">
            <el-table-column prop="name" label="供应商" min-width="140" />
            <el-table-column prop="amount" label="质量成本" width="120" align="right">
              <template #default="{ row }">¥ {{ row.amount }}</template>
            </el-table-column>
            <el-table-column prop="ratio" label="占比" width="90" align="right" />
          </el-table>
        </el-col>
      </el-row>
    </el-card>

    <!-- 归集对话框 -->
    <el-dialog v-model="createVisible" title="手工归集质量成本" width="500px">
      <el-form label-width="100px" size="small">
        <el-form-item label="成本类别" required>
          <el-select v-model="createForm.category" style="width:100%;">
            <el-option label="预防成本" value="PREVENTION" />
            <el-option label="鉴定成本" value="APPRAISAL" />
            <el-option label="内部失败" value="INTERNAL_FAILURE" />
            <el-option label="外部失败" value="EXTERNAL_FAILURE" />
          </el-select>
        </el-form-item>
        <el-form-item label="金额" required>
          <el-input-number v-model="createForm.amount" :min="0.01" :precision="2" />
        </el-form-item>
        <el-form-item label="供应商">
          <el-input v-model="createForm.supplierName" placeholder="可留空（记未归因）" />
        </el-form-item>
        <el-form-item label="发生日期">
          <el-date-picker v-model="createForm.occurDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="createForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="doCreate">归集</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import {
  getCopqPageApi, createCopqApi, submitFinanceApi, resolveAnomalyApi, getReportApi
} from '@/api/qms/copq'

const route = useRoute()
const userStore = useUserStore()

const CATS = ['PREVENTION', 'APPRAISAL', 'INTERNAL_FAILURE', 'EXTERNAL_FAILURE']
const CAT_TEXT = {
  PREVENTION: '预防成本', APPRAISAL: '鉴定成本',
  INTERNAL_FAILURE: '内部失败', EXTERNAL_FAILURE: '外部失败'
}
const SRC_TEXT = { NCR: 'NCR', RETURN: '退货', SCAR: 'SCAR', CAPA: 'CAPA', MANUAL: '手工' }

const tab = route.meta.tab || 'ledger'
const showAnalysis = ref(tab === 'analysis')
const reportMonth = ref('')

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const filters = reactive({ category: '', status: '', keyword: '', current: 1, size: 10 })
const rep = reactive({
  period: '', confirmed: {}, confirmedTotal: 0, draft: {}, draftTotal: 0,
  externalFailure: 0, externalRatio: 0, externalAlert: false, suggestion: '', bySupplier: {}
})

const myRoles = computed(() => (userStore.userInfo && userStore.userInfo.roles) || [])
const canConfirm = computed(() => myRoles.value.some(r =>
  ['ROLE_ADMIN', 'ROLE_QUALITY_MGR', 'ROLE_QUALITY_ENG'].includes(r)))

function fmt(v) {
  const n = Number(v || 0)
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function barWidth(v) {
  const max = Math.max(
    ...CATS.map(c => Number(rep.confirmed[c] || 0) + Number(rep.draft[c] || 0)), 1)
  return `${Math.round((Number(v || 0) / max) * 100)}%`
}
const supplierRows = computed(() => {
  const map = rep.bySupplier || {}
  const totalV = Object.values(map).reduce((a, b) => a + Number(b || 0), 0) || 1
  return Object.entries(map)
    .map(([name, amount]) => ({
      name,
      amount: Number(amount),
      ratio: `${((Number(amount) / totalV) * 100).toFixed(1)}%`
    }))
    .sort((a, b) => b.amount - a.amount)
})

async function load() {
  loading.value = true
  try {
    const res = await getCopqPageApi({
      current: filters.current, size: filters.size,
      category: filters.category || undefined, status: filters.status || undefined,
      keyword: filters.keyword || undefined
    })
    rows.value = (res.data && res.data.records) || []
    total.value = (res.data && res.data.total) || 0
  } finally {
    loading.value = false
  }
}

async function loadReport() {
  const res = await getReportApi(reportMonth.value || undefined)
  Object.assign(rep, res.data || {})
}

async function doConfirm(row) {
  try {
    await ElMessageBox.confirm(`确认归集单 ${row.entryNo}（¥${row.amount}）计入正式报表？`,
      '财务确认（ADMIN 代）', { type: 'warning' })
  } catch { return }
  try {
    await submitFinanceApi(row.id)
    ElMessage.success('已提交财务确认，请在质量审批待办签署')
    load()
  } catch { /* 拦截器已弹错 */ }
}

async function doResolve(row) {
  try {
    const { value } = await ElMessageBox.prompt(
      '异常核实说明（输入 ok 表示金额属实放行，其他表示不予入账）', '金额异常核实',
      { inputPattern: /\S+/, inputErrorMessage: '请填写说明' })
    const pass = value.trim().toLowerCase() === 'ok'
    await resolveAnomalyApi(row.id, pass, value)
    ElMessage.success(pass ? '核实通过，已回草稿可确认' : '已作废不予入账')
    load()
    loadReport()
  } catch { /* 取消或拦截器已处理 */ }
}

const createVisible = ref(false)
const createForm = reactive({ category: 'INTERNAL_FAILURE', amount: undefined, supplierName: '', occurDate: '', remark: '' })
function openCreate() {
  createForm.category = 'INTERNAL_FAILURE'
  createForm.amount = undefined
  createForm.supplierName = ''
  createForm.occurDate = ''
  createForm.remark = ''
  createVisible.value = true
}
async function doCreate() {
  try {
    await createCopqApi({ ...createForm, sourceType: 'MANUAL' })
    ElMessage.success('已归集（待财务确认）')
    createVisible.value = false
    load()
    loadReport()
  } catch { /* 拦截器已弹错 */ }
}

onMounted(async () => {
  if (!userStore.userInfo) {
    try { await userStore.getUserInfo() } catch { /* ignore */ }
  }
  load()
  loadReport()
})
</script>

<style scoped>
.copq-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.spacer { flex: 1; }
.sec { margin: 4px 0 10px; font-size: 14px; color: #303133; }
.kpi { text-align: center; }
.kpi-label { font-size: 12px; color: #909399; margin-bottom: 6px; }
.kpi-value { font-size: 22px; font-weight: 700; color: #303133; }
.bar-table { width: 100%; border-collapse: collapse; }
.bar-table td { padding: 6px 6px; vertical-align: middle; }
.bar-table .cat { width: 80px; font-size: 13px; color: #606266; }
.bar-table .num { width: 110px; text-align: right; font-size: 13px; font-variant-numeric: tabular-nums; }
.bar-table .draft-num { width: 140px; color: #E6A23C; font-size: 12px; }
.bar-table .bar-cell { width: auto; }
.bar { height: 14px; border-radius: 3px; margin-bottom: 3px; min-width: 2px; }
.bar.confirmed { background: #409EFF; }
.bar.draft { background: #F3D19E; }
</style>
