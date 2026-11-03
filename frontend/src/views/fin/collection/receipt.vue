<template>
  <div class="page collection-page">
    <!-- ================= 8.4.3 回单对账（/m/8.4.3 回单对账、/m/8.7.1 流水导入、/m/8.7.2 自动比对 同指本页） ================= -->
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
        <AppPagination
      v-model:current-page="rcQuery.current"
      v-model:page-size="rcQuery.size"
      :total="rcTotal"
      @current-change="loadReceipts"
      style="margin-top:8px;justify-content:flex-end"
    />
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
  </div>
</template>

<script setup>
import AppPagination from '@/components/AppPagination.vue'
/**
 * 8.4.3 回单对账（/m/8.4.3、/m/8.7.1 流水导入、/m/8.7.2 自动比对 同指本页）。
 * 拆分自原收付管理多 tab 页（design D1）；收付列表刷新复用 useCollection.js 单例。
 */
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  receiptPage, createReceipt, importReceipts, autoMatch, manualMatch,
  markReceiptException, unreconcile
} from '@/api/fin/collection'
import { useCollection } from './useCollection'

const { loadInDocs, loadOutDocs } = useCollection()

const rcQuery = ref({ current: 1, size: 20, keyword: '', matchStatus: '' })
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
  loadReceipts()
})
</script>
