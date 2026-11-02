import request from '@/utils/request'

// ---------- 13.3 设备巡检 ----------
export function inspectGenerateApi() {
  return request.post('/iot/inspections/generate')
}
export function inspectCreateApi(data) {
  return request.post('/iot/inspections', data)
}
export function inspectCheckinApi(id, deviceNo) {
  return request.post(`/iot/inspections/${id}/checkin`, null, { params: { deviceNo } })
}
export function inspectSubmitApi(id, data) {
  return request.post(`/iot/inspections/${id}/submit`, data)
}
export function inspectListApi(params) {
  return request.get('/iot/inspections', { params })
}
export function inspectOverdueApi() {
  return request.post('/iot/inspections/scan-overdue')
}

// ---------- 13.4 异常上报 ----------
export function exceptionReportApi(data) {
  return request.post('/iot/exceptions', data)
}
export function exceptionConfirmApi(id) {
  return request.post(`/iot/exceptions/${id}/confirm`)
}
export function exceptionDisposeApi(id, data) {
  return request.post(`/iot/exceptions/${id}/dispose`, data)
}
export function exceptionListApi(params) {
  return request.get('/iot/exceptions', { params })
}
export function exceptionEscalateApi() {
  return request.post('/iot/exceptions/scan-escalate')
}
