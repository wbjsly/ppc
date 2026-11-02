import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  trfPage, trfCreate, trfSubmit, trfHandover, trfComplete, intercoCheck, idlePool,
  leasePage, leaseCreate, leaseClassify, leaseConfirm, leaseSchedule,
  leaseMonthly, leaseReverse, leaseRemeasure, leaseExpiry, leaseTerminate
} from '@/api/eam/transfer-lease'
import request from '@/utils/request'

/**
 * 7.4 调拨与租赁三页共享状态与动作（拆分自原调拨租赁操作台，design D2 composable 规则）。
 * 模块级单例：部门调拨/法人调拨/租赁管理三页共享，等价原组件复用行为。
 * 调拨加载与建单按 mode（'dept'|'cross'）参数化。
 */
export const loading = ref(false)
export const saving = ref(false)

// ================= 调拨 =================
export const trfRows = ref([])
export const poolRows = ref([])
export const intercoResult = ref(false)
export const trfDlg = ref(false)
export const trfForm = ref({})

export async function loadTrfs(mode) {
  loading.value = true
  try {
    const transferType = mode === 'cross' ? 'CROSS_LE' : 'DEPT'
    const res = await trfPage({ current: 1, size: 100, transferType })
    trfRows.value = res.data?.records || []
  } finally {
    loading.value = false
  }
}

export async function loadPool() {
  const res = await idlePool()
  poolRows.value = res.data || []
}

export async function doIntercoCheck() {
  try {
    await intercoCheck()
    intercoResult.value = true
    ElMessage.success('C-10 月结卡控通过')
  } catch (e) {
    intercoResult.value = false
    ElMessage.error(e?.response?.data?.msg || e.message)
  }
}

export function openTrfDialog(mode, poolRow) {
  trfForm.value = {
    cardId: poolRow?.cardId || '',
    cardNo: poolRow?.cardNo || '',
    transferType: mode === 'cross' ? 'CROSS_LE' : 'DEPT',
    toDept: '', toCc: '', toLocation: '', toLe: '', reason: '', planDate: ''
  }
  trfDlg.value = true
}

export async function doCreateTrf(mode) {
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
    loadTrfs(mode)
  } finally {
    saving.value = false
  }
}

export async function doSubmit(row, mode) {
  await trfSubmit(row.id)
  ElMessage.success('已提交审批')
  loadTrfs(mode)
}

export async function doHandover(row, direction, mode) {
  await trfHandover(row.id, direction)
  ElMessage.success(direction === 'OUT' ? '调出方已确认' : '调入方已确认')
  loadTrfs(mode)
}

export async function doComplete(row, mode) {
  await ElMessageBox.confirm(`确认调拨完成？${row.transferType === 'CROSS_LE' ? '跨法人将同事务生成双凭证与内部交易单据。' : '完成即改卡片归属。'}`, '确认完成')
  const res = await trfComplete(row.id)
  ElMessage.success('调拨完成' + (res.data?.intercoNo ? `，内部交易 ${res.data.intercoNo}` : ''))
  loadTrfs(mode)
}

// ================= 租赁 =================
export const leaseRows = ref([])
export const leaseDlg = ref(false)
export const leaseForm = ref({})
export const periodTag = ref('')
export const schedDlg = ref(false)
export const schedRows = ref([])
export const schedLseNo = ref('')
export const expiryDlg = ref(false)
export const expiryRows = ref([])

export async function loadLeases() {
  loading.value = true
  try {
    const res = await leasePage({ current: 1, size: 100 })
    leaseRows.value = res.data?.records || []
  } finally {
    loading.value = false
  }
}

export function openLeaseDialog() {
  leaseForm.value = {
    lessor: '', assetDesc: '', startDate: '', endDate: '',
    paymentAmount: 10000, payCycleMonths: 3, discountRatePct: 4.5,
    rateBasis: 'INCREMENTAL', buyOption: '0', buyPrice: 0,
    initDirectCost: 0, prepaidRent: 0, leaseIncentive: 0, exemptReason: ''
  }
  leaseDlg.value = true
}

export async function doCreateLease() {
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

export async function doClassify(row) {
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

export async function doConfirm(row) {
  const res = await leaseConfirm(row.id)
  ElMessage.success(res.data?.exempt
    ? '豁免确认完成（不建卡不出计划，直线进损益留痕）'
    : `使用权确认完成：PV ${res.data.pv}，ROU 卡片 ${res.data.rouCard?.cardNo}，计划 ${res.data.scheduleRows} 行`)
  loadLeases()
}

export async function viewSchedule(row) {
  const res = await leaseSchedule(row.id)
  schedRows.value = res.data?.rows || []
  schedLseNo.value = row.lseNo
  schedDlg.value = true
}

export async function doMonthly() {
  if (!periodTag.value) { ElMessage.warning('请选择会计月'); return }
  const res = await leaseMonthly(periodTag.value)
  ElMessage.success(`月度处理完成：合同 ${res.data.processedContracts} 份，利息凭证 ${res.data.interestVouchers}，付款凭证 ${res.data.payVouchers}，豁免 ${res.data.exemptVouchers}`)
  loadLeases()
}

export async function doReverse() {
  if (!periodTag.value) { ElMessage.warning('请选择会计月'); return }
  const res = await leaseReverse(periodTag.value)
  ElMessage.success(`冲回完成：凭证 ${res.data.reversedVouchers} 张，计划行复位 ${res.data.resetRows}`)
  loadLeases()
}

export async function openRemeasure(row) {
  const { value } = await ElMessageBox.prompt(
    '输入新增租期月数（如续租 12 月）：', '变更重新计量（财务资产会计单签）', {
      inputValue: '12',
      inputPattern: /^\d+$/,
      inputErrorMessage: '须为正整数'
    })
  await leaseRemeasure(row.id, { extraMonths: Number(value), reason: '条款变更重新计量' })
  ElMessage.success('重新计量已提交审批，通过后自动重算')
}

export async function loadExpiry() {
  const res = await leaseExpiry()
  expiryRows.value = res.data || []
  expiryDlg.value = true
}

export async function doTerminate(row) {
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
export const trfLabel = (s) => ({ DRAFT: '草稿', PENDING_APPROVE: '审批中', APPROVED: '已审批', HANDOVERED: '已交接', COMPLETED: '已完成', REJECTED: '已驳回', CANCELLED: '已取消' }[s] || s)
export const trfTag = (s) => ({ DRAFT: 'info', PENDING_APPROVE: 'warning', APPROVED: 'primary', HANDOVERED: 'warning', COMPLETED: 'success', REJECTED: 'danger' }[s] || 'info')
export const leaseLabel = (s) => ({ DRAFT: '草稿', CLASSIFIED: '已分类', CONFIRMED: '已确认', MATURED: '已到期', TERMINATED: '已终止' }[s] || s)
export const leaseTag = (s) => ({ DRAFT: 'info', CLASSIFIED: 'warning', CONFIRMED: 'success', MATURED: 'warning', TERMINATED: 'danger' }[s] || 'info')
export const classifyLabel = (c) => ({ ROU: '使用权', FINANCE: '融资租赁', OPERATING: '经营租赁', EXEMPT: '豁免' }[c] || c)
export const cycleLabel = (m) => ({ 1: '月付', 3: '季付', 6: '半年付', 12: '年付' }[m] || `${m}月`)
