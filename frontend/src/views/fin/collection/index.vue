<template>
  <div class="page collection-page">
    <el-tabs v-model="tab">
      <!-- ================= 8.4.1 收款执行 ================= -->
      <el-tab-pane label="收款执行" name="receive">
        <el-form inline>
          <el-input v-model="inQuery.keyword" clearable placeholder="单号/客户" style="width:150px" />
          <el-select v-model="inQuery.status" clearable placeholder="状态" style="width:130px;margin-left:8px">
            <el-option label="待过账" value="PENDING_POST" />
            <el-option label="已过账" value="POSTED" />
            <el-option label="已对账" value="RECONCILED" />
            <el-option label="已作废" value="VOID" />
          </el-select>
          <el-button type="primary" @click="loadInDocs" style="margin-left:8px">查询</el-button>
          <el-button type="success" @click="inDialogVisible = true">录入收款单</el-button>
        </el-form>
        <el-table :data="inRows" border size="small">
          <el-table-column prop="docNo" label="收款单号" width="160" />
          <el-table-column prop="partyName" label="客户" min-width="140" show-overflow-tooltip />
          <el-table-column prop="amount" label="金额" width="110" align="right" />
          <el-table-column prop="payMethod" label="方式" width="90" />
          <el-table-column prop="payDate" label="收款日期" width="110" />
          <el-table-column prop="status" label="状态" width="100" />
          <el-table-column prop="writeoffState" label="核销状态" width="100" />
          <el-table-column label="跨期" width="70" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.crossPeriod === '1'" type="warning" size="small">跨期</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="onDocDetail(row)">详情</el-button>
              <el-button v-if="row.status === 'PENDING_POST'" link type="success" @click="onPost(row)">过账</el-button>
              <el-button v-if="row.status === 'PENDING_POST'" link type="danger" @click="onVoid(row)">作废</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-model:current-page="inQuery.current" v-model:page-size="inQuery.size"
          :total="inTotal" layout="total, prev, pager, next" @change="loadInDocs"
          style="margin-top:8px;justify-content:flex-end" />
        <el-alert type="info" :closable="false" show-icon style="margin-top:8px"
          title="收款过账：借 银行存款 / 贷 应收账款（FIFO 核销合计）/ 贷 预收账款 2205（差额）；跨期核销自动挂财务主管审批（C-4.6-02）。" />

        <el-dialog v-model="inDialogVisible" title="录入收款单" width="520">
          <el-form :model="inForm" label-width="90px">
            <el-form-item label="客户 ID" required>
              <el-input v-model="inForm.partyId" placeholder="客户主数据 ID" />
            </el-form-item>
            <el-form-item label="客户名称">
              <el-input v-model="inForm.partyName" />
            </el-form-item>
            <el-form-item label="金额" required>
              <el-input-number v-model="inForm.amount" :min="0.01" :precision="2" :step="100" />
            </el-form-item>
            <el-form-item label="支付方式">
              <el-select v-model="inForm.payMethod">
                <el-option v-for="m in payMethods" :key="m" :label="m" :value="m" />
              </el-select>
            </el-form-item>
            <el-form-item label="银行账户" required>
              <el-input v-model="inForm.bankAccountId" placeholder="银行账户 ID" />
            </el-form-item>
            <el-form-item label="收款日期" required>
              <el-date-picker v-model="inForm.payDate" type="date" value-format="YYYY-MM-DD" />
            </el-form-item>
            <el-form-item label="备注">
              <el-input v-model="inForm.remark" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="inDialogVisible = false">取消</el-button>
            <el-button type="primary" @click="submitInDoc">创建（待过账）</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>

      <!-- ================= 8.4.2 付款执行 ================= -->
      <el-tab-pane label="付款执行" name="pay">
        <el-form inline>
          <el-input v-model="outQuery.keyword" clearable placeholder="单号/供应商" style="width:150px" />
          <el-select v-model="outQuery.status" clearable placeholder="状态" style="width:130px;margin-left:8px">
            <el-option label="待过账" value="PENDING_POST" />
            <el-option label="已过账" value="POSTED" />
            <el-option label="已对账" value="RECONCILED" />
            <el-option label="已作废" value="VOID" />
          </el-select>
          <el-button type="primary" @click="loadOutDocs" style="margin-left:8px">查询</el-button>
          <el-button type="success" @click="outDialogVisible = true">录入付款单</el-button>
        </el-form>
        <el-table :data="outRows" border size="small">
          <el-table-column prop="docNo" label="付款单号" width="160" />
          <el-table-column prop="partyName" label="供应商" min-width="140" show-overflow-tooltip />
          <el-table-column prop="amount" label="金额" width="110" align="right" />
          <el-table-column prop="payMethod" label="方式" width="90" />
          <el-table-column prop="payDate" label="付款日期" width="110" />
          <el-table-column prop="status" label="状态" width="100" />
          <el-table-column prop="writeoffState" label="核销状态" width="100" />
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="onDocDetail(row)">详情</el-button>
              <el-button v-if="row.status === 'PENDING_POST'" link type="success" @click="onPost(row)">过账</el-button>
              <el-button v-if="row.status === 'PENDING_POST'" link type="danger" @click="onVoid(row)">作废</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-model:current-page="outQuery.current" v-model:page-size="outQuery.size"
          :total="outTotal" layout="total, prev, pager, next" @change="loadOutDocs"
          style="margin-top:8px;justify-content:flex-end" />
        <el-alert type="info" :closable="false" show-icon style="margin-top:8px"
          title="付款过账：借 应付账款 / 贷 银行存款（余额不足 L1 阻断 C-4.6-10）；超审批金额阻断 C-4.6-09；预付单（PREPAY）凭证借 1123。" />

        <el-dialog v-model="outDialogVisible" title="录入付款单" width="520">
          <el-form :model="outForm" label-width="90px">
            <el-form-item label="供应商 ID" required>
              <el-input v-model="outForm.partyId" placeholder="供应商主数据 ID" />
            </el-form-item>
            <el-form-item label="供应商名">
              <el-input v-model="outForm.partyName" />
            </el-form-item>
            <el-form-item label="关联申请">
              <el-input v-model="outForm.reqId" placeholder="付款申请 ID（可空；关联后受审批额度卡控）" />
            </el-form-item>
            <el-form-item label="金额" required>
              <el-input-number v-model="outForm.amount" :min="0.01" :precision="2" :step="100" />
            </el-form-item>
            <el-form-item label="支付方式">
              <el-select v-model="outForm.payMethod">
                <el-option v-for="m in payMethods" :key="m" :label="m" :value="m" />
              </el-select>
            </el-form-item>
            <el-form-item label="银行账户" required>
              <el-input v-model="outForm.bankAccountId" placeholder="银行账户 ID" />
            </el-form-item>
            <el-form-item label="付款日期" required>
              <el-date-picker v-model="outForm.payDate" type="date" value-format="YYYY-MM-DD" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="outDialogVisible = false">取消</el-button>
            <el-button type="primary" @click="submitOutDoc">创建（待过账）</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>

      <!-- ================= 8.4.3 回单对账 ================= -->
      <el-tab-pane label="回单对账" name="receipt">
        <el-form inline>
          <el-input v-model="rcQuery.keyword" clearable placeholder="回单号" style="width:150px" />
          <el-select v-model="rcQuery.matchStatus" clearable placeholder="匹配状态" style="width:130px;margin-left:8px">
            <el-option label="未匹配" value="UNMATCHED" />
            <el-option label="已匹配" value="MATCHED" />
            <el-option label="异常" value="EXCEPTION" />
          </el-select>
          <el-button type="primary" @click="loadReceipts" style="margin-left:8px">查询</el-button>
          <el-button type="warning" plain @click="importVisible = true">CSV 导入</el-button>
          <el-button type="success" plain @click="onAutoMatch">自动匹配</el-button>
          <el-button type="primary" plain @click="rcDialogVisible = true">录入回单</el-button>
        </el-form>
        <el-table :data="rcRows" border size="small">
          <el-table-column prop="receiptNo" label="回单号" width="160" />
          <el-table-column prop="tradeDate" label="交易日期" width="110" />
          <el-table-column label="方向" width="70">
            <template #default="{ row }">{{ row.direction === 'RECEIVE' ? '收' : '付' }}</template>
          </el-table-column>
          <el-table-column prop="amount" label="金额" width="110" align="right" />
          <el-table-column prop="counterparty" label="对方户名" min-width="130" show-overflow-tooltip />
          <el-table-column prop="source" label="来源" width="80" />
          <el-table-column prop="matchStatus" label="匹配状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statusTag(row.matchStatus)" size="small">{{ row.matchStatus }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="matchedDocNo" label="匹配单号" width="150" />
          <el-table-column label="操作" width="230" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.matchStatus === 'UNMATCHED'" link type="primary"
                @click="onManualMatch(row)">人工匹配</el-button>
              <el-button v-if="row.matchStatus === 'UNMATCHED'" link type="warning"
                @click="onException(row)">标异常</el-button>
              <el-button v-if="row.matchStatus === 'MATCHED'" link type="danger"
                @click="onUnreconcile(row)">解除匹配</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-model:current-page="rcQuery.current" v-model:page-size="rcQuery.size"
          :total="rcTotal" layout="total, prev, pager, next" @change="loadReceipts"
          style="margin-top:8px;justify-content:flex-end" />
        <el-alert type="info" :closable="false" show-icon style="margin-top:8px"
          title="逐笔对账：账户+方向+金额相等±3 天自动匹配；已对账单据禁冲销（须先解除匹配）。流水数据源为 M8-7 银行对账预留。" />

        <el-dialog v-model="rcDialogVisible" title="录入银行回单" width="520">
          <el-form :model="rcForm" label-width="90px">
            <el-form-item label="回单号" required>
              <el-input v-model="rcForm.receiptNo" placeholder="银行回单号（唯一）" />
            </el-form-item>
            <el-form-item label="银行账户" required>
              <el-input v-model="rcForm.bankAccountId" />
            </el-form-item>
            <el-form-item label="交易日期" required>
              <el-date-picker v-model="rcForm.tradeDate" type="date" value-format="YYYY-MM-DD" />
            </el-form-item>
            <el-form-item label="方向" required>
              <el-radio-group v-model="rcForm.direction">
                <el-radio value="RECEIVE">收</el-radio>
                <el-radio value="PAY">付</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="金额" required>
              <el-input-number v-model="rcForm.amount" :min="0.01" :precision="2" />
            </el-form-item>
            <el-form-item label="对方户名">
              <el-input v-model="rcForm.counterparty" />
            </el-form-item>
            <el-form-item label="摘要">
              <el-input v-model="rcForm.summary" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="rcDialogVisible = false">取消</el-button>
            <el-button type="primary" @click="submitReceipt">录入</el-button>
          </template>
        </el-dialog>

        <el-dialog v-model="importVisible" title="CSV 批量导入（回单号,账户ID,日期,方向,金额,对方户名,摘要）" width="640">
          <el-input v-model="csvContent" type="textarea" :rows="8"
            placeholder="receiptNo,bankAccountId,2026-10-15,RECEIVE,10000.00,客户A,货款回款" />
          <template #footer>
            <el-button @click="importVisible = false">取消</el-button>
            <el-button type="primary" @click="doImport">导入</el-button>
          </template>
        </el-dialog>

        <el-dialog v-model="matchVisible" title="人工匹配（选择收付款单）" width="520">
          <el-input v-model="matchDocId" placeholder="收付款单 ID（须已过账且方向/账户一致）" />
          <template #footer>
            <el-button @click="matchVisible = false">取消</el-button>
            <el-button type="primary" @click="doManualMatch">匹配</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>
    </el-tabs>

    <!-- 详情抽屉（含核销明细） -->
    <el-drawer v-model="detailVisible" :title="detailDoc?.docNo || '单据详情'" size="480px">
      <template v-if="detailDoc">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="方向">{{ detailDoc.direction === 'IN' ? '收款' : '付款' }}</el-descriptions-item>
          <el-descriptions-item label="往来对象">{{ detailDoc.partyName }}</el-descriptions-item>
          <el-descriptions-item label="金额">{{ detailDoc.amount }}</el-descriptions-item>
          <el-descriptions-item label="实收/付">{{ detailDoc.actualAmount }}</el-descriptions-item>
          <el-descriptions-item label="方式">{{ detailDoc.payMethod }}</el-descriptions-item>
          <el-descriptions-item label="日期">{{ detailDoc.payDate }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ detailDoc.status }}</el-descriptions-item>
          <el-descriptions-item label="核销状态">{{ detailDoc.writeoffState }}</el-descriptions-item>
          <el-descriptions-item label="凭证 ID">{{ detailDoc.voucherId || '—' }}</el-descriptions-item>
          <el-descriptions-item v-if="detailDoc.rejectReason" label="驳回原因">
            {{ detailDoc.rejectReason }}
          </el-descriptions-item>
        </el-descriptions>
        <el-divider>核销明细</el-divider>
        <el-table :data="detailWriteoffs" border size="small">
          <el-table-column prop="woNo" label="核销单号" width="150" />
          <el-table-column prop="targetType" label="类型" width="60" />
          <el-table-column prop="targetNo" label="目标单号" min-width="130" show-overflow-tooltip />
          <el-table-column prop="amount" label="金额" width="90" align="right" />
          <el-table-column prop="writeoffMode" label="方式" width="80" />
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  cashDocPage, cashDocDetail, createCashDoc, postCashDoc, voidCashDoc,
  receiptPage, createReceipt, importReceipts, autoMatch, manualMatch,
  markReceiptException, unreconcile
} from '@/api/fin/collection'

const route = useRoute()
const tab = ref(route.meta?.tab || 'receive')
const payMethods = ['电汇', '支票', '商业汇票', '银行承兑汇票', '现金']

// ---- 收款（IN）----
const inQuery = ref({ current: 1, size: 10, keyword: '', status: '' })
const inRows = ref([])
const inTotal = ref(0)
const inDialogVisible = ref(false)
const inForm = ref({ partyId: '', partyName: '', amount: 1000, payMethod: '电汇', bankAccountId: '', payDate: new Date().toISOString().slice(0, 10), remark: '' })

async function loadInDocs() {
  const { data } = await cashDocPage({ ...inQuery.value, direction: 'IN' })
  inRows.value = data.records
  inTotal.value = Number(data.total)
}

async function submitInDoc() {
  const { data } = await createCashDoc({ ...inForm.value, direction: 'IN', currency: 'CNY' })
  ElMessage.success(`收款单 ${data.docNo} 已创建（待过账）`)
  inDialogVisible.value = false
  loadInDocs()
}

// ---- 付款（OUT）----
const outQuery = ref({ current: 1, size: 10, keyword: '', status: '' })
const outRows = ref([])
const outTotal = ref(0)
const outDialogVisible = ref(false)
const outForm = ref({ partyId: '', partyName: '', reqId: '', amount: 1000, payMethod: '电汇', bankAccountId: '', payDate: new Date().toISOString().slice(0, 10) })

async function loadOutDocs() {
  const { data } = await cashDocPage({ ...outQuery.value, direction: 'OUT' })
  outRows.value = data.records
  outTotal.value = Number(data.total)
}

async function submitOutDoc() {
  const payload = { ...outForm.value, direction: 'OUT', currency: 'CNY' }
  const { data } = await createCashDoc(payload)
  ElMessage.success(`付款单 ${data.docNo} 已创建（待过账）`)
  outDialogVisible.value = false
  loadOutDocs()
}

// ---- 通用：过账 / 作废 / 详情 ----
async function onPost(row) {
  try {
    await ElMessageBox.confirm(`确认过账 ${row.docNo}？将执行余额维护 + 资金凭证 + FIFO 核销。`, '过账确认', { type: 'warning' })
  } catch { return }
  const { data } = await postCashDoc(row.id)
  ElMessage.success(`已过账：凭证 ${data.voucherNo}，核销 ${data.writeoffTotal}${data.crossPeriod ? '（跨期，已挂审批）' : ''}`)
  loadInDocs(); loadOutDocs()
}

async function onVoid(row) {
  let reason = ''
  try {
    const { value } = await ElMessageBox.prompt('作废原因（必填）', `作废 ${row.docNo}`, { inputPattern: /\S+/, inputErrorMessage: '原因必填' })
    reason = value
  } catch { return }
  await voidCashDoc(row.id, reason)
  ElMessage.success('已作废')
  loadInDocs(); loadOutDocs()
}

const detailVisible = ref(false)
const detailDoc = ref(null)
const detailWriteoffs = ref([])
async function onDocDetail(row) {
  const { data } = await cashDocDetail(row.id)
  detailDoc.value = data.doc
  detailWriteoffs.value = data.writeoffs || []
  detailVisible.value = true
}

// ---- 回单对账 ----
const rcQuery = ref({ current: 1, size: 10, keyword: '', matchStatus: '' })
const rcRows = ref([])
const rcTotal = ref(0)
const rcDialogVisible = ref(false)
const importVisible = ref(false)
const matchVisible = ref(false)
const matchDocId = ref('')
const matchReceiptId = ref('')
const csvContent = ref('')
const rcForm = ref({ receiptNo: '', bankAccountId: '', tradeDate: new Date().toISOString().slice(0, 10), direction: 'RECEIVE', amount: 1000, counterparty: '', summary: '' })

async function loadReceipts() {
  const { data } = await receiptPage(rcQuery.value)
  rcRows.value = data.records
  rcTotal.value = Number(data.total)
}

function statusTag(s) {
  return s === 'MATCHED' ? 'success' : s === 'EXCEPTION' ? 'danger' : 'info'
}

async function submitReceipt() {
  await createReceipt(rcForm.value)
  ElMessage.success('回单已录入（未匹配）')
  rcDialogVisible.value = false
  loadReceipts()
}

async function doImport() {
  const { data } = await importReceipts(csvContent.value)
  ElMessage.success(`导入成功 ${data.imported} 条，重复 ${data.duplicates}，失败 ${data.failed}`)
  importVisible.value = false
  csvContent.value = ''
  loadReceipts()
}

async function onAutoMatch() {
  const { data } = await autoMatch()
  ElMessage.success(`自动匹配 ${data.matched} 对，未匹配 ${data.unmatched} 条`)
  loadReceipts()
  loadInDocs(); loadOutDocs()
}

function onManualMatch(row) {
  matchReceiptId.value = row.id
  matchDocId.value = ''
  matchVisible.value = true
}

async function doManualMatch() {
  await manualMatch(matchReceiptId.value, matchDocId.value)
  ElMessage.success('匹配成功，单据已置已对账')
  matchVisible.value = false
  loadReceipts()
}

async function onException(row) {
  let reason = ''
  try {
    const { value } = await ElMessageBox.prompt('异常原因（必填）', `标记异常 ${row.receiptNo}`, { inputPattern: /\S+/, inputErrorMessage: '原因必填' })
    reason = value
  } catch { return }
  await markReceiptException(row.id, reason)
  ElMessage.success('已标记异常')
  loadReceipts()
}

async function onUnreconcile(row) {
  let reason = ''
  try {
    const { value } = await ElMessageBox.prompt('解除原因（必填）', `解除匹配 ${row.receiptNo}`, { inputPattern: /\S+/, inputErrorMessage: '原因必填' })
    reason = value
  } catch { return }
  await unreconcile(row.id, reason)
  ElMessage.success('已解除匹配，单据回已过账')
  loadReceipts()
  loadInDocs(); loadOutDocs()
}

onMounted(() => {
  loadInDocs(); loadOutDocs(); loadReceipts()
})
</script>

<style scoped>
.collection-page :deep(.el-tabs__item.is-active) { font-weight: 600; }
</style>
