<template>
  <div class="opp-page">
    <el-alert
      type="info" :closable="false" show-icon style="margin-bottom: 12px;"
      title="商机录入（3.1.1）"
      description="手工登记或从 B 级以上线索转化；编号自动且创建后不可改；预期金额超 100 万自动通知销售经理；页顶漏斗/转化率/赢率全自动统计（BR-4.8-09）。"
    />

    <!-- ============ 页顶统计卡片 ============ -->
    <el-row :gutter="12" style="margin-bottom: 12px;">
      <el-col :span="4" v-for="b in funnel" :key="b.stage">
        <el-card shadow="never" class="funnel-card">
          <div class="funnel-name">{{ b.stageName }}</div>
          <div class="funnel-count">{{ b.count }}</div>
          <div class="funnel-amount">¥ {{ fmtAmount(b.amount) }}</div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="never" class="funnel-card highlight">
          <div class="funnel-name">赢率</div>
          <div class="funnel-count">{{ stats.winRate || 0 }}%</div>
          <div class="funnel-amount">赢 {{ stats.won || 0 }} / 负 {{ stats.lost || 0 }}</div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="never" class="funnel-card highlight">
          <div class="funnel-name">转化率</div>
          <div class="funnel-count">{{ stats.conversionRate || 0 }}%</div>
          <div class="funnel-amount">线索 {{ stats.leadConverted || 0 }}/{{ stats.leadTotal || 0 }}</div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="never" class="funnel-card highlight">
          <div class="funnel-name">平均销售周期</div>
          <div class="funnel-count">{{ stats.avgCycleDays || 0 }}</div>
          <div class="funnel-amount">天（已终结商机）</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">商机列表</span>
          <div class="header-actions">
            <el-input v-model="query.keyword" placeholder="编号/名称/客户" clearable
                      :prefix-icon="Search" style="width: 200px;" @keyup.enter="loadData(1)" />
            <el-select v-model="query.status" placeholder="状态" clearable style="width: 120px;">
              <el-option label="跟进中" value="OPEN" />
              <el-option label="已赢单" value="WON" />
              <el-option label="已丢失" value="LOST" />
              <el-option label="已关闭" value="CLOSED" />
            </el-select>
            <el-select v-model="query.stage" placeholder="阶段" clearable style="width: 130px;">
              <el-option v-for="s in STAGES" :key="s.value" :label="s.label" :value="s.value" />
            </el-select>
            <el-button type="primary" @click="loadData(1)">查询</el-button>
            <el-button type="primary" :icon="Plus" @click="openForm()">新建商机</el-button>
            <el-button :icon="Promotion" @click="openFromLead()">从线索转化</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column prop="oppNo" label="商机编号" width="150" />
        <el-table-column prop="oppName" label="商机名称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="customerName" label="客户" width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.customerCode }} {{ row.customerName }}</template>
        </el-table-column>
        <el-table-column prop="expectAmount" label="预期金额" width="120" align="right">
          <template #default="{ row }">{{ row.expectAmount == null ? '-' : '¥ ' + fmtAmount(row.expectAmount) }}</template>
        </el-table-column>
        <el-table-column prop="stage" label="阶段" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="stageTagType(row.stage)">{{ stageName(row.stage) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ statusName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ownerName" label="负责人" width="110">
          <template #default="{ row }">{{ row.ownerName || row.ownerId || '-' }}</template>
        </el-table-column>
        <el-table-column label="停留" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.stageOverdueLive === '1'" type="warning" size="small">
              {{ row.stageDays }} 天·超期
            </el-tag>
            <span v-else>{{ row.stageDays == null ? '-' : row.stageDays + ' 天' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="在途审批" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.stagePending" type="warning" size="small">审批中</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openForm(row)">编辑</el-button>
            <el-button link type="primary" @click="$router.push('/m/3.1.3?oppId=' + row.id)">跟进</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination style="margin-top: 12px; justify-content: flex-end;"
        v-model:current-page="query.current" v-model:page-size="query.size"
        :total="total" :page-sizes="[10, 20, 50]"
        layout="total, prev, pager, next" @current-change="loadData" />
    </el-card>

    <!-- ============ 新建 / 编辑商机 ============ -->
    <el-dialog v-model="formVisible" :title="form.id ? '编辑商机' : '新建商机'" width="640px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="商机名称" required>
          <el-input v-model="form.oppName" maxlength="200" placeholder="如：XX 集团年度框架采购" />
        </el-form-item>
        <el-form-item label="客户" required>
          <el-select v-model="form.customerId" filterable remote :remote-method="searchCustomers"
                     :loading="custLoading" placeholder="搜索客户编码/名称" style="width: 100%;">
            <el-option v-for="c in customers" :key="c.id"
                       :label="`${c.customerCode} ${c.customerName}`" :value="c.id" />
          </el-select>
          <div class="form-tip">客户主数据不存在时请先到 1.3.1 客户建档（不允许跳过建档直接生成商机）。</div>
        </el-form-item>
        <el-form-item label="预期金额">
          <el-input-number v-model="form.expectAmount" :min="0" :precision="2" style="width: 220px;" />
          <span class="form-tip" style="margin-left: 8px;">超 100 万将自动通知销售经理</span>
        </el-form-item>
        <el-form-item label="预计成交日期">
          <el-date-picker v-model="form.expectCloseDate" type="date" value-format="YYYY-MM-DD" style="width: 220px;" />
        </el-form-item>
        <el-form-item label="竞争分析">
          <el-input v-model="form.competition" type="textarea" :rows="2" maxlength="500" />
        </el-form-item>
        <el-form-item label="需求摘要">
          <el-input v-model="form.demandSummary" type="textarea" :rows="2" maxlength="1000" />
        </el-form-item>
        <el-form-item label="预期项目预算">
          <el-input v-model="form.budgetRef" maxlength="255" placeholder="如：Q4 预算 200 万" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveForm">保存</el-button>
      </template>
    </el-dialog>

    <!-- ============ 从线索转化 ============ -->
    <el-dialog v-model="leadDialogVisible" title="从线索转化商机（等级 ≥ B）" width="720px">
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 10px;"
        title="仅展示 A/B 级且未转化的线索；转化要素（金额/日期/竞争分析/需求摘要）必填。" />
      <el-table :data="convertibleLeads" stripe height="320" v-loading="leadLoading"
                highlight-current-row @current-change="l => (selectedLead = l)">
        <el-table-column prop="leadNo" label="线索编号" width="150" />
        <el-table-column prop="companyName" label="公司" min-width="140" show-overflow-tooltip />
        <el-table-column prop="contactName" label="联系人" width="90" />
        <el-table-column prop="grade" label="等级" width="70">
          <template #default="{ row }">
            <el-tag size="small" :type="row.grade === 'A' ? 'danger' : 'warning'">{{ row.grade }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="score" label="评分" width="80" />
        <el-table-column prop="expectAmount" label="预期金额" width="110" align="right">
          <template #default="{ row }">{{ row.expectAmount == null ? '-' : fmtAmount(row.expectAmount) }}</template>
        </el-table-column>
      </el-table>

      <div v-if="selectedLead" style="margin-top: 12px;">
        <el-form :model="leadForm" label-width="110px">
          <el-form-item label="商机名称" required>
            <el-input v-model="leadForm.oppName" maxlength="200" />
          </el-form-item>
          <el-form-item label="客户" required>
            <el-select v-model="leadForm.customerId" filterable remote :remote-method="searchCustomers"
                       :loading="custLoading" placeholder="搜索客户（线索客户尚未建档时先建档）" style="width: 100%;">
              <el-option v-for="c in customers" :key="c.id"
                         :label="`${c.customerCode} ${c.customerName}`" :value="c.id" />
            </el-select>
          </el-form-item>
          <el-row :gutter="12">
            <el-col :span="12">
              <el-form-item label="预期金额" required>
                <el-input-number v-model="leadForm.expectAmount" :min="0" :precision="2" style="width: 100%;" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="预计成交日期" required>
                <el-date-picker v-model="leadForm.expectCloseDate" type="date" value-format="YYYY-MM-DD" style="width: 100%;" />
              </el-form-item>
            </el-col>
          </el-row>
          <el-form-item label="竞争分析" required>
            <el-input v-model="leadForm.competition" type="textarea" :rows="2" maxlength="500" />
          </el-form-item>
          <el-form-item label="需求摘要" required>
            <el-input v-model="leadForm.demandSummary" type="textarea" :rows="2" maxlength="1000" />
          </el-form-item>
          <el-form-item label="预期项目预算">
            <el-input v-model="leadForm.budgetRef" maxlength="255" />
          </el-form-item>
        </el-form>
      </div>

      <template #footer>
        <el-button @click="leadDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!selectedLead" @click="convertLead">确认转化</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Plus, Refresh, Promotion } from '@element-plus/icons-vue'
import { getOpportunitiesApi, getOppStatsApi, createOpportunityApi, updateOpportunityApi } from '@/api/crm/opportunity'
import { getLeadsApi } from '@/api/crm/lead'
import request from '@/utils/request'

const STAGES = [
  { value: 'REQUIREMENT', label: '需求确认' },
  { value: 'DEMO', label: '方案演示' },
  { value: 'NEGOTIATION', label: '商务谈判' },
  { value: 'QUOTE', label: '报价' },
  { value: 'CONTRACT', label: '合同签订' }
]

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = ref({ current: 1, size: 10, keyword: '', status: '', stage: '' })

const stats = ref({})
const funnel = computed(() => stats.value.funnel || [])

onMounted(() => {
  loadData(1)
  loadStats()
})

async function loadData(page) {
  if (page) query.value.current = page
  loading.value = true
  try {
    const res = await getOpportunitiesApi(query.value)
    rows.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

async function loadStats() {
  const res = await getOppStatsApi()
  stats.value = res.data || {}
}

// ---------- 客户搜索 ----------
const customers = ref([])
const custLoading = ref(false)
async function searchCustomers(kw) {
  custLoading.value = true
  try {
    const res = await request.get('/mdm/customer-groups', { params: { current: 1, size: 20, keyword: kw || '' } })
    customers.value = (res.data.records || [])
  } finally {
    custLoading.value = false
  }
}

// ---------- 手工新建 / 编辑 ----------
const formVisible = ref(false)
const saving = ref(false)
const form = ref({})
function openForm(row) {
  form.value = row
    ? { id: row.id, oppNo: row.oppNo, oppName: row.oppName, customerId: row.customerId,
        expectAmount: row.expectAmount, expectCloseDate: row.expectCloseDate,
        competition: row.competition, demandSummary: row.demandSummary, budgetRef: row.budgetRef }
    : { oppName: '', customerId: '', expectAmount: null, expectCloseDate: null,
        competition: '', demandSummary: '', budgetRef: '' }
  if (row && row.customerId) {
    customers.value = [{ id: row.customerId, customerCode: row.customerCode, customerName: row.customerName }]
  } else {
    searchCustomers('')
  }
  formVisible.value = true
}

async function saveForm() {
  if (!form.value.oppName || !form.value.customerId) {
    ElMessage.warning('商机名称与客户必填')
    return
  }
  saving.value = true
  try {
    if (form.value.id) {
      await updateOpportunityApi(form.value)
      ElMessage.success('商机已更新')
    } else {
      await createOpportunityApi(form.value)
      ElMessage.success('商机已录入（编号自动生成）')
    }
    formVisible.value = false
    loadData(1)
    loadStats()
  } finally {
    saving.value = false
  }
}

// ---------- 从线索转化 ----------
const leadDialogVisible = ref(false)
const leadLoading = ref(false)
const convertibleLeads = ref([])
const selectedLead = ref(null)
const leadForm = ref({})

async function openFromLead() {
  leadDialogVisible.value = true
  selectedLead.value = null
  leadForm.value = { oppName: '', customerId: '', expectAmount: null, expectCloseDate: null,
                     competition: '', demandSummary: '', budgetRef: '' }
  leadLoading.value = true
  try {
    const res = await getLeadsApi({ current: 1, size: 50, status: 'OPEN' })
    // 只留 A/B 级（FR-4.8-1-5 转化门槛）；未评分线索不进列表
    convertibleLeads.value = (res.data.records || []).filter(l => l.grade === 'A' || l.grade === 'B')
  } finally {
    leadLoading.value = false
  }
}

async function convertLead() {
  const l = selectedLead.value
  if (!l) return
  const f = leadForm.value
  if (!f.oppName || !f.customerId || !f.expectAmount || !f.expectCloseDate || !f.competition || !f.demandSummary) {
    ElMessage.warning('转化要素必填：商机名称 / 客户 / 金额 / 成交日期 / 竞争分析 / 需求摘要')
    return
  }
  saving.value = true
  try {
    await createOpportunityApi({ ...f, leadId: l.id, ownerId: l.ownerId, ownerName: l.ownerName })
    ElMessage.success(`线索 ${l.leadNo} 已转化为商机`)
    leadDialogVisible.value = false
    loadData(1)
    loadStats()
  } finally {
    saving.value = false
  }
}

// ---------- 展示辅助 ----------
function stageName(s) {
  return (STAGES.find(x => x.value === s) || {}).label || s
}
function stageTagType(s) {
  return { REQUIREMENT: 'info', DEMO: '', NEGOTIATION: 'warning', QUOTE: 'primary', CONTRACT: 'success' }[s] || 'info'
}
function statusName(s) {
  return { OPEN: '跟进中', WON: '已赢单', LOST: '已丢失', CLOSED: '已关闭' }[s] || s
}
function statusType(s) {
  return { OPEN: '', WON: 'success', LOST: 'danger', CLOSED: 'info' }[s] || 'info'
}
function fmtAmount(v) {
  const n = Number(v || 0)
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
</script>

<style scoped>
.opp-page { padding: 12px; }
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.header-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.funnel-card { text-align: center; }
.funnel-card .funnel-name { color: #909399; font-size: 12px; }
.funnel-card .funnel-count { font-size: 24px; font-weight: bold; color: #303133; margin: 4px 0; }
.funnel-card .funnel-amount { color: #909399; font-size: 12px; }
.funnel-card.highlight .funnel-count { color: #f56c6c; }
.form-tip { color: #909399; font-size: 12px; }
</style>
