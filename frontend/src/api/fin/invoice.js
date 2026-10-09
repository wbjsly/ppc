import request from '@/utils/request'

/** 三方匹配（2.7.2，spec three-way-match）—— 登记/匹配/确认 ADMIN+PM；跨期手工过账仅 ADMIN */

/** 发票分页（关键字/状态/供应商/日期区间，含匹配状态与差异率） */
export function getInvoicePageApi(params) {
  return request.get('/fin/invoices', { params })
}

/** 发票详情（行 + 关联匹配单） */
export function getInvoiceDetailApi(id) {
  return request.get(`/fin/invoices/${id}`)
}

/**
 * 发票登记（头 + 行）
 * payload = { invoiceNo, supplierId, supplierName, invoiceDate, poNo, remark,
 *             lines: [{ poId, poNo, poLineId, itemCode, itemName, unit, qty, unitPrice }] }
 */
export function createInvoiceApi(payload) {
  return request.post('/fin/invoices', payload)
}

/** 执行三单匹配：容差内自动冲回暂估 + 转正式应付；超容差冻结生成异常对账单 */
export function matchInvoiceApi(id) {
  return request.post(`/fin/invoices/${id}/match`, {})
}

/** 匹配台账（状态/供应商/发票号/PO 筛选） */
export function getMatchPageApi(params) {
  return request.get('/fin/invoices/matches', { params })
}

/** 匹配单详情（逐行差异明细 + 凭证） */
export function getMatchDetailApi(matchId) {
  return request.get(`/fin/invoices/matches/${matchId}`)
}

/** 采购员确认价差（必填意见）：非跨期确认即过账，跨期待手工过账 */
export function confirmMatchApi(matchId, opinion) {
  return request.post(`/fin/invoices/matches/${matchId}/confirm`, { opinion })
}

/** 跨期手工过账（仅 ADMIN）：生成当期价差调整凭证并注明跨期原因 */
export function manualPostMatchApi(matchId, note) {
  return request.post(`/fin/invoices/matches/${matchId}/manual-post`, { note })
}
