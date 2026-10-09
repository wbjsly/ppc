<template>
  <div class="qc-page">
    <el-alert
      type="info" :closable="false" show-icon style="margin-bottom: 12px;"
      title="报价创建（3.2.1）"
      description="由商机入口创建（引用商机编号）；三类协议取价「专属 > 阶梯 > 时间」，无协议 L1 阻断；MOQ 行级拒绝（样品单豁免）；试算出毛利后再提交审批（小额高毛利自动发布）。"
    />

    <el-row :gutter="12">
      <!-- ============ 左：草稿列表 ============ -->
      <el-col :span="9">
        <el-card>
          <template #header>
            <div class="card-header">
              <span style="font-weight: bold;">报价单</span>
              <div>
                <el-input v-model="query.keyword" placeholder="编号/客户" clearable size="small"
                          style="width: 150px;" @keyup.enter="loadList(1)" />
                <el-button size="small" type="primary" style="margin-left: 6px;" @click="loadList(1)">查询</el-button>
              </div>
            </div>
          </template>
          <el-table :data="rows" v-loading="loading" stripe height="600"
                    highlight-current-row @current-change="selectQuote">
            <el-table-column label="编号" width="150" show-overflow-tooltip>
              <template #default="{ row }">{{ row.quoteNo || row.draftNo }}</template>
            </el-table-column>
            <el-table-column prop="customerName" label="客户" min-width="110" show-overflow-tooltip />
            <el-table-column label="金额" width="100" align="right">
              <template #default="{ row }">{{ fmtAmt(row.totalAmount) }}</template>
            </el-table-column>
            <el-table-column label="毛利" width="80">
              <template #default="{ row }">
                <span :style="{ color: marginColor(row) }">{{ pct(row.marginRate) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="86">
              <template #default="{ row }">
                <el-tag size="small" :type="stType(row.status)">{{ stName(row.status) }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination small style="margin-top: 10px; justify-content: flex-end;"
            v-model:current-page="query.current" :page-size="10" :total="total"
            layout="prev, pager, next" @current-change="loadList" />
        </el-card>
      </el-col>

      <!-- ============ 右：创建表单 ============ -->
      <el-col :span="15">
        <el-card>
          <template #header>
            <div class="card-header">
              <span style="font-weight: bold;">{{ form.id ? '编辑 ' + (form.draftNo || '') : '新建报价' }}</span>
              <div>
                <el-button size="small" @click="runPrecheck">试算预检</el-button>
                <el-button size="small" type="primary" :loading="saving" @click="saveDraft">保存草稿</el-button>
              </div>
            </div>
          </template>

          <!-- 预检结果 -->
          <div v-if="check.errors.length || check.warnings.length" style="margin-bottom: 10px;">
            <el-alert v-for="(e, i) in check.errors" :key="'e' + i" type="error" :closable="false"
                      :title="e" style="margin-bottom: 6px;" show-icon />
            <el-alert v-for="(w, i) in check.warnings" :key="'w' + i" type="warning" :closable="false"
                      :title="w" style="margin-bottom: 6px;" show-icon />
          </div>

          <el-form :model="form" label-width="90px" size="small">
            <el-form-item label="商机" required>
              <el-select v-model="form.oppId" filterable remote :remote-method="searchOpps"
                         :loading="oppLoading" placeholder="搜索商机（须已到报价阶段）"
                         style="width: 100%;" @change="onOppChange">
                <el-option v-for="o in opps" :key="o.id"
                           :label="`${o.oppNo} ${o.oppName}（${o.customerName || ''}）`" :value="o.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="客户" required>
              <el-select v-model="form.customerId" disabled style="width: 100%;">
                <el-option :label="`${form.customerCode || ''} ${form.customerName || ''}`" :value="form.customerId" />
              </el-select>
              <div class="tip">由商机自动带出（不重复建档）；冻结/证照过期在试算时 L1 阻断</div>
            </el-form-item>
            <el-row :gutter="12">
              <el-col :span="8">
                <el-form-item label="单据类型">
                  <el-select v-model="form.orderType" style="width: 100%;">
                    <el-option label="标准单" value="STANDARD" />
                    <el-option label="样品单（豁免 MOQ）" value="SAMPLE" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="联系人">
                  <el-input v-model="form.contactName" maxlength="64" />
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="交货地址">
                  <el-input v-model="form.shipAddress" maxlength="255" placeholder="不完整仅 L4 提示" />
                </el-form-item>
              </el-col>
            </el-row>
          </el-form>

          <!-- 明细行 -->
          <div class="lines-head">
            <span style="font-weight: bold;">报价明细</span>
            <el-button size="small" :icon="Plus" @click="addLine">加行</el-button>
          </div>
          <el-table :data="lines" size="small" border>
            <el-table-column label="#" width="44" type="index" />
            <el-table-column label="SKU" min-width="170">
              <template #default="{ row }">
                <el-select v-model="row.itemCode" filterable remote :remote-method="k => searchItems(k, row)"
                           :loading="row.loading" placeholder="搜索物料" size="small" style="width: 100%;"
                           @change="v => onItemChange(v, row)">
                  <el-option v-for="it in row.options" :key="it.itemCode"
                             :label="`${it.itemCode} ${it.itemName}`" :value="it.itemCode" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="数量" width="120">
              <template #default="{ row }">
                <el-input-number v-model="row.qty" :min="0" :precision="3" size="small" style="width: 100%;" />
              </template>
            </el-table-column>
            <el-table-column label="MOQ" width="80">
              <template #default="{ row }">
                <span v-if="row.moqWarn" style="color: #f56c6c; font-weight: bold;">
                  {{ row.moq }} ↓
                </span>
                <span v-else>{{ row.moq == null ? '-' : row.moq }}</span>
              </template>
            </el-table-column>
            <el-table-column label="期望交期" width="140">
              <template #default="{ row }">
                <el-date-picker v-model="row.expectDeliveryDate" type="date" value-format="YYYY-MM-DD"
                                size="small" style="width: 100%;" />
              </template>
            </el-table-column>
            <el-table-column label="单价" width="96" align="right">
              <template #default="{ row }">{{ row.unitPrice == null ? '-' : row.unitPrice }}</template>
            </el-table-column>
            <el-table-column label="价源" width="90">
              <template #default="{ row }">
                <el-tag v-if="row.priceSource" size="small">{{ srcName(row.priceSource) }}</el-tag>
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column label="金额" width="100" align="right">
              <template #default="{ row }">{{ row.amount == null ? '-' : fmtAmt(row.amount) }}</template>
            </el-table-column>
            <el-table-column label="" width="56">
              <template #default="{ $index }">
                <el-button link type="danger" @click="lines.splice($index, 1)">删</el-button>
              </template>
            </el-table-column>
          </el-table>

          <!-- 汇总 -->
          <div class="summary" v-if="check.totalAmount != null">
            <span>报价总额：<b>¥ {{ fmtAmt(check.totalAmount) }}</b></span>
            <span style="margin-left: 16px;">标准成本：¥ {{ fmtAmt(check.totalCost) }}</span>
            <span style="margin-left: 16px;">
              毛利率：
              <b :style="{ color: check.marginLevel === 'NEGATIVE' ? '#f56c6c' : check.marginLevel === 'LOW' ? '#e6a23c' : '#67c23a' }">
                {{ pct(check.marginRate) }}
              </b>
              <el-tag v-if="check.marginLevel === 'LOW'" type="warning" size="small" style="margin-left: 6px;">低毛利待确认</el-tag>
              <el-tag v-if="check.marginLevel === 'NEGATIVE'" type="danger" size="small" style="margin-left: 6px;">负毛利锁定（双签）</el-tag>
            </span>
          </div>
          <!-- 价格协议匹配记录 -->
          <div v-if="matchRows.length" class="match">
            <div style="font-weight: bold; margin: 8px 0 4px;">《价格协议匹配记录》</div>
            <el-table :data="matchRows" size="small" border>
              <el-table-column prop="itemCode" label="SKU" width="160" />
              <el-table-column label="结果" width="90">
                <template #default="{ row }">
                  <el-tag :type="row.matched ? 'success' : 'danger'" size="small">{{ row.matched ? '命中' : '未命中' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="paCode" label="协议" width="120" />
              <el-table-column label="类型/挂靠" width="140">
                <template #default="{ row }">{{ srcName(row.agreementType) }} {{ row.attachLevel || '' }}</template>
              </el-table-column>
              <el-table-column label="未命中原因" min-width="200">
                <template #default="{ row }">{{ (row.reasons || []).join('；') || '-' }}</template>
              </el-table-column>
            </el-table>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import {
  getQuotesApi, getQuoteApi, getQuoteLinesApi, precheckQuoteApi, createQuoteApi, updateQuoteApi
} from '@/api/sd/quote'
import { getOpportunitiesApi } from '@/api/crm/opportunity'
import request from '@/utils/request'

const route = useRoute()

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = reactive({ current: 1, keyword: '' })

const form = reactive({
  id: '', oppId: '', customerId: '', customerCode: '', customerName: '',
  orderType: 'STANDARD', contactName: '', shipAddress: '', draftNo: ''
})
const lines = ref([])
const check = reactive({ errors: [], warnings: [], totalAmount: null, totalCost: null, marginRate: null, marginLevel: null })
const matchRows = ref([])
const saving = ref(false)

const opps = ref([])
const oppLoading = ref(false)

onMounted(async () => {
  await loadList(1)
  searchOpps('')
  // 从 3.1.3 一键创建报价带入
  if (route.query.oppId) {
    form.oppId = route.query.oppId
    form.customerId = route.query.customerId || ''
    form.customerCode = route.query.customerCode || ''
    form.customerName = route.query.customerName || ''
    if (route.query.oppNo) {
      opps.value = [{ id: route.query.oppId, oppNo: route.query.oppNo, oppName: route.query.oppName || '', customerName: form.customerName }]
    }
    if (route.query.demandSummary) {
      form.remark = '需求摘要：' + route.query.demandSummary
    }
  }
})

async function loadList(page) {
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

async function searchOpps(kw) {
  oppLoading.value = true
  try {
    const res = await getOpportunitiesApi({ current: 1, size: 20, keyword: kw || '', status: 'OPEN', stage: 'QUOTE' })
    opps.value = res.data.records || []
  } finally {
    oppLoading.value = false
  }
}

function onOppChange(id) {
  const o = opps.value.find(x => x.id === id)
  if (o) {
    form.customerId = o.customerId
    form.customerCode = o.customerCode
    form.customerName = o.customerName
    if (!form.contactName) form.contactName = ''
  }
}

async function searchItems(kw, row) {
  row.loading = true
  try {
    const res = await request.get('/mdm/items', { params: { current: 1, size: 20, keyword: kw || '', status: '1' } })
    row.options = res.data.records || []
  } finally {
    row.loading = false
  }
}

function onItemChange(code, row) {
  const it = (row.options || []).find(x => x.itemCode === code)
  if (it) {
    row.itemName = it.itemName
    row.moq = it.minOrderQty
    row.moqWarn = false
  }
}

function addLine() {
  lines.value.push({ itemCode: '', itemName: '', qty: 1, moq: null, moqWarn: false,
    expectDeliveryDate: '', unitPrice: null, priceSource: null, amount: null, options: [], loading: false })
}

function payload() {
  return {
    id: form.id || undefined,
    oppId: form.oppId,
    customerId: form.customerId,
    orderType: form.orderType,
    contactName: form.contactName,
    shipAddress: form.shipAddress,
    remark: form.remark,
    lines: lines.value.map((l, i) => ({
      lineNo: i + 1, itemCode: l.itemCode, qty: l.qty,
      expectDeliveryDate: l.expectDeliveryDate || null, specialPack: l.specialPack || null
    }))
  }
}

function applyCheck(d) {
  check.errors = d.errors || []
  check.warnings = d.warnings || []
  check.totalAmount = d.totalAmount
  check.totalCost = d.totalCost
  check.marginRate = d.marginRate
  check.marginLevel = d.marginLevel
  matchRows.value = Object.values(d.priceMatch || {})
  // 行内回填取价与 MOQ 告警
  const byNo = {}
  ;(d.pricingLines || []).forEach(pl => { byNo[pl.lineNo] = pl })
  lines.value.forEach((l, i) => {
    const pl = byNo[i + 1]
    if (pl) {
      l.unitPrice = pl.unitPrice
      l.priceSource = pl.priceSource
      l.amount = pl.amount
      l.moq = pl.moq
    }
    l.moqWarn = l.moq != null && l.qty != null && Number(l.qty) < Number(l.moq) && form.orderType !== 'SAMPLE'
  })
}

async function runPrecheck() {
  if (!form.oppId || !form.customerId) {
    ElMessage.warning('请先选择商机（客户自动带出）')
    return
  }
  if (!lines.value.length) {
    ElMessage.warning('至少一行明细')
    return
  }
  const res = await precheckQuoteApi(payload())
  applyCheck(res.data)
  if (res.data.ok) {
    ElMessage.success('预检通过，可保存草稿')
  } else {
    ElMessage.error(`预检未通过（${res.data.errors.length} 项）`)
  }
}

async function saveDraft() {
  if (!form.oppId || !form.customerId) {
    ElMessage.warning('请先选择商机')
    return
  }
  if (!lines.value.length) {
    ElMessage.warning('至少一行明细')
    return
  }
  saving.value = true
  try {
    let saved
    if (form.id) {
      const res = await updateQuoteApi(form.id, payload())
      saved = res.data
      ElMessage.success('草稿已更新（取价与毛利已重算）')
    } else {
      const res = await createQuoteApi(payload())
      saved = res.data
      ElMessage.success(`草稿已创建：${saved.draftNo}`)
      form.id = saved.id
      form.draftNo = saved.draftNo
    }
    loadList(1)
  } catch (e) {
    // L1 错误（无协议/MOQ/卡控）由 request 拦截器提示；刷新匹配记录
    if (form.oppId && lines.value.length) {
      try {
        const res = await precheckQuoteApi(payload())
        applyCheck(res.data)
      } catch (ignore) { /* 预检也失败则保留原提示 */ }
    }
    throw e
  } finally {
    saving.value = false
  }
}

async function selectQuote(row) {
  if (!row) return
  const res = await getQuoteApi(row.id)
  Object.assign(form, {
    id: res.data.id, draftNo: res.data.draftNo, oppId: res.data.oppId,
    customerId: res.data.customerId, customerCode: res.data.customerCode,
    customerName: res.data.customerName, orderType: res.data.orderType || 'STANDARD',
    contactName: res.data.contactName, shipAddress: res.data.shipAddress, remark: res.data.remark
  })
  const lr = await getQuoteLinesApi(row.id)
  lines.value = (lr.data || []).map(l => ({
    itemCode: l.itemCode, itemName: l.itemName, qty: l.qty, moq: l.moq,
    moqWarn: l.moq != null && l.qty != null && Number(l.qty) < Number(l.moq) && form.orderType !== 'SAMPLE',
    expectDeliveryDate: l.expectDeliveryDate, specialPack: l.specialPack,
    unitPrice: l.unitPrice, priceSource: l.priceSource, amount: l.amount, options: [], loading: false
  }))
  check.errors = []
  check.warnings = []
  check.totalAmount = res.data.totalAmount
  check.totalCost = res.data.totalCost
  check.marginRate = res.data.marginRate
  check.marginLevel = res.data.marginRate != null && res.data.marginRate < 0 ? 'NEGATIVE'
    : res.data.marginRate != null && res.data.marginRate < 0.05 ? 'LOW' : 'OK'
  matchRows.value = []
}

// ---------- 展示辅助 ----------
function stName(s) {
  return { DRAFT: '草稿', PENDING: '审批中', REJECTED: '已驳回', PUBLISHED: '已发布',
    SUPERSEDED: '旧版本', CONVERTED: '已转化' }[s] || s
}
function stType(s) {
  return { DRAFT: 'info', PENDING: 'warning', REJECTED: 'danger', PUBLISHED: 'success',
    SUPERSEDED: 'info', CONVERTED: 'primary' }[s] || 'info'
}
function srcName(s) {
  return { EXCLUSIVE: '客户专属', LADDER: '量价阶梯', TIME: '时间促销' }[s] || s || '-'
}
function pct(v) { return v == null ? '-' : (Number(v) * 100).toFixed(1) + '%' }
function marginColor(row) {
  if (row.marginRate == null) return ''
  if (row.marginRate < 0) return '#f56c6c'
  if (row.marginRate < 0.05) return '#e6a23c'
  return '#67c23a'
}
function fmtAmt(v) {
  return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
</script>

<style scoped>
.qc-page { padding: 12px; }
.card-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.lines-head { display: flex; justify-content: space-between; align-items: center; margin: 12px 0 6px; }
.summary { margin-top: 10px; font-size: 13px; color: #303133; }
.match { margin-top: 6px; }
.tip { color: #909399; font-size: 12px; line-height: 1.4; }
</style>
