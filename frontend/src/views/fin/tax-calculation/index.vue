<template>
  <div class="page">
    <div class="head">
      <span class="title">{{ pageTitle }}</span>
      <el-radio-group v-model="tab" size="small">
        <el-radio-button value="workbench">计税工作台</el-radio-button>
        <el-radio-button value="ledger">计税台账</el-radio-button>
      </el-radio-group>
    </div>

    <!-- ================= 9.1.1/9.1.2 工作台：匹配 → 计算 → 落台账 ================= -->
    <template v-if="tab === 'workbench'">
      <el-card class="block">
        <template #header>① 税码匹配（FR-4.14-1-2：按业务发生日；多命中须人工选择）</template>
        <el-form :inline="true" size="small">
          <el-form-item label="交易类型">
            <el-select v-model="matchForm.trxType" style="width: 130px">
              <el-option label="销售 SALES" value="SALES" />
              <el-option label="采购 PURCHASE" value="PURCHASE" />
            </el-select>
          </el-form-item>
          <el-form-item label="适用范围">
            <el-select v-model="matchForm.scope" style="width: 130px">
              <el-option label="境内 DOMESTIC" value="DOMESTIC" />
              <el-option label="出口 EXPORT" value="EXPORT" />
              <el-option label="免税 EXEMPT" value="EXEMPT" />
            </el-select>
          </el-form-item>
          <el-form-item label="计税方式">
            <el-select v-model="matchForm.calcType" style="width: 130px">
              <el-option label="一般计税" value="GENERAL" />
              <el-option label="简易计税" value="SIMPLE" />
            </el-select>
          </el-form-item>
          <el-form-item label="税率种类">
            <el-select v-model="matchForm.rateKind" clearable style="width: 120px" placeholder="不限">
              <el-option label="标准 STANDARD" value="STANDARD" />
              <el-option label="低 LOW" value="LOW" />
              <el-option label="零 ZERO" value="ZERO" />
            </el-select>
          </el-form-item>
          <el-form-item label="业务发生日">
            <el-date-picker v-model="matchForm.businessDate" type="date" value-format="YYYY-MM-DD"
              style="width: 150px" placeholder="默认今天" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" size="small" @click="doMatch">匹配税码</el-button>
          </el-form-item>
        </el-form>
        <el-alert v-if="matchResult && matchResult.rateNote" :title="matchResult.rateNote" type="warning"
          :closable="false" class="mb8" />
        <el-table v-if="candidates.length" :data="candidates" size="small" border
          @row-click="pickCandidate" :row-class-name="candidateRowClass">
          <el-table-column prop="taxCode" label="税码" width="120" />
          <el-table-column prop="taxRate" label="税率" width="100">
            <template #default="{ row }">{{ (Number(row.taxRate) * 100).toFixed(0) }}%</template>
          </el-table-column>
          <el-table-column prop="rateKind" label="税率种类" width="100" />
          <el-table-column prop="effectiveDate" label="生效日" width="120" />
          <el-table-column prop="expireDate" label="失效日" width="120" />
          <el-table-column label="" width="90">
            <template #default="{ row }">
              <el-button v-if="row.taxCode !== pickedCode" link type="primary" size="small"
                @click.stop="pickCandidate(row)">选定</el-button>
              <el-tag v-else type="success" size="small">已选定</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="matchResult && matchResult.multiCandidate" class="hint">
          多命中（L4）：请人工选择适用税码后继续计算
        </div>
      </el-card>

      <el-card class="block">
        <template #header>② 税额计算（FR-4.14-1-3：含税先价税分离；BR-4.14-12 折扣按占比分摊）</template>
        <el-form :inline="true" size="small">
          <el-form-item label="金额口径">
            <el-radio-group v-model="calcForm.grossFlag">
              <el-radio-button value="Y">含税</el-radio-button>
              <el-radio-button value="N">不含税</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="整体税率">
            <el-input v-model="calcForm.taxRate" style="width: 90px" placeholder="0.13" />
          </el-form-item>
          <el-form-item label="折扣">
            <el-input v-model="calcForm.discount" style="width: 100px" placeholder="0" />
          </el-form-item>
          <el-form-item label="运费">
            <el-input v-model="calcForm.freight" style="width: 100px" placeholder="0" />
          </el-form-item>
        </el-form>
        <el-table :data="calcForm.lines" size="small" border>
          <el-table-column label="行" width="56">
            <template #default="{ $index }">{{ $index + 1 }}</template>
          </el-table-column>
          <el-table-column label="金额">
            <template #default="{ row }">
              <el-input v-model="row.amount" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="行税率（多税率时逐行填）" width="170">
            <template #default="{ row }">
              <el-input v-model="row.taxRate" size="small" placeholder="留空用整体" />
            </template>
          </el-table-column>
          <el-table-column label="" width="70">
            <template #default="{ $index }">
              <el-button link type="danger" size="small" @click="calcForm.lines.splice($index, 1)">删</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="mt8">
          <el-button size="small" @click="calcForm.lines.push({ amount: '', taxRate: '' })">加行</el-button>
          <el-button type="primary" size="small" @click="doCalc">计算税额</el-button>
        </div>
        <template v-if="calcResult">
          <el-alert v-if="calcResult.overTolerance" type="warning" :closable="false" class="mt8"
            :title="`尾差超容差（差额 ${calcResult.toleranceDiff}）——L4 复核提示，请确认计税结果`" />
          <el-alert v-if="calcResult.deductible === 'N'" type="warning" :closable="false" class="mt8"
            :title="calcResult.note" />
          <el-table :data="calcResult.lines" size="small" border class="mt8">
            <el-table-column prop="lineNo" label="行" width="56" />
            <el-table-column prop="taxableBase" label="计税基数" />
            <el-table-column prop="taxRate" label="税率" width="100" />
            <el-table-column prop="taxAmount" label="税额" />
          </el-table>
          <div class="summary">
            合计基数 <b>{{ calcResult.taxableBase }}</b> ／ 合计税额 <b>{{ calcResult.taxAmount }}</b>
            ／ 可抵扣：<el-tag :type="calcResult.deductible === 'Y' ? 'success' : 'danger'" size="small">
              {{ calcResult.deductible === 'Y' ? '可抵扣' : '不得抵扣（BR-4.14-08）' }}</el-tag>
          </div>
          <el-button type="success" size="small" class="mt8" @click="saveLedger">落台账（留痕）</el-button>
        </template>
      </el-card>
    </template>

    <!-- ================= 计税台账：留痕列表 + 补录入账 + 生成凭证 ================= -->
    <template v-else>
      <el-card class="block">
        <template #header>计税台账（每次匹配+计算自动留痕；补录对手方/期间/摘要后可入账）</template>
        <el-form :inline="true" size="small">
          <el-form-item label="状态">
            <el-select v-model="ledgerQuery.status" clearable style="width: 150px">
              <el-option label="已留痕" value="RECORDED" />
              <el-option label="入账申请" value="APPLYING" />
              <el-option label="已挂凭证" value="VOUCHED" />
            </el-select>
          </el-form-item>
          <el-form-item label="交易类型">
            <el-select v-model="ledgerQuery.trxType" clearable style="width: 130px">
              <el-option label="销售" value="SALES" />
              <el-option label="采购" value="PURCHASE" />
            </el-select>
          </el-form-item>
          <el-form-item label="期间">
            <el-input v-model="ledgerQuery.period" placeholder="YYYYMM" style="width: 110px" />
          </el-form-item>
          <el-form-item>
            <el-button size="small" @click="loadLedger">查询</el-button>
          </el-form-item>
        </el-form>
        <el-table :data="ledgerRows" size="small" border>
          <el-table-column prop="id" label="行号" width="180" show-overflow-tooltip />
          <el-table-column prop="trxType" label="类型" width="70" />
          <el-table-column prop="taxCode" label="税码" width="90" />
          <el-table-column prop="taxRate" label="税率" width="70" />
          <el-table-column prop="taxAmount" label="税额" width="90" />
          <el-table-column prop="deductible" label="抵扣" width="70">
            <template #default="{ row }">
              <el-tag :type="row.deductible === 'Y' ? 'success' : 'danger'" size="small">
                {{ row.deductible === 'Y' ? '可抵' : '不得抵' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="counterparty" label="对手方" width="120" />
          <el-table-column prop="period" label="期间" width="80" />
          <el-table-column prop="status" label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="210">
            <template #default="{ row }">
              <template v-if="row.status === 'RECORDED'">
                <el-button link type="primary" size="small" @click="openApply(row)">补录入账</el-button>
              </template>
              <template v-else-if="row.status === 'APPLYING'">
                <el-button link type="success" size="small" @click="doGenerate(row)">生成凭证</el-button>
              </template>
              <span v-else class="muted">凭证 {{ row.voucherId || '—' }}</span>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>

    <!-- 补录入账对话框 -->
    <el-dialog v-model="applyVisible" title="补录入账申请（FR-4.14-1-4 三字段必填）" width="480px">
      <el-form label-width="80px" size="small">
        <el-form-item label="对手方" required>
          <el-input v-model="applyForm.counterparty" placeholder="客户/供应商名称" />
        </el-form-item>
        <el-form-item label="期间" required>
          <el-input v-model="applyForm.period" placeholder="YYYYMM，如 209902" />
        </el-form-item>
        <el-form-item label="摘要" required>
          <el-input v-model="applyForm.summary" placeholder="入账事由" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="applyVisible = false">取消</el-button>
        <el-button type="primary" size="small" @click="doApply">提交申请</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  matchTaxCode, calcTax, createLedger, ledgerList, submitApply, generateVoucher
} from '@/api/fin/tax-calc'

const route = useRoute()
const tab = ref('workbench')
const pageTitle = computed(() => {
  if (route.name === 'TaxMatch') return '9.1.1 税码匹配'
  if (route.name === 'TaxCalc') return '9.1.2 税额计算'
  return '计税管理'
})

// ---------- 匹配 ----------
const matchForm = ref({ trxType: 'SALES', scope: 'DOMESTIC', calcType: 'GENERAL', rateKind: '', businessDate: '' })
const matchResult = ref(null)
const candidates = ref([])
const pickedCode = ref('')

async function doMatch() {
  try {
    const params = { ...matchForm.value }
    if (!params.businessDate) delete params.businessDate
    if (!params.rateKind) delete params.rateKind
    const { data } = await matchTaxCode(params)
    matchResult.value = data
    candidates.value = data.candidates || []
    pickedCode.value = candidates.value.length === 1 ? candidates.value[0].taxCode : ''
    if (data.multiCandidate) {
      ElMessage.warning('匹配到多个税码（L4），请人工选择')
    } else {
      ElMessage.success(`唯一命中 ${pickedCode.value}`)
    }
  } catch (e) {
    // C-4.14-01：税率主数据缺失或已过期（422 已由拦截器提示）
    matchResult.value = null
    candidates.value = []
  }
}

function pickCandidate(row) {
  pickedCode.value = row.taxCode
  matchForm.value.calcType = row.calcType
}

function candidateRowClass({ row }) {
  return row.taxCode === pickedCode.value ? 'picked-row' : ''
}

// ---------- 计算 ----------
const calcForm = ref({
  grossFlag: 'Y', taxRate: '0.13', discount: '0', freight: '0',
  lines: [{ amount: '', taxRate: '' }]
})
const calcResult = ref(null)

async function doCalc() {
  if (!pickedCode.value) {
    ElMessage.warning('请先在 ① 匹配并选定税码')
    return
  }
  try {
    const lines = calcForm.value.lines
      .filter(l => l.amount && Number(l.amount) > 0)
      .map(l => ({ amount: l.amount, taxRate: l.taxRate || undefined }))
    if (!lines.length) {
      ElMessage.warning('至少一行有效金额')
      return
    }
    const payload = {
      calcType: matchForm.value.calcType,
      grossFlag: calcForm.value.grossFlag,
      taxRate: calcForm.value.taxRate || undefined,
      discount: Number(calcForm.value.discount || 0) || undefined,
      freight: Number(calcForm.value.freight || 0) || undefined,
      lines
    }
    const { data } = await calcTax(payload)
    calcResult.value = data
    if (data.overTolerance) ElMessage.warning('尾差超容差（L4）：请复核计税结果')
  } catch (e) { /* 422 已提示 */ }
}

async function saveLedger() {
  const picked = candidates.value.find(c => c.taxCode === pickedCode.value)
  const first = calcResult.value.lines[0]
  try {
    await createLedger({
      trxType: matchForm.value.trxType,
      scope: matchForm.value.scope,
      calcType: calcResult.value.calcType,
      rateKind: picked ? picked.rateKind : null,
      businessDate: matchForm.value.businessDate || new Date().toISOString().slice(0, 10),
      amount: calcForm.value.grossFlag === 'Y'
        ? calcResult.value.lines.reduce((s, l) => s + Number(l.taxableBase) * (1 + Number(l.taxRate)), 0).toFixed(2)
        : calcResult.value.taxableBase,
      grossFlag: calcForm.value.grossFlag,
      taxCode: pickedCode.value,
      taxRate: first.taxRate,
      taxableBase: calcResult.value.taxableBase,
      taxAmount: calcResult.value.taxAmount,
      deductible: calcResult.value.deductible,
      overTolerance: calcResult.value.overTolerance,
      multiCandidate: matchResult.value ? matchResult.value.multiCandidate : false
    })
    ElMessage.success('已落台账（留痕）——入账请到「计税台账」补录要素')
    tab.value = 'ledger'
    loadLedger()
  } catch (e) { /* 422 已提示 */ }
}

// ---------- 台账 ----------
const ledgerRows = ref([])
const ledgerQuery = ref({ status: '', trxType: '', period: '' })

async function loadLedger() {
  try {
    const params = {}
    Object.entries(ledgerQuery.value).forEach(([k, v]) => { if (v) params[k] = v })
    const { data } = await ledgerList(params)
    ledgerRows.value = data
  } catch (e) { /* ignore */ }
}

const applyVisible = ref(false)
const applyForm = ref({ counterparty: '', period: '', summary: '' })
let applyRowId = ''

function openApply(row) {
  applyRowId = row.id
  applyForm.value = { counterparty: '', period: '', summary: '' }
  applyVisible.value = true
}

async function doApply() {
  try {
    await submitApply(applyRowId, applyForm.value)
    ElMessage.success('入账申请已提交——可生成税务凭证（DRAFT）')
    applyVisible.value = false
    loadLedger()
  } catch (e) { /* 422 已提示 */ }
}

async function doGenerate(row) {
  try {
    await ElMessageBox.confirm(
      '生成税务凭证（DRAFT）？凭证经审核过账后方影响总账（制单≠审核）。', '生成凭证', { type: 'warning' })
    const { data } = await generateVoucher(row.id)
    ElMessage.success(`凭证 ${data.voucherNo} 已生成（DRAFT，待审核过账）`)
    loadLedger()
  } catch (e) { /* 取消或 422 */ }
}

function statusText(s) {
  return { RECORDED: '已留痕', APPLYING: '入账申请', VOUCHED: '已挂凭证' }[s] || s
}

function statusTag(s) {
  return { RECORDED: 'info', APPLYING: 'warning', VOUCHED: 'success' }[s] || ''
}

onMounted(() => { loadLedger() })
</script>

<style scoped>
.page { padding: 12px; }
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-size: 16px; font-weight: 600; }
.block { margin-bottom: 12px; }
.mb8 { margin-bottom: 8px; }
.mt8 { margin-top: 8px; }
.hint { color: #e6a23c; font-size: 12px; margin-top: 6px; }
.summary { margin-top: 8px; font-size: 13px; }
.muted { color: #909399; font-size: 12px; }
:deep(.picked-row) { background: #f0f9eb; }
</style>
