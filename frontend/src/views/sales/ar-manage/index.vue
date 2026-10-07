<template>
  <div class="page-wrap">
    <el-alert type="info" :closable="false" show-icon class="mb12"
              title="3.8.3 应收管理"
              description="应收台账多维查询（客户/SO/发票号/合同/超期）→ 月度对账单（未核销逐笔明细 + 差异逐笔排查）→ 计划达成对比（应收回看所属合同期次与收款进度，超期标红）。余额口径 = 应收金额 − 红冲 − 已核销。" />

    <el-tabs v-model="tab">
      <!-- ===== 台账 ===== -->
      <el-tab-pane label="应收台账" name="ledger">
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <span>应收台账</span>
              <div class="filters">
                <el-input v-model="q.keyword" size="small" style="width:170px"
                          placeholder="应收单/客户/发票号/SO" clearable @keyup.enter="loadAr" />
                <el-select v-model="q.status" size="small" style="width:130px" clearable
                           placeholder="状态" @change="loadAr">
                  <el-option label="未付 UNPAID" value="UNPAID" />
                  <el-option label="部分付 PARTIAL" value="PARTIAL" />
                  <el-option label="已付 PAID" value="PAID" />
                  <el-option label="红冲 RED" value="RED" />
                </el-select>
                <el-select v-model="q.customerId" size="small" style="width:180px" filterable
                           clearable placeholder="客户" @change="loadAr">
                  <el-option v-for="c in customers" :key="c.id"
                             :label="`${c.customerCode} ${c.customerName}`" :value="c.id" />
                </el-select>
                <el-input v-model="q.soNo" size="small" style="width:150px" clearable
                          placeholder="SO 单号" @keyup.enter="loadAr" />
                <el-input v-model="q.contractId" size="small" style="width:150px" clearable
                          placeholder="合同 ID" @keyup.enter="loadAr" />
                <el-switch v-model="q.overdue" active-text="仅超期" @change="loadAr" />
                <el-button size="small" type="primary" @click="loadAr">查询</el-button>
              </div>
            </div>
          </template>

          <el-table :data="arRows" size="small" border v-loading="loading">
            <el-table-column prop="arNo" label="应收单号" width="165" />
            <el-table-column prop="customerName" label="客户" min-width="140" show-overflow-tooltip />
            <el-table-column prop="soNo" label="SO" width="155" show-overflow-tooltip>
              <template #default="{ row }">{{ row.soNo || '—' }}</template>
            </el-table-column>
            <el-table-column prop="invoiceNo" label="发票号" width="150">
              <template #default="{ row }">{{ row.invoiceNo || '—' }}</template>
            </el-table-column>
            <el-table-column prop="invoiceDate" label="开票日" width="105" />
            <el-table-column label="到期日" width="125">
              <template #default="{ row }">
                <span :style="{ color: overdueDays(row) > 0 ? '#f56c6c' : '' }">
                  {{ row.dueDate }}
                  <template v-if="overdueDays(row) > 0">（超{{ overdueDays(row) }}天）</template>
                </span>
              </template>
            </el-table-column>
            <el-table-column label="应收金额" width="105" align="right">
              <template #default="{ row }">¥{{ fmt(row.amount) }}</template>
            </el-table-column>
            <el-table-column label="红冲" width="95" align="right">
              <template #default="{ row }">
                <span :style="{ color: Number(row.redAmount) > 0 ? '#f56c6c' : '' }">
                  ¥{{ fmt(row.redAmount) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="已核销" width="100" align="right">
              <template #default="{ row }">¥{{ fmt(row.paidAmount) }}</template>
            </el-table-column>
            <el-table-column label="余额" width="105" align="right">
              <template #default="{ row }"><b>¥{{ fmt(balance(row)) }}</b></template>
            </el-table-column>
            <el-table-column label="状态" width="95">
              <template #default="{ row }">
                <el-tag size="small" :type="arTag(row.status)">{{ arName(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button size="small" link @click="openAr(row)">详情</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- ===== 月度对账 ===== -->
      <el-tab-pane label="月度对账" name="statement">
        <el-card shadow="never" class="mb12">
          <template #header>
            <div class="card-head">
              <span>生成月度对账单</span>
              <div class="filters">
                <el-select v-model="stmtForm.customerId" size="small" style="width:200px"
                           filterable placeholder="选择客户">
                  <el-option v-for="c in customers" :key="c.id"
                             :label="`${c.customerCode} ${c.customerName}`" :value="c.id" />
                </el-select>
                <el-input v-model="stmtForm.period" size="small" style="width:130px"
                          placeholder="期间 yyyyMM" maxlength="6" />
                <el-button size="small" type="primary" :loading="submitting"
                           @click="doStatement">生成对账单</el-button>
              </div>
            </div>
          </template>
          <p class="hint">对账单 = 期初余额 + 本期开票 − 本期核销 = 期末余额；含未核销逐笔明细，差异支持逐笔排查记录（FR-4.3-7-6）。</p>
        </el-card>

        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <span>对账单列表</span>
              <el-button size="small" @click="loadStatements">刷新</el-button>
            </div>
          </template>
          <el-table :data="stmtRows" size="small" border>
            <el-table-column prop="stmtNo" label="对账单号" width="165" />
            <el-table-column prop="customerName" label="客户" min-width="150" show-overflow-tooltip />
            <el-table-column prop="period" label="期间" width="90" />
            <el-table-column label="期初" width="110" align="right">
              <template #default="{ row }">¥{{ fmt(row.openBal) }}</template>
            </el-table-column>
            <el-table-column label="本期开票" width="110" align="right">
              <template #default="{ row }">¥{{ fmt(row.invoiceAmt) }}</template>
            </el-table-column>
            <el-table-column label="本期核销" width="110" align="right">
              <template #default="{ row }">¥{{ fmt(row.writeoffAmt) }}</template>
            </el-table-column>
            <el-table-column label="期末" width="110" align="right">
              <template #default="{ row }"><b>¥{{ fmt(row.closeBal) }}</b></template>
            </el-table-column>
            <el-table-column prop="unpaidCnt" label="未核销笔" width="90" align="right" />
            <el-table-column label="状态" width="95">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'CONFIRMED' ? 'success' : 'info'">
                  {{ row.status === 'CONFIRMED' ? '已确认' : '已生成' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button size="small" link @click="openStatement(row)">明细</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- ===== 计划达成 ===== -->
      <el-tab-pane label="计划达成" name="plan">
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <span>合同收款计划达成对比</span>
              <div class="filters">
                <el-input v-model="planContractId" size="small" style="width:280px"
                          placeholder="合同 ID（合同 → SO → 应收链路）" clearable />
                <el-button size="small" type="primary" @click="loadPlan">查询</el-button>
              </div>
            </div>
          </template>
          <el-alert v-if="planRows.length" type="info" :closable="false" show-icon class="mb12"
                    :title="`合同应收汇总：${planSummary.arCount || 0} 笔 / ¥${fmt(planSummary.arAmt)}，已核销 ¥${fmt(planSummary.paidAmt)}`" />
          <el-empty v-else description="输入合同 ID 查询逐期计划与实际达成（超期未收标红）" :image-size="80" />
          <el-table v-if="planRows.length" :data="planRows" size="small" border>
            <el-table-column prop="periodNo" label="期数" width="70" align="center" />
            <el-table-column prop="dueDate" label="计划到期日" width="120" />
            <el-table-column label="计划金额" width="120" align="right">
              <template #default="{ row }">¥{{ fmt(row.planAmount) }}</template>
            </el-table-column>
            <el-table-column label="实际应收" width="120" align="right">
              <template #default="{ row }">¥{{ fmt(row.arAmount) }}</template>
            </el-table-column>
            <el-table-column label="已核销" width="120" align="right">
              <template #default="{ row }">¥{{ fmt(row.paidAmount) }}</template>
            </el-table-column>
            <el-table-column label="应收笔数" width="90" align="right">
              <template #default="{ row }">{{ row.arCount }}</template>
            </el-table-column>
            <el-table-column label="达成率" width="110" align="right">
              <template #default="{ row }">
                <span :style="{ color: Number(row.rate) >= 1 ? '#67c23a' : '#e6a23c' }">
                  {{ (Number(row.rate) * 100).toFixed(1) }}%
                </span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="140">
              <template #default="{ row }">
                <el-tag size="small" :type="row.overdue ? 'danger'
                  : (row.status === 'RECEIVED' ? 'success' : 'info')">
                  {{ row.overdue ? '超期未收' : (row.status === 'RECEIVED' ? '已收足' : '计划中') }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 应收详情 -->
    <el-dialog v-model="arVisible" :title="`应收 ${arDetail?.ar?.arNo}`" width="860px">
      <template v-if="arDetail">
        <el-descriptions :column="3" size="small" border class="mb12">
          <el-descriptions-item label="客户">{{ arDetail.ar.customerName }}</el-descriptions-item>
          <el-descriptions-item label="SO">{{ arDetail.ar.soNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="发票号">{{ arDetail.ar.invoiceNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="开票日">{{ arDetail.ar.invoiceDate }}</el-descriptions-item>
          <el-descriptions-item label="到期日">{{ arDetail.ar.dueDate }}</el-descriptions-item>
          <el-descriptions-item label="余额">
            <b>¥{{ fmt(arDetail.balance) }}</b>
          </el-descriptions-item>
          <el-descriptions-item v-if="arDetail.contract" label="所属合同" :span="2">
            {{ arDetail.contract.contractNo }} {{ arDetail.contract.title }}
          </el-descriptions-item>
          <el-descriptions-item v-if="arDetail.contract" label="合同金额">
            ¥{{ fmt(arDetail.contract.amount) }}
          </el-descriptions-item>
        </el-descriptions>

        <h4>应收明细行</h4>
        <el-table :data="arDetail.items" size="small" border class="mb12">
          <el-table-column prop="lineNo" label="#" width="50" />
          <el-table-column prop="itemCode" label="SKU" width="140" />
          <el-table-column prop="qty" label="数量" width="90" align="right" />
          <el-table-column label="金额" width="110" align="right">
            <template #default="{ row }">¥{{ fmt(row.amount) }}</template>
          </el-table-column>
          <el-table-column label="税额" width="100" align="right">
            <template #default="{ row }">¥{{ fmt(row.taxAmount) }}</template>
          </el-table-column>
        </el-table>

        <h4>核销记录</h4>
        <el-table :data="arDetail.writeoffs" size="small" border class="mb12">
          <el-table-column prop="woNo" label="核销单号" width="165" />
          <el-table-column label="金额" width="110" align="right">
            <template #default="{ row }">¥{{ fmt(row.amount) }}</template>
          </el-table-column>
          <el-table-column prop="payDate" label="付款日" width="110" />
          <el-table-column label="方式" width="90">
            <template #default="{ row }">
              {{ row.writeType === 'AUTO' ? '自动FIFO' : '人工' }}
            </template>
          </el-table-column>
          <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip />
        </el-table>

        <template v-if="arDetail.planProgress && arDetail.planProgress.length">
          <h4>所属合同期次与收款进度（10.8）</h4>
          <el-table :data="arDetail.planProgress" size="small" border>
            <el-table-column prop="periodNo" label="期数" width="70" align="center" />
            <el-table-column prop="dueDate" label="计划到期" width="110" />
            <el-table-column label="计划金额" width="110" align="right">
              <template #default="{ row }">¥{{ fmt(row.planAmount) }}</template>
            </el-table-column>
            <el-table-column label="已核销" width="110" align="right">
              <template #default="{ row }">¥{{ fmt(row.paidAmount) }}</template>
            </el-table-column>
            <el-table-column label="达成率" width="100" align="right">
              <template #default="{ row }">{{ (Number(row.rate) * 100).toFixed(1) }}%</template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="row.overdue ? 'danger' : 'info'">
                  {{ row.overdue ? '超期未收' : row.status === 'RECEIVED' ? '已收足' : '计划中' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </template>
      <template #footer>
        <el-button @click="arVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 对账单明细 -->
    <el-dialog v-model="stmtVisible" :title="`对账单 ${stmtDetail?.statement?.stmtNo}`" width="960px">
      <template v-if="stmtDetail">
        <el-descriptions :column="4" size="small" border class="mb12">
          <el-descriptions-item label="客户">{{ stmtDetail.statement.customerName }}</el-descriptions-item>
          <el-descriptions-item label="期间">{{ stmtDetail.statement.period }}</el-descriptions-item>
          <el-descriptions-item label="期初">¥{{ fmt(stmtDetail.statement.openBal) }}</el-descriptions-item>
          <el-descriptions-item label="期末">¥{{ fmt(stmtDetail.statement.closeBal) }}</el-descriptions-item>
          <el-descriptions-item label="本期开票">¥{{ fmt(stmtDetail.statement.invoiceAmt) }}</el-descriptions-item>
          <el-descriptions-item label="本期核销">¥{{ fmt(stmtDetail.statement.writeoffAmt) }}</el-descriptions-item>
          <el-descriptions-item label="未核销笔数">{{ stmtDetail.statement.unpaidCnt }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ stmtDetail.statement.status === 'CONFIRMED' ? '已确认' : '已生成' }}
          </el-descriptions-item>
        </el-descriptions>
        <el-table :data="stmtDetail.lines" size="small" border>
          <el-table-column prop="arNo" label="应收单" width="160" />
          <el-table-column prop="invoiceNo" label="发票号" width="145">
            <template #default="{ row }">{{ row.invoiceNo || '—' }}</template>
          </el-table-column>
          <el-table-column prop="dueDate" label="到期日" width="100" />
          <el-table-column label="金额" width="100" align="right">
            <template #default="{ row }">¥{{ fmt(row.amount) }}</template>
          </el-table-column>
          <el-table-column label="余额" width="100" align="right">
            <template #default="{ row }"><b>¥{{ fmt(row.balance) }}</b></template>
          </el-table-column>
          <el-table-column label="本期核销" width="100" align="right">
            <template #default="{ row }">¥{{ fmt(row.writeoffAmt) }}</template>
          </el-table-column>
          <el-table-column label="超期天数" width="90" align="right">
            <template #default="{ row }">
              <span :style="{ color: row.overdueDays > 0 ? '#f56c6c' : '' }">
                {{ row.overdueDays }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="排查状态" width="95">
            <template #default="{ row }">
              <el-tag size="small" :type="row.checkStatus === 'DIFF' ? 'danger'
                : (row.checkStatus === 'CHECKED' ? 'success' : 'info')">
                {{ row.checkStatus === 'DIFF' ? '有差异'
                  : (row.checkStatus === 'CHECKED' ? '已核对' : '待排查') }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="排查记录" width="300">
            <template #default="{ row }">
              <el-input v-model="row.checkNote" size="small" placeholder="逐笔排查记录"
                        :disabled="row.checkStatus === 'CHECKED'" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <el-button size="small" type="success" link
                         :disabled="row.checkStatus === 'CHECKED'"
                         @click="checkLine(row, 'CHECKED')">核对无误</el-button>
              <el-button size="small" type="danger" link
                         :disabled="row.checkStatus === 'CHECKED'"
                         @click="checkLine(row, 'DIFF')">标记差异</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <template #footer>
        <el-button @click="stmtVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getArPageApi, getArDetailApi, generateStatementApi, getStatementPageApi,
  getStatementDetailApi, checkStatementLineApi, getPlanProgressApi,
  getContractArSummaryApi
} from '@/api/fin/ar'
import { getGroupPageApi } from '@/api/mdm/customer'

const tab = ref('ledger')
const arRows = ref([])
const stmtRows = ref([])
const customers = ref([])
const planRows = ref([])
const planSummary = ref({})
const loading = ref(false)
const submitting = ref(false)
const q = ref({ keyword: '', status: '', customerId: '', soNo: '', contractId: '', overdue: false })
const stmtForm = ref({ customerId: '', period: '' })
const arVisible = ref(false)
const stmtVisible = ref(false)
const arDetail = ref(null)
const stmtDetail = ref(null)
const planContractId = ref('')
const currentStmt = ref(null)

function fmt(v) { return Number(v || 0).toFixed(2) }
function balance(a) {
  return Number(a.amount || 0) - Number(a.redAmount || 0) - Number(a.paidAmount || 0)
}
function overdueDays(row) {
  if (!row.dueDate || balance(row) <= 0) return 0
  const d = (Date.now() - new Date(row.dueDate).getTime()) / 86400000
  return d > 0 ? Math.floor(d) : 0
}
function arName(s) {
  return { UNPAID: '未付', PARTIAL: '部分付', PAID: '已付', RED: '已红冲' }[s] || s
}
function arTag(s) {
  return { UNPAID: 'warning', PARTIAL: 'info', PAID: 'success', RED: 'danger' }[s] || ''
}

async function loadCustomers() {
  try {
    const res = await getGroupPageApi({ current: 1, size: 200 })
    customers.value = res.data.records || res.data || []
  } catch (e) { /* 忽略 */ }
}

async function loadAr() {
  loading.value = true
  try {
    const res = await getArPageApi({
      current: 1, size: 100,
      keyword: q.value.keyword || undefined,
      status: q.value.status || undefined,
      customerId: q.value.customerId || undefined,
      soNo: q.value.soNo || undefined,
      contractId: q.value.contractId || undefined,
      overdue: q.value.overdue ? true : undefined
    })
    arRows.value = res.data.records || []
  } finally {
    loading.value = false
  }
}

async function openAr(row) {
  const res = await getArDetailApi(row.id)
  arDetail.value = res.data
  arVisible.value = true
}

async function loadStatements() {
  const res = await getStatementPageApi({ current: 1, size: 50 })
  stmtRows.value = res.data.records || []
}

async function doStatement() {
  if (!stmtForm.value.customerId || !stmtForm.value.period) {
    ElMessage.warning('请选择客户与对账期间（yyyyMM）')
    return
  }
  submitting.value = true
  try {
    const res = await generateStatementApi(stmtForm.value)
    ElMessage.success(`对账单 ${res.data.statement.stmtNo} 已生成`
      + `（期末余额 ¥${fmt(res.data.statement.closeBal)}）`)
    await loadStatements()
  } catch (e) {
    ElMessage.error(e?.message || '生成失败')
  } finally {
    submitting.value = false
  }
}

async function openStatement(row) {
  currentStmt.value = row
  const res = await getStatementDetailApi(row.id)
  stmtDetail.value = res.data
  stmtVisible.value = true
}

async function checkLine(line, status) {
  try {
    await checkStatementLineApi(line.id, {
      checkStatus: status,
      checkNote: line.checkNote || '',
      diffAmt: status === 'DIFF' ? 0 : 0
    })
    ElMessage.success(status === 'DIFF' ? '已标记差异并留痕' : '已记录核对结果')
    const res = await getStatementDetailApi(currentStmt.value.id)
    stmtDetail.value = res.data
  } catch (e) {
    ElMessage.error(e?.message || '记录失败')
  }
}

async function loadPlan() {
  if (!planContractId.value) {
    ElMessage.warning('请输入合同 ID')
    return
  }
  try {
    const [p, s] = await Promise.all([
      getPlanProgressApi(planContractId.value),
      getContractArSummaryApi(planContractId.value)
    ])
    planRows.value = p.data || []
    planSummary.value = s.data || {}
    if (!planRows.value.length) {
      ElMessage.info('该合同未维护收款计划分期')
    }
  } catch (e) {
    ElMessage.error(e?.message || '查询失败')
  }
}

onMounted(() => {
  loadCustomers()
  loadAr()
  loadStatements()
  const now = new Date()
  stmtForm.value.period = `${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}`
})
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; font-weight: bold; }
.filters { display: flex; gap: 8px; align-items: center; font-weight: normal; }
.hint { color: #909399; font-size: 12px; }
.mb12 { margin-bottom: 12px; }
h4 { margin: 12px 0 8px; }
</style>
