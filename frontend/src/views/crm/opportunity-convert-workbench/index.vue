<template>
  <div class="convert-bench">

    <!-- ============ 上区：线索 → 商机转化 ============ -->
    <el-card style="margin-bottom: 14px;">
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">① 线索 → 商机转化</span>
          <div class="header-actions">
            <el-select v-model="leadGrade" style="width: 120px;" @change="loadLeads">
              <el-option label="A 级线索" value="A" />
              <el-option label="B 级线索" value="B" />
            </el-select>
            <el-button :icon="Refresh" @click="loadLeads">刷新</el-button>
            <PageInfo class="page-info--inline" title="商机转化" description="CRM 全链路工作台：上区线索→商机转化（A/B 级未转化线索，FR-4.8-1-5 门槛与要素全由服务端校验）；下区可转化商机→报价闸口（BR-4.3-07，校验通过跳转，不通过回写原因）。同功能不同入口。" />
          </div>
        </div>
      </template>

      <el-table :data="leads" v-loading="leadLoading" stripe size="small" max-height="280">
        <el-table-column prop="leadNo" label="线索编号" width="140" />
        <el-table-column prop="companyName" label="公司" min-width="150" show-overflow-tooltip />
        <el-table-column prop="contactName" label="联系人" width="100" />
        <el-table-column prop="grade" label="等级" width="70" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.grade === 'A' ? 'danger' : 'warning'">{{ row.grade }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="score" label="评分" width="70" align="center" />
        <el-table-column prop="expectAmount" label="预期金额" width="120" align="right">
          <template #default="{ row }">{{ row.expectAmount == null ? '-' : '¥ ' + fmtAmount(row.expectAmount) }}</template>
        </el-table-column>
        <el-table-column prop="demandDesc" label="需求摘要" min-width="160" show-overflow-tooltip />
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="openConvert(row)">转化为商机</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!leadLoading && leads.length === 0" class="empty-tip">
        暂无 {{ leadGrade }} 级未转化线索（先到 11.1.1 录入、11.1.2 评分）
      </div>
    </el-card>

    <!-- ============ 下区：可转化商机 → 报价闸口 ============ -->
    <el-card>
      <template #header>
        <div class="card-header">
          <span style="font-weight: bold; font-size: 16px;">② 可转化商机 → 创建报价（闸口校验）</span>
          <div class="header-actions">
            <el-input v-model="oppKeyword" placeholder="商机编号/名称" clearable
                      :prefix-icon="Search" style="width: 200px;" @keyup.enter="loadQuotable" />
            <el-button type="primary" @click="loadQuotable">查询</el-button>
          </div>
        </div>
      </template>

      <el-table :data="quotable" v-loading="oppLoading" stripe size="small">
        <el-table-column prop="oppNo" label="商机编号" width="150" />
        <el-table-column prop="oppName" label="商机名称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="customerName" label="客户" width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.customerCode }} {{ row.customerName }}</template>
        </el-table-column>
        <el-table-column prop="expectAmount" label="预期金额" width="120" align="right">
          <template #default="{ row }">{{ row.expectAmount == null ? '-' : '¥ ' + fmtAmount(row.expectAmount) }}</template>
        </el-table-column>
        <el-table-column label="阶段" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="stageTagType(row.stage)">{{ stageName(row.stage) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="闸口" width="100">
          <template #default="{ row }">
            <el-tag v-if="gateOk(row)" type="success" size="small">可转化</el-tag>
            <el-tag v-else type="info" size="small">未达报价</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-button size="small" type="primary" :disabled="!gateOk(row)"
                       @click="createQuote(row)">创建报价</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!oppLoading && quotable.length === 0" class="empty-tip">
        暂无可转化商机（推进到「报价」阶段后出现）
      </div>
    </el-card>

    <!-- ============ 线索转化要素抽屉 ============ -->
    <el-drawer v-model="drawerVisible" :title="`线索转化：${selectedLead?.leadNo || ''}`" size="520px">
      <el-descriptions v-if="selectedLead" :column="1" border size="small" style="margin-bottom: 14px;">
        <el-descriptions-item label="公司">{{ selectedLead.companyName }}</el-descriptions-item>
        <el-descriptions-item label="联系人">{{ selectedLead.contactName }} {{ selectedLead.contactPhone || '' }}</el-descriptions-item>
        <el-descriptions-item label="需求">{{ selectedLead.demandDesc || '—' }}</el-descriptions-item>
        <el-descriptions-item label="等级/评分">{{ selectedLead.grade }} 级 / {{ selectedLead.score }} 分</el-descriptions-item>
      </el-descriptions>

      <el-form :model="cForm" label-width="110px">
        <el-form-item label="商机名称" required>
          <el-input v-model="cForm.oppName" placeholder="转化后商机名称" />
        </el-form-item>
        <el-form-item label="客户主数据" required>
          <el-select v-model="cForm.customerId" filterable remote reserve-keyword clearable
                     :remote-method="searchCustomers" :loading="custLoading"
                     style="width: 100%;" placeholder="搜索客户（不存在请先到 1.1.1 建档）">
            <el-option v-for="c in customers" :key="c.id"
                       :label="`${c.customerCode} ${c.customerName}`" :value="c.id" />
          </el-select>
          <div class="form-tip">客户主数据必填；缺失时请先创建客户档案（服务端 422 引导）</div>
        </el-form-item>
        <el-form-item label="预期金额" required>
          <el-input-number v-model="cForm.expectAmount" :min="0" :precision="2" style="width: 100%;" />
        </el-form-item>
        <el-form-item label="预计成交日期" required>
          <el-date-picker v-model="cForm.expectCloseDate" type="date" value-format="YYYY-MM-DD"
                          style="width: 100%;" />
        </el-form-item>
        <el-form-item label="竞争分析" required>
          <el-input v-model="cForm.competition" type="textarea" :rows="2" placeholder="主要竞品与我方差异" />
        </el-form-item>
        <el-form-item label="客户需求摘要" required>
          <el-input v-model="cForm.demandSummary" type="textarea" :rows="3" placeholder="客户核心诉求" />
        </el-form-item>
        <el-form-item label="预期项目预算">
          <el-input v-model="cForm.budgetRef" placeholder="预算编号/说明（可选）" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitConvert">确认转化</el-button>
      </template>
    </el-drawer>

    <!-- ============ 闸口阻断提示 ============ -->
    <el-dialog v-model="blockVisible" title="闸口校验未通过" width="480px">
      <el-result icon="error" :title="blockTitle" :sub-title="blockReason" />
      <template #footer>
        <el-button type="primary" @click="blockVisible = false">知道了</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import PageInfo from '@/components/PageInfo.vue'
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import request from '@/utils/request'
import { getLeadsApi } from '@/api/crm/lead'
import { getOpportunitiesApi, createOpportunityApi, checkQuoteApi } from '@/api/crm/opportunity'

const router = useRouter()

const STAGES = [
  { value: 'REQUIREMENT', label: '需求确认' },
  { value: 'DEMO', label: '方案演示' },
  { value: 'NEGOTIATION', label: '商务谈判' },
  { value: 'QUOTE', label: '报价' },
  { value: 'CONTRACT', label: '合同签订' }
]

// ---------- 上区：线索转化 ----------
const leads = ref([])
const leadLoading = ref(false)
const leadGrade = ref('A')
const drawerVisible = ref(false)
const selectedLead = ref(null)
const saving = ref(false)
const customers = ref([])
const custLoading = ref(false)
const cForm = ref({ oppName: '', customerId: '', expectAmount: null, expectCloseDate: null,
                    competition: '', demandSummary: '', budgetRef: '' })

// ---------- 下区：报价闸口 ----------
const quotable = ref([])
const oppLoading = ref(false)
const oppKeyword = ref('')
const blockVisible = ref(false)
const blockTitle = ref('')
const blockReason = ref('')

onMounted(() => {
  loadLeads()
  loadQuotable()
})

async function loadLeads() {
  leadLoading.value = true
  try {
    // status=OPEN 天然排除已转化（CONVERTED）；grade 单值，前端按选中级取（D3 默认零后端改动）
    const res = await getLeadsApi({ current: 1, size: 50, status: 'OPEN', grade: leadGrade.value })
    leads.value = res.data.records || []
  } finally {
    leadLoading.value = false
  }
}

async function loadQuotable() {
  oppLoading.value = true
  try {
    const res = await getOpportunitiesApi({
      current: 1, size: 50, status: 'OPEN', stage: 'QUOTE', keyword: oppKeyword.value || undefined
    })
    quotable.value = res.data.records || []
  } finally {
    oppLoading.value = false
  }
}

function openConvert(row) {
  selectedLead.value = row
  cForm.value = {
    oppName: `${row.companyName || row.leadNo} 商机`,
    customerId: '', expectAmount: row.expectAmount ?? null,
    expectCloseDate: row.expectCloseDate || null,
    competition: '', demandSummary: row.demandDesc || '', budgetRef: ''
  }
  customers.value = []
  drawerVisible.value = true
}

async function searchCustomers(kw) {
  custLoading.value = true
  try {
    const res = await request.get('/mdm/customer-groups', {
      params: { current: 1, size: 20, keyword: kw || '' }
    })
    customers.value = res.data.records || []
  } finally {
    custLoading.value = false
  }
}

async function submitConvert() {
  const l = selectedLead.value
  const f = cForm.value
  if (!f.oppName || !f.customerId || !f.expectAmount || !f.expectCloseDate || !f.competition || !f.demandSummary) {
    ElMessage.warning('转化要素必填：商机名称 / 客户 / 金额 / 成交日期 / 竞争分析 / 需求摘要')
    return
  }
  saving.value = true
  try {
    // 服务端既有校验全继承：A/B 门槛 / 四要素 / 不可重复转化 / 客户建档（FR-4.8-1-5）
    await createOpportunityApi({ ...f, leadId: l.id, ownerId: l.ownerId, ownerName: l.ownerName })
    ElMessage.success(`线索 ${l.leadNo} 已转化为商机`)
    drawerVisible.value = false
    loadLeads()
    loadQuotable()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '转化失败（服务端校验未通过）')
  } finally {
    saving.value = false
  }
}

// ---------- 闸口（BR-4.3-07）----------
function gateOk(row) {
  return row.stage === 'QUOTE'
}

async function createQuote(row) {
  // check-quote 恒返 200：data.ok=false + reason 表阻断（服务端已回写追踪），不走异常
  try {
    const res = await checkQuoteApi(row.id)
    const q = res.data || {}
    if (q.ok === false) {
      blockTitle.value = '该商机尚未达到报价阶段'
      blockReason.value = q.reason
        || '不可引用原因已回写商机追踪记录；达到报价阶段或解决客户状态后可再次校验。'
      blockVisible.value = true
      loadQuotable()
      return
    }
    // 校验通过 → 带出商机与客户跳转 3.2.1 报价创建（本页不承载报价表单）
    router.push({
      path: '/m/3.2.1',
      query: { oppId: row.id, oppNo: row.oppNo, customerId: row.customerId }
    })
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '闸口校验失败')
  }
}

// ---------- 展示辅助 ----------
function stageName(s) {
  return (STAGES.find(x => x.value === s) || {}).label || s
}
function stageTagType(s) {
  return { REQUIREMENT: 'info', DEMO: '', NEGOTIATION: 'warning', QUOTE: 'primary', CONTRACT: 'success' }[s] || 'info'
}
function fmtAmount(v) {
  const n = Number(v || 0)
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
</script>

<style scoped>
.convert-bench { padding: 12px; }
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.header-actions { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.empty-tip { color: #909399; font-size: 13px; text-align: center; padding: 18px 0; }
.form-tip { color: #909399; font-size: 12px; line-height: 1.5; }
</style>
