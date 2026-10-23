<template>
  <div class="page ap-mgmt-page">
    <el-tabs v-model="tab">
      <!-- ================= 8.3.1 应付确认（三方匹配财务审核） ================= -->
      <el-tab-pane label="应付确认" name="confirm">
        <el-form inline>
          <el-input v-model="mtQuery.supplierId" clearable placeholder="供应商 ID" style="width:160px" />
          <el-input v-model="mtQuery.status" clearable placeholder="匹配状态" style="width:130px;margin-left:8px" />
          <el-button type="primary" @click="loadMatches" style="margin-left:8px">查询</el-button>
        </el-form>
        <el-table :data="mtRows" border size="small">
          <el-table-column prop="invoiceNo" label="发票号" width="150" show-overflow-tooltip />
          <el-table-column prop="supplierName" label="供应商" min-width="150" show-overflow-tooltip />
          <el-table-column prop="poNo" label="PO 号" width="140" show-overflow-tooltip />
          <el-table-column prop="status" label="匹配状态" width="110" />
          <el-table-column prop="diffRate" label="差异率" width="90" align="right" />
          <el-table-column label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="onMatchDetail(row)">详情</el-button>
              <el-button v-if="row.status === 'EXCEPTION'" link type="warning" @click="onConfirmMatch(row)">确认价差</el-button>
              <el-button v-if="row.status === 'EXCEPTION'" link type="danger" @click="onManualPost(row)">跨期过账</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-model:current-page="mtQuery.current" v-model:page-size="mtQuery.size"
          :total="mtTotal" layout="total, prev, pager, next" @change="loadMatches"
          style="margin-top:8px;justify-content:flex-end" />
        <el-alert type="info" :closable="false" show-icon style="margin-top:8px"
          title="财务确认视角：价差超容差须确认意见后过账；跨会计期间须 ADMIN 手工过账。确认后发票转正式应付并自动冲回暂估。" />
        <el-dialog v-model="cfOpinionVisible" title="确认价差（必填意见）" width="480">
          <el-input v-model="cfOpinion" type="textarea" :rows="3" placeholder="价差原因与确认意见" />
          <template #footer>
            <el-button @click="cfOpinionVisible = false">取消</el-button>
            <el-button type="primary" @click="submitConfirmMatch">确认</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>

      <!-- ================= 8.3.2 应付核销（账龄 + 对账单 + 核销记录） ================= -->
      <el-tab-pane label="应付核销" name="writeoff">
        <el-form inline>
          <el-input v-model="agSupplierId" clearable placeholder="供应商 ID（账龄）" style="width:160px" />
          <el-button type="primary" @click="loadAging" style="margin-left:8px">账龄分析</el-button>
          <el-button type="warning" plain @click="onBackfillDueDate">补录存量到期日</el-button>
        </el-form>
        <template v-if="agData">
          <el-row :gutter="10" style="margin-bottom:10px">
            <el-col :span="4" v-for="b in agBuckets" :key="b.label">
              <el-card shadow="never" body-style="padding:8px">
                <div class="bucket-label" :class="{ overdue: isOverdue(b.label) }">{{ b.label }}</div>
                <div class="bucket-base">{{ b.base }}</div>
                <div class="bucket-ratio">{{ (Number(b.ratio) * 100).toFixed(1) }}%</div>
              </el-card>
            </el-col>
          </el-row>
          <el-alert v-if="Number(agData.overdueRatio) > 0" type="warning" :closable="false" show-icon style="margin-bottom:8px"
            :title="`超期合计 ${agData.overdue}（占比 ${(Number(agData.overdueRatio) * 100).toFixed(1)}%）；未补录到期日 ${agData.nullDueCount} 张已跳过`" />
        </template>
        <el-divider />
        <el-tabs type="border-card">
          <el-tab-pane label="供应商对账单">
            <el-table :data="stRows" border size="small">
              <el-table-column prop="statementNo" label="对账单号" width="160" show-overflow-tooltip />
              <el-table-column prop="supplierName" label="供应商" min-width="150" show-overflow-tooltip />
              <el-table-column prop="period" label="期间" width="90" />
              <el-table-column prop="status" label="状态" width="100" />
              <el-table-column label="操作" width="120">
                <template #default="{ row }">
                  <el-button link type="primary" @click="onStatementDetail(row)">详情</el-button>
                  <el-button v-if="row.status === 'MATCHED'" link type="success" @click="onConfirmStatement(row)">确认</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="核销记录">
            <el-table :data="woRows" border size="small">
              <el-table-column prop="invoiceNo" label="发票号" width="150" show-overflow-tooltip />
              <el-table-column prop="supplierName" label="供应商" min-width="140" show-overflow-tooltip />
              <el-table-column prop="amount" label="核销金额" width="120" align="right" />
              <el-table-column prop="kind" label="类型" width="90" />
              <el-table-column prop="writeoffDate" label="核销日" width="110" />
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </el-tab-pane>

      <!-- ================= 8.3.3 付款管理（预测 + 计划） ================= -->
      <el-tab-pane label="付款管理" name="payment">
        <el-form inline>
          <el-button type="primary" @click="loadForecast">资金预测</el-button>
          <el-button type="success" @click="plCreateVisible = true">新建付款计划</el-button>
        </el-form>
        <template v-if="fcData">
          <el-table :data="fcData.windows" border size="small" style="margin-bottom:12px">
            <el-table-column prop="days" label="窗口（天）" width="110" />
            <el-table-column prop="apDue" label="到期未清应付" width="130" align="right" />
            <el-table-column prop="scheduled" label="已排期待付" width="120" align="right" />
            <el-table-column prop="prepay" label="预付款占用" width="120" align="right" />
            <el-table-column prop="need" label="资金需求合计" width="130" align="right" />
            <el-table-column prop="bank" label="银行可用余额" width="130" align="right" />
            <el-table-column label="资金缺口" width="120" align="right">
              <template #default="{ row }">
                <span :style="Number(row.gap) < 0 ? 'color:#f56c6c;font-weight:600' : 'color:#67c23a'">
                  {{ row.gap }}
                </span>
              </template>
            </el-table-column>
          </el-table>
        </template>
        <el-divider />
        <el-table :data="plRows" border size="small">
          <el-table-column prop="planNo" label="计划单号" width="160" show-overflow-tooltip />
          <el-table-column prop="period" label="期间" width="90" />
          <el-table-column prop="planTotal" label="计划总额" width="120" align="right" />
          <el-table-column prop="executedTotal" label="已执行" width="120" align="right" />
          <el-table-column prop="status" label="状态" width="100" />
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="onPlanDetail(row)">明细</el-button>
              <el-button v-if="row.status === 'DRAFT'" link type="primary" @click="onSubmitPlan(row)">提交</el-button>
              <el-button v-if="row.status === 'PENDING'" link type="success" @click="onPlanApproval(row, true)">通过</el-button>
              <el-button v-if="row.status === 'PENDING'" link type="danger" @click="onPlanApproval(row, false)">驳回</el-button>
              <el-button v-if="row.status === 'APPROVED'" link type="warning" @click="onClosePlan(row)">关闭</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-model:current-page="plQuery.current" v-model:page-size="plQuery.size"
          :total="plTotal" layout="total, prev, pager, next" @change="loadPlans"
          style="margin-top:8px;justify-content:flex-end" />
        <el-dialog v-model="plCreateVisible" title="新建付款计划" width="520">
          <el-form label-width="90px">
            <el-form-item label="期间"><el-input v-model="plForm.period" placeholder="yyyyMM" maxlength="6" /></el-form-item>
            <el-form-item label="计划总额"><el-input-number v-model="plForm.planTotal" :min="0" :precision="2" /></el-form-item>
            <el-form-item label="供应商行"><el-input v-model="plForm.planLines" type="textarea" :rows="4" placeholder='[{"supplierId":"S001","supplierName":"甲","planAmount":10000}]' /></el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="plCreateVisible = false">取消</el-button>
            <el-button type="primary" @click="submitCreatePlan">创建</el-button>
          </template>
        </el-dialog>
        <el-dialog v-model="plDetailVisible" title="计划明细（分供应商行）" width="560">
          <el-table :data="plDetailLines" border size="small">
            <el-table-column prop="supplierName" label="供应商" min-width="140" show-overflow-tooltip />
            <el-table-column prop="planAmount" label="计划金额" width="120" align="right" />
            <el-table-column prop="executedAmount" label="已执行" width="120" align="right" />
          </el-table>
        </el-dialog>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  apAging, apForecast, backfillDueDate,
  createPlan, submitPlan, planApproval, closePlan, planPage, planDetail,
  matchPage, matchDetail, confirmMatch, manualPostMatch,
  statementPage, statementDetail, confirmStatement, writeoffPage
} from '@/api/fin/ap'

const route = useRoute()
const tab = ref(route.meta?.tab || 'confirm')

// ---------- 8.3.1 应付确认 ----------
const mtQuery = reactive({ current: 1, size: 20, supplierId: '', status: '' })
const mtRows = ref([])
const mtTotal = ref(0)
const cfOpinionVisible = ref(false)
const cfOpinion = ref('')
let cfCurrentMatch = null

async function loadMatches() {
  const { data } = await matchPage({
    ...mtQuery, supplierId: mtQuery.supplierId || undefined, status: mtQuery.status || undefined
  })
  mtRows.value = data.records || []
  mtTotal.value = Number(data.total || 0)
}

async function onMatchDetail(row) {
  const { data } = await matchDetail(row.id)
  ElMessageBox.alert(JSON.stringify(data, null, 2), '匹配单详情', { customStyle: { width: '60%' } })
}

function onConfirmMatch(row) {
  cfCurrentMatch = row
  cfOpinion.value = ''
  cfOpinionVisible.value = true
}

async function submitConfirmMatch() {
  if (!cfOpinion.value.trim()) {
    ElMessage.warning('确认意见必填')
    return
  }
  await confirmMatch(cfCurrentMatch.id, cfOpinion.value.trim())
  ElMessage.success('价差已确认')
  cfOpinionVisible.value = false
  loadMatches()
}

async function onManualPost(row) {
  await ElMessageBox.confirm('跨期手工过账（仅 ADMIN）：生成当期价差调整凭证并注明跨期原因。继续？', '跨期过账', { type: 'warning' })
  await manualPostMatch(row.id, '跨期手工过账')
  ElMessage.success('已过账')
  loadMatches()
}

// ---------- 8.3.2 应付核销（账龄 + 对账单 + 核销记录） ----------
const agSupplierId = ref('')
const agData = ref(null)
const agBuckets = ref([])
const stRows = ref([])
const woRows = ref([])

function isOverdue(label) {
  const m = String(label).match(/^(\d+)-/)
  if (m) return Number(m[1]) > 0
  return String(label).endsWith('+')
}

async function onBackfillDueDate() {
  await ElMessageBox.confirm('按供应商账期批量回填存量发票到期日（DUE_DATE 为空且账期为数字天数的发票）？', '补录到期日', { type: 'warning' })
  const { data } = await backfillDueDate()
  ElMessage.success(`已回填 ${data.updated} 张，无账期跳过 ${data.skippedNoTerms} 张`)
  loadAging()
}

async function loadAging() {
  const { data } = await apAging(agSupplierId.value ? { supplierId: agSupplierId.value } : {})
  agData.value = data
  agBuckets.value = data.buckets || []
}

async function loadStatements() {
  const { data } = await statementPage({ current: 1, size: 50 })
  stRows.value = data.records || []
}

async function onStatementDetail(row) {
  const { data } = await statementDetail(row.id)
  ElMessageBox.alert(JSON.stringify(data, null, 2), '对账单详情', { customStyle: { width: '60%' } })
}

async function onConfirmStatement(row) {
  await ElMessageBox.confirm(`确认对账单 ${row.statementNo}？`, '确认', { type: 'warning' })
  await confirmStatement(row.id)
  ElMessage.success('已确认')
  loadStatements()
}

async function loadWriteoffs() {
  const { data } = await writeoffPage({ current: 1, size: 50 })
  woRows.value = data.records || []
}

// ---------- 8.3.3 付款管理（预测 + 计划） ----------
const fcData = ref(null)
const plQuery = reactive({ current: 1, size: 20, period: '' })
const plRows = ref([])
const plTotal = ref(0)
const plCreateVisible = ref(false)
const plDetailVisible = ref(false)
const plDetailLines = ref([])
const plForm = reactive({ period: '', planTotal: 0, planLines: '' })

async function loadForecast() {
  const { data } = await apForecast({})
  fcData.value = data
}

async function loadPlans() {
  const { data } = await planPage({ ...plQuery, period: plQuery.period || undefined })
  plRows.value = data.records || []
  plTotal.value = Number(data.total || 0)
}

async function submitCreatePlan() {
  if (!plForm.period || plForm.period.length !== 6) {
    ElMessage.warning('期间须为 yyyyMM')
    return
  }
  let lines = null
  if (plForm.planLines.trim()) {
    try {
      lines = JSON.parse(plForm.planLines)
    } catch (e) {
      ElMessage.warning('供应商行 JSON 格式错误')
      return
    }
  }
  await createPlan({ period: plForm.period, planTotal: plForm.planTotal, planLines: JSON.stringify(lines) })
  ElMessage.success('计划已创建')
  plCreateVisible.value = false
  loadPlans()
}

async function onSubmitPlan(row) {
  await submitPlan(row.id)
  ElMessage.success('已提交审批')
  loadPlans()
}

async function onPlanApproval(row, approved) {
  await ElMessageBox.confirm(`${approved ? '通过' : '驳回'}付款计划 ${row.planNo}？`, '审批', { type: 'warning' })
  await planApproval(row.id, approved, approved ? null : '不通过')
  ElMessage.success('已处理')
  loadPlans()
}

async function onClosePlan(row) {
  await ElMessageBox.confirm(`关闭付款计划 ${row.planNo}？关闭后不可再执行付款。`, '关闭', { type: 'warning' })
  await closePlan(row.id)
  ElMessage.success('已关闭')
  loadPlans()
}

async function onPlanDetail(row) {
  const { data } = await planDetail(row.id)
  plDetailLines.value = data.lines || []
  plDetailVisible.value = true
}

onMounted(() => {
  loadMatches()
  loadAging()
  loadStatements()
  loadWriteoffs()
  loadPlans()
})
</script>

<style scoped>
.bucket-label { font-size: 12px; color: #909399; }
.bucket-label.overdue { color: #f56c6c; font-weight: 600; }
.bucket-base { font-size: 17px; font-weight: 600; margin: 3px 0 2px; }
.bucket-ratio { font-size: 12px; color: #606266; }
</style>
