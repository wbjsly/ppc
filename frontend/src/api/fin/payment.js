import request from '@/utils/request'

/** 付款管理（2.7.3，spec supplier-statement-reconciliation / payment-request / payment-execution / prepayment） */

// ---------------- 对账单（C-4.2-15 冻结与双签） ----------------

export function getStatementPageApi(params) {
  return request.get('/fin/statements', { params })
}

export function getStatementDetailApi(id) {
  return request.get(`/fin/statements/${id}`)
}

/** 登记对账单（自动与 OPEN 暂估余额比对，超容差冻结付款） */
export function createStatementApi(data) {
  return request.post('/fin/statements', data)
}

/** 发起双签（PM → 财务；通过解冻，驳回维持冻结） */
export function confirmStatementApi(id) {
  return request.post(`/fin/statements/${id}/confirm`, {})
}

// ---------------- 付款申请与执行 ----------------

export function getPaymentPageApi(params) {
  return request.get('/fin/payments', { params })
}

export function getPaymentDetailApi(id) {
  return request.get(`/fin/payments/${id}`)
}

/** 创建付款申请（supplierId + applyAmount + invoiceIds[]） */
export function createPaymentApi(data) {
  return request.post('/fin/payments', data)
}

export function submitPaymentApi(id) {
  return request.post(`/fin/payments/${id}/submit`, {})
}

export function cancelPaymentApi(id, reason) {
  return request.post(`/fin/payments/${id}/cancel`, { reason })
}

/** 排期（仅 ADMIN）：planDate yyyy-MM-dd */
export function schedulePaymentApi(id, planDate) {
  return request.post(`/fin/payments/${id}/schedule`, { planDate })
}

/** 待付款标记/恢复（仅 ADMIN） */
export function waitFundsApi(id, wait) {
  return request.post(`/fin/payments/${id}/wait-funds`, { wait })
}

/** 执行付款（仅 ADMIN）：data = { payMethod, bankAccountId, payDate, applyAmount, deductIds[] } */
export function executePaymentApi(id, data) {
  return request.post(`/fin/payments/${id}/execute`, data)
}

/** 可选发票（该供应商 POSTED 且未清 >0） */
export function getInvoiceCandidatesApi(supplierId) {
  return request.get('/fin/payments/invoice-candidates', { params: { supplierId } })
}

/** 可抵扣扣款单（该供应商 TO_DEDUCT 的 SCAR 扣款单） */
export function getDeductCandidatesApi(supplierId) {
  return request.get('/fin/payments/deduct-candidates', { params: { supplierId } })
}

/** 核销记录（KIND=PAYMENT/SETTLE） */
export function getWriteoffPageApi(params) {
  return request.get('/fin/payments/writeoffs', { params })
}

// ---------------- 预付款（BR-4.2-52 / C-4.2-13） ----------------

export function getPrepaymentPageApi(params) {
  return request.get('/fin/prepayments', { params })
}

export function getPrepaymentDetailApi(id) {
  return request.get(`/fin/prepayments/${id}`)
}

/** 创建预付款（poId + applyAmount；双 L1 校验后端执行） */
export function createPrepaymentApi(data) {
  return request.post('/fin/prepayments', data)
}

export function submitPrepaymentApi(id) {
  return request.post(`/fin/prepayments/${id}/submit`, {})
}

export function cancelPrepaymentApi(id, reason) {
  return request.post(`/fin/prepayments/${id}/cancel`, { reason })
}

/** 执行预付（仅 ADMIN）：data = { payMethod, bankAccountId, payDate } */
export function executePrepaymentApi(id, data) {
  return request.post(`/fin/prepayments/${id}/execute`, data)
}

/** 手动冲抵应付（兜底幂等） */
export function settlePrepaymentApi(poNo) {
  return request.post('/fin/prepayments/settle', null, { params: { poNo } })
}

/** 预付款清理待办（冻结供应商 + 未核销预付） */
export function getCleanupTodosApi() {
  return request.get('/fin/prepayments/cleanup-todos')
}
