<template>
  <div class="qm-page">
    <el-alert
      type="info" :closable="false" show-icon style="margin-bottom: 12px;"
      title="毛利测算（3.2.2）"
      description="毛利率 =（报价金额 − 标准成本 × 数量）/ 报价金额；0 ≤ 毛利率 < MIN_MARGIN_RATE 须二次确认（留痕确认人与时间，不构成价格授权）；负毛利锁定待审批，销售主管与财务会签，释放前禁止转化 SO。"
    />

    <!-- 阈值卡 -->
    <el-row :gutter="12" style="margin-bottom: 12px;">
      <el-col :span="6">
        <el-card shadow="never">
          <div class="kpi-name">最低毛利率阈值</div>
          <div class="kpi-val">{{ pct(minMargin) }}</div>
          <div class="kpi-sub">MIN_MARGIN_RATE（参数可调）</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never">
          <div class="kpi-name">低毛利待确认</div>
          <div class="kpi-val" style="color: #e6a23c;">{{ lowCount }}</div>
          <div class="kpi-sub">0 ≤ 毛利率 &lt; 阈值</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never">
          <div class="kpi-name">负毛利锁定</div>
          <div class="kpi-val" style="color: #f56c6c;">{{ negCount }}</div>
          <div class="kpi-sub">待双签会签</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never">
          <div class="kpi-name">审批中</div>
          <div class="kpi-val" style="color: #409eff;">{{ pendingCount }}</div>
          <div class="kpi-sub">含会签与分档审批</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">报价毛利明细</span>
          <div class="header-actions">
            <el-select v-model="filter" placeholder="筛选" clearable style="width: 160px;" @change="loadData(1)">
              <el-option label="低毛利待确认" value="LOW" />
              <el-option label="负毛利锁定" value="NEGATIVE" />
              <el-option label="审批中" value="PENDING" />
              <el-option label="草稿" value="DRAFT" />
              <el-option label="已发布" value="PUBLISHED" />
            </el-select>
            <el-input v-model="query.keyword" placeholder="编号/客户" clearable
                      :prefix-icon="Search" style="width: 180px;" @keyup.enter="loadData(1)" />
            <el-button type="primary" @click="loadData(1)">查询</el-button>
            <el-button :icon="Refresh" @click="loadData()">刷新</el-button>
          </div>
        </div>
      </template>

      <el-table :data="displayRows" v-loading="loading" stripe>
        <el-table-column label="报价单" width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.quoteNo || row.draftNo }}</template>
        </el-table-column>
        <el-table-column prop="customerName" label="客户" min-width="130" show-overflow-tooltip />
        <el-table-column label="报价金额" width="120" align="right">
          <template #default="{ row }">¥ {{ fmt(row.totalAmount) }}</template>
        </el-table-column>
        <el-table-column label="标准成本" width="120" align="right">
          <template #default="{ row }">¥ {{ fmt(row.totalCost) }}</template>
        </el-table-column>
        <el-table-column label="毛利额" width="110" align="right">
          <template #default="{ row }">
            <span :style="{ color: profitOf(row) < 0 ? '#f56c6c' : '#303133' }">
              ¥ {{ fmt(profitOf(row)) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="毛利率" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="mType(row)" size="small">{{ pct(row.marginRate) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="94">
          <template #default="{ row }">
            <el-tag size="small" :type="stType(row.status)">{{ stName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="确认留痕" width="160">
          <template #default="{ row }">
            <span v-if="row.marginConfirmBy">{{ row.marginConfirmBy }} @ {{ fmtTime(row.marginConfirmAt) }}</span>
            <span v-else style="color: #909399;">{{ needConfirm(row) ? '待确认' : '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">毛利明细</el-button>
            <el-button v-if="needConfirm(row) && !row.marginConfirmBy" link type="warning"
                       @click="doConfirm(row)">二次确认</el-button>
            <el-button v-if="row.status === 'DRAFT' || row.status === 'REJECTED'" link type="primary"
                       @click="doSubmit(row)">提交审批</el-button>
            <el-button v-if="row.status === 'PENDING'" link type="info" @click="openApproval(row)">审批进度</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination style="margin-top: 12px; justify-content: flex-end;"
        v-model:current-page="query.current" :page-size="10" :total="total"
        layout="total, prev, pager, next" @current-change="loadData" />
    </el-card>

    <!-- 毛利明细对话框 -->
    <el-dialog v-model="detailVisible" :title="`毛利明细 — ${detail.quoteNo || detail.draftNo || ''}`" width="760px">
      <el-descriptions :column="3" size="small" border style="margin-bottom: 10px;">
        <el-descriptions-item label="报价金额">¥ {{ fmt(detail.totalAmount) }}</el-descriptions-item>
        <el-descriptions-item label="标准成本">¥ {{ fmt(detail.totalCost) }}</el-descriptions-item>
        <el-descriptions-item label="毛利率">
          <b :style="{ color: mColor(detail) }">{{ pct(detail.marginRate) }}</b>
        </el-descriptions-item>
        <el-descriptions-item label="毛利额">¥ {{ fmt(profitOf(detail)) }}</el-descriptions-item>
        <el-descriptions-item label="阈值">{{ pct(minMargin) }}</el-descriptions-item>
        <el-descriptions-item label="判定">{{ verdict(detail) }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="detailLines" size="small" border v-loading="linesLoading">
        <el-table-column prop="lineNo" label="#" width="46" />
        <el-table-column prop="itemCode" label="SKU" width="150" />
        <el-table-column prop="itemName" label="名称" min-width="140" show-overflow-tooltip />
        <el-table-column prop="qty" label="数量" width="80" align="right" />
        <el-table-column label="单价" width="90" align="right">
          <template #default="{ row }">{{ row.unitPrice }}</template>
        </el-table-column>
        <el-table-column label="金额" width="100" align="right">
          <template #default="{ row }">{{ fmt(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="单位成本" width="90" align="right">
          <template #default="{ row }">{{ row.standardCost }}</template>
        </el-table-column>
        <el-table-column label="行成本" width="100" align="right">
          <template #default="{ row }">{{ fmt(row.lineCost) }}</template>
        </el-table-column>
        <el-table-column label="价源" width="86">
          <template #default="{ row }">{{ srcName(row.priceSource) }}</template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button v-if="needConfirm(detail) && !detail.marginConfirmBy" type="warning"
                   @click="doConfirm(detail)">低毛利二次确认</el-button>
      </template>
    </el-dialog>

    <!-- 审批进度 -->
    <el-dialog v-model="approvalVisible" title="审批进度" width="560px">
      <el-timeline>
        <el-timeline-item v-for="t in approvalLogs" :key="t.seq + '-' + t.nodeName"
          :type="logType(t)" :timestamp="fmtTime(t.opTime || t.applyDate)" placement="top">
          <div>
            <b>{{ t.nodeName }}</b>
            <el-tag size="small" style="margin-left: 6px;" :type="logType(t)">{{ logStatus(t) }}</el-tag>
            <div class="tip">{{ t.roleRequired }}{{ t.opinion ? ' · ' + t.opinion : '' }}</div>
          </div>
        </el-timeline-item>
      </el-timeline>
      <div class="tip" style="margin-top: 8px;">签署请到「审批中心」以对应角色处理。</div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import {
  getQuotesApi, getQuoteLinesApi, marginConfirmApi, submitQuoteApi
} from '@/api/sd/quote'
import request from '@/utils/request'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = reactive({ current: 1, keyword: '' })
const filter = ref('')
const minMargin = ref(0.05)

const detailVisible = ref(false)
const detail = reactive({ id: '', quoteNo: '', draftNo: '', totalAmount: null, totalCost: null, marginRate: null, marginConfirmBy: '', status: '' })
const detailLines = ref([])
const linesLoading = ref(false)

const approvalVisible = ref(false)
const approvalLogs = ref([])

onMounted(async () => {
  loadData(1)
  try {
    const res = await request.get('/system/params')
    const all = res.data || []
    const m = all.find(p => p.paramKey === 'MIN_MARGIN_RATE')
    if (m) minMargin.value = Number(m.paramValue)
  } catch (e) { /* 参数接口不可用时用默认 5% */ }
})

async function loadData(page) {
  if (page) query.current = page
  loading.value = true
  try {
    const res = await getQuotesApi({ current: query.current, size: 10, keyword: query.keyword })
    rows.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

const displayRows = computed(() => {
  const all = rows.value
  if (filter.value === 'LOW') return all.filter(r => notPublished(r) && isLow(r))
  if (filter.value === 'NEGATIVE') return all.filter(r => notPublished(r) && isNeg(r))
  if (filter.value) return all.filter(r => r.status === filter.value)
  return all
})

function notPublished(r) {
  return ['DRAFT', 'PENDING', 'REJECTED'].includes(r.status)
}
function isLow(r) {
  return r.marginRate != null && r.marginRate >= 0 && Number(r.marginRate) < minMargin.value
}
function isNeg(r) {
  return r.marginRate != null && Number(r.marginRate) < 0
}
function needConfirm(r) {
  return notPublished(r) && isLow(r)
}

const lowCount = computed(() => rows.value.filter(r => needConfirm(r) && !r.marginConfirmBy).length)
const negCount = computed(() => rows.value.filter(r => r.status !== 'PUBLISHED' && r.status !== 'CONVERTED' && isNeg(r)).length)
const pendingCount = computed(() => rows.value.filter(r => r.status === 'PENDING').length)

async function openDetail(row) {
  Object.assign(detail, row)
  detailVisible.value = true
  linesLoading.value = true
  try {
    const res = await getQuoteLinesApi(row.id)
    detailLines.value = res.data || []
  } finally {
    linesLoading.value = false
  }
}

async function doConfirm(row) {
  await ElMessageBox.confirm(
    `确认「${row.quoteNo || row.draftNo}」毛利率 ${pct(row.marginRate)} 低于阈值 ${pct(minMargin.value)}？` +
    '确认将记录确认人与时间并附在审批单上（该确认不构成价格授权）。',
    '低毛利二次确认', { type: 'warning' })
  await marginConfirmApi(row.id)
  ElMessage.success('已确认，可提交审批')
  loadData()
}

async function doSubmit(row) {
  if (needConfirm(row) && !row.marginConfirmBy) {
    ElMessage.warning('请先完成低毛利二次确认')
    return
  }
  const tip = isNeg(row)
    ? '负毛利报价将锁定为「待审批」，派发销售主管与财务会签双节点，未释放前不可转化 SO。确认提交？'
    : '按金额分档提交审批（小额高毛利将自动发布）。确认提交？'
  await ElMessageBox.confirm(tip, '提交审批', { type: 'warning' })
  const res = await submitQuoteApi(row.id)
  if (res.data.status === 'PUBLISHED') {
    ElMessage.success(`自动审批通过并发布：${res.data.quoteNo}（有效期至 ${res.data.validTo}）`)
  } else {
    ElMessage.success('已提交审批（负毛利会签/分档审批）')
  }
  loadData()
}

async function openApproval(row) {
  const res = await request.get('/qms/approvals/logs', { params: { bizType: 'Quote', bizId: row.id } })
  const d = res.data || {}
  approvalLogs.value = d.tasks || d.nodes || (Array.isArray(d) ? d : [])
  approvalVisible.value = true
}

// ---------- 辅助 ----------
function profitOf(r) {
  return Number(r.totalAmount || 0) - Number(r.totalCost || 0)
}
function verdict(r) {
  if (isNeg(r)) return '负毛利：锁定待审批 + 双签会签'
  if (isLow(r)) return r.marginConfirmBy ? '低毛利已确认，附审批单' : '低毛利：待二次确认'
  return '达标'
}
function mType(r) {
  if (isNeg(r)) return 'danger'
  if (isLow(r)) return 'warning'
  return 'success'
}
function mColor(r) {
  if (isNeg(r)) return '#f56c6c'
  if (isLow(r)) return '#e6a23c'
  return '#67c23a'
}
function stName(s) {
  return { DRAFT: '草稿', PENDING: '待审批', REJECTED: '已驳回', PUBLISHED: '已发布',
    SUPERSEDED: '旧版本', CONVERTED: '已转化' }[s] || s
}
function stType(s) {
  return { DRAFT: 'info', PENDING: 'warning', REJECTED: 'danger', PUBLISHED: 'success',
    SUPERSEDED: 'info', CONVERTED: 'primary' }[s] || 'info'
}
function srcName(s) {
  return { EXCLUSIVE: '客户专属', LADDER: '量价阶梯', TIME: '时间促销' }[s] || s || '-'
}
function logStatus(t) {
  return { PASSED: '已通过', REJECTED: '已驳回', ACTIVE: '待签', PENDING: '未激活', SKIPPED: '跳过' }[t.status] || t.status
}
function logType(t) {
  return { PASSED: 'success', REJECTED: 'danger', ACTIVE: 'warning', PENDING: 'info', SKIPPED: 'info' }[t.status] || 'info'
}
function pct(v) { return v == null ? '-' : (Number(v) * 100).toFixed(1) + '%' }
function fmt(v) {
  return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function fmtTime(t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '-' }
</script>

<style scoped>
.qm-page { padding: 12px; }
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.header-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.kpi-name { color: #909399; font-size: 12px; }
.kpi-val { font-size: 26px; font-weight: bold; margin: 4px 0; }
.kpi-sub { color: #c0c4cc; font-size: 12px; }
.tip { color: #909399; font-size: 12px; }
</style>
