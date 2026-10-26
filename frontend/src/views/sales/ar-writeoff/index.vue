<template>
  <div class="page-wrap">
    <el-alert type="info" :closable="false" show-icon class="mb12"
              title="3.8.2 自动核销"
              description="客户回款经统一收付款中心（M8-4-1）过账：借 银行存款 / 贷 应收账款（FIFO 核销合计）/ 贷 预收账款 2205（差额）；核销仅记录匹配关系不再单独产凭证（add-payment-collection MODIFIED FR-4.3-7-5）。收付款执行与回单对账请前往 财务管理→收付管理。" />

    <el-row :gutter="12" class="mb12">
      <el-col :span="8">
        <el-card shadow="never">
          <template #header><b>回款登记（FIFO 自动核销）</b></template>
          <el-form label-width="90px" size="small">
            <el-form-item label="客户">
              <el-select v-model="receiptForm.customerId" filterable placeholder="选择客户"
                         style="width:100%">
                <el-option v-for="c in customers" :key="c.id"
                           :label="`${c.customerCode} ${c.customerName}`" :value="c.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="到账金额">
              <el-input-number v-model="receiptForm.amount" :min="0" :precision="2"
                               style="width:100%" />
            </el-form-item>
            <el-form-item label="到账日期">
              <el-date-picker v-model="receiptForm.payDate" type="date" value-format="YYYY-MM-DD"
                              style="width:100%" />
            </el-form-item>
            <el-form-item label="备注">
              <el-input v-model="receiptForm.remark" placeholder="可空" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="submitting" style="width:100%"
                         @click="doReceipt">登记回款并自动核销</el-button>
            </el-form-item>
          </el-form>
          <p class="hint">匹配结果：核销合计与差额见上方结果；跨期核销自动挂财务主管审批（C-4.6-02）。</p>
        </el-card>
      </el-col>

      <el-col :span="16">
        <el-card shadow="never" style="height:100%">
          <template #header>
            <div class="card-head">
              <b>最近核销结果</b>
              <el-button size="small" @click="loadResult">刷新</el-button>
            </div>
          </template>
          <el-empty v-if="!lastResult" description="尚未登记回款" :image-size="70" />
          <template v-else>
            <el-descriptions :column="4" size="small" border class="mb12">
              <el-descriptions-item label="回款单">
                {{ lastResult.receipt.docNo }}
              </el-descriptions-item>
              <el-descriptions-item label="金额">¥{{ fmt(lastResult.receipt.amount) }}</el-descriptions-item>
              <el-descriptions-item label="凭证号">{{ lastResult.voucherNo || "—" }}</el-descriptions-item>
              <el-descriptions-item label="待匹配">
                <span :style="{ color: Number(lastResult.unmatched) > 0 ? '#f56c6c' : '#67c23a' }">
                  ¥{{ fmt(lastResult.unmatched) }}
                </span>
              </el-descriptions-item>
            </el-descriptions>
            <el-alert v-if="lastResult.manualHint" type="warning" :closable="false" show-icon
                      class="mb12" :title="lastResult.manualHint" />
            <el-table :data="lastResult.details" size="small" border>
              <el-table-column prop="arNo" label="核销应收" width="180" />
              <el-table-column label="本次核销" width="130" align="right">
                <template #default="{ row }">¥{{ fmt(row.amount) }}</template>
              </el-table-column>
              <el-table-column label="核销后余额" align="right">
                <template #default="{ row }">¥{{ fmt(row.balance) }}</template>
              </el-table-column>
            </el-table>
          </template>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="mb12">
      <template #header>
        <div class="card-head">
          <b>人工核销队列（无法自动匹配的回款）</b>
          <div>
            <el-select v-model="receiptStatus" size="small" style="width:150px" @change="loadReceipts">
              <el-option label="待过账 PENDING_POST" value="PENDING_POST" />
              <el-option label="已过账 POSTED" value="POSTED" />
              <el-option label="已对账 RECONCILED" value="RECONCILED" />
              <el-option label="全部" value="" />
            </el-select>
            <el-button size="small" style="margin-left:8px" @click="loadReceipts">刷新</el-button>
          </div>
        </div>
      </template>
      <el-table :data="receipts" size="small" border v-loading="loading">
        <el-table-column prop="docNo" label="收款单号" width="165" />
        <el-table-column prop="partyName" label="客户" min-width="150" show-overflow-tooltip />
        <el-table-column label="金额" width="110" align="right">
          <template #default="{ row }">¥{{ fmt(row.amount) }}</template>
        </el-table-column>
        <el-table-column prop="writeoffState" label="核销状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.writeoffState === 'DONE' ? 'success' : 'info'">
              {{ row.writeoffState }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="payDate" label="到账日" width="110" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="rcptTag(row.status)">{{ rcptName(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-button v-if="row.status === 'POSTED' && row.writeoffState !== 'DONE'"
                       size="small" type="primary" @click="openManual(row)">人工核销</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <b>核销明细（含匹配明细与核销凭证）</b>
          <div>
            <el-input v-model="woKeyword" size="small" style="width:180px"
                      placeholder="核销单/应收/回款单" clearable @keyup.enter="loadWriteoffs" />
            <el-button size="small" style="margin-left:8px" @click="loadWriteoffs">查询</el-button>
          </div>
        </div>
      </template>
      <el-table :data="writeoffs" size="small" border>
        <el-table-column prop="woNo" label="核销单号" width="165" />
        <el-table-column prop="targetNo" label="应收单" width="165" />
        <el-table-column prop="docNo" label="收款单" width="165">
          <template #default="{ row }">{{ row.docNo || '—' }}</template>
        </el-table-column>
        <el-table-column label="核销金额" width="110" align="right">
          <template #default="{ row }">¥{{ fmt(row.amount) }}</template>
        </el-table-column>
        <el-table-column prop="writeoffDate" label="核销日" width="110" />
        <el-table-column prop="dueDate" label="到期日" width="110" />
        <el-table-column label="及时" width="70">
          <template #default="{ row }">
            <el-tag size="small" :type="row.onTime === '1' ? 'success' : 'danger'">
              {{ row.onTime === '1' ? '按时' : '逾期' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="方式" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.writeoffMode === 'AUTO' ? 'success' : 'info'">
              {{ row.writeoffMode === 'AUTO' ? '自动FIFO' : row.writeoffMode }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="150" show-overflow-tooltip />
      </el-table>
    </el-card>

    <!-- 人工核销 -->
    <el-dialog v-model="manualDialog" :title="`人工核销 ${currentReceipt?.rcptNo}`" width="560px">
      <el-alert type="warning" :closable="false" show-icon class="mb12"
                :title="`待匹配余额 ¥${fmt(currentReceipt?.unmatchedAmt)}，请选择应收逐笔核销`" />
      <el-form label-width="90px" size="small">
        <el-form-item label="应收单">
          <el-select v-model="manualForm.arId" filterable placeholder="选择未清应收"
                     style="width:100%" @change="onArChange">
            <el-option v-for="a in openArs" :key="a.id"
                       :label="`${a.arNo}  余额 ¥${fmt(balance(a))}`" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="核销金额">
          <el-input-number v-model="manualForm.amount" :min="0" :precision="2"
                           style="width:220px" />
        </el-form-item>
        <el-form-item label="付款日期">
          <el-date-picker v-model="manualForm.payDate" type="date" value-format="YYYY-MM-DD"
                          style="width:220px" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="manualForm.remark" placeholder="人工核销说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="manualDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="doManual">执行核销</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  registerReceiptApi, manualWriteoffApi, getReceiptPageApi,
  getWriteoffPageApi, getArPageApi
} from '@/api/fin/ar'
import { getGroupPageApi } from '@/api/mdm/customer'

const receipts = ref([])
const writeoffs = ref([])
const customers = ref([])
const openArs = ref([])
const lastResult = ref(null)
const loading = ref(false)
const submitting = ref(false)
const receiptStatus = ref('PENDING')
const woKeyword = ref('')
const manualDialog = ref(false)
const currentReceipt = ref(null)
const receiptForm = ref({ customerId: '', amount: 0, payDate: '', remark: '' })
const manualForm = ref({ arId: '', amount: 0, payDate: '', remark: '' })

function fmt(v) { return Number(v || 0).toFixed(2) }
function balance(a) {
  return Number(a.amount || 0) - Number(a.redAmount || 0) - Number(a.paidAmount || 0)
}
function rcptName(s) {
  return { PENDING_POST: '待过账', POSTED: '已过账',
    RECONCILED: '已对账', VOID: '已作废' }[s] || s
}
function rcptTag(s) {
  return { PENDING_POST: 'warning', POSTED: 'success',
    RECONCILED: 'info', VOID: 'danger' }[s] || ''
}

async function loadCustomers() {
  try {
    const res = await getGroupPageApi({ current: 1, size: 200 })
    customers.value = res.data.records || res.data || []
  } catch (e) { /* 客户接口失败不阻断 */ }
}

async function loadReceipts() {
  loading.value = true
  try {
    const res = await getReceiptPageApi({
      current: 1, size: 50, status: receiptStatus.value || undefined
    })
    receipts.value = res.data.records || []
  } finally {
    loading.value = false
  }
}

async function loadWriteoffs() {
  const res = await getWriteoffPageApi({
    current: 1, size: 50, keyword: woKeyword.value || undefined
  })
  writeoffs.value = res.data.records || []
}

function loadResult() { loadWriteoffs() }

async function doReceipt() {
  if (!receiptForm.value.customerId) {
    ElMessage.warning('请选择客户')
    return
  }
  submitting.value = true
  try {
    const res = await registerReceiptApi(receiptForm.value)
    lastResult.value = res.data
    ElMessage.success(`收款单 ${res.data.receipt.docNo} 已过账，核销 ¥${fmt(res.data.unmatched !== undefined ? Number(res.data.receipt.amount) - Number(res.data.unmatched) : 0)}`)
    receiptForm.value = { customerId: '', amount: 0, payDate: '', remark: '' }
    await Promise.all([loadReceipts(), loadWriteoffs()])
  } catch (e) {
    ElMessage.error(e?.message || '登记失败')
  } finally {
    submitting.value = false
  }
}

async function openManual(row) {
  currentReceipt.value = row
  manualForm.value = { arId: '', amount: 0, payDate: row.payDate, remark: '' }
  const res = await getArPageApi({
    current: 1, size: 100, customerId: row.partyId, status: 'UNPAID'
  })
  const res2 = await getArPageApi({
    current: 1, size: 100, customerId: row.partyId, status: 'PARTIAL'
  })
  openArs.value = [...(res.data.records || []), ...(res2.data.records || [])]
    .filter(a => balance(a) > 0)
  manualDialog.value = true
}

function onArChange(id) {
  const a = openArs.value.find(x => x.id === id)
  if (a) {
    manualForm.value.amount = Math.min(Number(manualForm.value.amount) || 0, balance(a))
  }
}

async function doManual() {
  if (!manualForm.value.arId) {
    ElMessage.warning('请选择应收单')
    return
  }
  submitting.value = true
  try {
    await manualWriteoffApi({
      receiptId: currentReceipt.value.id,
      ...manualForm.value
    })
    ElMessage.success('人工核销完成（核销记录已落统一核销表）')
    manualDialog.value = false
    await Promise.all([loadReceipts(), loadWriteoffs()])
  } catch (e) {
    ElMessage.error(e?.message || '核销失败')
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadCustomers()
  loadReceipts()
  loadWriteoffs()
})
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; font-weight: bold; }
.hint { color: #909399; font-size: 12px; margin-top: 8px; }
.mb12 { margin-bottom: 12px; }
.muted { color: #909399; }
</style>
