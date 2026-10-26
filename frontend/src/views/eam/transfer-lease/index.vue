<template>
  <div class="eam-page">
    <el-alert type="info" :closable="false" style="margin-bottom:12px;"
      :title="tab === 'dept' ? '部门调拨（7.4.1）：共享池入口 → 申请（BR-21 四前置）→ 三节点审批 → 交接双确认 → 完成（BR-22 当月归属回溯）'
        : tab === 'cross' ? '法人调拨（7.4.2）：四节点审批（+财务经理）→ 完成同事务双凭证 + 内部交易单据 + 开票待办（C-10 月结卡控）'
        : '租赁管理（7.4.3）：合同 → 分类确认 → 使用权确认（PV+摊余计划+ROU 建卡）→ 月度处理（幂等）→ 重计量 / 临期 / 到期终止'"
      :description="tab === 'dept'
        ? '完成当月折旧仍归调出成本中心（引擎回溯调出方），次月起自动切调入方。'
        : tab === 'cross'
        ? '跨法人以双方法人 USCC 不同为判定；任一凭证失败整体回滚。'
        : '短期低值豁免（BR-25）不建卡不出计划，直线进损益留痕；重新计量经财务资产会计单签。'" />

    <!-- ================= 7.4.1 / 7.4.2 调拨 ================= -->
    <template v-if="tab === 'dept' || tab === 'cross'">
      <el-card shadow="never">
        <div class="toolbar">
          <el-button type="primary" @click="openTrfDialog()">新建调拨</el-button>
          <template v-if="tab === 'dept'">
            <el-button @click="loadPool">闲置共享池</el-button>
          </template>
          <template v-else>
            <el-button @click="doIntercoCheck">C-10 月结卡控</el-button>
          </template>
          <span class="spacer" />
          <el-button plain size="small" @click="loadTrfs">刷新</el-button>
        </div>

        <div v-if="intercoResult" class="gen-result">
          <el-alert type="success" closable style="margin:8px 0;"
            title="C-4.13-10 月结卡控通过：无未闭环的跨法人内部交易" />
        </div>

        <el-table :data="trfRows" size="small" border v-loading="loading" max-height="360">
          <el-table-column prop="trfNo" label="调拨单号" width="170" />
          <el-table-column prop="cardNo" label="卡片号" width="140" />
          <el-table-column prop="fromDept" label="调出部门" min-width="110" />
          <el-table-column prop="fromCc" label="调出成本中心" width="110" />
          <el-table-column label="→" width="40" align="center">→</el-table-column>
          <el-table-column prop="toDept" label="调入部门" min-width="110" />
          <el-table-column prop="toCc" label="调入成本中心" width="110" />
          <el-table-column prop="toLe" label="调入法人" width="90" v-if="tab === 'cross'" />
          <el-table-column prop="status" label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="trfTag(row.status)" size="small">{{ trfLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="intercoNo" label="内部交易单" width="160" v-if="tab === 'cross'">
            <template #default="{ row }">
              <span v-if="row.intercoNo">{{ row.intercoNo }}</span>
              <el-tag v-else type="warning" size="small">待生成</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="230" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.status === 'DRAFT' || row.status === 'REJECTED'" link type="primary"
                size="small" @click="doSubmit(row)">提交审批</el-button>
              <template v-if="row.status === 'APPROVED' || row.status === 'HANDOVERED'">
                <el-button link type="warning" size="small" :disabled="!!row.outConfirmBy"
                  @click="doHandover(row, 'OUT')">调出确认</el-button>
                <el-button link type="success" size="small" :disabled="!!row.inConfirmBy"
                  @click="doHandover(row, 'IN')">调入确认</el-button>
              </template>
              <el-button v-if="row.status === 'HANDOVERED'" link type="danger" size="small"
                @click="doComplete(row)">确认完成</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- 闲置共享池（7.4.1） -->
      <el-card shadow="never" style="margin-top:12px;" v-if="tab === 'dept' && poolRows.length">
        <template #header>闲置共享池（≥12 月闲置可发起调拨；≥24 月处置评估待办）</template>
        <el-table :data="poolRows" size="small" border max-height="260">
          <el-table-column prop="cardNo" label="卡片号" width="140" />
          <el-table-column prop="assetName" label="资产名称" min-width="160" />
          <el-table-column prop="deptName" label="使用部门" min-width="110" />
          <el-table-column prop="lastUsedAt" label="最后使用日" width="110" align="center" />
          <el-table-column prop="idleMonths" label="闲置月数" width="90" align="right" />
          <el-table-column label="分级" width="120" align="center">
            <template #default="{ row }">
              <el-tag :type="row.idleLevel === 'DISPOSAL_EVAL' ? 'danger' : 'warning'" size="small">
                {{ row.idleLevel === 'DISPOSAL_EVAL' ? '处置评估' : '闲置' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openTrfDialog(row)">发起调拨</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- 新建调拨对话框 -->
      <el-dialog v-model="trfDlg" title="新建资产调拨申请" width="620px">
        <el-form :model="trfForm" label-width="120px">
          <el-form-item label="资产卡片" required>
            <el-input v-model="trfForm.cardNo" placeholder="卡片号（从共享池带入或手输 ZC 开头卡号）" />
          </el-form-item>
          <el-form-item label="调拨类型">
            <el-radio-group v-model="trfForm.transferType" :disabled="tab === 'cross'">
              <el-radio value="DEPT">部门间</el-radio>
              <el-radio value="CROSS_LE">跨法人</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="调入部门"><el-input v-model="trfForm.toDept" /></el-form-item>
          <el-form-item label="调入成本中心"><el-input v-model="trfForm.toCc" /></el-form-item>
          <el-form-item label="调入存放地点"><el-input v-model="trfForm.toLocation" /></el-form-item>
          <el-form-item v-if="trfForm.transferType === 'CROSS_LE'" label="调入法人" required>
            <el-input v-model="trfForm.toLe" placeholder="LE_CODE，如 LE-002" />
          </el-form-item>
          <el-form-item label="调拨原因" required><el-input v-model="trfForm.reason" type="textarea" /></el-form-item>
          <el-form-item label="拟调拨日期"><el-date-picker v-model="trfForm.planDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="trfDlg = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="doCreateTrf">创建（BR-21 前置校验）</el-button>
        </template>
      </el-dialog>
    </template>

    <!-- ================= 7.4.3 租赁管理 ================= -->
    <template v-else>
      <el-card shadow="never">
        <div class="toolbar">
          <el-button type="primary" @click="openLeaseDialog()">新建合同</el-button>
          <el-date-picker v-model="periodTag" type="month" value-format="YYYYMM" placeholder="会计月"
            style="width:130px;margin-right:8px;" />
          <el-button type="success" @click="doMonthly">月度处理</el-button>
          <el-button type="warning" plain @click="doReverse">冲回</el-button>
          <el-button @click="loadExpiry">临期清单</el-button>
          <span class="spacer" />
          <el-button plain size="small" @click="loadLeases">刷新</el-button>
        </div>

        <el-table :data="leaseRows" size="small" border v-loading="loading" max-height="360">
          <el-table-column prop="lseNo" label="合同号" width="170" />
          <el-table-column prop="lessor" label="出租方" min-width="130" />
          <el-table-column prop="assetDesc" label="租赁资产" min-width="150" />
          <el-table-column prop="termsMonths" label="租期(月)" width="80" align="right" />
          <el-table-column prop="paymentAmount" label="每期租金" width="100" align="right" />
          <el-table-column label="付款周期" width="80" align="center">
            <template #default="{ row }">{{ cycleLabel(row.payCycleMonths) }}</template>
          </el-table-column>
          <el-table-column prop="classify" label="分类" width="90" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.classify" size="small">{{ classifyLabel(row.classify) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="pv" label="PV" width="110" align="right" />
          <el-table-column prop="rouAmount" label="使用权资产" width="110" align="right" />
          <el-table-column prop="status" label="状态" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="leaseTag(row.status)" size="small">{{ leaseLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="300" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.status === 'DRAFT'" link type="primary" size="small"
                @click="doClassify(row)">分类</el-button>
              <el-button v-if="row.status === 'CLASSIFIED'" link type="success" size="small"
                @click="doConfirm(row)">使用权确认</el-button>
              <el-button v-if="row.status === 'CONFIRMED'" link type="warning" size="small"
                @click="viewSchedule(row)">摊余计划</el-button>
              <el-button v-if="row.status === 'CONFIRMED'" link type="primary" size="small"
                @click="openRemeasure(row)">变更重计量</el-button>
              <el-button v-if="row.status === 'CONFIRMED'" link type="danger" size="small"
                @click="doTerminate(row)">到期终止</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- 新建合同对话框 -->
      <el-dialog v-model="leaseDlg" title="租赁合同录入" width="640px">
        <el-form :model="leaseForm" label-width="120px">
          <el-form-item label="出租方" required><el-input v-model="leaseForm.lessor" /></el-form-item>
          <el-form-item label="租赁资产" required><el-input v-model="leaseForm.assetDesc" /></el-form-item>
          <el-form-item label="租赁期起" required>
            <el-date-picker v-model="leaseForm.startDate" type="date" value-format="YYYY-MM-DD" />
          </el-form-item>
          <el-form-item label="租赁期止" required>
            <el-date-picker v-model="leaseForm.endDate" type="date" value-format="YYYY-MM-DD" />
          </el-form-item>
          <el-form-item label="每期租金" required><el-input-number v-model="leaseForm.paymentAmount" :min="0.01" :precision="2" /></el-form-item>
          <el-form-item label="付款周期">
            <el-select v-model="leaseForm.payCycleMonths" style="width:140px;">
              <el-option :value="1" label="月付" /><el-option :value="3" label="季付" />
              <el-option :value="6" label="半年付" /><el-option :value="12" label="年付" />
            </el-select>
          </el-form-item>
          <el-form-item label="折现率(%)">
            <el-input-number v-model="leaseForm.discountRatePct" :min="0" :precision="4" />
            <span class="hint">留空取 SysParam LEASE_DEFAULT_DISCOUNT_RATE</span>
          </el-form-item>
          <el-form-item label="取值依据">
            <el-radio-group v-model="leaseForm.rateBasis">
              <el-radio value="INCLUSIVE">租赁内含利率</el-radio>
              <el-radio value="INCREMENTAL">增量借款利率</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="购买选择权">
            <el-radio-group v-model="leaseForm.buyOption">
              <el-radio value="0">无</el-radio><el-radio value="1">有</el-radio>
            </el-radio-group>
            <el-input-number v-if="leaseForm.buyOption === '1'" v-model="leaseForm.buyPrice"
              :min="0" :precision="2" placeholder="约定购买价" style="margin-left:8px;" />
          </el-form-item>
          <el-form-item label="初始直接费用"><el-input-number v-model="leaseForm.initDirectCost" :min="0" :precision="2" /></el-form-item>
          <el-form-item label="预付租金"><el-input-number v-model="leaseForm.prepaidRent" :min="0" :precision="2" /></el-form-item>
          <el-form-item label="租赁激励"><el-input-number v-model="leaseForm.leaseIncentive" :min="0" :precision="2" /></el-form-item>
          <el-form-item label="豁免理由">
            <el-input v-model="leaseForm.exemptReason" placeholder="分类为短期低值豁免时必填（BR-25 L5 留痕）" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="leaseDlg = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="doCreateLease">保存（条款完整性校验）</el-button>
        </template>
      </el-dialog>

      <!-- 摊余计划查看 -->
      <el-dialog v-model="schedDlg" :title="`摊余成本计划（${schedLseNo}）`" width="860px">
        <el-table :data="schedRows" size="small" border max-height="420">
          <el-table-column prop="seq" label="月序" width="60" align="center" />
          <el-table-column prop="periodTag" label="会计月" width="90" align="center" />
          <el-table-column prop="beginBal" label="期初负债" width="120" align="right" />
          <el-table-column prop="interest" label="利息" width="110" align="right" />
          <el-table-column prop="payment" label="付款" width="110" align="right" />
          <el-table-column prop="endBal" label="期末负债" width="120" align="right" />
          <el-table-column label="已处理" width="80" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.processedFlag === '1'" type="success" size="small">已处理</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
        </el-table>
      </el-dialog>

      <!-- 临期清单 -->
      <el-dialog v-model="expiryDlg" title="租赁临期清单（6 月/1 月分级）" width="820px">
        <el-table :data="expiryRows" size="small" border max-height="380">
          <el-table-column prop="lseNo" label="合同号" width="170" />
          <el-table-column prop="lessor" label="出租方" min-width="120" />
          <el-table-column prop="assetDesc" label="租赁资产" min-width="140" />
          <el-table-column prop="endDate" label="到期日" width="110" align="center" />
          <el-table-column prop="monthsLeft" label="剩(月)" width="80" align="right" />
          <el-table-column label="分级" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="row.level === 'M1' ? 'danger' : 'warning'" size="small">
                {{ row.level === 'M1' ? '剩1月·暂估' : '剩6月' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-dialog>
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  trfPage, trfCreate, trfSubmit, trfHandover, trfComplete, intercoCheck, idlePool,
  leasePage, leaseCreate, leaseClassify, leaseConfirm, leaseSchedule,
  leaseMonthly, leaseReverse, leaseRemeasure, leaseExpiry, leaseTerminate
} from '@/api/eam/transfer-lease'
import request from '@/utils/request'

const route = useRoute()
const tab = route.meta.tab || 'dept'

const loading = ref(false)
const saving = ref(false)

// ================= 调拨 =================
const trfRows = ref([])
const poolRows = ref([])
const intercoResult = ref(false)
const trfDlg = ref(false)
const trfForm = ref({})

async function loadTrfs() {
  loading.value = true
  try {
    const transferType = tab === 'cross' ? 'CROSS_LE' : 'DEPT'
    const res = await trfPage({ current: 1, size: 100, transferType })
    trfRows.value = res.data?.records || []
  } finally {
    loading.value = false
  }
}

async function loadPool() {
  const res = await idlePool()
  poolRows.value = res.data || []
}

async function doIntercoCheck() {
  try {
    await intercoCheck()
    intercoResult.value = true
    ElMessage.success('C-10 月结卡控通过')
  } catch (e) {
    intercoResult.value = false
    ElMessage.error(e?.response?.data?.msg || e.message)
  }
}

function openTrfDialog(poolRow) {
  trfForm.value = {
    cardId: poolRow?.cardId || '',
    cardNo: poolRow?.cardNo || '',
    transferType: tab === 'cross' ? 'CROSS_LE' : 'DEPT',
    toDept: '', toCc: '', toLocation: '', toLe: '', reason: '', planDate: ''
  }
  trfDlg.value = true
}

async function doCreateTrf() {
  const f = trfForm.value
  if (!f.cardNo) { ElMessage.warning('卡片号必填'); return }
  // 卡片号 → 卡片 ID（后端按 cardId 处理；手输时先反查）
  let cardId = f.cardId
  if (!cardId) {
    const res = await request.get('/eam/cards', { params: { current: 1, size: 1, keyword: f.cardNo } })
    const rec = res.data?.records?.[0]
    if (!rec) { ElMessage.error('卡片不存在：' + f.cardNo); return }
    cardId = rec.id
  }
  saving.value = true
  try {
    await trfCreate({ ...f, cardId })
    ElMessage.success('调拨申请已创建（BR-21 前置校验通过）')
    trfDlg.value = false
    loadTrfs()
  } finally {
    saving.value = false
  }
}

async function doSubmit(row) {
  await trfSubmit(row.id)
  ElMessage.success('已提交审批')
  loadTrfs()
}

async function doHandover(row, direction) {
  await trfHandover(row.id, direction)
  ElMessage.success(direction === 'OUT' ? '调出方已确认' : '调入方已确认')
  loadTrfs()
}

async function doComplete(row) {
  await ElMessageBox.confirm(`确认调拨完成？${row.transferType === 'CROSS_LE' ? '跨法人将同事务生成双凭证与内部交易单据。' : '完成即改卡片归属。'}`, '确认完成')
  const res = await trfComplete(row.id)
  ElMessage.success('调拨完成' + (res.data?.intercoNo ? `，内部交易 ${res.data.intercoNo}` : ''))
  loadTrfs()
}

// ================= 租赁 =================
const leaseRows = ref([])
const leaseDlg = ref(false)
const leaseForm = ref({})
const periodTag = ref('')
const schedDlg = ref(false)
const schedRows = ref([])
const schedLseNo = ref('')
const expiryDlg = ref(false)
const expiryRows = ref([])

async function loadLeases() {
  loading.value = true
  try {
    const res = await leasePage({ current: 1, size: 100 })
    leaseRows.value = res.data?.records || []
  } finally {
    loading.value = false
  }
}

function openLeaseDialog() {
  leaseForm.value = {
    lessor: '', assetDesc: '', startDate: '', endDate: '',
    paymentAmount: 10000, payCycleMonths: 3, discountRatePct: 4.5,
    rateBasis: 'INCREMENTAL', buyOption: '0', buyPrice: 0,
    initDirectCost: 0, prepaidRent: 0, leaseIncentive: 0, exemptReason: ''
  }
  leaseDlg.value = true
}

async function doCreateLease() {
  const f = leaseForm.value
  saving.value = true
  try {
    await leaseCreate({
      ...f,
      discountRate: f.discountRatePct ? f.discountRatePct / 100 : null
    })
    ElMessage.success('合同已录入')
    leaseDlg.value = false
    loadLeases()
  } finally {
    saving.value = false
  }
}

async function doClassify(row) {
  const sug = await request.get(`/eam/leases/${row.id}/suggest`)
  const suggestion = sug.data?.suggest || 'ROU'
  const basis = sug.data?.basis || ''
  const { value } = await ElMessageBox.prompt(
    `系统辅助判断：${suggestion}。请确认分类（ROU/FINANCE/OPERATING/EXEMPT）与依据：`,
    '分类人工确认', {
      inputValue: suggestion,
      inputPattern: /^(ROU|FINANCE|OPERATING|EXEMPT)$/,
      inputErrorMessage: '取值域：ROU/FINANCE/OPERATING/EXEMPT'
    })
  await leaseClassify(row.id, { classify: value, basis, disputeByFinMgr: false })
  ElMessage.success('分类已确认')
  loadLeases()
}

async function doConfirm(row) {
  const res = await leaseConfirm(row.id)
  ElMessage.success(res.data?.exempt
    ? '豁免确认完成（不建卡不出计划，直线进损益留痕）'
    : `使用权确认完成：PV ${res.data.pv}，ROU 卡片 ${res.data.rouCard?.cardNo}，计划 ${res.data.scheduleRows} 行`)
  loadLeases()
}

async function viewSchedule(row) {
  const res = await leaseSchedule(row.id)
  schedRows.value = res.data?.rows || []
  schedLseNo.value = row.lseNo
  schedDlg.value = true
}

async function doMonthly() {
  if (!periodTag.value) { ElMessage.warning('请选择会计月'); return }
  const res = await leaseMonthly(periodTag.value)
  ElMessage.success(`月度处理完成：合同 ${res.data.processedContracts} 份，利息凭证 ${res.data.interestVouchers}，付款凭证 ${res.data.payVouchers}，豁免 ${res.data.exemptVouchers}`)
  loadLeases()
}

async function doReverse() {
  if (!periodTag.value) { ElMessage.warning('请选择会计月'); return }
  const res = await leaseReverse(periodTag.value)
  ElMessage.success(`冲回完成：凭证 ${res.data.reversedVouchers} 张，计划行复位 ${res.data.resetRows}`)
  loadLeases()
}

async function openRemeasure(row) {
  const { value } = await ElMessageBox.prompt(
    '输入新增租期月数（如续租 12 月）：', '变更重新计量（财务资产会计单签）', {
      inputValue: '12',
      inputPattern: /^\d+$/,
      inputErrorMessage: '须为正整数'
    })
  await leaseRemeasure(row.id, { extraMonths: Number(value), reason: '条款变更重新计量' })
  ElMessage.success('重新计量已提交审批，通过后自动重算')
}

async function loadExpiry() {
  const res = await leaseExpiry()
  expiryRows.value = res.data || []
  expiryDlg.value = true
}

async function doTerminate(row) {
  const { value } = await ElMessageBox.prompt(
    '终止类型：RETURN 退租 / BUY 购买转自有', '到期终止', {
      inputValue: 'RETURN',
      inputPattern: /^(RETURN|BUY)$/,
      inputErrorMessage: '取值域：RETURN / BUY'
    })
  const res = await leaseTerminate(row.id, { type: value, reason: '到期终止' })
  ElMessage.success(`${value === 'BUY' ? '购买转自有建卡 ' + res.data.newCard?.cardNo : '退租核销完成'}（负债余额 ${res.data.liabilityBal ?? '—'}）`)
  loadLeases()
}

// ================= 标签映射 =================
const trfLabel = (s) => ({ DRAFT: '草稿', PENDING_APPROVE: '审批中', APPROVED: '已审批', HANDOVERED: '已交接', COMPLETED: '已完成', REJECTED: '已驳回', CANCELLED: '已取消' }[s] || s)
const trfTag = (s) => ({ DRAFT: 'info', PENDING_APPROVE: 'warning', APPROVED: 'primary', HANDOVERED: 'warning', COMPLETED: 'success', REJECTED: 'danger' }[s] || 'info')
const leaseLabel = (s) => ({ DRAFT: '草稿', CLASSIFIED: '已分类', CONFIRMED: '已确认', MATURED: '已到期', TERMINATED: '已终止' }[s] || s)
const leaseTag = (s) => ({ DRAFT: 'info', CLASSIFIED: 'warning', CONFIRMED: 'success', MATURED: 'warning', TERMINATED: 'danger' }[s] || 'info')
const classifyLabel = (c) => ({ ROU: '使用权', FINANCE: '融资租赁', OPERATING: '经营租赁', EXEMPT: '豁免' }[c] || c)
const cycleLabel = (m) => ({ 1: '月付', 3: '季付', 6: '半年付', 12: '年付' }[m] || `${m}月`)

onMounted(() => {
  if (tab === 'cross' || tab === 'dept') {
    loadTrfs()
  } else {
    loadLeases()
  }
})
</script>

<style scoped>
.eam-page { padding: 4px; }
.toolbar { display: flex; align-items: center; gap: 4px; margin-bottom: 8px; }
.spacer { flex: 1; }
.hint { margin-left: 8px; color: #909399; font-size: 12px; }
.gen-result { margin-bottom: 4px; }
</style>
