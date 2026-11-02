<template>
  <div class="page">
    <el-alert type="info" :closable="false" show-icon class="hint"
      description="银行对账（M8-7）：余额级对账闭环——对账单期末余额（权威锚点）与账面余额比对、四类未达账项、差异率卡控（0.5%）、余额调节表与对账确认。流水导入与自动比对复用「财务管理→收付管理→回单对账」（同一数据源）。" />

    <!-- ============ 8.7.3 差异处理 ============ -->
    <el-form inline class="filter">
      <el-form-item label="银行账户">
        <el-select v-model="diffForm.bankAccountId" placeholder="选择账户" style="width: 220px" @change="loadOutstanding">
          <el-option v-for="a in accounts" :key="a.id" :label="`${a.accountName}（${a.currency}）`" :value="a.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="期间">
        <el-input v-model="diffForm.period" placeholder="yyyyMM" maxlength="6" style="width: 130px" @change="loadOutstanding" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="loadOutstanding">查询</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="12" class="sum-row" v-if="outstanding.sums">
      <el-col :span="6" v-for="t in TYPES" :key="t.key">
        <el-card shadow="never" class="sum-card">
          <div class="sum-label">{{ t.label }}</div>
          <div class="sum-value">¥{{ fmt(outstanding.sums[t.key]) }}</div>
        </el-card>
      </el-col>
    </el-row>
    <el-alert v-if="Number(outstanding.agedCount) > 0" type="warning" :closable="false" show-icon class="hint"
      :title="`存在 ${outstanding.agedCount} 条超 3 个月未达账项，需升级财务主管处理（BR-4.6-35）`" />

    <el-table :data="outstanding.items || []" border stripe>
      <el-table-column label="类型" min-width="150">
        <template #default="{ row }">{{ typeName(row.itemType) }}</template>
      </el-table-column>
      <el-table-column prop="anchorNo" label="关联单号" width="180" />
      <el-table-column label="金额" width="120" align="right">
        <template #default="{ row }">¥{{ fmt(row.amount) }}</template>
      </el-table-column>
      <el-table-column prop="tradeDate" label="交易日" width="110" />
      <el-table-column label="账龄(天)" width="100" align="right">
        <template #default="{ row }">
          <span :style="{ color: row.aged ? '#f56c6c' : '', fontWeight: row.aged ? 600 : '' }">
            {{ row.ageDays }}{{ row.aged ? ' ⚠' : '' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openHandling(row, 'PROCESSING')">处理中</el-button>
          <el-button size="small" type="success" @click="openHandling(row, 'DONE')">已处理</el-button>
          <el-button size="small" type="warning" @click="openHandling(row, 'ESCALATED')">升级主管</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-divider content-position="left">处理记录（追加式轨迹）</el-divider>
    <el-table :data="records" border size="small">
      <el-table-column prop="anchorNo" label="关联单号" width="180" />
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag size="small" :type="recTag(row.status)">{{ recName(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="handlingNote" label="备注" min-width="200" show-overflow-tooltip />
      <el-table-column prop="handleBy" label="处理人" width="110" />
      <el-table-column prop="handleAt" label="处理时间" width="170" />
    </el-table>

    <el-dialog v-model="handlingVisible" :title="handlingTitle" width="480px">
      <el-form label-width="80px">
        <el-form-item label="备注">
          <el-input v-model="handlingNote" type="textarea" :rows="3"
            :placeholder="handlingStatus === 'ESCALATED' ? '升级原因/说明（必填建议）' : '处理说明（可选）'" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="handlingVisible = false">取消</el-button>
        <el-button type="primary" @click="submitHandling">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 8.7.3 差异处理（拆分自原银行对账多 tab 页，design D1）。
 * 银行账户下拉共享状态见 useBankRecon.js。
 */
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { outstandingItems, markHandling, handlingRecords } from '@/api/fin/bank-reconciliation'
import { useBankRecon } from './useBankRecon'
import { fmt } from './bank-recon-helpers'

const { currentPeriod, accounts, loadAccounts } = useBankRecon()

const TYPES = [
  { key: 'ENT_RCPT_BANK_PEND', label: '①企业已收银行未收' },
  { key: 'ENT_PAY_BANK_PEND', label: '②企业已付银行未付' },
  { key: 'BANK_RCPT_ENT_PEND', label: '③银行已收企业未收' },
  { key: 'BANK_PAY_ENT_PEND', label: '④银行已付企业未付' }
]
const typeName = (k) => (TYPES.find(t => t.key === k) || {}).label || k

const outstanding = ref({})
const records = ref([])
const diffForm = ref({ bankAccountId: '', period: currentPeriod() })

const handlingVisible = ref(false)
const handlingStatus = ref('')
const handlingNote = ref('')
const handlingRow = ref(null)
const handlingTitle = computed(() => ({ PROCESSING: '标记处理中', DONE: '标记已处理', ESCALATED: '升级财务主管' }[handlingStatus.value] || ''))

function recName(s) { return { OPEN: '待处理', PROCESSING: '处理中', DONE: '已处理', ESCALATED: '已升级' }[s] || s }
function recTag(s) { return { OPEN: 'info', PROCESSING: 'warning', DONE: 'success', ESCALATED: 'danger' }[s] || '' }

async function loadOutstanding() {
  if (!diffForm.value.bankAccountId || !diffForm.value.period) return
  const res = await outstandingItems(diffForm.value.bankAccountId, diffForm.value.period)
  outstanding.value = res.data || {}
  const rec = await handlingRecords(diffForm.value.bankAccountId, diffForm.value.period)
  records.value = rec.data || []
}

function openHandling(row, status) {
  handlingRow.value = row
  handlingStatus.value = status
  handlingNote.value = ''
  handlingVisible.value = true
}
async function submitHandling() {
  const row = handlingRow.value
  await markHandling({
    bankAccountId: diffForm.value.bankAccountId,
    period: diffForm.value.period,
    itemType: row.itemType,
    anchorType: row.anchorType,
    anchorId: row.anchorId,
    anchorNo: row.anchorNo,
    amount: row.amount,
    tradeDate: row.tradeDate,
    status: handlingStatus.value,
    handlingNote: handlingNote.value
  })
  ElMessage.success(handlingStatus.value === 'ESCALATED' ? '已升级财务主管' : '处理记录已留痕')
  handlingVisible.value = false
  loadOutstanding()
}

onMounted(async () => {
  await loadAccounts()
  if (accounts.value.length) {
    diffForm.value.bankAccountId = accounts.value[0].id
  }
  if (diffForm.value.bankAccountId) loadOutstanding()
})
</script>

<style scoped>
.page { padding: 12px }
.hint { margin-bottom: 12px }
.filter { margin-bottom: 4px }
.sum-row { margin-bottom: 12px }
.sum-card { text-align: center }
.sum-label { font-size: 12px; color: #909399 }
.sum-value { font-size: 18px; font-weight: 600; margin-top: 4px }
</style>
