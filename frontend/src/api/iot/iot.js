import request from '@/utils/request'

// ---------- 终端注册与签审（13 域前置，C-4.11-18） ----------
export function registerTerminalApi(data) {
  return request.post('/iot/terminals/register', data)
}
export function bindTerminalApi(deviceCode, userId) {
  return request.put(`/iot/terminals/${deviceCode}/bind`, null, { params: { userId } })
}
export function unbindTerminalApi(deviceCode) {
  return request.put(`/iot/terminals/${deviceCode}/unbind`)
}
export function verifySignApi(data) {
  return request.post('/iot/terminals/verify-sign', data)
}
export function unlockTerminalApi(deviceCode, reason) {
  return request.post(`/iot/terminals/${deviceCode}/unlock`, null, { params: { reason } })
}
export function revokeCertApi(deviceCode) {
  return request.post(`/iot/terminals/${deviceCode}/revoke-cert`)
}
export function listTerminalsApi(params) {
  return request.get('/iot/terminals', { params })
}
export function terminalDetailApi(deviceCode) {
  return request.get(`/iot/terminals/${deviceCode}`)
}

// ---------- 13.1 移动报工 ----------
export function myOrdersApi(params) {
  return request.get('/iot/reporting/my-orders', { params })
}
export function scanPushEscalationsApi() {
  return request.post('/iot/reporting/push-escalations/scan')
}
export function verifyMaterialApi(data) {
  return request.post('/iot/reporting/verify-material', data)
}
export function submitFirstArticleApi(data) {
  return request.post('/iot/reporting/first-article', data)
}
export function firstArticleOfApi(dispatchId) {
  return request.get('/iot/reporting/first-article', { params: { dispatchId } })
}
export function confirmFirstArticleApi(id, data) {
  return request.post(`/iot/reporting/first-article/${id}/confirm`, data)
}
export function reportOutputApi(data) {
  return request.post('/iot/reporting/output', data)
}
export function reviewReportApi(rptId, data) {
  return request.put(`/iot/reporting/${rptId}/review`, data)
}
