import { ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  disposalPage, disposalDetail, disposalCreate, disposalSubmit, disposalExec,
  disposalReceive, disposalSubmitReport, disposalReverse, cardSearch
} from '@/api/eam/disposal'

/**
 * 7.6 资产处置三页共享状态与动作（拆分自原资产处置操作台，design D2 composable 规则）。
 * 模块级单例：报废申请/资产出售/损益处理三页间共享列表、四个对话框与交叉刷新，
 * 等价原「同一组件被三条路由复用」的行为。
 */
export const STATUSES = ['DRAFT', 'PENDING_APPROVE', 'APPROVED', 'EXECUTED', 'RECEIVABLE', 'CLOSED', 'REJECTED']

export const statusType = (s) => ({
  DRAFT: 'info', PENDING_APPROVE: 'warning', APPROVED: 'primary',
  EXECUTED: '', RECEIVABLE: 'warning', CLOSED: 'success', REJECTED: 'danger'
}[s] || 'info')

// ---------- 三表列表 ----------
const scrapQuery = ref({ status: '' })
const scrapRows = ref([])
const saleRows = ref([])
const plRows = ref([])

async function loadScrap() {
  const res = await disposalPage({ type: 'SCRAP', status: scrapQuery.value.status, size: 50 })
  scrapRows.value = res.data.records
}
async function loadSale() {
  const res = await disposalPage({ type: 'SALE', size: 50 })
  saleRows.value = res.data.records
}
async function loadPl() {
  const res = await disposalPage({ size: 50 })
  plRows.value = res.data.records
}

// ---------- 新建处置单对话框（报废 + 出售共用） ----------
const createVisible = ref(false)
const createForm = ref({})
const createLines = ref([])
const createRecycles = ref([])
const cardKeyword = ref('')

const residualAlert = computed(() => {
  const nbv = createLines.value.reduce((s, l) => s + (Number(l._nbv) || 0), 0)
  return nbv > 0 && (Number(createForm.value.estResidual) || 0) > nbv * 0.1
})

function openCreate(type) {
  createForm.value = { disposalType: type, reasonCode: 'TECH', reasonNote: '', estResidual: 0, evalReportNo: '', reviewNote: '' }
  createLines.value = []
  createRecycles.value = []
  cardKeyword.value = ''
  createVisible.value = true
}

async function searchCards() {
  if (!cardKeyword.value) return
  const res = await cardSearch(cardKeyword.value)
  const records = res.data.records || []
  for (const c of records) {
    if (createLines.value.some(l => l.cardId === c.id)) continue
    if (c.status !== 'IN_USE') continue
    createLines.value.push({
      cardId: c.id, cardNo: c.cardNo, assetName: c.assetName,
      _cost: c.assetCost, _nbv: (Number(c.assetCost) || 0) - (Number(c.accumDepr) || 0),
      lineProceeds: 0
    })
  }
}

async function doCreate() {
  const payload = {
    order: createForm.value,
    lines: createLines.value.map(l => ({ cardId: l.cardId, lineProceeds: l.lineProceeds })),
    recycles: createRecycles.value.filter(r => r.itemName)
  }
  const res = await disposalCreate(payload)
  ElMessage.success(`处置单 ${res.data.order.dspNo} 已创建（${res.data.lineCount} 张卡）`)
  createVisible.value = false
  loadScrap(); loadSale(); loadPl()
}

// ---------- 提交审批（报废 + 出售共用） ----------
async function doSubmit(id) {
  const res = await disposalSubmit(id)
  ElMessage.success(`已提交（${res.data.nodes} 节点链 ${res.data.tier}）`)
  loadScrap(); loadSale()
}

// ---------- 执行确认对话框（报废 + 出售共用） ----------
const execVisible = ref(false)
const execId = ref('')
const execType = ref('SCRAP')
const execForm = ref({})

function openExec(id, type) {
  execId.value = id
  execType.value = type
  execForm.value = { proofNo: '', envAssessNo: '', dataDestroyNo: '' }
  execVisible.value = true
}
async function doExec() {
  if (!execForm.value.proofNo) { ElMessage.warning('执行凭证编号必填'); return }
  const res = await disposalExec(execId.value, execForm.value)
  ElMessage.success(`执行完成：损益 ${res.data.gainLoss}，状态 ${res.data.status}，回收入库 ${res.data.recyclePosted} 条`)
  execVisible.value = false
  loadScrap(); loadSale(); loadPl()
}

// ---------- 收款登记对话框（出售 + 损益共用） ----------
const receiveVisible = ref(false)
const receiveId = ref('')
const receiveForm = ref({ amount: 0, note: '' })
function openReceive(id) {
  receiveId.value = id
  receiveForm.value = { amount: 0, note: '' }
  receiveVisible.value = true
}
async function doReceive() {
  const res = await disposalReceive(receiveId.value, receiveForm.value)
  ElMessage.success(`收款成功，应收余额 ${res.data.receivableBal}`)
  receiveVisible.value = false
  loadSale(); loadPl()
}

// ---------- 明细对话框（三页共用） ----------
const detailVisible = ref(false)
const detail = ref({})
async function openDetail(id) {
  const res = await disposalDetail(id)
  detail.value = res.data
  detailVisible.value = true
}

// ---------- 结案 / 冲回（损益处理） ----------
async function doSubmitReport(id) {
  await disposalSubmitReport(id)
  ElMessage.success('结案报告已提交（财务资产会计单签）')
  loadPl(); loadSale()
}
async function doReverse(id) {
  await ElMessageBox.confirm('整单冲回将逆序冲回全部处置凭证并恢复卡片，库存入库单不自动冲销。继续？', '整单冲回', { type: 'warning' })
  const res = await disposalReverse(id)
  ElMessage.success(`已冲回 ${res.data.reversedVouchers} 张凭证`)
  loadScrap(); loadSale(); loadPl()
}

export function useDisposal() {
  return {
    STATUSES, statusType,
    scrapQuery, scrapRows, saleRows, plRows, loadScrap, loadSale, loadPl,
    createVisible, createForm, createLines, createRecycles, cardKeyword,
    residualAlert, openCreate, searchCards, doCreate,
    doSubmit,
    execVisible, execType, execForm, openExec, doExec,
    receiveVisible, receiveForm, openReceive, doReceive,
    detailVisible, detail, openDetail,
    doSubmitReport, doReverse
  }
}
