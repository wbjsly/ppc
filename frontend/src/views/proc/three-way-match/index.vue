<template>
  <div class="tm-page">
    <el-alert type="info" :closable="false" style="margin-bottom: 12px;"
      title="三方匹配（2.7.2）：PO - 收货单 - 发票逐行比对（容差 TOLERANCE_DEFAULT = 0.5%）"
      description="容差内自动冲回暂估并生成正式应付凭证（借 应付暂估 + 采购价差 / 贷 应付账款）；超容差冻结生成异常对账单，采购员确认后过账价差；跨期（发票月份早于当前月）确认后仍须 ADMIN 手工过账。未关联 PO 的发票仅登记留存，不入匹配。" />

    <el-card shadow="never">
      <div class="toolbar">
        <el-select v-model="filters.status" placeholder="状态" clearable style="width: 150px;" @change="load">
          <el-option label="待匹配(DRAFT)" value="DRAFT" />
          <el-option label="匹配异常(EXCEPTION)" value="EXCEPTION" />
          <el-option label="已过账(POSTED)" value="POSTED" />
        </el-select>
        <el-select v-model="filters.supplierId" placeholder="供应商" clearable filterable
          style="width: 200px;" @change="load">
          <el-option v-for="s in suppliers" :key="s.id" :label="s.supplierName" :value="s.id" />
        </el-select>
        <el-input v-model="filters.keyword" placeholder="发票号" clearable style="width: 170px;"
          @keyup.enter="load" @clear="load" />
        <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD"
          start-placeholder="开票开始" end-placeholder="开票结束" style="width: 250px;" @change="load" />
        <el-button type="primary" @click="load">查询</el-button>
        <el-button type="success" @click="openCreate">登记发票</el-button>
      </div>

      <el-table :data="rows" size="small" border v-loading="loading" @expand-change="onExpand">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div style="padding: 8px 16px;" v-loading="row._loading">
              <h4 class="sec">发票行</h4>
              <el-table :data="(row._detail && row._detail.lines) || []" size="mini" border>
                <el-table-column prop="lineNo" label="#" width="45" />
                <el-table-column prop="poNo" label="PO" width="150" show-overflow-tooltip />
                <el-table-column prop="itemCode" label="物料" width="140" />
                <el-table-column prop="itemName" label="名称" min-width="120" show-overflow-tooltip />
                <el-table-column prop="qty" label="发票数量" width="100" align="right" />
                <el-table-column prop="unitPrice" label="发票单价" width="100" align="right" />
                <el-table-column prop="amount" label="金额" width="110" align="right" />
              </el-table>

              <template v-if="row._detail && row._detail.match">
                <h4 class="sec" style="margin-top: 12px;">
                  匹配结果 {{ row._detail.match.matchNo }}
                  <el-tag size="small" style="margin-left: 6px;" :type="matchTag(row._detail.match.status)">
                    {{ matchText(row._detail.match.status) }}
                  </el-tag>
                  <el-tag v-if="row._detail.match.periodCross === 1" size="small" type="danger"
                    style="margin-left: 6px;">跨会计期间</el-tag>
                </h4>
                <div style="font-size: 12px; color: #606266; margin-bottom: 6px;">
                  数量差异率 {{ row._detail.match.diffQtyRate }} ·
                  单价差异率 {{ row._detail.match.diffPriceRate }} ·
                  金额差异 {{ row._detail.match.diffAmount }}（{{ row._detail.match.diffAmountRate }}）·
                  容差 0.005
                  <template v-if="row._detail.match.confirmOpinion">
                    · 确认意见：{{ row._detail.match.confirmOpinion }}（{{ row._detail.match.confirmBy }}）
                  </template>
                </div>
                <el-table :data="diffRows(row._detail.match)" size="mini" border>
                  <el-table-column prop="lineNo" label="#" width="45" />
                  <el-table-column prop="itemCode" label="物料" width="140" />
                  <el-table-column prop="invoiceQty" label="发票数量" width="100" align="right" />
                  <el-table-column prop="postedQty" label="已过账量" width="100" align="right" />
                  <el-table-column prop="qtyRate" label="数量差率" width="100" align="right" />
                  <el-table-column prop="invoiceUnitPrice" label="发票单价" width="100" align="right" />
                  <el-table-column prop="poUnitPrice" label="PO 单价" width="100" align="right" />
                  <el-table-column prop="priceRate" label="单价差率" width="100" align="right" />
                  <el-table-column label="判定" width="120" align="center">
                    <template #default="scope">
                      <el-tag size="mini"
                        :type="(scope.row.qtyWithinTolerance && scope.row.priceWithinTolerance) ? 'success' : 'danger'">
                        {{ (scope.row.qtyWithinTolerance && scope.row.priceWithinTolerance) ? '容差内' : '超容差' }}
                      </el-tag>
                    </template>
                  </el-table-column>
                </el-table>
                <div v-if="row._detail.match.voucher" style="margin-top: 6px; font-size: 12px; color: #606266;">
                  凭证：<b>{{ row._detail.match.voucher.voucherNo }}</b>
                  （借 {{ row._detail.match.voucher.totalDr }} / 贷 {{ row._detail.match.voucher.totalCr }}）
                </div>
              </template>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="invoiceNo" label="发票号" width="170">
          <template #default="{ row }"><b style="color:#409EFF;">{{ row.invoiceNo }}</b></template>
        </el-table-column>
        <el-table-column prop="supplierName" label="供应商" min-width="140" show-overflow-tooltip />
        <el-table-column prop="invoiceDate" label="开票日期" width="110" />
        <el-table-column prop="totalAmount" label="金额" width="110" align="right" />
        <el-table-column prop="poNo" label="关联 PO" width="150" show-overflow-tooltip />
        <el-table-column label="发票状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="invTag(row.status)">{{ invText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="matchNo" label="匹配单" width="160" show-overflow-tooltip />
        <el-table-column label="差异率" width="100" align="right">
          <template #default="{ row }">
            <span :style="{ color: row.diffAmountRate > 0.005 ? '#F56C6C' : '#67C23A' }">
              {{ row.diffAmountRate == null ? '—' : row.diffAmountRate }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canOperate && row.status === 'DRAFT'" link type="primary" size="small"
              @click="doMatch(row)">执行匹配</el-button>
            <el-button v-if="canOperate && row.matchStatus === 'EXCEPTION'" link type="warning" size="small"
              @click="doConfirm(row)">确认价差</el-button>
            <el-button v-if="isAdmin && row.periodCross === 1 && row.matchStatus === 'CONFIRMED'"
              link type="danger" size="small" @click="doManualPost(row)">手工过账</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination style="margin-top: 10px; justify-content: flex-end;"
        layout="total, prev, pager, next" :total="total"
        v-model:current-page="filters.current" :page-size="filters.size" @current-change="load" />
    </el-card>

    <!-- 发票登记对话框 -->
    <el-dialog v-model="createVisible" title="登记供应商发票" width="860px" top="6vh">
      <el-form :inline="false" label-width="90px">
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="发票号" required>
              <el-input v-model="form.invoiceNo" placeholder="供应商发票号" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="供应商" required>
              <el-select v-model="form.supplierId" filterable placeholder="选择供应商" style="width: 100%;">
                <el-option v-for="s in suppliers" :key="s.id" :label="s.supplierName" :value="s.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="开票日期" required>
              <el-date-picker v-model="form.invoiceDate" type="date" value-format="YYYY-MM-DD"
                placeholder="选择日期" style="width: 100%;" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="16">
            <el-form-item label="关联 PO">
              <el-select v-model="form.poId" filterable placeholder="选择 PO（进入三单匹配须关联）"
                style="width: 100%;" @change="onPoChange">
                <el-option v-for="p in poCandidates" :key="p.id"
                  :label="`${p.poNo}（${p.supplierName}）`" :value="p.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="备注">
              <el-input v-model="form.remark" placeholder="选填" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <div class="toolbar" style="margin-bottom: 8px;">
        <el-button size="small" type="primary" plain @click="addLine" :disabled="!form.poId">添加行</el-button>
        <span style="font-size: 12px; color: #909399;">合计金额：<b>{{ lineTotal }}</b></span>
      </div>
      <el-table :data="form.lines" size="mini" border>
        <el-table-column label="PO 行" min-width="260">
          <template #default="{ row }">
            <el-select v-model="row.poLineId" filterable placeholder="选择 PO 行" size="small"
              style="width: 100%;" @change="v => onLineChange(row, v)">
              <el-option v-for="l in poLines" :key="l.id"
                :label="`${l.itemCode} ${l.itemName}｜单价 ${l.unitPrice}｜PO 量 ${l.qty}`"
                :value="l.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="数量" width="140">
          <template #default="{ row }">
            <el-input-number v-model="row.qty" :min="0.0001" :precision="4" size="small" style="width: 120px;" />
          </template>
        </el-table-column>
        <el-table-column label="单价" width="140">
          <template #default="{ row }">
            <el-input-number v-model="row.unitPrice" :min="0" :precision="4" size="small" style="width: 120px;" />
          </template>
        </el-table-column>
        <el-table-column label="金额" width="110" align="right">
          <template #default="{ row }">{{ lineAmount(row) }}</template>
        </el-table-column>
        <el-table-column label="" width="70" align="center">
          <template #default="{ row }">
            <el-button link type="danger" size="small" @click="removeLine(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreate">登记</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getInvoicePageApi, getInvoiceDetailApi, createInvoiceApi, matchInvoiceApi,
  confirmMatchApi, manualPostMatchApi
} from '@/api/fin/invoice'
import { getPoCandidatesApi, getPoLinesApi } from '@/api/proc/goods-receipt'
import { getSupplierPageApi } from '@/api/mdm/supplier-admission'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const roles = computed(() => (userStore.userInfo && userStore.userInfo.roles) || [])
const isAdmin = computed(() => roles.value.includes('ROLE_ADMIN'))
// 登记 / 匹配 / 确认 = ADMIN+PM（Q5 + SecurityConfig /api/fin/**）；手工过账仅 ADMIN
const canOperate = computed(() => isAdmin.value || roles.value.includes('ROLE_PM'))

const loading = ref(false)
const submitting = ref(false)
const rows = ref([])
const total = ref(0)
const suppliers = ref([])
const poCandidates = ref([])
const poLines = ref([])
const dateRange = ref([])
const filters = reactive({ status: '', supplierId: '', keyword: '', current: 1, size: 10 })

const createVisible = ref(false)
const form = reactive({ invoiceNo: '', supplierId: '', invoiceDate: '', poId: '', poNo: '', remark: '', lines: [] })
const lineTotal = computed(() =>
  form.lines.reduce((s, l) => s + Number(lineAmount(l) || 0), 0).toFixed(2))

const INV_TEXT = { DRAFT: '待匹配', MATCHED: '已匹配', EXCEPTION: '匹配异常', POSTED: '已过账' }
const MATCH_TEXT = {
  PENDING: '待判定', MATCHED: '匹配通过', EXCEPTION: '异常对账单',
  CONFIRMED: '已确认待过账', POSTED: '已过账', FAILED: '匹配失败'
}
const invText = s => INV_TEXT[s] || s
const invTag = s => ({ DRAFT: 'info', MATCHED: 'success', EXCEPTION: 'danger', POSTED: 'success' }[s] || 'info')
const matchText = s => MATCH_TEXT[s] || s
const matchTag = s => ({
  PENDING: 'info', MATCHED: 'success', EXCEPTION: 'danger',
  CONFIRMED: 'warning', POSTED: 'success', FAILED: 'info'
}[s] || 'info')

/** 差异明细：表头汇总行（head）与行级差异分离 */
function diffRows(match) {
  const d = match.detail
  if (!Array.isArray(d)) return []
  return d.filter(x => x && x.lineNo != null)
}

async function load() {
  loading.value = true
  try {
    const res = await getInvoicePageApi({
      current: filters.current, size: filters.size,
      status: filters.status || undefined,
      supplierId: filters.supplierId || undefined,
      keyword: filters.keyword || undefined,
      dateFrom: (dateRange.value && dateRange.value[0]) || undefined,
      dateTo: (dateRange.value && dateRange.value[1]) || undefined
    })
    rows.value = (res.data && res.data.records) || []
    total.value = (res.data && res.data.total) || 0
  } finally {
    loading.value = false
  }
}

async function onExpand(row) {
  if (row._detail || row._loading) return
  row._loading = true
  try {
    const res = await getInvoiceDetailApi(row.id)
    row._detail = res.data || {}
  } finally {
    row._loading = false
  }
}

async function ensureMatchId(row) {
  if (row._detail && row._detail.match) return row._detail.match.id
  const res = await getInvoiceDetailApi(row.id)
  row._detail = res.data || {}
  return row._detail.match && row._detail.match.id
}

async function doMatch(row) {
  try {
    const res = await matchInvoiceApi(row.id)
    const m = res.data || {}
    ElMessage.success(m.status === 'MATCHED'
      ? `匹配通过（${m.matchNo}）：暂估已自动冲回并生成正式应付凭证`
      : `匹配异常（${m.matchNo}）：已冻结自动核销，待采购员确认价差`)
    await load()
  } catch (e) { /* 拦截器已提示（无收货 422 等） */ }
}

async function doConfirm(row) {
  try {
    const { value } = await ElMessageBox.prompt(
      '请填写价差原因/确认意见（确认后非跨期自动过账至采购价差科目）',
      '确认价差', { inputPlaceholder: '如：发票含运费按合同价开票，差异 1.2% 可接受', inputType: 'textarea' })
    if (!value || !value.trim()) { ElMessage.warning('必须填写确认意见'); return }
    const matchId = await ensureMatchId(row)
    const res = await confirmMatchApi(matchId, value.trim())
    const m = res.data || {}
    ElMessage.success(m.status === 'POSTED'
      ? `已确认并过账（凭证 ${m.voucherId ? '已生成' : ''}）`
      : '已确认：跨会计期间，待 ADMIN 手工过账')
    await load()
  } catch (e) { /* 取消或拦截器提示 */ }
}

async function doManualPost(row) {
  try {
    const { value } = await ElMessageBox.prompt(
      '跨期价差手工过账：将在当期生成价差调整凭证，请注明跨期原因',
      '跨期手工过账', { inputPlaceholder: '如：9 月发票跨月到票，价差 2% 按当期调整入账', inputType: 'textarea' })
    const matchId = await ensureMatchId(row)
    const res = await manualPostMatchApi(matchId, (value || '').trim())
    ElMessage.success(`手工过账完成（凭证 ${res.data && res.data.voucherId ? '已生成' : ''}）`)
    await load()
  } catch (e) { /* 取消或拦截器提示 */ }
}

// ---------------- 发票登记 ----------------

function openCreate() {
  form.invoiceNo = ''
  form.supplierId = ''
  form.invoiceDate = ''
  form.poId = ''
  form.poNo = ''
  form.remark = ''
  form.lines = []
  poLines.value = []
  createVisible.value = true
}

async function onPoChange(poId) {
  const po = poCandidates.value.find(p => p.id === poId)
  form.poNo = po ? po.poNo : ''
  if (po && !form.supplierId) form.supplierId = po.supplierId
  form.lines = []
  if (!poId) { poLines.value = []; return }
  const { data } = await getPoLinesApi(poId)
  poLines.value = data.lines || []
}

function addLine() {
  form.lines.push({ poLineId: '', itemCode: '', itemName: '', unit: '', qty: 1, unitPrice: 0 })
}

function removeLine(row) {
  const i = form.lines.indexOf(row)
  if (i >= 0) form.lines.splice(i, 1)
}

function onLineChange(row, poLineId) {
  const l = poLines.value.find(x => x.id === poLineId)
  if (!l) return
  row.itemCode = l.itemCode
  row.itemName = l.itemName
  row.unit = l.unit
  row.unitPrice = Number(l.unitPrice)
}

function lineAmount(l) {
  return (Number(l.qty || 0) * Number(l.unitPrice || 0)).toFixed(2)
}

async function submitCreate() {
  if (!form.invoiceNo.trim()) { ElMessage.warning('发票号必填'); return }
  if (!form.supplierId) { ElMessage.warning('供应商必填'); return }
  if (!form.invoiceDate) { ElMessage.warning('开票日期必填'); return }
  if (form.lines.some(l => !l.poLineId)) { ElMessage.warning('发票行须选择 PO 行'); return }
  submitting.value = true
  try {
    const supplier = suppliers.value.find(s => s.id === form.supplierId)
    await createInvoiceApi({
      invoiceNo: form.invoiceNo.trim(),
      supplierId: form.supplierId,
      supplierName: supplier ? supplier.supplierName : '',
      invoiceDate: form.invoiceDate,
      poNo: form.poNo || undefined,
      remark: form.remark || undefined,
      lines: form.lines.map((l, i) => ({
        lineNo: i + 1, poId: form.poId, poNo: form.poNo, poLineId: l.poLineId,
        itemCode: l.itemCode, itemName: l.itemName, unit: l.unit,
        qty: l.qty, unitPrice: l.unitPrice, amount: Number(lineAmount(l))
      }))
    })
    ElMessage.success('发票登记成功（状态：待匹配）')
    createVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  await load()
  const [{ data: sups }, { data: pos }] = await Promise.all([
    getSupplierPageApi({ current: 1, size: 200 }).catch(() => ({ data: [] })),
    getPoCandidatesApi().catch(() => ({ data: {} }))
  ])
  suppliers.value = sups.records || sups || []
  poCandidates.value = (pos && pos.records) || []
})
</script>

<style scoped>
.tm-page { padding: 4px; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.sec { margin: 4px 0 8px; font-size: 13px; color: #303133; }
</style>
