import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  cashDocPage, cashDocDetail, postCashDoc, voidCashDoc
} from '@/api/fin/collection'

/**
 * 8.4/8.7 收付执行三页共享状态（拆分自原收付管理多 tab 页，design D2 composable 规则）。
 * 模块级单例：
 * - 收款单/付款单列表与查询（收款执行页、付款执行页各自展示，过账/作废后双列表刷新）
 * - 单据详情抽屉（收款执行、付款执行两页共用，见 _parts/CashDocDetailDrawer.vue）
 * - 过账/作废（两页共用，函数内仍同时刷新收付两个列表，保持原行为）
 * 等价原「同一组件被多条路由复用」的行为。
 */

const payMethods = ['电汇', '支票', '商业汇票', '银行承兑汇票', '现金']

// ---- 收款（IN）----
const inQuery = ref({ current: 1, size: 20, keyword: '', status: '' })
const inRows = ref([])
const inTotal = ref(0)
const inDialogVisible = ref(false)
const inForm = ref({ partyId: '', partyName: '', amount: 1000, payMethod: '电汇', bankAccountId: '', payDate: new Date().toISOString().slice(0, 10), remark: '' })

async function loadInDocs() {
  const { data } = await cashDocPage({ ...inQuery.value, direction: 'IN' })
  inRows.value = data.records
  inTotal.value = Number(data.total)
}

// ---- 付款（OUT）----
const outQuery = ref({ current: 1, size: 20, keyword: '', status: '' })
const outRows = ref([])
const outTotal = ref(0)
const outDialogVisible = ref(false)
const outForm = ref({ partyId: '', partyName: '', reqId: '', amount: 1000, payMethod: '电汇', bankAccountId: '', payDate: new Date().toISOString().slice(0, 10) })

async function loadOutDocs() {
  const { data } = await cashDocPage({ ...outQuery.value, direction: 'OUT' })
  outRows.value = data.records
  outTotal.value = Number(data.total)
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

export function useCollection() {
  return {
    payMethods,
    inQuery, inRows, inTotal, inDialogVisible, inForm, loadInDocs,
    outQuery, outRows, outTotal, outDialogVisible, outForm, loadOutDocs,
    onPost, onVoid,
    detailVisible, detailDoc, detailWriteoffs, onDocDetail
  }
}
