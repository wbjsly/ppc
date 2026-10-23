<template>
  <div class="page">
    <el-alert type="info" :closable="false" show-icon class="hint"
      description="银行对账（M8-7）：余额级对账闭环——对账单期末余额（权威锚点）与账面余额比对、四类未达账项、差异率卡控（0.5%）、余额调节表与对账确认。流水导入与自动比对复用「财务管理→收付管理→回单对账」（同一数据源）。" />
    <el-tabs v-model="activeTab">
      <!-- ============ 8.7.3 差异处理 ============ -->
      <el-tab-pane label="差异处理" name="diff">
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
      </el-tab-pane>

      <!-- ============ 8.7.4 余额调节 ============ -->
      <el-tab-pane label="余额调节" name="reconcile">
        <el-form inline class="filter">
          <el-form-item label="期间">
            <el-input v-model="stmtPeriod" placeholder="yyyyMM" maxlength="6" style="width: 130px" @change="loadStatements" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="loadStatements">查询</el-button>
            <el-button type="success" @click="stmtFormVisible = true">录入对账单</el-button>
          </el-form-item>
        </el-form>

        <el-table :data="statements" border stripe>
          <el-table-column prop="accountName" label="账户" min-width="150" />
          <el-table-column prop="period" label="期间" width="90" />
          <el-table-column prop="stmtNo" label="对账单号" width="150" />
          <el-table-column label="期初余额" width="120" align="right">
            <template #default="{ row }">¥{{ fmt(row.openingBalance) }}</template>
          </el-table-column>
          <el-table-column label="期末余额" width="120" align="right">
            <template #default="{ row }">¥{{ fmt(row.closingBalance) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="stmtTag(row.status)">{{ stmtName(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="超容差" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.overTolerance === '1'" size="small" type="danger">是</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="280" fixed="right">
            <template #default="{ row }">
              <el-button size="small" @click="openDetail(row)">调节表</el-button>
              <el-button v-if="row.status === 'DRAFT'" size="small" type="primary" @click="doConfirm(row)">确认</el-button>
              <el-button v-if="row.status === 'CONFIRMED'" size="small" type="warning" @click="doAudit(row)">审核</el-button>
              <el-button v-if="row.status !== 'DRAFT'" size="small" type="danger" @click="openRevoke(row)">撤销</el-button>
            </template>
          </el-table-column>
        </el-table>

        <!-- 录入对账单 -->
        <el-dialog v-model="stmtFormVisible" title="录入银行对账单" width="520px">
          <el-form label-width="110px">
            <el-form-item label="银行账户">
              <el-select v-model="stmtForm.bankAccountId" placeholder="选择账户" style="width: 100%">
                <el-option v-for="a in accounts" :key="a.id" :label="`${a.accountName}（${a.currency}）`" :value="a.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="期间"><el-input v-model="stmtForm.period" placeholder="yyyyMM" maxlength="6" /></el-form-item>
            <el-form-item label="对账单号"><el-input v-model="stmtForm.stmtNo" /></el-form-item>
            <el-form-item label="期初余额"><el-input-number v-model="stmtForm.openingBalance" :precision="2" :min="0" style="width: 100%" /></el-form-item>
            <el-form-item label="期末余额"><el-input-number v-model="stmtForm.closingBalance" :precision="2" :min="0" style="width: 100%" /></el-form-item>
            <el-form-item label="备注"><el-input v-model="stmtForm.remark" type="textarea" :rows="2" /></el-form-item>
          </el-form>
          <el-alert v-if="saveResult" :type="saveResult.selfCheckBalanced ? 'success' : 'warning'" :closable="false" show-icon
            :title="saveResult.selfCheckBalanced
              ? '勾稽平衡（期初+已匹配收−已匹配付=期末）'
              : `勾稽差异 ¥${fmt(saveResult.selfCheckGap)}（该差额即未达账项，允许保存）`" />
          <template #footer>
            <el-button @click="stmtFormVisible = false">取消</el-button>
            <el-button type="primary" @click="submitStatement">保存</el-button>
          </template>
        </el-dialog>

        <!-- 调节表（结构化 + CSV + A4 打印） -->
        <el-dialog v-model="detailVisible" :title="`银行存款余额调节表 ${detailTitle}`" width="860px" top="4vh">
          <div v-if="detail">
            <el-row :gutter="12" class="sum-row">
              <el-col :span="8">
                <el-card shadow="never" class="sum-card">
                  <div class="sum-label">对账差异</div>
                  <div class="sum-value">¥{{ fmt(detail.diffAmount) }}</div>
                </el-card>
              </el-col>
              <el-col :span="8">
                <el-card shadow="never" class="sum-card">
                  <div class="sum-label">差异率</div>
                  <div class="sum-value" :style="{ color: detail.overTolerance ? '#f56c6c' : '' }">
                    {{ (Number(detail.diffRate) * 100).toFixed(2) }}%
                  </div>
                </el-card>
              </el-col>
              <el-col :span="8">
                <el-card shadow="never" class="sum-card">
                  <div class="sum-label">调节平衡</div>
                  <div class="sum-value" :style="{ color: detail.reconcileTable.balanced ? '#67c23a' : '#f56c6c' }">
                    {{ detail.reconcileTable.balanced ? '平衡' : '不平' }}
                  </div>
                </el-card>
              </el-col>
            </el-row>
            <el-alert v-if="detail.overTolerance" type="error" :closable="false" show-icon class="hint"
              title="差异率超容差（>0.5%），已进入差异处理流程提示（C-0-02）——请在「差异处理」tab 排查未达账项" />

            <!-- A4 打印区 -->
            <div id="reconcile-print" class="print-area">
              <h3 class="print-title">{{ companyName }} 银行存款余额调节表</h3>
              <p class="print-meta">账户：{{ detail.statement.accountName }}（{{ detail.statement.accountNo }}）
                期间：{{ detail.statement.period }} 币种：{{ detail.statement.currency }}
                对账单号：{{ detail.statement.stmtNo || '—' }}</p>
              <table class="reconcile-table">
                <thead>
                  <tr><th style="width:55%">企业账面方</th><th>金额</th></tr>
                </thead>
                <tbody>
                  <tr><td>企业账面银行存款余额</td><td class="num">{{ fmt(detail.reconcileTable.bookBalance) }}</td></tr>
                  <tr><td>加：银行已收企业未收</td><td class="num">{{ fmt(detail.reconcileTable.sums.BANK_RCPT_ENT_PEND) }}</td></tr>
                  <tr><td>减：银行已付企业未付</td><td class="num">{{ fmt(detail.reconcileTable.sums.BANK_PAY_ENT_PEND) }}</td></tr>
                  <tr class="total"><td>调节后余额（账面方）</td><td class="num">{{ fmt(detail.reconcileTable.leftSide) }}</td></tr>
                </tbody>
              </table>
              <table class="reconcile-table">
                <thead>
                  <tr><th style="width:55%">银行对账单方</th><th>金额</th></tr>
                </thead>
                <tbody>
                  <tr><td>银行对账单期末余额</td><td class="num">{{ fmt(detail.reconcileTable.bankClosingBalance) }}</td></tr>
                  <tr><td>加：企业已收银行未收</td><td class="num">{{ fmt(detail.reconcileTable.sums.ENT_RCPT_BANK_PEND) }}</td></tr>
                  <tr><td>减：企业已付银行未付</td><td class="num">{{ fmt(detail.reconcileTable.sums.ENT_PAY_BANK_PEND) }}</td></tr>
                  <tr class="total"><td>调节后余额（银行方）</td><td class="num">{{ fmt(detail.reconcileTable.rightSide) }}</td></tr>
                </tbody>
              </table>
              <p class="print-sign">制表人：____________　　出纳确认：____________　　财务主管审核：____________　　日期：____________</p>
            </div>

            <div class="print-actions">
              <el-button type="primary" @click="exportCsv">导出 CSV</el-button>
              <el-button @click="printTable">打印（A4）</el-button>
            </div>
          </div>
        </el-dialog>

        <!-- 撤销 -->
        <el-dialog v-model="revokeVisible" title="撤销对账" width="420px">
          <el-form label-width="80px">
            <el-form-item label="原因">
              <el-input v-model="revokeNote" type="textarea" :rows="3" placeholder="撤销原因（AUDITED 撤销必填并留痕）" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="revokeVisible = false">取消</el-button>
            <el-button type="danger" @click="submitRevoke">确认撤销</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getBankAccountsApi } from '@/api/fin/bank-account'
import {
  saveStatement, listStatements, statementDetail, confirmStatement,
  auditStatement, revokeStatement, outstandingItems, markHandling, handlingRecords
} from '@/api/fin/bank-reconciliation'

const route = useRoute()
const activeTab = ref(route.meta?.tab === 'reconcile' ? 'reconcile' : 'diff')
const companyName = '×××有限公司'

const TYPES = [
  { key: 'ENT_RCPT_BANK_PEND', label: '①企业已收银行未收' },
  { key: 'ENT_PAY_BANK_PEND', label: '②企业已付银行未付' },
  { key: 'BANK_RCPT_ENT_PEND', label: '③银行已收企业未收' },
  { key: 'BANK_PAY_ENT_PEND', label: '④银行已付企业未付' }
]
const typeName = (k) => (TYPES.find(t => t.key === k) || {}).label || k

const accounts = ref([])
const outstanding = ref({})
const records = ref([])
const diffForm = ref({ bankAccountId: '', period: currentPeriod() })

const stmtPeriod = ref(currentPeriod())
const statements = ref([])
const stmtFormVisible = ref(false)
const stmtForm = ref({ bankAccountId: '', period: currentPeriod(), stmtNo: '', openingBalance: 0, closingBalance: 0, remark: '' })
const saveResult = ref(null)

const detailVisible = ref(false)
const detail = ref(null)
const detailTitle = ref('')
const revokeVisible = ref(false)
const revokeNote = ref('')
const revokeTarget = ref(null)

const handlingVisible = ref(false)
const handlingStatus = ref('')
const handlingNote = ref('')
const handlingRow = ref(null)
const handlingTitle = computed(() => ({ PROCESSING: '标记处理中', DONE: '标记已处理', ESCALATED: '升级财务主管' }[handlingStatus.value] || ''))

function currentPeriod() {
  const d = new Date()
  return `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}`
}
function fmt(v) {
  const n = Number(v)
  return isNaN(n) ? '0.00' : n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function stmtName(s) { return { DRAFT: '草稿', CONFIRMED: '已确认', AUDITED: '已审核' }[s] || s }
function stmtTag(s) { return { DRAFT: 'info', CONFIRMED: 'warning', AUDITED: 'success' }[s] || '' }
function recName(s) { return { OPEN: '待处理', PROCESSING: '处理中', DONE: '已处理', ESCALATED: '已升级' }[s] || s }
function recTag(s) { return { OPEN: 'info', PROCESSING: 'warning', DONE: 'success', ESCALATED: 'danger' }[s] || '' }

async function loadAccounts() {
  const res = await getBankAccountsApi()
  accounts.value = res.data || []
}
async function loadOutstanding() {
  if (!diffForm.value.bankAccountId || !diffForm.value.period) return
  const res = await outstandingItems(diffForm.value.bankAccountId, diffForm.value.period)
  outstanding.value = res.data || {}
  const rec = await handlingRecords(diffForm.value.bankAccountId, diffForm.value.period)
  records.value = rec.data || []
}
async function loadStatements() {
  const res = await listStatements(stmtPeriod.value || undefined)
  statements.value = res.data || []
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

async function submitStatement() {
  const res = await saveStatement(stmtForm.value)
  saveResult.value = res.data
  ElMessage.success('对账单已保存')
  loadStatements()
}
async function doConfirm(row) {
  await confirmStatement(row.id)
  ElMessage.success('已确认（出纳）')
  loadStatements()
}
async function doAudit(row) {
  await auditStatement(row.id)
  ElMessage.success('已审核（财务主管），本账户本期对账完成')
  loadStatements()
}
function openRevoke(row) {
  revokeTarget.value = row
  revokeNote.value = ''
  revokeVisible.value = true
}
async function submitRevoke() {
  await revokeStatement(revokeTarget.value.id, revokeNote.value)
  ElMessage.success('已撤销回草稿（留痕）')
  revokeVisible.value = false
  loadStatements()
}

async function openDetail(row) {
  const res = await statementDetail(row.id)
  detail.value = res.data
  detailTitle.value = `${row.accountName} ${row.period}`
  detailVisible.value = true
}
function exportCsv() {
  const t = detail.value.reconcileTable
  const rows = [
    ['银行存款余额调节表', detailTitle.value],
    ['企业账面银行存款余额', fmt(t.bookBalance)],
    ['加：银行已收企业未收', fmt(t.sums.BANK_RCPT_ENT_PEND)],
    ['减：银行已付企业未付', fmt(t.sums.BANK_PAY_ENT_PEND)],
    ['调节后余额（账面方）', fmt(t.leftSide)],
    [],
    ['银行对账单期末余额', fmt(t.bankClosingBalance)],
    ['加：企业已收银行未收', fmt(t.sums.ENT_RCPT_BANK_PEND)],
    ['减：企业已付银行未付', fmt(t.sums.ENT_PAY_BANK_PEND)],
    ['调节后余额（银行方）', fmt(t.rightSide)]
  ]
  const csv = '\uFEFF' + rows.map(r => r.join(',')).join('\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `余额调节表_${detailTitle.value.replace(/\s+/g, '_')}.csv`
  a.click()
  URL.revokeObjectURL(a.href)
}
function printTable() {
  const el = document.getElementById('reconcile-print')
  if (!el) return
  const win = window.open('', '_blank', 'width=900,height=1200')
  win.document.write(`<!doctype html><html><head><meta charset="utf-8"><title>余额调节表 ${detailTitle.value}</title>
<style>
  body{font-family:"SimSun",serif;padding:32px;color:#000}
  .print-title{text-align:center;font-size:18px;letter-spacing:4px;margin:0 0 8px}
  .print-meta{font-size:12px;color:#333;margin-bottom:16px}
  .reconcile-table{width:100%;border-collapse:collapse;margin-bottom:18px}
  .reconcile-table th,.reconcile-table td{border:1px solid #000;padding:6px 10px;font-size:13px}
  .reconcile-table th{background:#f2f2f2;text-align:left}
  .reconcile-table .num{text-align:right;font-variant-numeric:tabular-nums}
  .reconcile-table .total td{font-weight:700}
  .print-sign{margin-top:48px;font-size:13px}
  @media print{ body{padding:0} }
</style></head><body>${el.innerHTML}</body></html>`)
  win.document.close()
  win.focus()
  setTimeout(() => { win.print(); win.close() }, 300)
}

onMounted(async () => {
  await loadAccounts()
  if (accounts.value.length) {
    diffForm.value.bankAccountId = accounts.value[0].id
    stmtForm.value.bankAccountId = accounts.value[0].id
  }
  loadStatements()
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
.print-area { background: #fff; padding: 8px }
.print-title { text-align: center; font-size: 16px; letter-spacing: 2px }
.print-meta { font-size: 12px; color: #606266 }
.reconcile-table { width: 100%; border-collapse: collapse; margin-bottom: 14px }
.reconcile-table th, .reconcile-table td { border: 1px solid #dcdfe6; padding: 6px 10px; font-size: 13px }
.reconcile-table th { background: #f5f7fa; text-align: left }
.reconcile-table .num { text-align: right; font-variant-numeric: tabular-nums }
.reconcile-table .total td { font-weight: 600 }
.print-sign { margin-top: 36px; font-size: 13px; color: #303133 }
.print-actions { margin-top: 12px; text-align: right }
</style>
