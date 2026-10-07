<template>
  <div class="page-wrap">
    <el-alert type="info" :closable="false" show-icon class="mb12"
              title="3.5.1 订单创建"
              description="单价由价格协议自动带出（客户专属 > 量价阶梯 > 时间促销），无协议阻断建单；付款条件与客户协议不一致须销售主管确认；计划发货晚于客户期望交期自动标记「交期待确认」。" />

    <el-card shadow="never" class="mb12">
      <template #header>
        <div class="card-head">
          <span>订单头</span>
          <div>
            <el-button size="small" @click="resetForm">清空</el-button>
            <el-button size="small" type="primary" @click="saveDraft">保存草稿</el-button>
            <el-button size="small" type="success" @click="saveAndSubmit">保存并提交审批</el-button>
          </div>
        </div>
      </template>

      <el-form :model="form" label-width="110px" size="small">
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="客户" required>
              <el-select v-model="form.customerId" filterable placeholder="选择客户"
                         style="width:100%" @change="onCustomerChange">
                <el-option v-for="c in customers" :key="c.id"
                           :label="`${c.customerCode} ${c.customerName}`" :value="c.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="订单类型">
              <el-select v-model="form.orderType" style="width:100%">
                <el-option label="标准" value="STANDARD" />
                <el-option label="寄售" value="CONSIGN" />
                <el-option label="样品（豁免 MOQ）" value="SAMPLE" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="付款条件">
              <el-input v-model="form.paymentTerms" placeholder="如 NET30 / 月结30天" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="贸易条款">
              <el-input v-model="form.tradeTerms" placeholder="如 FOB / CIF" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="关联商机">
              <el-select v-model="form.oppId" clearable filterable placeholder="可选"
                         style="width:100%">
                <el-option v-for="o in opps" :key="o.id" :label="o.oppNo + ' ' + o.oppName"
                           :value="o.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="备注">
              <el-input v-model="form.remark" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <!-- 付款条件差异提示（BR-4.3-25） -->
      <el-alert v-if="payDiff && payDiff.differs" type="warning" :closable="false" show-icon>
        <template #title>
          付款条件差异：订单「{{ payDiff.actual }}」 vs 客户协议「{{ payDiff.expected }}」
          <el-button size="small" type="primary" link @click="confirmPayDialog = true">
            提交销售主管确认
          </el-button>
        </template>
      </el-alert>
    </el-card>

    <el-card shadow="never" class="mb12">
      <template #header>
        <div class="card-head">
          <span>订单行（价格协议自动带出）</span>
          <el-button size="small" @click="addLine">添加行</el-button>
        </div>
      </template>

      <el-table :data="lines" size="small" border>
        <el-table-column type="index" label="#" width="45" />
        <el-table-column label="SKU" width="170">
          <template #default="{ row }">
            <el-select v-model="row.itemCode" filterable style="width:100%"
                       placeholder="选择物料" @change="v => onItemChange(row, v)">
              <el-option v-for="it in items" :key="it.itemCode"
                         :label="`${it.itemCode} ${it.itemName}`" :value="it.itemCode" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column prop="itemName" label="名称" min-width="120" show-overflow-tooltip />
        <el-table-column label="数量" width="110">
          <template #default="{ row }">
            <el-input-number v-model="row.qty" :min="1" :precision="3" size="small"
                             controls-position="right" style="width:100%"
                             @change="() => priceLine(row)" />
          </template>
        </el-table-column>
        <el-table-column label="协议单价" width="100">
          <template #default="{ row }">
            <span :class="{ 'price-none': row.unitPrice == null }">
              {{ row.unitPrice != null ? '¥' + row.unitPrice : (row.priceError ? '无协议' : '—') }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="价源" width="86">
          <template #default="{ row }">
            <el-tag v-if="row.priceSource" size="small" type="info">{{ srcName(row.priceSource) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="paCode" label="协议号" width="130" show-overflow-tooltip />
        <el-table-column label="行金额" width="110" align="right">
          <template #default="{ row }">
            {{ row.unitPrice != null ? '¥' + (row.qty * row.unitPrice).toFixed(2) : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="计划发货" width="140">
          <template #default="{ row }">
            <el-date-picker v-model="row.planShipDate" type="date" value-format="YYYY-MM-DD"
                            size="small" style="width:130px" />
          </template>
        </el-table-column>
        <el-table-column label="客户期望交期" width="140">
          <template #default="{ row }">
            <el-date-picker v-model="row.customerExpectDate" type="date" value-format="YYYY-MM-DD"
                            size="small" style="width:130px" />
          </template>
        </el-table-column>
        <el-table-column label="交期" width="92">
          <template #default="{ row }">
            <el-tag v-if="deliveryPending(row)" size="small" type="warning">交期待确认</el-tag>
            <el-tag v-else size="small" type="success">正常</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="仓库" width="120">
          <template #default="{ row }">
            <el-select v-model="row.warehouseCode" size="small" style="width:100%">
              <el-option v-for="w in warehouses" :key="w.whCode"
                         :label="`${w.whCode} ${w.whName}`" :value="w.whCode" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="70">
          <template #default="{ $index }">
            <el-button size="small" type="danger" link @click="lines.splice($index, 1)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="sum-bar">
        <span>合计金额：<b>¥{{ totalAmount.toFixed(2) }}</b></span>
        <span v-if="matchedCount < lines.filter(l => l.itemCode).length" class="warn-text">
          ⚠ 有行未取到价格协议，无法建单（BR-4.3-27）
        </span>
      </div>
    </el-card>

    <!-- 付款条件主管确认对话框（BR-4.3-25） -->
    <el-dialog v-model="confirmPayDialog" title="付款条件差异 · 销售主管确认" width="480px">
      <el-alert type="warning" :closable="false" class="mb12"
                :title="`订单「${form.paymentTerms}」 vs 客户协议「${payDiff?.expected}」`"
                description="确认后将记录确认人与原因，订单方可提交审批。" />
      <el-input v-model="payConfirmReason" type="textarea" :rows="3"
                placeholder="确认原因（必填，如：客户要求账期放宽至 60 天，已评估信用风险）" />
      <template #footer>
        <el-button @click="confirmPayDialog = false">取消</el-button>
        <el-button type="primary" :disabled="!payConfirmReason"
                   @click="doConfirmPay">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  createSoApi, submitSoApi, confirmPaymentTermsApi, getSoDetailApi
} from '@/api/sd/so'
import { getGroupPageApi } from '@/api/mdm/customer'
import { getItemPageApi } from '@/api/mdm/item'
import { getWarehousesApi } from '@/api/inv/warehouse'
import { getOpportunitiesApi } from '@/api/crm/opportunity'
import { trialApi } from '@/api/mdm/cross-domain-sharing'

const form = ref({
  customerId: '', orderType: 'STANDARD', paymentTerms: '',
  tradeTerms: '', oppId: '', remark: ''
})
const lines = ref([])
const customers = ref([])
const items = ref([])
const warehouses = ref([])
const opps = ref([])
const payDiff = ref(null)
const confirmPayDialog = ref(false)
const payConfirmReason = ref('')
let savedSoId = ''

const totalAmount = computed(() =>
  lines.value.reduce((s, l) =>
    s + (l.unitPrice != null && l.qty ? l.qty * l.unitPrice : 0), 0))
const matchedCount = computed(() =>
  lines.value.filter(l => l.itemCode && l.unitPrice != null).length)

function addLine() {
  lines.value.push({
    itemCode: '', itemName: '', qty: 1, unitPrice: null, priceSource: '',
    paCode: '', priceError: '', warehouseCode: 'WH-MAIN',
    planShipDate: '', customerExpectDate: ''
  })
}

function deliveryPending(row) {
  return !!(row.planShipDate && row.customerExpectDate && row.planShipDate > row.customerExpectDate)
}

const srcName = s => ({ EXCLUSIVE: '专属价', LADDER: '阶梯价', TIME: '促销价' }[s] || s)

async function onItemChange(row) {
  const it = items.value.find(i => i.itemCode === row.itemCode)
  row.itemName = it ? it.itemName : ''
  await priceLine(row)
}

/** 取价（7.3：无协议行标红，建单被后端阻断） */
async function priceLine(row) {
  if (!row.itemCode || !form.value.customerId) return
  row.unitPrice = null
  row.priceError = ''
  try {
    const res = await trialApi({
      groupId: form.value.customerId, itemCode: row.itemCode, qty: row.qty || 1
    })
    const d = res.data || {}
    if (d.matched) {
      row.unitPrice = d.unitPrice
      row.priceSource = d.agreementType
      row.paCode = d.paCode
    } else {
      row.priceError = (d.reasons || []).join(';') || '无生效协议'
      ElMessage.warning(`${row.itemCode} 无生效价格协议：${row.priceError}`)
    }
  } catch (e) {
    row.priceError = e?.message || '取价失败'
  }
}

function onCustomerChange() {
  payDiff.value = null
  lines.value.forEach(l => priceLine(l))
}

function payload() {
  return {
    ...form.value,
    lines: lines.value.filter(l => l.itemCode).map(l => ({
      itemCode: l.itemCode, qty: l.qty, warehouseCode: l.warehouseCode,
      planShipDate: l.planShipDate || null,
      customerExpectDate: l.customerExpectDate || null
    }))
  }
}

async function saveDraft() {
  if (!form.value.customerId) return ElMessage.error('请选择客户')
  if (!lines.value.filter(l => l.itemCode).length) return ElMessage.error('请添加订单行')
  try {
    const res = await createSoApi(payload())
    savedSoId = res.data.so.id
    payDiff.value = res.data.payTermsDiff
    ElMessage.success(`草稿已保存：${res.data.so.soNo}`)
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  }
}

async function saveAndSubmit() {
  if (!form.value.customerId) return ElMessage.error('请选择客户')
  try {
    let soId = savedSoId
    if (!soId) {
      const res = await createSoApi(payload())
      soId = res.data.so.id
      payDiff.value = res.data.payTermsDiff
      savedSoId = soId
    }
    const sub = await submitSoApi(soId)
    const mode = sub.data.mode
    if (mode === 'AUTO') {
      ElMessage.success('小额高毛利自动确认（免审），订单已确认并生成批次预留')
    } else {
      ElMessage.success(`已提交审批（${sub.data.basis}，${sub.data.chain.length} 段节点）`)
    }
    await refreshDetail(soId)
  } catch (e) {
    ElMessage.error(e?.message || '提交失败')
  }
}

async function refreshDetail(soId) {
  try {
    const res = await getSoDetailApi(soId)
    payDiff.value = res.data.payTermsDiff
  } catch (e) { /* 忽略刷新失败 */ }
}

async function doConfirmPay() {
  try {
    await confirmPaymentTermsApi(savedSoId, payConfirmReason.value)
    ElMessage.success('已记录销售主管确认')
    confirmPayDialog.value = false
    await refreshDetail(savedSoId)
  } catch (e) {
    ElMessage.error(e?.message || '确认失败')
  }
}

function resetForm() {
  form.value = { customerId: '', orderType: 'STANDARD', paymentTerms: '', tradeTerms: '', oppId: '', remark: '' }
  lines.value = []
  payDiff.value = null
  savedSoId = ''
}

onMounted(async () => {
  addLine()
  try {
    const [c, i, w, o] = await Promise.all([
      getGroupPageApi({ current: 1, size: 200 }),
      getItemPageApi({ current: 1, size: 300 }),
      getWarehousesApi(),
      getOpportunitiesApi({ current: 1, size: 50, status: 'OPEN' })
    ])
    customers.value = c.data.records || c.data || []
    items.value = i.data.records || i.data || []
    warehouses.value = w.data || []
    opps.value = o.data.records || o.data || []
  } catch (e) {
    console.error('load options failed', e)
  }
})
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; }
.mb12 { margin-bottom: 12px; }
.sum-bar { display: flex; gap: 24px; justify-content: flex-end; padding-top: 10px; font-size: 13px; }
.price-none { color: #f56c6c; }
.warn-text { color: #e6a23c; }
</style>
