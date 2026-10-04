import request from '@/utils/request'

/** 询价单分页（后端查询前懒 sweep：锁价+不足标记） */
export function getRfqPageApi(params) {
  return request.get('/proc/rfqs', { params })
}

export function getRfqDetailApi(id) {
  return request.get(`/proc/rfqs/${id}`)
}

/** PR 候选（待询价 + 紧急放行状态） */
export function getRfqPrCandidatesApi() {
  return request.get('/proc/rfqs/pr-candidates')
}

/** 创建（C-4.2-01 卡控 + 紧急放行例外） */
export function createRfqApi(data) {
  return request.post('/proc/rfqs', data)
}

export function sendRfqApi(id, sendMode) {
  return request.post(`/proc/rfqs/${id}/send`, { sendMode })
}

export function saveQuoteApi(rfqId, data) {
  return request.post(`/proc/rfqs/${rfqId}/quotes`, data)
}

export function postponeRfqApi(id, newDeadline, reason) {
  return request.post(`/proc/rfqs/${id}/postpone`, { newDeadline, reason })
}

export function addRfqSuppliersApi(id, supplierIds) {
  return request.post(`/proc/rfqs/${id}/suppliers`, { supplierIds })
}

export function closeRfqApi(id, reason) {
  return request.post(`/proc/rfqs/${id}/close`, { reason })
}

/** 比价矩阵（权重即时算） */
export function getMatrixApi(id, weightPrice, weightDelivery) {
  return request.get(`/proc/rfqs/${id}/matrix`, { params: { weightPrice, weightDelivery } })
}

export function confirmAnomalyApi(quoteId) {
  return request.post(`/proc/rfqs/quotes/${quoteId}/confirm-anomaly`)
}

export function excludeQuoteApi(quoteId, reason) {
  return request.post(`/proc/rfqs/quotes/${quoteId}/exclude`, { reason })
}

export function negotiateQuoteApi(quoteId, price, note) {
  return request.post(`/proc/rfqs/quotes/${quoteId}/negotiate`, { price, note })
}

/** 定标 */
export function awardRfqApi(id, data) {
  return request.post(`/proc/rfqs/${id}/award`, data)
}

/** 中选桩（供 2.3.1） */
export function getAwardedApi(prNo) {
  return request.get('/proc/rfqs/awarded', { params: { prNo } })
}
