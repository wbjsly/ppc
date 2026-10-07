import request from '@/utils/request'

// ---------- 销售开票（spec sales-invoicing-receivable，3.8.1） ----------

/** 开票申请分页（状态/客户/SO/关键字） */
export function getApplyPageApi(params) {
  return request.get('/fin/invoice-applies', { params })
}

/** 申请详情（发货行 + 发票 + 应收 + 税务资质实时结论） */
export function getApplyDetailApi(id) {
  return request.get(`/fin/invoice-applies/${id}`)
}

/** 手工调整开票金额（提交时按 TOLERANCE_DEFAULT 校验差异） */
export function updateApplyAmountApi(id, data) {
  return request.put(`/fin/invoice-applies/${id}/amount`, data)
}

/** 提交审核（税务资质 C-4.3-06 L1 + 金额容差校验） */
export function submitApplyApi(id) {
  return request.post(`/fin/invoice-applies/${id}/submit`, {})
}

/** 财务审核：通过 / 退回修改（退回必填意见） */
export function auditApplyApi(id, pass, opinion) {
  return request.post(`/fin/invoice-applies/${id}/audit`, { pass, opinion })
}

/** 发票开具（外部开票系统桩 + 回写应收与 SO） */
export function issueApplyApi(id) {
  return request.post(`/fin/invoice-applies/${id}/issue`, {})
}

/** 客户确认发票 */
export function confirmApplyApi(id) {
  return request.post(`/fin/invoice-applies/${id}/confirm`, {})
}

/** 客户异议（协商处理或转红字） */
export function disputeApplyApi(id, note) {
  return request.post(`/fin/invoice-applies/${id}/dispute`, { note })
}

/** 销项发票台账分页 */
export function getInvoicePageApi(params) {
  return request.get('/fin/invoice-applies/invoices', { params })
}

/** 红字发票开具与应收红冲（关联原票与退货单） */
export function redInvoiceApi(data) {
  return request.post('/fin/invoice-applies/red-invoices', data)
}

// ---------- 应收台账（3.8.3） ----------

/** 应收多维查询（客户/SO/发票号/合同/超期） */
export function getArPageApi(params) {
  return request.get('/fin/ar', { params })
}

/** 应收详情（明细行 + 核销记录 + 所属合同期次与收款进度） */
export function getArDetailApi(id) {
  return request.get(`/fin/ar/${id}`)
}

// ---------- 自动核销（3.8.2） ----------

/** 回款登记：FIFO 自动核销（最早应收优先、支持部分核销） */
export function registerReceiptApi(data) {
  return request.post('/fin/ar/receipts', data)
}

/** 人工核销（无法匹配转人工） */
export function manualWriteoffApi(data) {
  return request.post('/fin/ar/writeoffs/manual', data)
}

/** 回款单分页（含待人工核销队列） */
export function getReceiptPageApi(params) {
  return request.get('/fin/ar/receipts', { params })
}

/** 核销明细分页 */
export function getWriteoffPageApi(params) {
  return request.get('/fin/ar/writeoffs', { params })
}

// ---------- 月度对账（10.7） ----------

/** 生成月度对账单 */
export function generateStatementApi(data) {
  return request.post('/fin/ar/statements', data)
}

/** 对账单分页 */
export function getStatementPageApi(params) {
  return request.get('/fin/ar/statements', { params })
}

/** 对账单详情（未核销逐笔 + 差异排查记录） */
export function getStatementDetailApi(id) {
  return request.get(`/fin/ar/statements/${id}`)
}

/** 差异逐笔排查记录 */
export function checkStatementLineApi(lineId, data) {
  return request.post(`/fin/ar/statements/lines/${lineId}/check`, data)
}

// ---------- 计划达成（10.8） ----------

/** 计划达成对比（逐期计划/实际应收/已核销/达成率，超期标红） */
export function getPlanProgressApi(contractId) {
  return request.get(`/fin/ar/plan-progress/${contractId}`)
}

/** 合同名下应收汇总 */
export function getContractArSummaryApi(contractId) {
  return request.get(`/fin/ar/contract-summary/${contractId}`)
}
