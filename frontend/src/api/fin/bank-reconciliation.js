import request from '@/utils/request'

// ================= 银行对账（M8-7，add-bank-reconciliation，spec bank-reconciliation） =================
// 8.7.1 流水导入/8.7.2 自动比对复用 /fin/bank-receipts（同一数据源，决策①）
/** 录入/修改对账单（DRAFT/CONFIRMED 可改；勾稽自校验不平提示仍可保存） */
export const saveStatement = (payload) => request.post('/fin/bank-reconciliation/statements', payload)
/** 对账单列表（可按期间过滤） */
export const listStatements = (period) => request.get('/fin/bank-reconciliation/statements', { params: { period } })
/** 对账单详情（差异率/超容差/调节表/四类未达账项） */
export const statementDetail = (id) => request.get(`/fin/bank-reconciliation/statements/${id}`)
/** 出纳确认（DRAFT→CONFIRMED，调节平衡才放行） */
export const confirmStatement = (id) => request.post(`/fin/bank-reconciliation/statements/${id}/confirm`)
/** 财务主管审核（CONFIRMED→AUDITED，BR-4.6-36） */
export const auditStatement = (id) => request.post(`/fin/bank-reconciliation/statements/${id}/audit`)
/** 撤销（回 DRAFT；AUDITED 撤销仅主管且必填原因留痕） */
export const revokeStatement = (id, reason) => request.post(`/fin/bank-reconciliation/statements/${id}/revoke`, { reason })
/** 四类未达账项实时分类（含账龄超 90 天红标） */
export const outstandingItems = (bankAccountId, period) =>
  request.get('/fin/bank-reconciliation/outstanding-items', { params: { bankAccountId, period } })
/** 差异处理留痕（追加式：PROCESSING/DONE/ESCALATED） */
export const markHandling = (payload) => request.post('/fin/bank-reconciliation/handling', payload)
/** 处理记录回看 */
export const handlingRecords = (bankAccountId, period) =>
  request.get('/fin/bank-reconciliation/handling-records', { params: { bankAccountId, period } })
