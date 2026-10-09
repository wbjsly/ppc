import request from '@/utils/request'

// ---------- 4.10.1 效期预警（每日报告） ----------
export function getExpiryReportHistoryApi(params) {
  return request.get('/inv/expiry-reports/history', { params })
}
export function getExpiryReportDetailApi(reportDate) {
  return request.get('/inv/expiry-reports/detail', { params: { reportDate } })
}
export function getTodayExpiryReportApi() {
  return request.get('/inv/expiry-reports/today')
}
export function generateExpiryReportApi() {
  return request.post('/inv/expiry-reports/generate')
}

// ---------- 4.10.2 临期锁定 ----------
export function getExpiryLedgerApi(params) {
  return request.get('/inv/expiry-locks/ledger', { params })
}
export function getExpiryLockHistoryApi(params) {
  return request.get('/inv/expiry-locks/history', { params })
}
export function manualLockApi(params) {
  return request.post('/inv/expiry-locks/manual', null, { params })
}
export function triggerExpiryScanApi() {
  return request.post('/inv/expiry-locks/scan')
}

// ---------- 4.10.3 质量评估 ----------
export function submitExpiryEvalApi(data) {
  return request.post('/inv/expiry-evals', data)
}
export function submitEvalConclusionApi(id, data) {
  return request.post(`/inv/expiry-evals/${id}/conclusion`, data)
}
export function getExpiryEvalPageApi(params) {
  return request.get('/inv/expiry-evals', { params })
}
export function getExpiryEvalDetailApi(id) {
  return request.get(`/inv/expiry-evals/${id}`)
}
