import request from '@/utils/request'

/** SCAR 供应商质量索赔（6.8.1~6.8.2） */

export function getScarPageApi(params) {
  return request.get('/qms/scar', { params })
}
export function getScarDetailApi(id) {
  return request.get(`/qms/scar/${id}`)
}
export function createScarApi(data) {
  return request.post('/qms/scar', data)
}
export function submitScarApi(id) {
  return request.post(`/qms/scar/${id}/submit`)
}
/** SQE 代录 8D 回复（浅层根因退回计数） */
export function replyScarApi(id, replyText) {
  return request.post(`/qms/scar/${id}/reply`, { replyText })
}
export function verifyScarApi(id, pass, conclusion) {
  return request.post(`/qms/scar/${id}/verify`, { pass, conclusion })
}
export function createDeductionApi(scarId, data) {
  return request.post(`/qms/scar/${scarId}/deductions`, data)
}
export function submitDeductionFinanceApi(deductionId) {
  return request.post(`/qms/scar/deductions/${deductionId}/finance`)
}
export function toDeductApi(deductionId) {
  return request.post(`/qms/scar/deductions/${deductionId}/to-deduct`)
}
export function disputeApi(deductionId, reason) {
  return request.post(`/qms/scar/deductions/${deductionId}/dispute`, { reason })
}
export function deductionSummaryApi(supplierId) {
  return request.get('/qms/scar/deduction-summary', { params: { supplierId } })
}
